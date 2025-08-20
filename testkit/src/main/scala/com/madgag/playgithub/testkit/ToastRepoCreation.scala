/*
 * Copyright 2025 Roberto Tyley
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.madgag.playgithub.testkit

import cats.*
import cats.effect.{IO, Temporal}
import cats.syntax.all.*
import com.madgag.git.*
import com.madgag.git.test.unpackRepo
import com.madgag.scalagithub.commands.CreateRepo
import com.madgag.scalagithub.model.Repo
import com.madgag.scalagithub.{AccountAccess, GitHub, GitHubCredentials}
import org.eclipse.jgit.api.{CloneCommand, Git}
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.*
import org.eclipse.jgit.lib.Constants.{HEAD, R_HEADS}
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.scalatest.matchers.should.Matchers.all
import retry.ResultHandler.retryOnAllErrors
import retry.RetryPolicies.{fullJitter, limitRetries, limitRetriesByCumulativeDelay}
import retry.syntax.*
import retry.{ResultHandler, RetryPolicies}

import java.nio.file.Files.createTempDirectory
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

class ToastRepoCreation(
  testFixtureAccountCredentials: AccountAccess,
  testRepoNamePrefix: String
) {
  given credsProvider: GitHubCredentials.Provider = testFixtureAccountCredentials.credentials
  given gitHub: GitHub = testFixtureAccountCredentials.gitHub

  def createTestRepo(fileName: String)(implicit ec: ExecutionContext): IO[Repo] = {
    val localGitRepo = unpackRepo(fileName)
    for {
      testGithubRepo <- createBlankGitHubRepo()
      _ <- pushLocalRepoToGitHub(localGitRepo, testGithubRepo) >>
        Temporal[IO].sleep(2.seconds) >> // pause so we don't immediately cache results before the GitHub API catches up
        validateGitHubAPISuccessfullyRepresentsLocalRepo(testGithubRepo, localGitRepo.getRefDatabase)
    } yield testGithubRepo
  }

  private def createBlankGitHubRepo()(implicit ec: ExecutionContext): IO[Repo] = IO.fromFuture(IO(for {
    testRepoId <- testFixtureAccountCredentials.account.createRepo(CreateRepo(
      name = testRepoNamePrefix + System.currentTimeMillis().toString,
      `private` = false
    )).map(_.repoId)
    testGithubRepo <- gitHub.getRepo(testRepoId)
  } yield testGithubRepo))

  private def pushLocalRepoToGitHub(
    localGitRepo: FileRepository, testGithubRepo: Repo
  )(implicit ec: ExecutionContext): IO[Unit] = IO.fromFuture(IO(for {
    creds <- credsProvider()
  } yield {
    configLocalRepoToPushToGitHubRepo(localGitRepo, testGithubRepo)

    val pushResults = localGitRepo.git.push.setCredentialsProvider(creds.git).setPushTags().setPushAll().call()
    pushResults.asScala.foreach { pushResult =>
      all(pushResult.getRemoteUpdates.asScala.map(_.getStatus)) shouldBe RemoteRefUpdate.Status.OK
    }
  }))

  private def configLocalRepoToPushToGitHubRepo(localGitRepo: FileRepository, testGithubRepo: Repo): Unit = {
    val config = localGitRepo.getConfig
    config.setString("remote", "origin", "url", testGithubRepo.clone_url)
    config.save()

    val defaultBranchName = testGithubRepo.default_branch
    if (Option(localGitRepo.findRef(defaultBranchName)).isEmpty) {
      println(s"Going to create a '$defaultBranchName' branch")
      localGitRepo.git.branchCreate().setName(defaultBranchName).setStartPoint("HEAD").call()
    }
  }

  private def validateGitHubAPISuccessfullyRepresentsLocalRepo(
    testGithubRepo: Repo, expectedRefs: RefDatabase
  )(using ExecutionContext): IO[Unit] = for {
    _ <- validateGitHubAPIGives(expectedRefs, testGithubRepo)
    _ <- validateGitHubRepoCanBeSuccessfullyLocallyCloned(expectedRefs, testGithubRepo)
  } yield ()

  private def validateGitHubAPIGives(expectedRefs: RefDatabase, testGitHubRepo: Repo)(using ExecutionContext) = {
    val branchRefs: Seq[Ref] = expectedRefs.branchRefs

    // Option[(Ref, RefProblem)] give first ref that failed (after retries)
    IO.traverse(branchRefs) { branchRef =>
      getRefDataFromGitHubApi(testGitHubRepo, branchRef)
        .map(_.result.objectId).map { a =>
          require(a.toObjectId == branchRef.getObjectId, s"${a.toObjectId} IS NOT ${branchRef.getObjectId}")
          //branchRef -> a.fold[Option[RefProblem]](t => Some(ErrorGettingRef(t)), b => Option.when(b != branchRef.getObjectId)(WrongValue(b)))
        }
    }
  }

  private def validateGitHubRepoCanBeSuccessfullyLocallyCloned(expectedRefs: RefDatabase, testGithubRepo: Repo) = for {
    localCloneOfGitHubRepo <- performCloneOf(testGithubRepo)
  } yield {
    val actualId: ObjectId = localCloneOfGitHubRepo.findRef(testGithubRepo.default_branch).getObjectId
    val expectedId: ObjectId = expectedRefs.exactRef(HEAD).getObjectId
    require(actualId == expectedId, s"actualId=$actualId expectedId=$expectedId")
  }

  private def getRefDataFromGitHubApi(testGithubRepo: Repo, branchRef: Ref)(using ExecutionContext, GitHub) =
    IO.fromFuture(IO(testGithubRepo.refs.get(branchRef)))
      .retryingOnErrors(limitRetriesByCumulativeDelay(70.seconds, fullJitter(1.seconds)), retryOnAllErrors(log = (x, y) =>
        IO(println(s"retrying get ${branchRef.getName} - delay so far=${y.cumulativeDelay.toSeconds}s"))))

  private def performCloneOf(githubRepo: Repo): IO[Repository] = for {
    creds <- IO.fromFuture(IO(credsProvider()))
    clonedRepo <- IO.blocking {
      creds.applyAuthTo[CloneCommand, Git](Git.cloneRepository()).setBare(true).setURI(githubRepo.clone_url)
        .setDirectory(createTempDirectory("test-repo").toFile).call().getRepository
    }.retryingOnErrors(limitRetries[IO](5), retryOnAllErrors(log = ResultHandler.noop))
  } yield clonedRepo
}

sealed trait RefProblem
case class ErrorGettingRef(t: Throwable) extends RefProblem
case class WrongValue(incorrectObjectId: ObjectId) extends RefProblem