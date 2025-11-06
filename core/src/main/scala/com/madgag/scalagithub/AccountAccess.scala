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

package com.madgag.scalagithub

import com.madgag.scalagithub.GitHubCredentials.Provider
import com.madgag.scalagithub.model.*

class ClientWithAccess[A <: AccountAccess](
  val gitHub: GitHub,
  val credentialsProvider: GitHubCredentials.Provider,
  val accountAccess: A
)

trait AccountAccess {
  /**
   * An account, either an [[Org]] or a [[User]] - the main account (possibly only account) the principal
   * can access with these credentials. Eg if the principal wants to list what repos they can operate on,
   * or create a repo, what account holds those resources?
   */
  val focus: Account

  /**
   * The entity, either [[GitHubApp]] or [[User]], that is authorized to perform actions, and to which
   * those actions will be attributed: "who wrote this comment?", "who merged this PR?" etc.
   */
  val principal: Principal

//  /**
//   * These credentials can be used for authenticated Git operations
//   */
//  val credentials: Provider
}

/**
 * Note a user may be able to access many orgs:
 * 
 * - personal access token (classic) seems to give access to all orgs the user has access to
 * - fine-grained personal access token will only grant access to a single 'Resource owner'
 * 
 * TODO: think about https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/authenticating-with-a-github-app-on-behalf-of-a-user
 */
case class UserTokenAccess(
  user: User,
// accessToken: AccessToken
) extends AccountAccess {
//   val credentials: Provider = GitHubCredentials.Provider.fromStatic(accessToken)
  override val focus: User = user // TODO fine-grained PATs are specific to a single 'resource owner', eg an Org the user can access
  override val principal: User = user
}


case class GitHubAppAccess(
  principal: GitHubApp,
  installation: Installation,
//  credentials: Provider
) extends AccountAccess {

  /**
   * The account ([[User]] or [[Org]]) on which the GitHub App was installed
   */
  override val focus: Account = installation.account
}
