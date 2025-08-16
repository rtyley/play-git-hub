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

import com.madgag.ratelimitstatus.{QuotaUpdate, RateLimit}
import com.madgag.rfc5988link.{LinkParser, LinkTarget}
import fastparse.parse
import play.api.http.Status.NOT_MODIFIED
import sttp.model.{HasHeaders, Header, Uri}

import java.time.Duration.ofHours
import java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
import java.time.{Instant, ZonedDateTime}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.language.implicitConversions

object ResponseMeta {
  
  val GitHubRateLimit: RateLimit = RateLimit(ofHours(1))

  def rateLimitStatusFrom(headers: HasHeaders): Option[RateLimit.Status] = for {
    remaining <- headers.header("X-RateLimit-Remaining")
    limit <- headers.header("X-RateLimit-Limit")
    reset <- headers.header("X-RateLimit-Reset")
    date <- headers.header("Date")
  } yield GitHubRateLimit.statusFor(QuotaUpdate(
    remaining = remaining.toInt,
    limit = limit.toInt,
    reset = Instant.ofEpochSecond(reset.toLong),
    capturedAt = ZonedDateTime.parse(date, RFC_1123_DATE_TIME).toInstant
  ))

  def rateLimitFrom(notModified: Boolean, headers: Option[HasHeaders]): Quota = Quota(
    consumed = if (notModified) 1 else 0,
    headers.flatMap(rateLimitStatusFrom)
  )

  def requestScopesFrom(headers: HasHeaders): Option[RequestScopes] = {
    def scopes(h: String): Option[Set[String]] = headers.header(h).map(_.split(',').map(_.trim).toSet)

    for {
      oAuthScopes <- scopes("X-OAuth-Scopes")
      acceptedOAuthScopes <- scopes("X-Accepted-OAuth-Scopes")
    } yield RequestScopes(oAuthScopes, acceptedOAuthScopes)
  }

  def linksFrom(headers: HasHeaders): Seq[LinkTarget] =
    headers.headers("Link").flatMap(parse(_, LinkParser.linkValues(_)).get.value)
  
  def from(resp: sttp.client4.Response[_]) = ResponseMeta(
    rateLimitFrom(resp.code.code == NOT_MODIFIED, Some(resp)),
    requestScopesFrom(resp),
    linksFrom(resp)
  )
}

case class ResponseMeta(quota: Quota, requestScopes: Option[RequestScopes], links: Seq[LinkTarget]) {
  val nextOpt: Option[Uri] = links.find(_.relOpt.contains("next")).map(_.uri)
}

case class StoredMeta(nextOpt: Option[Uri])
