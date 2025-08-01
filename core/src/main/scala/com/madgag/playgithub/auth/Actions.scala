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

import com.madgag.playgithub.auth.AuthenticatedSessions.{AccessToken, RedirectToPathAfterAuthKey}
import com.madgag.scalagithub.GitHubCredentials
import play.api.mvc.Security.{AuthenticatedBuilder, AuthenticatedRequest}
import play.api.mvc._

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}


object Actions {

  type AuthRequest[A] = AuthenticatedRequest[A, GitHubCredentials]

  class GitHubAuthenticatedAction(scopes: Seq[String], workingDir: Path, parser: BodyParser[AnyContent])(
    implicit ec: ExecutionContext, authClient: Client, accessTokenProvider: AccessToken.Provider
  ) extends AuthenticatedBuilder[GitHubCredentials](
    { req: RequestHeader => accessTokenProvider(req).map(accessKey => GitHubCredentials(com.madgag.github.AccessToken(accessKey))) },
    parser,
    onUnauthorized = implicit req => authClient.redirectForAuthWith(scopes).addingToSession(RedirectToPathAfterAuthKey -> req.path)
  )
  class AuthenticatedActionToGHRequest(implicit val executionContext: ExecutionContext)
    extends ActionTransformer[AuthRequest, GHRequest] {
    def transform[A](request: AuthRequest[A]) = Future.successful(new GHRequest[A](request.user, request))
  }
  
  def gitHubAction(scopes: Seq[String], workingDir: Path, parser: BodyParser[AnyContent])(implicit authClient: Client, accessTokenProvider: AccessToken.Provider, ec: ExecutionContext) =
    (new GitHubAuthenticatedAction(scopes, workingDir, parser)) andThen (new AuthenticatedActionToGHRequest)

}