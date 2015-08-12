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

import play.api.libs.json.Json
import play.api.libs.json.Json._
import play.api.mvc.RequestHeader

object MinimalGHPerson {

  val SessionKey = "user"

  implicit val formatsPerson = Json.format[MinimalGHPerson]

  def fromRequest(implicit req: RequestHeader) = for {
    userJson <- req.session.get(SessionKey)
    user <- Json.parse(userJson).validate[MinimalGHPerson].asOpt
  } yield user

}

case class MinimalGHPerson(login: String, avatarUrl: String) {

  lazy val sessionTuple: (String, String) = MinimalGHPerson.SessionKey -> toJson(this).toString()
}