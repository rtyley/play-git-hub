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

package com.madgag.scalagithub.commands

import com.madgag.scalagithub._
import org.eclipse.jgit.lib.ObjectId
import play.api.libs.json.Json

/*
{
  "base_tree": "9fb037999f264ba9a7fc6274d15fa3ae2ab98312",
  "tree": [
    {
      "path": "file.rb",
      "mode": "100644",
      "type": "blob",
      "sha": "44b4fc6d56897b048c772eb4087f854f46256132"
    }
  ]
}
 */
case class CreateTree(
  base_tree: ObjectId,
  tree: ObjectId
)

object CreateTree {
  case class Entry(
    path: String,
    mode: String,
    `type`: String,
    sha: Option[ObjectId],
    content: Option[String]
  )

  object Entry {
    implicit val writesEntry = Json.writes[Entry]
  }

  implicit val writesCreateTree = Json.writes[CreateTree]
}

