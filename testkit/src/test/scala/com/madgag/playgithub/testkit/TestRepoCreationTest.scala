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
import com.madgag.github.apps.{GitHubAppAuth, GitHubAppJWTs, InstallationAccess}
import org.scalatest.OptionValues
import sttp.client4.httpclient.cats.HttpClientCatsBackend
import weaver.IOSuite

object TestRepoCreationTest extends IOSuite with OptionValues {

  type Res = InstallationAccess

  def sharedResource: Resource[IO, Res] = for {
    httpBackend <- HttpClientCatsBackend.resource[IO]()
    dispatcher <- Dispatcher.parallel[IO]
    installationAccess <- Resource.eval(GitHubAppAuth(GitHubAppJWTs.fromConfigMap(sys.env, prefix = "PLAY_GIT_HUB_TEST"), httpBackend).accessSoleInstallation()(using dispatcher))
  } yield installationAccess

  test("reliably create test repos") { installationAccess =>
    TestRepoCreation(installationAccess, "test-creating-test-repos").use { testRepoCreation =>
      val gitHub = testRepoCreation.gitHub
      val reps = 35
      val fetchCount = gitHub.gitHubHttp.fetching.fetchCount
      for {
        startCount <- fetchCount // initialising the `TestRepoCreation` can itself make some requests
        duration <- testRepoCreation.createTestRepo(pathForResource("/small-example.git.zip", getClass)).parReplicateA_(reps).timed.map(_._1)
        endCount <- fetchCount
      } yield {
        val fetchesExecutedOverRun = endCount - startCount
        val consumedPerRep = fetchesExecutedOverRun.toFloat / reps
        println(f"Consumed $fetchesExecutedOverRun in ${duration.toMillis}ms - $consumedPerRep%.1f per rep")
        expect(clue(consumedPerRep) <= 7f)
      }
    }
  }

}
