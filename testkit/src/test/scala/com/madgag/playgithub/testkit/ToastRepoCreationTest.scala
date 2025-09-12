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
import cats.effect.unsafe.implicits.global
import com.madgag.git.test.unpackRepo
import com.madgag.github.apps.{GitHubAppAuth, InstallationAccess}
import com.madgag.scalagithub.AccountAccess
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should
import org.scalatest.time.{Seconds, Span}

import java.net.URL
import java.nio.file.Path
import scala.concurrent.ExecutionContext.Implicits.global

class ToastRepoCreationTest extends AnyFlatSpec with should.Matchers with ScalaFutures with OptionValues {
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(90, Seconds)), interval = scaled(Span(2, Seconds)))

  val installationAccess: InstallationAccess =
    GitHubAppAuth.fromConfigMap(sys.env, prefix = "PLAY_GIT_HUB_TEST").accessSoleInstallation().futureValue


  "Dunk" should "create a test repo" in {
    val accountAccess = AccountAccess(installationAccess.installedOnAccount, installationAccess.credentials)
    println(accountAccess.gitHub.checkRateLimit().futureValue.value.summary)
    val toastRepoCreation = new ToastRepoCreation(
      accountAccess,
      "funky-times"
    )

    val reps = 20

    def quotaConsumedSoFar() = {
      accountAccess.gitHub.checkRateLimit().futureValue.value.quotaUpdate.consumed
    }

    val start = quotaConsumedSoFar()
    for (_ <- 1 to 15) {
      val repoFilePath = "/bunk.git.zip"

      val resource: URL = getClass.getResource(repoFilePath)
      assert(resource != null, s"Resource for $repoFilePath is null.")

      val zippedRepo = Path.of(resource.toURI)
      val localGitRepo = unpackRepo(zippedRepo)

      toastRepoCreation.createTestRepo(localGitRepo).unsafeToFuture().futureValue
      println(accountAccess.gitHub.checkRateLimit().futureValue.value.summary)
    }
    val end = quotaConsumedSoFar()

    val consumedOverRun = end - start
    println(s"Consumed $consumedOverRun (${consumedOverRun.toFloat / reps}) per rep")
  }

}
