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
import org.kohsuke.github.GitHub
import org.kohsuke.github.extras.OkHttpConnector
import play.api.Logger

class OkGitHub(workingDir: Path) {

  def conn(oauthAccessToken: String) = {
    val gh = GitHub.connectUsingOAuth(oauthAccessToken)

    val userDir = workingDir.resolve(gh.getMyself.getName).toAbsolutePath
    assert(userDir.startsWith(workingDir))

    userDir.toFile.mkdirs()

    val okHttpClient = {
      val client = new OkHttpClient

      if (Files.exists(userDir)) {
        client.setCache(new okhttp.Cache(userDir.toFile, 5 * 1024 * 1024))
      } else Logger.warn(s"Couldn't create HttpResponseCache dir $userDir")

      client
    }

    gh.setConnector(new OkHttpConnector(new OkUrlFactory(okHttpClient)))
    gh
  }

}
