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

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import com.gu.etagcaching.fetching.{ETaggedData, Fetching, Missing, MissingOrETagged}
import sttp.client4.{Backend, PartialRequest, Response}
import sttp.model.StatusCode.{NotFound, NotModified}
import sttp.model.{Header, StatusCode, Uri}

import scala.concurrent.Future

/**
 * 
 * @param dispatcher We're using [[IO]] rather than [[Future]] for `httpClient` & `nonConstantHeaders`, but implementing
 *                   the [[Fetching]] interface from `etag-caching`, which returns [[Future]]s. Consequently, we need
 *                   [[Dispatcher]] to convert the [[IO]]s to [[Future]]s. See https://typelevel.org/cats-effect/docs/std/dispatcher
 */
class HttpFetching[T](
  httpClient: Backend[IO],
  baseRequest: PartialRequest[T],
  nonConstantHeaders: IO[Seq[Header]],
  dispatcher: Dispatcher[IO],
  fetchCounter: Ref[IO, Long]
) extends Fetching[Uri, Response[T]] {
  
  val fetchCount: IO[Long] = fetchCounter.get

  private def wrapWithETag(resp: Response[T]): ETaggedData[Response[T]] = {
    val optETag = resp.header("ETag")
    if (optETag.isEmpty) {
      println(resp.code)
      println(s"${resp.request.uri} ${resp.body}")
    }
    val eTag = optETag.get // ? we're assuming that all responses will have an ETag...
    ETaggedData(eTag, resp)
  }

  private def missingOrETagged(resp: Response[T]) =
    if (resp.code == NotFound) Missing else wrapWithETag(resp)

  private def fetchFuture[O](key: Uri, eTag: Option[String], f: Response[T] => O): Future[O] = 
    dispatcher.unsafeToFuture(for {
        currentHeaders <- nonConstantHeaders
        request = baseRequest.headers(currentHeaders ++ eTag.map(Header("If-None-Match", _)) *).get(key)
        _ <- fetchCounter.update(_ + 1)
        resp <- request.send(httpClient)
      } yield f(resp)
    )
  
  def fetch(key: Uri): Future[MissingOrETagged[Response[T]]] =
    fetchFuture(key, None, missingOrETagged)

  def fetchOnlyIfETagChanged(key: Uri, eTag: String): Future[Option[MissingOrETagged[Response[T]]]] =
    fetchFuture(key, Some(eTag), resp => Option.when(resp.code != NotModified)(missingOrETagged(resp)))
}
