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

package com.madgag.scalagithub

import com.madgag.github.Implicits._
import com.madgag.github.apps.{GitHubAppAuth, InstallationAccess}
import com.madgag.scalagithub.GitHubCredentials.Provider
import com.madgag.scalagithub.model.{Account, RepoId}
import org.apache.pekko.actor.ActorSystem
import org.scalatest.OptionValues
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext.Implicits.global

class GitHubTest extends AnyFlatSpec with Matchers with OptionValues with ScalaFutures with IntegrationPatience {
  private val installationAccess: InstallationAccess =
    GitHubAppAuth.fromConfigMap(sys.env, prefix = "PLAY_GIT_HUB_TEST").accessSoleInstallation().futureValue

  println(s"Installation account: ${installationAccess.installedOnAccount.atLogin}")

  val gitHub: GitHub = new GitHub(installationAccess.credentials)

  it should "be able to make a request" in {
    gitHub.getUser("rtyley").futureValue.result.name.value should startWith("Roberto")
  }

  it should "be able to get a public repo" in {
    gitHub.getRepo(RepoId("rtyley", "bfg-repo-cleaner")).futureValue.result.id shouldBe 7266492
  }

  it should "be able to list repos accessible to the installation" in {
    implicit val sys: ActorSystem = ActorSystem("MyTest")

    val installationReposHead = gitHub.listReposAccessibleToTheApp().allItems().futureValue.head
    installationReposHead.total_count should be > 0 // needs to be granted access to at least one repo!
  }

  it should "be able to get rate limit info" in {
    gitHub.checkRateLimit().futureValue.value.quotaUpdate.limit shouldBe > (1000)
  }
}
