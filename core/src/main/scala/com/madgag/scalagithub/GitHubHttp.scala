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

import cats.effect.IO
import cats.effect.std.Dispatcher
import com.gu.etagcaching.ETagCache
import com.gu.etagcaching.FreshnessPolicy.AlwaysWaitForRefreshedValue
import com.madgag.scalagithub.GitHub.FR
import com.madgag.scalagithub.GitHubHttp.{PermanentHeaders, UrlAndParser}
import play.api.libs.json.{JsResult, Json, Reads}
import sttp.client4.*
import sttp.model.{Header, Uri}

import scala.concurrent.ExecutionContext as EC
import scala.concurrent.duration.*

/**
 * @param dispatcher [[HttpFetching]] (used by the internal [[ETagCache]]) requires a [[Dispatcher]]
 * @param parsingEC the parsing done in [[Fetching.thenParsingWithKey]] requires a CPU-bound [[ExecutionContext]]
 */
class GitHubHttp(
  ghCredentials: GitHubCredentials.Provider,
  httpClient: Backend[IO],
  dispatcher: Dispatcher[IO],
  fetchCount: cats.effect.kernel.Ref[IO, Long]
)(using parsingEC: EC) {

  private val authHeader: IO[Header] =
    ghCredentials.map { creds => Header("Authorization", s"Bearer ${creds.accessToken.value}") }

  def execute[T](req: Request[T]): IO[Response[T]] = for {
    auth <- authHeader
    resp <- req.headers(PermanentHeaders :+ auth *).send(httpClient)
  } yield resp

  val fetching: HttpFetching[String] = new HttpFetching[String](
    httpClient,
    quickRequest.headers(PermanentHeaders *),
    authHeader.map(Seq(_)),
    dispatcher,
    fetchCount
  )

  private val etagCache: ETagCache[UrlAndParser, GitHubResponse[_]] = new ETagCache(
    fetching.keyOn[UrlAndParser](_.uri).thenParsingWithKey { (urlAndParser, httpResp) =>
      GitHubResponse(
        ResponseMeta.from(httpResp),
        {
          val jsResult: JsResult[_] = {
            val jsValue = Json.parse(httpResp.body)
            jsValue.validate(urlAndParser.parser)
          }
          if (jsResult.isError) {
            println(urlAndParser.uri)
            println(httpResp.body)
            println(jsResult)
            println(urlAndParser.parser)
          }
          jsResult.get
        })
    },
    AlwaysWaitForRefreshedValue,
    _.expireAfterWrite(1.minutes)
  )

  def getAndCache[T: Reads](uri: Uri): FR[T] =
    IO.fromFuture(IO(etagCache.get(UrlAndParser(uri, summon[Reads[T]])))).map { gitHubResponseOfAnyOpt =>
      if (gitHubResponseOfAnyOpt.isEmpty) {
        println(s"Nothing found for: $uri")
      }
      gitHubResponseOfAnyOpt.get.asInstanceOf[GitHubResponse[T]]
    }
}

object GitHubHttp {

  def apply(
    ghCredentials: GitHubCredentials.Provider,
    httpClient: Backend[IO],
    dispatcher: Dispatcher[IO]
  )(using parsingEC: EC): IO[GitHubHttp] = for {
    fetchCountRef <- cats.effect.kernel.Ref[IO].of(0L)
  } yield new GitHubHttp(ghCredentials, httpClient, dispatcher, fetchCountRef)
  
  val PermanentHeaders: Seq[Header] = Seq(
    Header("Accept", "application/vnd.github+json"),
    Header("X-GitHub-Api-Version", "2022-11-28")
  )
  
  case class UrlAndParser(uri: Uri, parser: Reads[_])
}