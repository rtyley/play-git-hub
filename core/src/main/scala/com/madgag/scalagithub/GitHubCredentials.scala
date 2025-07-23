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

package com.madgag.scalagithub

import java.nio.file.{Files, Path}
import okhttp3.OkHttpClient
import org.eclipse.jgit.transport.{CredentialsProvider, UsernamePasswordCredentialsProvider}
import play.api.{Logger, Logging}

import scala.util.Try

/*
Stuff you might want to know:

* Working Dir
* AccessToken
* User - whoami
* The API url - "https://api.github.com" and also the OAuth url, eg: https://github.com/login/oauth/authorize
 */

object GitHubCredentials extends Logging {

  def forAccessKey(accessKey: String, workingDir: Path): Try[GitHubCredentials] = Try {

    val userDir = workingDir.resolve(s"key-${accessKey.take(16)}").toAbsolutePath

    assert(userDir.startsWith(workingDir))

    userDir.toFile.mkdirs()

    val okHttpClient = {
      val clientBuilder = new OkHttpClient.Builder()

      if (Files.exists(userDir)) {
        clientBuilder.cache(new okhttp3.Cache(userDir.toFile, 5 * 1024 * 1024))
      } else logger.warn(s"Couldn't create HttpResponseCache dir $userDir")

      clientBuilder.build()
    }

    GitHubCredentials(accessKey, okHttpClient)
  }
}

case class GitHubCredentials(
  accessKey: String,
  okHttpClient: OkHttpClient
) {
  lazy val git: CredentialsProvider = new UsernamePasswordCredentialsProvider("x-access-token", accessKey)
}