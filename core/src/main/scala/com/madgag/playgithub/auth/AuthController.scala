/*
 * Copyright 2015 Roberto Tyley
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

package com.madgag.playgithub.auth

import cats.effect.*
import com.madgag.github.GitHubAuthResponse
import com.madgag.playgithub.auth.AuthenticatedSessions.AccessToken
import com.madgag.scalagithub.{GitHub, GitHubCredentials}
import play.api.libs.json.Json
import play.api.mvc.*

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import cats.effect.implicits.*
import sttp.model.Uri.*
import sttp.client4.{WebSocketBackend, quickRequest}
import sttp.client4.httpclient.cats.HttpClientCatsBackend
import sttp.client4.{UriContext, *}
import sttp.model.Uri
import sttp.model.Uri.*
import cats.effect.unsafe.implicits.global

trait AuthController extends BaseController {

  val httpClient: Backend[IO]
  
  val gitHubFactory: GitHub.Factory

  val authClient: Client

  val defaultPage = "/"

  val AccessTokenUrl: Uri = uri"https://github.com/login/oauth/access_token"

  def oauthCallback(code: String): Action[AnyContent] = Action.async { req =>
    val accessTokenRequest = quickRequest
      .post(AccessTokenUrl.withParams("client_id" -> authClient.id, "client_secret" -> authClient.secret, "code" -> code))
      .header(ACCEPT, "application/json")

    (for {
      accessToken <- accessTokenRequest.send(httpClient).map {
        resp => Json.parse(resp.body).validate[GitHubAuthResponse].get.access_token
      }
      client <- gitHubFactory.clientFor(GitHubCredentials.Provider.fromStatic(com.madgag.github.AccessToken(accessToken)))
      userResponse <- client.getUser() // TODO Resource?
    } yield {
      val user = userResponse.result
      Redirect(req.session.get(AuthenticatedSessions.RedirectToPathAfterAuthKey).getOrElse(defaultPage)).withSession(
        AccessToken.SessionKey -> accessToken,
        MinimalGHPerson(user.login, user.avatar_url).sessionTuple
      )
    }).unsafeToFuture()
  }

  def logout: Action[AnyContent] = Action {
    Redirect(defaultPage).withNewSession
  }
}
