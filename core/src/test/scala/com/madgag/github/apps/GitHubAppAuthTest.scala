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

package com.madgag.github.apps

import cats.effect.*
import sttp.client4.httpclient.cats.HttpClientCatsBackend
import weaver.IOSuite

object GitHubAppAuthTest extends IOSuite {

  type Res = GitHubAppAuth

  def sharedResource : Resource[IO, Res] = HttpClientCatsBackend.resource[IO]().map { httpBackend =>
    GitHubAppAuth(GitHubAppJWTs.fromConfigMap(sys.env, prefix="PLAY_GIT_HUB_TEST"), httpBackend)
  }

  test("be able to request GitHub App installations") { (gitHubAppAuth, log) => for {
      installation <- gitHubAppAuth.getSoleInstallation()
      _ <- log.info(s"Got installation: $installation")
    } yield {
      expect(clue(installation.id) == 80021990) and
        expect(clue(installation.account.login) == "play-git-hub-test-org")
    }
  }

}
