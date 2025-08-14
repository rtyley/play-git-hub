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
import okhttp3._
import play.api.http.Status.NOT_MODIFIED

import java.time.Duration.ofHours
import java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
import java.time.{Instant, ZonedDateTime}
import scala.jdk.CollectionConverters._
import scala.language.implicitConversions

object ResponseMeta {
  val GitHubRateLimit: RateLimit = RateLimit(ofHours(1))

  implicit class RichHeaders(headers: Headers) {
    def getOpt(name: String): Option[String] = Option(headers.get(name))
  }

  def rateLimitStatusFrom(headers: Headers): Option[RateLimit.Status] = for {
    remaining <- headers.getOpt("X-RateLimit-Remaining")
    limit <- headers.getOpt("X-RateLimit-Limit")
    reset <- headers.getOpt("X-RateLimit-Reset")
    date <- headers.getOpt("Date")
  } yield GitHubRateLimit.statusFor(QuotaUpdate(
    remaining = remaining.toInt,
    limit = limit.toInt,
    reset = Instant.ofEpochSecond(reset.toLong),
    capturedAt = ZonedDateTime.parse(date, RFC_1123_DATE_TIME).toInstant
  ))

  def rateLimitFrom(response: Response): Quota = {
    val networkResponse = Option(response.networkResponse())
    Quota(
      consumed = if (networkResponse.exists(_.code != NOT_MODIFIED)) 1 else 0,
      networkResponse.flatMap(resp => rateLimitStatusFrom(resp.headers))
    )
  }

  def requestScopesFrom(response: Response): Option[RequestScopes] = {
    def scopes(h: String): Option[Set[String]] = Option(response.header(h)).map(_.split(',').map(_.trim).toSet)

    for {
      oAuthScopes <- scopes("X-OAuth-Scopes")
      acceptedOAuthScopes <- scopes("X-Accepted-OAuth-Scopes")
    } yield RequestScopes(oAuthScopes, acceptedOAuthScopes)
  }

  def linksFrom(response: Response): Seq[LinkTarget] = for {
    linkHeader <- response.headers("Link").asScala.toSeq
    linkTargets <- parse(linkHeader, LinkParser.linkValues(_)).get.value
  } yield linkTargets

  def from(resp: Response) =
    ResponseMeta(rateLimitFrom(resp), requestScopesFrom(resp), linksFrom(resp))
}

case class ResponseMeta(quota: Quota, requestScopes: Option[RequestScopes], links: Seq[LinkTarget]) {
  val nextOpt: Option[HttpUrl] = links.find(_.relOpt.contains("next")).map(_.url)
}