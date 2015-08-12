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

import com.madgag.playgithub.auth.AuthenticatedSessions.RedirectToPathAfterAuthKey
import org.kohsuke.github.GitHub
import play.api.mvc.Security.{AuthenticatedBuilder, AuthenticatedRequest}
import play.api.mvc._

import scala.concurrent.Future


object Actions {

  type AuthRequest[A] = AuthenticatedRequest[A, GitHub]

  def gitHubAuthenticatedAction(scopes: Seq[String])(implicit authClient: Client) =
    new AuthenticatedBuilder(
      AuthenticatedSessions.userGitHubConnectionOpt,
      implicit req => authClient.redirectForAuthWith(scopes).addingToSession(RedirectToPathAfterAuthKey -> req.path)
    )

  private val AuthenticatedActionToGHRequest = new ActionTransformer[AuthRequest, GHRequest] {
    def transform[A](request: AuthRequest[A]) = Future.successful(new GHRequest[A](request.user, request))
  }
  
  def githubAction(scopes: Seq[String])(implicit authClient: Client) =
    gitHubAuthenticatedAction(scopes) andThen AuthenticatedActionToGHRequest

}