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
import com.madgag.github.apps.{GitHubAppAuth, InstallationAccess}
import com.madgag.scalagithub.AccountAccess
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should
import org.scalatest.time.{Seconds, Span}

import scala.concurrent.ExecutionContext.Implicits.global

class ToastRepoCreationTest extends AnyFlatSpec with should.Matchers with ScalaFutures with OptionValues {
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(90, Seconds)), interval = scaled(Span(2, Seconds)))

  val installationAccess: InstallationAccess =
    GitHubAppAuth.fromConfigMap(sys.env, prefix = "PLAY_GIT_HUB_TEST").accessSoleInstallation().futureValue


  "Dunk" should "create a test repo" in {
    val accountCredentials = AccountAccess(installationAccess.installedOnAccount, installationAccess.credentials)
    println(accountCredentials.gitHub.checkRateLimit().futureValue.value.summary)
    val toastRepoCreation = new ToastRepoCreation(
      accountCredentials,
      "funky-times"
    )


    for (_ <- 1 to 20) {
      toastRepoCreation.createTestRepo("/feature-branches.top-level-config.git.zip").unsafeToFuture().futureValue
      println(accountCredentials.gitHub.checkRateLimit().futureValue.value.summary)
    }
  }
}
