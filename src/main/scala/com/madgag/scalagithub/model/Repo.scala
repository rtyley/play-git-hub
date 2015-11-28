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

import com.madgag.scalagithub.GitHub
import play.api.libs.json.Json

import scala.concurrent.{ExecutionContext => EC, Future}

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

  val labels = SuffixedEndpointHandler(labels_url, "name")

  val labelsListUrl = labels_url.stripSuffix("{/name}")
}



case class Repo(
  name: String,
  url: String,
  full_name: String,
  html_url: String,
  clone_url: String,
  hooks_url: String,
  labels_url: String,
  teams_url: String,
  git_refs_url: String,
  pulls_url: String,
  contents_url: String,
  trees_url: String,
  default_branch: String,
  `private`: Boolean,
  permissions: Option[Permissions]
) extends HasLabelsUrl {
  val repoId = RepoId.from(full_name)

  val settingsUrl = s"$html_url/settings"

  val trees = SuffixedEndpointHandler[String](trees_url, "/sha")
  val refs = SuffixedEndpointHandler[String](git_refs_url, "/ref")
  val pullRequests = SuffixedEndpointHandler[Int](pulls_url, "/number")
  val contents = SuffixedEndpointHandler[String](contents_url, "+path")

  val collaborationSettingsUrl = s"$settingsUrl/collaboration"

  def delete()(implicit gh: GitHub, ec: EC): Future[Boolean] = gh.deleteRepo(this)
}

case class SuffixedEndpointHandler[P](suffixedUrl: String, suffix: String) {
  val encasedSuffix = s"{$suffix}"

  def urlFor(p: P) = suffixedUrl.replace(encasedSuffix, s"/$p")

  val listUrl = suffixedUrl.stripSuffix(encasedSuffix)
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