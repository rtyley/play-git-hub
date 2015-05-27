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

import play.api.mvc.Results

case class Client(id: String, secret: String) {
  def redirectForAuthWith(scopes: Seq[String]) =
    Results.Redirect(s"https://github.com/login/oauth/authorize?client_id=$id&scope=${scopes.mkString(",")}")
}
