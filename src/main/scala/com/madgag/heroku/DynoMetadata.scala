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



