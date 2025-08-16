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

import cats.*
import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.madgag.github.AccessToken
import com.madgag.github.apps.GitHubAppAuth
import com.madgag.scalagithub.model.{PullRequest, RepoId}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, OptionValues}

import java.time.Instant
import scala.concurrent.ExecutionContext

class GitHubTest extends AsyncFlatSpec with AsyncIOSpec with Matchers with OptionValues with ScalaFutures with IntegrationPatience {

  implicit override def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  val appGitHub = (for {
    installationAccess <- GitHubAppAuth.fromConfigMap(sys.env, prefix = "PLAY_GIT_HUB_TEST").accessSoleInstallation()
  } yield {
    val accountAccess = installationAccess.accountAccess()
    println(s"Installation account: ${installationAccess.installedOnAccount.atLogin}")
    accountAccess.gitHub
  }).unsafeRunSync()

  it should "be able to make a request" in {
    appGitHub.getUser("rtyley").map(_.result).asserting(_.name.value should startWith("Roberto"))
  }

  it should "be able to get a public repo" in {
    appGitHub.getRepo(RepoId("rtyley", "bfg-repo-cleaner")).map(_.result).asserting(_.id shouldBe 7266492)
  }

  it should "be able to get a public PR by id" in { // https://github.com/rtyley/bfg-repo-cleaner/pull/527
    appGitHub.getPullRequest(PullRequest.Id(RepoId("rtyley", "bfg-repo-cleaner"), 527)).map(_.result).asserting(_.merged_by.value.login shouldBe "rtyley")
  }

  it should "be able get the 'merged' state of a PR" in {
    given GitHub = appGitHub
    for {
      repo <- appGitHub.getRepo(RepoId("rtyley", "play-git-hub"))
      repoPRs = repo.result.pullRequests
      mergedPr <- repoPRs.get(2).map(_.result)
      unmergedPr <- repoPRs.get(16).map(_.result)
    } yield {
      mergedPr.merged.value shouldBe true
      mergedPr.merged_by.value.login shouldBe "rtyley"

      unmergedPr.merged.value shouldBe false
      unmergedPr.merged_by shouldBe None
    }
  }

  it should "be able list PRs" in {
    given GitHub = appGitHub
    for {
      repo <- appGitHub.getRepo(RepoId("rtyley", "play-git-hub")).map(_.result)
      prs <- repo.pullRequests.list(Map("state" -> "closed")).compile.toList
    } yield {
      prs.size should be > 2
    }
  }

  it should "be able to list repos accessible to the installation" in {
    appGitHub.listReposAccessibleToTheApp().take(1).compile.toList.asserting { installationRepos =>
      installationRepos.head.total_count should be > 0 // needs to be granted access to at least one repo!
    }
  }

  it should "be able to get rate limit info" in {
    appGitHub.checkRateLimit().asserting(_.value.quotaUpdate.limit should be > 1000)
  }

  {
    val userGitHub: GitHub =
      new GitHub(GitHubCredentials.Provider.fromStatic(AccessToken(sys.env("PLAY_GIT_HUB_TEST_GITHUB_ACCESS_TOKEN"))))

    it should "be able to get a decent rate limit" in {
      userGitHub.checkRateLimit().asserting(_.value.quotaUpdate.limit should be > 500)
    }

    it should "be able to get the authenticated user" in {
      userGitHub.getUser().map(_.result).asserting(_.created_at.value.toInstant should be < Instant.now())
    }
  }
}
