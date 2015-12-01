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
import com.madgag.scalagithub.commands.{CreatePullRequest, CreateRef}
import com.squareup.okhttp.Request.Builder
import play.api.libs.json.Json._
import play.api.libs.json.{Json, Reads, Writes}

import scala.concurrent.{ExecutionContext => EC}

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
) extends HasLabelsUrl
  with Deleteable // https://developer.github.com/v3/repos/#delete-a-repository
{
  val repoId = RepoId.from(full_name)

  val trees = SuffixedEndpointHandler[String](trees_url, "/sha")
  val contents = SuffixedEndpointHandler[String](contents_url, "+path")

  val refs = SuffixedEndpointHandler[String](git_refs_url, "/ref")
  val pullRequests = SuffixedEndpointHandler[Int](pulls_url, "/number")

  val hooks: Link[Int] = ???

  val refs2 = new Boomer[Ref, String](refs)
    with CanCreate[Ref, String, CreateRef] // https://developer.github.com/v3/git/refs/#create-a-reference
    with CanGet[Ref, String] // https://developer.github.com/v3/git/refs/#get-a-reference

  val pullRequests2 = new Boomer[PullRequest, Int](pullRequests)
    with CanCreate[PullRequest, Int, CreatePullRequest] // https://developer.github.com/v3/pulls/#create-a-pull-request
    with CanGet[PullRequest, Int] // https://developer.github.com/v3/pulls/#get-a-single-pull-request

  val hooks2 = new Boomer[Hook, Int](hooks)
    with CanGet[Hook, Int] // https://developer.github.com/v3/repos/hooks/#get-single-hook


  val settingsUrl = s"$html_url/settings"

  val collaborationSettingsUrl = s"$settingsUrl/collaboration"
}



class Boomer[T, ID](val link: Link[ID])(implicit val readsT: Reads[T]) {

  def list()(implicit g: GitHub, ec: EC): GitHub.FR[Seq[T]] = {
    g.executeAndReadJson[Seq[T]](g.addAuthAndCaching(new Builder().url(link.listUrl)))
  }
}

trait CanCreate[T, ID, CC] {

  val link: Link[ID]

  import GitHub._

  def create(cc: CC)(implicit g: GitHub, ec: EC, wCC: Writes[CC], rT: Reads[T]): FR[T] = {
    g.executeAndReadJson[T](g.addAuth(new Builder().url(link.listUrl).post(toJson(cc))).build())
  }
}

trait CanGet[T, ID] {

  val link: Link[ID]

  implicit val readsT: Reads[T]

  import GitHub._

  def get(id: ID)(implicit g: GitHub, ec: EC): FR[T] = {
    g.executeAndReadJson(g.addAuthAndCaching(new Builder().url(link.urlFor(id))))
  }
}

trait CanReplace[T, ID] {

  val link: Link[ID]

  implicit val readsT: Reads[T]

  import GitHub._

  def replace(ids: Seq[ID])(implicit g: GitHub, ec: EC, w: Writes[ID]): FR[Seq[T]] = {
    g.executeAndReadJson[Seq[T]](g.addAuth(new Builder().url(link.listUrl).put(toJson(ids))).build())
  }
}

trait Link[P] {
  def urlFor(p: P): String
  val listUrl: String
}

case class SuffixedEndpointHandler[P](suffixedUrl: String, suffix: String) extends Link[P] {
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