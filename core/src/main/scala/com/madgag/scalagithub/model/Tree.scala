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

import org.eclipse.jgit.lib.ObjectId
import play.api.libs.json.{Json, Reads}
import com.madgag.scalagithub._

/*
{
  "sha": "fc6274d15fa3ae2ab983129fb037999f264ba9a7",
  "url": "https://api.github.com/repos/octocat/Hello-World/trees/fc6274d15fa3ae2ab983129fb037999f264ba9a7",
  "tree": [
    {
      "path": "subdir/file.txt",
      "mode": "100644",
      "type": "blob",
      "size": 132,
      "sha": "7c258a9869f33c1e1e1f74fbb32f07c86cb5a75b",
      "url": "https://api.github.com/repos/octocat/Hello-World/git/7c258a9869f33c1e1e1f74fbb32f07c86cb5a75b"
    }
  ],
  "truncated": false
}
 */


case class Tree(
  sha: ObjectId,
  url: String,
  tree: Seq[Tree.Entry],
  truncated: Boolean
)

object Tree {

  /*
    {
      "path": "subdir/file.txt",
      "mode": "100644",
      "type": "blob",
      "size": 132,
      "sha": "7c258a9869f33c1e1e1f74fbb32f07c86cb5a75b",
      "url": "https://api.github.com/repos/octocat/Hello-World/git/7c258a9869f33c1e1e1f74fbb32f07c86cb5a75b"
    }
   */
  case class Entry(
    path: String,
    mode: String,
    `type`: String,
    size: Option[Long],
    sha: ObjectId,
    url: Option[String]
  )

  object Entry {
    implicit val readsEntry: Reads[Entry] = Json.reads[Entry]
  }

  implicit val readsTree: Reads[Tree] = Json.reads[Tree]
}