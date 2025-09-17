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
import cats.effect.testing.scalatest.AsyncIOSpec
import com.madgag.git.test.pathForResource
import com.madgag.github.apps.{GitHubAppAuth, InstallationAccess}
import com.madgag.scala.collection.decorators.*
import com.madgag.scalagithub.AccountAccess
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should
import org.scalatest.time.{Seconds, Span}

import scala.concurrent.ExecutionContext

class ToastRepoCreationTest extends AsyncFlatSpec with AsyncIOSpec with should.Matchers with ScalaFutures with OptionValues {

  implicit override def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(90, Seconds)), interval = scaled(Span(2, Seconds)))

  val installationAccess: InstallationAccess =
    GitHubAppAuth.fromConfigMap(sys.env, prefix = "PLAY_GIT_HUB_TEST").accessSoleInstallation().futureValue

  val accountAccess = AccountAccess(installationAccess.installedOnAccount, installationAccess.credentials)

  val toastRepoCreation = new ToastRepoCreation(
    accountAccess,
    "funky-times"
  )

  it should "reliably create test repos" in {
    for {
      startConsumption <- accountAccess.gitHub.checkRateLimit().map(_.value.quotaUpdate.consumed)
      reps = 35
      duration <- toastRepoCreation.createTestRepo(pathForResource("/bunk.git.zip", getClass)).parReplicateA_(reps).timed.map(_._1)
      endConsumption <- accountAccess.gitHub.checkRateLimit().map(_.value.quotaUpdate.consumed)
    } yield {
      val consumedOverRun = endConsumption - startConsumption
      val consumedPerRep = consumedOverRun.toFloat / reps
      println(f"Consumed $consumedOverRun in ${duration.toMillis}ms - $consumedPerRep%.1f per rep")
      consumedPerRep should be <= 8f
    }
  }

  it should "delete old test repos" in {
    for {
      result <- toastRepoCreation.deleteTestRepos()
    } yield {
      println(result.mapV(_.map(_.url).mkString(", ")))
      1 shouldBe 1
    }
  }

}
