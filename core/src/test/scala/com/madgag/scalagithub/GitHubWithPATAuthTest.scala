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
import cats.effect.*
import cats.effect.std.Dispatcher
import com.madgag.github.AccessToken
import org.scalatest.OptionValues
import sttp.client4.httpclient.cats.HttpClientCatsBackend
import weaver.IOSuite

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.math.Ordering.Implicits.*

object GitHubWithPATAuthTest extends IOSuite with OptionValues {

  type Res = GitHub

  def sharedResource : Resource[IO, Res] = for {
    githubFactory <- GitHub.Factory()
    clientWithContext <- Resource.eval(githubFactory.accessWithUserToken(AccessToken(sys.env("PLAY_GIT_HUB_TEST_GITHUB_ACCESS_TOKEN"))))
  } yield clientWithContext.gitHub

  test("get a decent rate limit") {
    _.checkRateLimit().map { rl =>
      expect(clue(rl.value.quotaUpdate.limit) > 500)
    }
  }

  test("get the authenticated user") {
    _.getUser().map { user =>
      expect(clue(user.result.created_at).exists(_.toInstant < Instant.now()))
    }
  }
}
