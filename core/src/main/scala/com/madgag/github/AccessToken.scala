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

import com.github.blemale.scaffeine.{AsyncLoadingCache, Scaffeine}
import com.madgag.github.AccessTokenCache.timeUntilNearExpirationOf
import com.madgag.scalagithub.GitHubCredentials
import play.api.libs.json.{JsPath, Reads}

import java.time.Clock.systemUTC
import java.time.{Clock, Duration, Instant}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.DurationConverters._

case class AccessToken(value: String) extends AnyVal

object AccessToken {
  implicit val reads: Reads[AccessToken] = JsPath.read[String].map(AccessToken(_))
}

case class Expirable[T](
  value: T,
  expires: Instant
) {
  def map[S](f: T => S): Expirable[S] = copy(f(value))
}

class AccessTokenCache(provider: () => Future[Expirable[AccessToken]])(implicit ec: ExecutionContext)
  extends (() => Future[GitHubCredentials]) {

  private val cache: AsyncLoadingCache[Unit, Expirable[GitHubCredentials]] = Scaffeine()
    .maximumSize(1)
    .expireAfter[Unit, Expirable[GitHubCredentials]](
      create = (_, token) => timeUntilNearExpirationOf(token),
      update = (_, token, _) => timeUntilNearExpirationOf(token),
      read = (_, _, currentDuration) => currentDuration
    )
    .buildAsyncFuture(_ => provider().map(_.map(GitHubCredentials(_))))

  override def apply(): Future[GitHubCredentials] = cache.get(()).map(_.value)
}

object AccessTokenCache {
  def timeUntilNearExpirationOf(expirable: Expirable[_])(implicit clock: Clock = systemUTC): FiniteDuration =
    Duration.between(clock.instant(), expirable.expires).toScala * 9 div 10
}
