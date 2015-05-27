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

package com.madgag.heroku

import java.io.File

import org.eclipse.jgit.lib.ObjectId
import play.api.Logger
import play.api.libs.json.Json

import scala.util.Try

object DynoMetadata  {

  private val fileOpt = {
    val f = new File("/etc/heroku/dyno")
    if (f.exists && f.isFile) Some(f) else None
  }

  def gitCommitIdFromHerokuFile: Option[String]  = {
    Logger.info(s"Heroku dyno metadata $fileOpt")

    for {
      f <- fileOpt
      text <- (Json.parse(scala.io.Source.fromFile(f).mkString) \ "release" \ "commit").asOpt[String]
      objectId <- Try(ObjectId.fromString(text)).toOption
    } yield objectId.name
  }
}



