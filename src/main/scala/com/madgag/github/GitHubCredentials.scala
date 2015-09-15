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

package com.madgag.github

import java.nio.file.{Files, Path}

import com.squareup.okhttp
import com.squareup.okhttp.{OkHttpClient, OkUrlFactory}
import org.eclipse.jgit.transport.{CredentialsProvider, UsernamePasswordCredentialsProvider}
import org.kohsuke
import org.kohsuke.github.extras.OkHttpConnector
import org.kohsuke.github.{GitHub, GitHubBuilder}
import play.api.Logger

import scala.util.Try

/*
Stuff you might want to know:

* Working Dir
* AccessToken
* User - whoami
* The API url - "https://api.github.com" and also the OAuth url, eg: https://github.com/login/oauth/authorize
 */

object GitHubCredentials {

  def userGitHubConnectionOpt(accessToken: String): Option[GitHub] =
    Try(GitHub.connectUsingOAuth(accessToken)).toOption


  def forAccessKey(accessKey: String, workingDir: Path)(implicit ghBuilder: GitHubBuilder = new GitHubBuilder): Try[GitHubCredentials] = {
    val gitHubBuilderWithAccessKey = ghBuilder.withOAuthToken(accessKey)

    for (user <- Try(gitHubBuilderWithAccessKey.build().getMyself)) yield {
      val userDir = workingDir.resolve(s"${user.getId}-${user.getLogin}").toAbsolutePath

      assert(userDir.startsWith(workingDir))

      userDir.toFile.mkdirs()

      val okHttpClient = {
        val client = new OkHttpClient

        if (Files.exists(userDir)) {
          client.setCache(new okhttp.Cache(userDir.toFile, 5 * 1024 * 1024))
        } else Logger.warn(s"Couldn't create HttpResponseCache dir $userDir")

        client
      }

      GitHubCredentials(accessKey, gitHubBuilderWithAccessKey, okHttpClient)
    }
  }
}

case class GitHubCredentials(
  accessKey: String,
  gitHubBuilder: kohsuke.github.GitHubBuilder,
  okHttpClient: OkHttpClient
) {

  private val okHttpConnector = new OkHttpConnector(new OkUrlFactory(okHttpClient))

  def conn(): GitHub = {
    gitHubBuilder.withOAuthToken(accessKey).withConnector(okHttpConnector).build()
  }

  lazy val git: CredentialsProvider = new UsernamePasswordCredentialsProvider(accessKey, "")
}