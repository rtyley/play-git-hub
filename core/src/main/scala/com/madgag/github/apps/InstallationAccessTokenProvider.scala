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

package com.madgag.github.apps

import com.madgag.github.{AccessToken, Expirable}

import scala.concurrent.{ExecutionContext, Future}

class InstallationAccessTokenProvider(
  githubAppAuth: GithubAppAuth,
  installationId: String
)(implicit ec: ExecutionContext)
  extends (() => Future[Expirable[AccessToken]]) {

  override def apply(): Future[Expirable[AccessToken]] =
    githubAppAuth.getInstallationAccessToken(installationId).map(resp => Expirable(resp.token, resp.expires_at))

}
