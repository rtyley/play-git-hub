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
import cats.data.*
import cats.effect.{IO, Temporal}
import cats.implicits.*
import cats.syntax.all.*
import com.madgag.git.*
import com.madgag.github.Implicits.*
import com.madgag.scalagithub.GitHub.FR
import com.madgag.scalagithub.commands.CreateRepo
import com.madgag.scalagithub.model.Repo
import com.madgag.scalagithub.{AccountAccess, GitHub, GitHubCredentials, GitHubResponse, model}
import com.madgag.time.Implicits.*
import org.eclipse.jgit.api.{CloneCommand, Git}
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.*
import org.eclipse.jgit.lib.Constants.HEAD
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.scalatest.matchers.should.Matchers.all
import retry.ResultHandler.retryOnAllErrors
import retry.RetryPolicies.{fullJitter, limitRetries, limitRetriesByCumulativeDelay}
import retry.syntax.*
import retry.*

import java.nio.file.Files.createTempDirectory
import java.time.Duration.ofMinutes
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import cats.syntax.all.*
import cats.data.*
import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import com.madgag.scalagithub


class ToastRepoCreation(
  testFixtureAccountAccess: AccountAccess,
  testRepoNamePrefix: String
) {
  given credsProvider: GitHubCredentials.Provider = testFixtureAccountAccess.credentials
  given gitHub: GitHub = testFixtureAccountAccess.gitHub

  val retryPolicy: RetryPolicy[IO, Throwable] = limitRetriesByCumulativeDelay(70.seconds, fullJitter(1.seconds))

  def createTestRepo(localGitRepo: FileRepository)(implicit ec: ExecutionContext): IO[Repo] = {
    for {
      testGithubRepo <- createBlankGitHubRepo()
      _ <- pushLocalRepoToGitHub(localGitRepo, testGithubRepo) >>
        Temporal[IO].sleep(1500.milli) >> // pause to allow the GitHub API to catch up - reduces retries
        validateGitHubAPISuccessfullyRepresentsLocalRepo(testGithubRepo, localGitRepo.getRefDatabase)
    } yield testGithubRepo
  }

  def isOldTestRepo(repo: Repo): Boolean =
    repo.name.startsWith(testRepoNamePrefix) && repo.created_at.toInstant.age() > ofMinutes(30)

  def deleteTestRepos()(implicit ec: ExecutionContext): IO[Unit] = gitHub
    .listRepos(sort = "created", direction = "asc").filter(isOldTestRepo)
    .parEvalMapUnordered(4)(_.delete(): IO[_]).compile.drain

  private def createBlankGitHubRepo()(implicit ec: ExecutionContext): IO[Repo] = for {
    testRepoId <- testFixtureAccountAccess.account.createRepo(CreateRepo(
      name = testRepoNamePrefix + System.currentTimeMillis().toString,
      `private` = false
    )).map(_.result.repoId)
    testGithubRepo <- gitHub.getRepo(testRepoId)
  } yield testGithubRepo.result

  private def pushLocalRepoToGitHub(
    localGitRepo: FileRepository, testGithubRepo: Repo
  )(implicit ec: ExecutionContext): IO[Unit] = for {
    creds <- credsProvider()
  } yield {
    configLocalRepoToPushToGitHubRepo(localGitRepo, testGithubRepo)

    val pushResults = localGitRepo.git.push.setCredentialsProvider(creds.git).setPushTags().setPushAll().call()
    pushResults.asScala.foreach { pushResult =>
      all(pushResult.getRemoteUpdates.asScala.map(_.getStatus)) shouldBe RemoteRefUpdate.Status.OK
    }
  }

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
    IO.traverse(expectedRefs.branchRefs) { branchRef =>
      getRefDataFromGitHubApi(testGitHubRepo, branchRef).map(_.result.objectId).map { a =>
          require(a.toObjectId == branchRef.getObjectId, s"${a.toObjectId} IS NOT ${branchRef.getObjectId}")
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

  private def getRefDataFromGitHubApi(testGithubRepo: Repo, branchRef: Ref)(using ExecutionContext, GitHub): FR[model.Ref] = {
    val foo: FR[model.Ref] = testGithubRepo.refs.get(branchRef)

    type G = GitHubResponse[model.Ref]

    (foo: IO[GitHubResponse[model.Ref]]).retryingOnErrors(retryPolicy, retryOnAllErrors(logRetry(s"get ${branchRef.getName}")))
  }

  private def logRetry(detail: String): (Throwable, RetryDetails) => IO[Unit] = (_, retryDetails) =>
    IO(println(s"retrying '$detail' - delay so far=${retryDetails.cumulativeDelay.toSeconds}s"))

  private def performCloneOf(githubRepo: Repo): IO[Repository] = for {
    creds <- credsProvider()
    clonedRepo <- IO.blocking {
      creds.applyAuthTo[CloneCommand, Git](Git.cloneRepository()).setBare(true).setURI(githubRepo.clone_url)
        .setDirectory(createTempDirectory("test-repo").toFile).call().getRepository
    }.retryingOnErrors(retryPolicy, retryOnAllErrors(logRetry(s"clone ${githubRepo.clone_url}")))
  } yield clonedRepo
}

sealed trait RefProblem
case class ErrorGettingRef(t: Throwable) extends RefProblem
case class WrongValue(incorrectObjectId: ObjectId) extends RefProblem