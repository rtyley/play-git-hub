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
import okhttp3.*
import play.api.http.Status.NOT_MODIFIED
import sourcecode.Text.generate

import java.time.Duration.ofHours
import java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
import java.time.{Instant, ZonedDateTime}
import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions
import scala.jdk.OptionConverters._

object ResponseMeta {
  
  trait Transformations[T] {
    
  }
  
  val GitHubRateLimit: RateLimit = RateLimit(ofHours(1))

  implicit class RichHeaders(headers: Headers) {
    def toJdkHeaders: java.net.http.HttpHeaders =
      java.net.http.HttpHeaders.of(headers.toMultimap, (_,_) => true )
  }

  def rateLimitStatusFrom(headers: java.net.http.HttpHeaders): Option[RateLimit.Status] = for {
    remaining <- headers.firstValue("X-RateLimit-Remaining").toScala
    limit <- headers.firstValue("X-RateLimit-Limit").toScala
    reset <- headers.firstValue("X-RateLimit-Reset").toScala
    date <- headers.firstValue("Date").toScala
  } yield GitHubRateLimit.statusFor(QuotaUpdate(
    remaining = remaining.toInt,
    limit = limit.toInt,
    reset = Instant.ofEpochSecond(reset.toLong),
    capturedAt = ZonedDateTime.parse(date, RFC_1123_DATE_TIME).toInstant
  ))

  def rateLimitFrom(notModified: Boolean, headers: Option[java.net.http.HttpHeaders]): Quota = Quota(
    consumed = if (notModified) 1 else 0,
    headers.flatMap(rateLimitStatusFrom)
  )

  def requestScopesFrom(headers: java.net.http.HttpHeaders): Option[RequestScopes] = {
    def scopes(h: String): Option[Set[String]] = headers.firstValue(h).toScala.map(_.split(',').map(_.trim).toSet)

    for {
      oAuthScopes <- scopes("X-OAuth-Scopes")
      acceptedOAuthScopes <- scopes("X-Accepted-OAuth-Scopes")
    } yield RequestScopes(oAuthScopes, acceptedOAuthScopes)
  }

  def linksFrom(headers: java.net.http.HttpHeaders): Seq[LinkTarget] = for {
    linkHeader <- headers.allValues("Link").asScala.toSeq
    linkTargets <- parse(linkHeader, LinkParser.linkValues(_)).get.value
  } yield linkTargets

  def from(resp: Response): ResponseMeta = {
    val headers = resp.headers.toJdkHeaders
    ResponseMeta(
      rateLimitFrom(resp.code == NOT_MODIFIED, Option(resp.networkResponse).map(_.headers.toJdkHeaders)),
      requestScopesFrom(headers),
      linksFrom(headers)
    )
  }

  def from(resp: java.net.http.HttpResponse[_]) = ResponseMeta(
    rateLimitFrom(resp.statusCode == NOT_MODIFIED, Some(resp.headers)),
    requestScopesFrom(resp.headers),
    linksFrom(resp.headers)
  )
}

case class ResponseMeta(quota: Quota, requestScopes: Option[RequestScopes], links: Seq[LinkTarget]) {
  val nextOpt: Option[HttpUrl] = links.find(_.relOpt.contains("next")).map(_.url)
}

case class StoredMeta(nextOpt: Option[HttpUrl])
