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

object RepoId {
  def from(fullName: String) = {
    val parts = fullName.split('/')
    require(parts.length == 2)

    RepoId(parts(0), parts(1))
  }
}

case class RepoId(owner: String, name: String) {

  require(!Seq(owner, name).exists(p => p.isEmpty || p.contains('/')))

  lazy val fullName = s"$owner/$name"
}

trait HasLabelsList {
  val labelsListUrl: String
}

trait HasLabelsUrl extends HasLabelsList {
  val labels_url: String

  val labelsListUrl = labels_url.stripSuffix("{/name}")
}



case class Repo(
  name: String,
  full_name: String,
  html_url: String,
  clone_url: String,
  hooks_url: String,
  labels_url: String,
  teams_url: String,
  git_refs_url: String,
  trees_url: String,
  default_branch: String,
  `private`: Boolean,
  permissions: Permissions
) extends HasLabelsUrl {
  val repoId = RepoId.from(full_name)

  val settingsUrl = s"$html_url/settings"

  val refsListUrl = git_refs_url.stripSuffix("{/ref}")

  def treeUrlFor(sha: String) = trees_url.replace("{/sha}", s"/$sha")

  val collaborationSettingsUrl = s"$settingsUrl/collaboration"
}

case class Permissions(
  pull: Boolean,
  push: Boolean,
  admin: Boolean
)

object Permissions {
  implicit val readsPermissions = Json.reads[Permissions]
}


object Repo {

  implicit val readsRepo = Json.reads[Repo]
}