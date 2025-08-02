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

import org.eclipse.jgit.lib.ObjectId
import play.api.libs.json.{Json, OWrites}
import com.madgag.scalagithub._

case class MergePullRequest(
  commit_message: Option[String] = None,
  sha: Option[ObjectId] = None
)

object MergePullRequest {
  implicit val writesMergePullRequest: OWrites[MergePullRequest] = Json.writes[MergePullRequest]
}