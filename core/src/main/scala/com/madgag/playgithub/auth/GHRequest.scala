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
import com.madgag.scalagithub.GitHub.ListStream
import com.madgag.scalagithub.model.Email
import com.madgag.scalagithub.{GitHub, GitHubCredentials}
import play.api.mvc.*

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class GHRequest[A](val gitHubCredentials: GitHubCredentials, request: Request[A]) extends WrappedRequest[A](request) {
  val gitHub = new GitHub(() => IO.pure(gitHubCredentials))

  lazy val userF = gitHub.getUser().map(_.result)

  lazy val userEmailsF: ListStream[Email] = gitHub.getUserEmails()

  lazy val userPrimaryEmailF: IO[Email] = userEmailsF.find(_.primary).compile.toList.map(_.head)

  lazy val userTeamsF = gitHub.getUserTeams()

}





