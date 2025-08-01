/*
 * Copyright 2023 Roberto Tyley
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

import org.apache.pekko.stream.Materializer
import com.madgag.git.RichRepo
import com.madgag.git.test.unpackRepo
import com.madgag.github.Implicits._
import com.madgag.scalagithub.commands.CreateRepo
import com.madgag.scalagithub.model.Repo
import com.madgag.scalagithub.{GitHub, GitHubCredentials}
import com.madgag.time.Implicits._
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.scalatest.Inspectors.forAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers._

import java.nio.file.Files.createTempDirectory
import java.time.Duration.ofMinutes
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

trait TestRepoCreation extends Eventually with ScalaFutures {

  val testRepoNamePrefix: String
  val githubCredentials: GitHubCredentials
  implicit val github: GitHub
  implicit val materializer: Materializer
  val repoLifecycle: RepoLifecycle

  def isOldTestRepo(repo: Repo): Boolean =
    repo.name.startsWith(testRepoNamePrefix) && repo.created_at.toInstant.age() > ofMinutes(30)

  def deleteTestRepos()(implicit ec: ExecutionContext): Future[Unit] = for {
    oldRepos <- repoLifecycle.listAllRepos()
    _ <- Future.traverse(oldRepos.filter(isOldTestRepo))(_.delete())
  } yield ()

  def createTestRepo(fileName: String)(implicit ec: ExecutionContext): Repo = {
    val cr = CreateRepo(
      name = testRepoNamePrefix + System.currentTimeMillis().toString,
      `private` = false
    )

    val testRepoId = repoLifecycle.createRepo(cr).futureValue.repoId

    val localGitRepo = unpackRepo(fileName)

    val testGithubRepo = eventually { github.getRepo(testRepoId).futureValue }

    val config = localGitRepo.getConfig
    config.setString("remote", "origin", "url", testGithubRepo.clone_url)
    config.save()

    val defaultBranchName = testGithubRepo.default_branch
    if (Option(localGitRepo.findRef(defaultBranchName)).isEmpty) {
      println(s"Going to create a '$defaultBranchName' branch")
      localGitRepo.git.branchCreate().setName(defaultBranchName).setStartPoint("HEAD").call()
    }

    val pushResults =
      localGitRepo.git.push.setCredentialsProvider(githubCredentials.git).setPushTags().setPushAll().call()

    forAll (pushResults.asScala) { pushResult =>
      all (pushResult.getRemoteUpdates.asScala.map(_.getStatus)) shouldBe RemoteRefUpdate.Status.OK
    }

    eventually {
      whenReady(testGithubRepo.refs.list().all()) { _ should not be empty }
    }

    val clonedRepo = eventually {
       Git.cloneRepository().setBare(true).setURI(testGithubRepo.clone_url)
         .setDirectory(createTempDirectory("test-repo").toFile).call()
    }
    require(clonedRepo.getRepository.findRef(defaultBranchName).getObjectId == localGitRepo.resolve("HEAD"))

    testGithubRepo
  }
}