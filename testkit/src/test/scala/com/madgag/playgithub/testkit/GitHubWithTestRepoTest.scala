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
import cats.effect.*
import cats.effect.std.Dispatcher
import com.madgag.git.test.pathForResource
import com.madgag.github.AccessToken
import com.madgag.github.apps.{GitHubAppAuth, GitHubAppJWTs, InstallationAccess}
import com.madgag.scalagithub.commands.{Base64EncodedBytes, CreateOrUpdateFile, DeleteFile}
import com.madgag.scalagithub.{ClientWithAccess, GitHub, GitHubAppAccess}
import org.scalatest.OptionValues
import sttp.client4.httpclient.cats.HttpClientCatsBackend
import weaver.IOSuite

import java.util.Base64
import scala.concurrent.ExecutionContext.Implicits.global

object GitHubWithTestRepoTest extends IOSuite with OptionValues {
  type Res = ClientWithAccess[GitHubAppAccess]

  def sharedResource: Resource[IO, Res] = for {
    httpBackend <- HttpClientCatsBackend.resource[IO]()
    dispatcher <- Dispatcher.parallel[IO]
    factory <- GitHub.Factory()
    clientWithContext <- Resource.eval(factory.accessSoleAppInstallation(GitHubAppJWTs.fromConfigMap(sys.env, prefix = "PLAY_GIT_HUB_TEST")))
  } yield clientWithContext

  test("create and delete a file") { installationAccess =>
    TestRepoCreation(installationAccess, "test-creating-and-deleting-files").use { testRepoCreation =>
      given gitHub: GitHub = testRepoCreation.gitHub
      val testFile = "test.txt"
      val fileContent = "Hello World!".getBytes
      for {
        repo <- testRepoCreation.createTestRepo(pathForResource("/small-example.git.zip", getClass))
        _ <- repo.contentsFile.createOrUpdate(testFile, CreateOrUpdateFile("My creation message", Base64EncodedBytes(fileContent)))
        content <- repo.contentsFile.get(testFile)
        deleteCommit <- repo.contentsFile.delete(testFile, DeleteFile("My deletion message", content.result.sha))
      } yield {
        expect(clue(content.result.size) == fileContent.length) and expect(clue(deleteCommit.result.commit.message) == "My deletion message")
      }
    }
  }
}
