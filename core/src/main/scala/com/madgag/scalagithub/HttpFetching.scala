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

import cats.Endo
import com.gu.etagcaching.fetching.{ETaggedData, Fetching, Missing, MissingOrETagged}

import java.net.HttpURLConnection.{HTTP_NOT_FOUND, HTTP_NOT_MODIFIED}
import java.net.URI
import java.net.http.HttpResponse.BodyHandler
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.{Future, ExecutionContext as EC}
import scala.jdk.FutureConverters.*
import scala.jdk.OptionConverters.*

class HttpFetching[T](
  httpClient: HttpClient,
  bodyHandler: BodyHandler[T],
  generalRequestModifier: HttpRequest.Builder => Future[HttpRequest.Builder]
)(implicit ec: EC) extends Fetching[URI, HttpResponse[T]] {

  httpClient.authenticator()

  private def wrapWithETag(resp: HttpResponse[T]): ETaggedData[HttpResponse[T]] = {
    val optETag = resp.headers().firstValue("ETag").toScala
    if (optETag.isEmpty) {
      println(resp.statusCode())
      println(s"${resp.request().uri()} ${resp.body()}")
    }
    val eTag = optETag.get // ? we're assuming that all responses will have an ETag...
    ETaggedData(eTag, resp)
  }

  private def performFetch(
    resourceId: URI,
    reqModifier: Endo[HttpRequest.Builder] = identity,
  ): Future[HttpResponse[T]] = for {
    reqBase <- generalRequestModifier(HttpRequest.newBuilder())
    request = reqModifier(reqBase.uri(resourceId)).build()
    resp <- httpClient.sendAsync(request, bodyHandler).asScala
  } yield resp

  private def missingOrETagged(resp: HttpResponse[T]) =
    if (resp.statusCode == HTTP_NOT_FOUND) Missing else wrapWithETag(resp)

  def fetch(key: URI): Future[MissingOrETagged[HttpResponse[T]]] = performFetch(key).map(missingOrETagged)

  def fetchOnlyIfETagChanged(key: URI, eTag: String): Future[Option[MissingOrETagged[HttpResponse[T]]]] =
    performFetch(key, _.header("If-None-Match", eTag)).map { resp =>
      Option.when(resp.statusCode != HTTP_NOT_MODIFIED)(missingOrETagged(resp))
    }
}
