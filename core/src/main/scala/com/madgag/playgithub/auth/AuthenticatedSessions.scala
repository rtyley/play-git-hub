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

import play.api.mvc._

object AuthenticatedSessions {

  val RedirectToPathAfterAuthKey = "redirectToPathAfterAuth"

  object AccessToken {
    type Provider = RequestHeader => Option[String]

    val SessionKey = "githubAccessToken"

    val FromSession: Provider = _.session.get(SessionKey)

    val FromQueryString: Provider = _.getQueryString("access_token")

    val FromBasicAuth: Provider = _.headers.get("Authorization").map(_.split(' ')(1))

    def provider(providers: Provider*) = {
      r : RequestHeader => providers.flatMap(_(r)).headOption
    }
  }

}