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
import cats.effect.std.Dispatcher
import cats.effect.*
import com.madgag.github.apps.{GitHubAppAuth, GitHubAppJWTs}
import com.madgag.scalagithub.model.{PullRequest, RepoId}
import org.scalatest.OptionValues
import sttp.client4.httpclient.cats.HttpClientCatsBackend
import weaver.IOSuite

import scala.concurrent.ExecutionContext.Implicits.global

object GitHubWithAppAuthTest extends IOSuite with OptionValues {

  type Res = GitHub

  def sharedResource : Resource[IO, Res] = for {
    githubFactory <- GitHub.Factory()
    accountAccess <- Resource.eval(githubFactory.accessSoleAppInstallation(GitHubAppJWTs.fromConfigMap(sys.env, prefix = "PLAY_GIT_HUB_TEST")))
  } yield accountAccess.gitHub

  test("make a request") {
    _.getUser("rtyley").map { user =>
      expect(clue(user.name).exists(_.startsWith("Roberto")))
    }
  }

  test("be able to get a public repo") {
    _.getRepo(RepoId("rtyley", "bfg-repo-cleaner")).map { repo =>
      expect(clue(repo.id) == 7266492)
    }
  }

  test("get a public PR by id") { // https://github.com/rtyley/bfg-repo-cleaner/pull/527
    _.getPullRequest(PullRequest.Id(RepoId("rtyley", "bfg-repo-cleaner"), 527)).map { pr =>
      expect(clue(pr.merged_by.value.login) == "rtyley")
    }
  }

  test("get the 'merged' state of a PR") { appGitHub =>
    given GitHub = appGitHub
    for {
      repo <- appGitHub.getRepo(RepoId("rtyley", "play-git-hub"))
      repoPRs = repo.pullRequests
      mergedPr <- repoPRs.get(2)
      unmergedPr <- repoPRs.get(16)
    } yield {
      expect(clue(mergedPr.merged.value) == true) and
      expect(clue(mergedPr.merged_by.map(_.login)).contains("rtyley")) and
      expect(clue(unmergedPr.merged.value) == false) and
      expect(clue(unmergedPr.merged_by).isEmpty)
    }
  }

  test("list PRs") { appGitHub =>
    given GitHub = appGitHub
    for {
      repo <- appGitHub.getRepo(RepoId("rtyley", "play-git-hub"))
      prs <- repo.pullRequests.list(Map("state" -> "closed")).compile.toList
    } yield expect(clue(prs.size) > 2)
  }

  test("list repos accessible to the installation") {
    _.listReposAccessibleToTheApp().take(1).compile.toList.map { installationRepos =>
      expect(clue(installationRepos.head.total_count) > 0) // needs to be granted access to at least one repo!
    }
  }

  test("get rate limit info") {
    _.checkRateLimit().map { rl =>
      expect(clue(rl.value.quotaUpdate.limit) > 1000)
    }
  }
}
