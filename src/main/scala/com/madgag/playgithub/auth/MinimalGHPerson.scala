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