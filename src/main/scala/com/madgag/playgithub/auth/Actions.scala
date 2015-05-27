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