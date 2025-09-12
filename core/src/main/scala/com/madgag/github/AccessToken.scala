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

package com.madgag.github

import cats.effect.IO
import com.github.blemale.scaffeine.{AsyncLoadingCache, Scaffeine}
import com.madgag.github.AccessToken.Cache.timeUntilNearExpirationOf
import com.madgag.scalagithub.GitHubCredentials
import play.api.libs.json.{JsPath, Reads}

import java.time.Clock.systemUTC
import java.time.{Clock, Duration, Instant}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.DurationConverters.*

case class AccessToken(value: String) extends AnyVal

object AccessToken {
  implicit val reads: Reads[AccessToken] = JsPath.read[String].map(AccessToken(_))

  class Cache(provider: () => Future[Expirable[AccessToken]])(implicit ec: ExecutionContext)
    extends GitHubCredentials.Provider {

    private val cache: AsyncLoadingCache[Unit, Expirable[GitHubCredentials]] = Scaffeine()
      .maximumSize(1)
      .expireAfter[Unit, Expirable[GitHubCredentials]](
        create = (_, token) => timeUntilNearExpirationOf(token),
        update = (_, token, _) => timeUntilNearExpirationOf(token),
        read = (_, _, currentDuration) => currentDuration
      )
      .buildAsyncFuture(_ => provider().map(_.map(GitHubCredentials(_))))

    override def apply(): IO[GitHubCredentials] = IO.fromFuture(IO(cache.get(()).map(_.value)))
  }

  object Cache {
    def timeUntilNearExpirationOf(expirable: Expirable[_])(implicit clock: Clock = systemUTC): FiniteDuration =
      Duration.between(clock.instant(), expirable.expires).toScala * 9 div 10
  }
}

case class Expirable[T](
  value: T,
  expires: Instant
) {
  def map[S](f: T => S): Expirable[S] = copy(f(value))
}

