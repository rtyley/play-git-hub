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

package com.madgag.scalagithub.model

import java.time.ZonedDateTime

import com.madgag.scalagithub.GitHub
import com.madgag.scalagithub.GitHub._
import play.api.libs.json.{Reads, Json}

import scala.concurrent.{ExecutionContext => EC}

case class User(
  login: String,
  id: Long,
  avatar_url: String,
  url: String,
  html_url: String,
  name: Option[String] = None,
  created_at: Option[ZonedDateTime] = None
) extends Account {
  override type Self = User
}

object User {
  implicit val readsUser: Reads[User] = Json.reads[User]
}
