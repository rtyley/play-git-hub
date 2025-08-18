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

import com.madgag.git.RichRepo
import com.madgag.git.test.unpackRepo
import com.madgag.github.Implicits._
import com.madgag.scalagithub.{AccountCredentials, GitHub}
import com.madgag.scalagithub.GitHub.FR
import com.madgag.scalagithub.GitHubCredentials.Provider
import com.madgag.scalagithub.commands.CreateRepo
import com.madgag.scalagithub.model.{Account, Repo}
import com.madgag.time.Implicits._
import org.apache.pekko.actor.ActorSystem
import org.eclipse.jgit.api.{CloneCommand, Git}
import org.eclipse.jgit.lib.Constants.R_HEADS
import org.eclipse.jgit.lib.Ref
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
  val testFixtureAccountCredentials: AccountCredentials

  implicit lazy val github: GitHub = new GitHub(testFixtureAccountCredentials.credentials)
  implicit val actorSystem: ActorSystem

  def isOldTestRepo(repo: Repo): Boolean =
    repo.name.startsWith(testRepoNamePrefix) && repo.created_at.toInstant.age() > ofMinutes(30)

  def deleteTestRepos()(implicit ec: ExecutionContext): Future[Unit] = for {
    oldRepos <- testFixtureAccountCredentials.account.listRepos().all()
    _ <- Future.traverse(oldRepos.filter(isOldTestRepo))(_.delete())
  } yield ()

  def eventuallyConsistent[T](thunk: => FR[T]): T = eventually {
    import org.scalatest.matchers.must.Matchers._

    val result = thunk.futureValue.result
    Thread.sleep(1000)
    thunk.futureValue.result mustEqual result
    result
  }

  def createTestRepo(fileName: String)(implicit ec: ExecutionContext): Repo = {
    val cr = CreateRepo(
      name = testRepoNamePrefix + System.currentTimeMillis().toString,
      `private` = false
    )

    val testRepoId = testFixtureAccountCredentials.account.createRepo(cr).futureValue.repoId

    val localGitRepo = unpackRepo(fileName)

    val testGithubRepo = // TODO eventuallyConsistent
      eventually { github.getRepo(testRepoId).futureValue }

    val config = localGitRepo.getConfig
    config.setString("remote", "origin", "url", testGithubRepo.clone_url)
    config.save()

    val defaultBranchName = testGithubRepo.default_branch
    if (Option(localGitRepo.findRef(defaultBranchName)).isEmpty) {
      println(s"Going to create a '$defaultBranchName' branch")
      localGitRepo.git.branchCreate().setName(defaultBranchName).setStartPoint("HEAD").call()
    }

    val branchRefs: Seq[Ref] = localGitRepo.getRefDatabase.getRefsByPrefix(R_HEADS).asScala.toSeq

    val creds = testFixtureAccountCredentials.credentials().futureValue
    val pushResults =
      localGitRepo.git.push.setCredentialsProvider(creds.git).setPushTags().setPushAll().call()

    forAll (pushResults.asScala) { pushResult =>
      all (pushResult.getRemoteUpdates.asScala.map(_.getStatus)) shouldBe RemoteRefUpdate.Status.OK
    }

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
    testGithubRepo
  }
}