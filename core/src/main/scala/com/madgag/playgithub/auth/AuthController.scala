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

import com.madgag.github.GitHubAuthResponse
import com.madgag.okhttpscala._
import com.madgag.playgithub.auth.AuthenticatedSessions.AccessToken
import com.madgag.scalagithub.{GitHub, GitHubCredentials}
import okhttp3.OkHttpClient
import play.api.libs.json.Json
import play.api.mvc._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait AuthController extends BaseController {

  val authClient: Client

  val defaultPage = "/"

  val client = new okhttp3.OkHttpClient()

  val AccessTokenUrl = "https://github.com/login/oauth/access_token"

  def oauthCallback(code: String): Action[AnyContent] = Action.async { req =>
    val accessTokenRequest = new okhttp3.Request.Builder()
      .url(s"$AccessTokenUrl?client_id=${authClient.id}&client_secret=${authClient.secret}&code=$code")
      .addHeader(ACCEPT, "application/json")
      .post(EmptyRequestBody)
      .build()

    for {
      accessToken <- client.execute(accessTokenRequest) { response =>
        Json.parse(response.body.byteStream()).validate[GitHubAuthResponse].get.access_token
      }
      userResponse <- new GitHub(() => Future.successful(GitHubCredentials(com.madgag.github.AccessToken(accessToken)))).getUser()
    } yield {
      val user = userResponse.result
      Redirect(req.session.get(AuthenticatedSessions.RedirectToPathAfterAuthKey).getOrElse(defaultPage)).withSession(
        AccessToken.SessionKey -> accessToken,
        MinimalGHPerson(user.login, user.avatar_url).sessionTuple
      )
    }
  }

  def logout: Action[AnyContent] = Action {
    Redirect(defaultPage).withNewSession
  }
}



