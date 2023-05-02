/*
 * Copyright 2016 Roberto Tyley
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

package com.madgag.scalagithub.model

import java.time.ZonedDateTime

import com.madgag.scalagithub.GitHub
import com.madgag.scalagithub.GitHub.FR
import okhttp3.Request.Builder
import play.api.libs.json.Reads

import scala.concurrent.{ExecutionContext => EC}

trait Account {
  type Self <: Account

  val login: String
  val id: Long
  val url: String
  val avatar_url: String
  val name: Option[String]
  val created_at: Option[ZonedDateTime]

  val atLogin = s"@$login"

  lazy val displayName = name.filter(_.nonEmpty).getOrElse(atLogin)

  def reFetch()(implicit g: GitHub, ec: EC, ev: Reads[Self]): FR[Self]  = {
    g.executeAndReadJson[Self](g.addAuthAndCaching(new Builder().url(url)))
  }

}
