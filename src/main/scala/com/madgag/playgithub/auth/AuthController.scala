/*
 * play-git-hub - a group of library code for Play, Git, and GitHub
 * Copyright (C) 2015 Roberto Tyley
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.madgag.playgithub.auth

import com.madgag.github.GitHubAuthResponse
import com.madgag.okhttpscala._
import com.squareup.okhttp
import org.kohsuke.github._
import play.api.libs.json.Json
import play.api.mvc._

import scala.concurrent.ExecutionContext.Implicits.global

trait AuthController extends Controller {

  val authClient: Client

  val defaultPage = "/"

  val client = new okhttp.OkHttpClient()

  val AccessTokenUrl = "https://github.com/login/oauth/access_token"

  def oauthCallback(code: String) = Action.async { req =>
    val accessTokenRequest = new okhttp.Request.Builder()
      .url(s"$AccessTokenUrl?client_id=${authClient.id}&client_secret=${authClient.secret}&code=$code")
      .addHeader(ACCEPT, "application/json")
      .post(EmptyRequestBody)
      .build()

    for (response <- client.execute(accessTokenRequest)) yield {
      val accessToken = Json.parse(response.body.byteStream()).validate[GitHubAuthResponse].get.access_token
      val user = GitHub.connectUsingOAuth(accessToken).getMyself
      Redirect(req.session.get(AuthenticatedSessions.RedirectToPathAfterAuthKey).getOrElse(defaultPage)).withSession(
        AuthenticatedSessions.AccessTokenKey -> accessToken,
        MinimalGHPerson(user.getLogin, user.getAvatarUrl).sessionTuple
      )
    }
  }

  def logout = Action {
    Redirect(defaultPage).withNewSession
  }
}



