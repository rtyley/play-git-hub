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
import play.api.libs.json.Json
import com.madgag.git._

import com.madgag.scalagithub._

/*
{
  "ref": "refs/heads/featureA",
  "url": "https://api.github.com/repos/octocat/Hello-World/git/refs/heads/featureA",
  "object": {
    "type": "commit",
    "sha": "aa218f56b14c9653891f9e74264a383fa43fefbd",
    "url": "https://api.github.com/repos/octocat/Hello-World/git/commits/aa218f56b14c9653891f9e74264a383fa43fefbd"
  }
}
 */

case class Ref(
  ref: String,
  url: String,
  `object`: Ref.Object
) extends Deleteable // https://developer.github.com/v3/git/refs/#delete-a-reference
{
  val objectId = `object`.sha
}

object Ref {
  case class Object(
    `type`: String,
    sha: ObjectId,
    url: String
  )

  object Object {
    implicit val readsObject = Json.reads[Object]
  }

  implicit val readsRef = Json.reads[Ref]
}