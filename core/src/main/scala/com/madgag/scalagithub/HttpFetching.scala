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

import com.gu.etagcaching.Endo
import com.gu.etagcaching.fetching.{ETaggedData, Fetching, Missing, MissingOrETagged}
import com.madgag.scalagithub.GitHub.UrlAndParser
import okhttp3.{HttpUrl, Response}
import sourcecode.Text.generate

import java.net.HttpURLConnection.{HTTP_NOT_FOUND, HTTP_NOT_MODIFIED}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.http.HttpResponse.{BodyHandler, BodyHandlers}
import scala.concurrent.{Future, ExecutionContext as EC}
import scala.jdk.FutureConverters.*

class HttpFetching[T](httpClient: HttpClient, bodyHandler: BodyHandler[T])(implicit ec: EC) extends Fetching[URI, HttpResponse[T]] {

  private def wrapWithETag(resp: HttpResponse[T]): ETaggedData[HttpResponse[T]] = {
    val eTag = resp.headers().firstValue("ETag").get() // ? we're assuming that all responses will have an ETag...
    ETaggedData(eTag, resp)
  }
  
  private def performFetch(
    resourceId: URI,
    reqModifier: Endo[HttpRequest.Builder] = identity,
  ): Future[HttpResponse[T]] = {
    val request = reqModifier(HttpRequest.newBuilder().uri(resourceId)).build()
    httpClient.sendAsync(request, bodyHandler).asScala
  }
  
  private def missingOrETagged(resp: HttpResponse[T]) = 
    if (resp.statusCode == HTTP_NOT_FOUND) Missing else wrapWithETag(resp)

  def fetch(key: URI): Future[MissingOrETagged[HttpResponse[T]]] = performFetch(key).map(missingOrETagged)

  def fetchOnlyIfETagChanged(key: URI, eTag: String): Future[Option[MissingOrETagged[HttpResponse[T]]]] = 
    performFetch(key, _.header("If-None-Match", eTag)).map { resp =>
      Option.when(resp.statusCode != HTTP_NOT_MODIFIED)(missingOrETagged(resp))
    }
}
