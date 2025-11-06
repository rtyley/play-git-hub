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

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.madgag.playgithub.auth.AuthenticatedSessions.{AccessToken, RedirectToPathAfterAuthKey}
import com.madgag.scalagithub.{GitHub, GitHubCredentials}
import play.api.mvc.*
import play.api.mvc.Security.{AuthenticatedBuilder, AuthenticatedRequest}

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}


object Actions {

  type AuthRequest[A] = AuthenticatedRequest[A, GitHubCredentials]

  class GitHubAuthenticatedAction(scopes: Seq[String], parser: BodyParser[AnyContent])(
    using ec: ExecutionContext, authClient: Client, accessTokenProvider: AccessToken.Provider
  ) extends AuthenticatedBuilder[GitHubCredentials](
    { (req: RequestHeader) => accessTokenProvider(req).map(accessKey => GitHubCredentials(com.madgag.github.AccessToken(accessKey))) },
    parser,
    onUnauthorized = implicit req => authClient.redirectForAuthWith(scopes).addingToSession(RedirectToPathAfterAuthKey -> req.path)
  )
  
  class AuthenticatedActionToGHRequest(using val executionContext: ExecutionContext, githubFactory: GitHub.Factory)
    extends ActionTransformer[AuthRequest, GHRequest] {
    def transform[A](request: AuthRequest[A]) = {
      val gitHubCredentials: GitHubCredentials = request.user // Counter-intuitive but correct...!
      githubFactory.dispatcher.unsafeToFuture(for {
        client <- githubFactory.clientFor(IO.pure(gitHubCredentials))
      } yield new GHRequest[A](client, request))
    }
  }
  
  def gitHubAction(scopes: Seq[String],parser: BodyParser[AnyContent])(using Client, AccessToken.Provider, ExecutionContext, GitHub.Factory) =
    new GitHubAuthenticatedAction(scopes, parser) andThen new AuthenticatedActionToGHRequest

}