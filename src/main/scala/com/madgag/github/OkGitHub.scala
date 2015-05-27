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
