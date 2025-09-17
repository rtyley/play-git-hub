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
import cats.effect.std.Random
import cats.effect.{Clock, IO, Temporal}
import cats.implicits.*
import com.madgag.git.*
import com.madgag.git.test.unpackRepo
import com.madgag.scalagithub
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
import retry.*
import retry.ResultHandler.retryOnAllErrors
import retry.RetryPolicies.{fullJitter, limitRetriesByCumulativeDelay}
import retry.syntax.*

import java.nio.file.Files.createTempDirectory
import java.nio.file.Path
import java.time.Duration
import java.time.Duration.ofMinutes
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*


class ToastRepoCreation(
  testFixtureAccountAccess: AccountAccess,
  testRepoNamePrefix: String
) {
  given credsProvider: GitHubCredentials.Provider = testFixtureAccountAccess.credentials
  given gitHub: GitHub = testFixtureAccountAccess.gitHub

  val retryPolicy: RetryPolicy[IO, Throwable] = limitRetriesByCumulativeDelay(70.seconds, fullJitter(1.seconds))

  def createTestRepo(path: Path): IO[Repo] = for {
    localGitRepo <- IO.blocking(unpackRepo(path))
    testGithubRepo <- createBlankGitHubRepo()
    _ <- pushLocalRepoToGitHub(localGitRepo, testGithubRepo) >>
      Temporal[IO].sleep(1500.milli) >> // pause to allow the GitHub API to catch up - reduces retries
      validateGitHubAPISuccessfullyRepresentsLocalRepo(testGithubRepo, localGitRepo.getRefDatabase)
  } yield testGithubRepo

  def isOldTestRepo(repo: Repo): Boolean =
    repo.name.startsWith(testRepoNamePrefix) && repo.created_at.toInstant.age() > ofMinutes(30)

  def deleteTestRepos(): IO[Map[Boolean, Set[Repo]]] = testFixtureAccountAccess.account
    .listRepos("sort" -> "created", "direction" -> "asc").filter(isOldTestRepo)
    .parEvalMapUnordered(4)(repo => repo.delete().map(res => Map(res.result -> Set(repo)))).compile.foldMonoid

  private def createBlankGitHubRepo()(using clock: Clock[IO]): IO[Repo] = for {
    now <- clock.realTimeInstant
    randInt <- Random[IO].nextIntBounded(1000000)
    testRepoId <- testFixtureAccountAccess.account.createRepo(CreateRepo(
      name = s"$testRepoNamePrefix-${now.getEpochSecond}-$randInt",
      `private` = false
    )).map(_.result.repoId)
    testGithubRepo <- gitHub.getRepo(testRepoId)
  } yield {

    val repo = testGithubRepo.result
    println(s"Created repo: ${repo.url}")
    repo
  }

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
      println(s"Creating a '$defaultBranchName' branch to correspond to the one in ${testGithubRepo.url}")
      localGitRepo.git.branchCreate().setName(defaultBranchName).setStartPoint("HEAD").call()
    }
  }

  private def validateGitHubAPISuccessfullyRepresentsLocalRepo(
    testGithubRepo: Repo, expectedRefs: RefDatabase
  ): IO[Unit] = for {
    _ <- validateGitHubAPIGives(expectedRefs, testGithubRepo)
    _ <- validateGitHubRepoCanBeSuccessfullyLocallyCloned(expectedRefs, testGithubRepo)
  } yield ()

  private def validateGitHubAPIGives(expectedRefs: RefDatabase, testGitHubRepo: Repo) = {
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

  private def getRefDataFromGitHubApi(testGithubRepo: Repo, branchRef: Ref)(using GitHub): FR[model.Ref] = {
    val foo: FR[model.Ref] = testGithubRepo.refs.get(branchRef)

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