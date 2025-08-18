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

import com.madgag.git.RichRepo
import com.madgag.git.test.unpackRepo
import com.madgag.scalagithub.{AccountCredentials, GitHub, GitHubCredentials, GitHubResponse}
import com.madgag.scalagithub.commands.CreateRepo
import com.madgag.scalagithub.model.Repo
import org.eclipse.jgit.api.{CloneCommand, Git}
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.Constants.R_HEADS
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.scalatest.Inspectors.forAll
import org.scalatest.matchers.should.Matchers.all

import java.nio.file.Files.createTempDirectory
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.IterableHasAsScala

/*
class ToastRepoCreation(
  testFixtureAccountCredentials: AccountCredentials,
  testRepoNamePrefix: String
) {
  implicit val github: GitHub = testFixtureAccountCredentials.gitHub

  def createTestRepo(fileName: String)(implicit ec: ExecutionContext): Future[Repo] = {
    val localGitRepo = unpackRepo(fileName)
    for {
      testGithubRepo <- createBlankGitHubRepo()
      creds <- testFixtureAccountCredentials.credentials()
      _ = pushLocalRepoToGitHub(localGitRepo, testGithubRepo, creds)
      _ <- validateGitHubAPISuccessfullyRepresentsLocalRepo(testGithubRepo, localGitRepo)
    } yield testGithubRepo
  }

  private def createBlankGitHubRepo()(implicit ec: ExecutionContext): Future[Repo] = for {
    testRepoId <- testFixtureAccountCredentials.account.createRepo(CreateRepo(
      name = testRepoNamePrefix + System.currentTimeMillis().toString,
      `private` = false
    )).map(_.repoId)
    testGithubRepo <- github.getRepo(testRepoId)
  } yield testGithubRepo

  private def validateGitHubAPISuccessfullyRepresentsLocalRepo(testGithubRepo: Repo, localGitRepo: FileRepository): Future[Unit] = {

    val branchRefs: Seq[Ref] = localGitRepo.getRefDatabase.getRefsByPrefix(R_HEADS).asScala.toSeq

    eventually {
      whenReady(testGithubRepo.refs.list().all()) { _ should not be empty }

      forAll(branchRefs) { branchRef =>
        val truncated = branchRef.getName.stripPrefix("refs/")
        whenReady(testGithubRepo.refs.get(truncated))(_.result.objectId shouldBe branchRef.getObjectId)
      }
    }

    val clonedRepo = eventually {
      creds.applyAuthTo[CloneCommand, Git](Git.cloneRepository()).setBare(true).setURI(testGithubRepo.clone_url)
        .setDirectory(createTempDirectory("test-repo").toFile).call()
    }
    require(clonedRepo.getRepository.findRef(defaultBranchName).getObjectId == localGitRepo.resolve("HEAD"))

    require(testGithubRepo.default_branch == "main") // TODO remove this...?
  }

  private def pushLocalRepoToGitHub(localGitRepo: FileRepository, testGithubRepo: Repo, creds: GitHubCredentials): Unit = {
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
}
*/