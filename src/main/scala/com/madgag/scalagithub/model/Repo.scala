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
import com.madgag.scalagithub.GitHub.FR
import com.madgag.scalagithub.commands._
import com.madgag.scalagithub.model.ContentCommit.readsContentCommit
import com.squareup.okhttp.Request.Builder
import play.api.libs.json.Json._
import play.api.libs.json.{Json, Reads, Writes}

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

trait HasLabels {
  val labels: Link[String]

  val labels2: CanList[Label, String]
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
  commits_url: String,
  contents_url: String,
  trees_url: String,
  default_branch: String,
  `private`: Boolean,
  permissions: Option[Permissions]
) extends HasLabels
  with Deleteable // https://developer.github.com/v3/repos/#delete-a-repository
{
  val repoId = RepoId.from(full_name)

  override val labels: Link[String] = Link.fromSuffixedUrl(labels_url, "/name")

  val trees = Link.fromSuffixedUrl[String](trees_url, "/sha")
  val contents = Link.fromSuffixedUrl[String](contents_url, "+path")

  val commits = Link.fromSuffixedUrl[String](commits_url, "/sha")

  val refs = Link.fromSuffixedUrl[String](git_refs_url, "/ref")
  val pullRequests = Link.fromSuffixedUrl[Int](pulls_url, "/number")

  val hooks: Link[Int] = Link.fromListUrl(hooks_url)

  val trees2 = new Repo.Trees(trees)

  val refs2 = new Boomer[Ref, String](refs)
    with CanGetAndCreate[Ref, String, CreateRef]
  // https://developer.github.com/v3/git/refs/#get-a-reference
  // https://developer.github.com/v3/git/refs/#create-a-reference

  val pullRequests2 = new Boomer[PullRequest, Int](pullRequests)
    with CanGetAndCreate[PullRequest, Int, CreatePullRequest]
  // https://developer.github.com/v3/pulls/#create-a-pull-request
  // https://developer.github.com/v3/pulls/#get-a-single-pull-request

  val hooks2 = new Boomer[Hook, Int](hooks)
    with CanGet[Hook, Int] // https://developer.github.com/v3/repos/hooks/#get-single-hook

  val contents2 = new CanPut[ContentCommit, String, CreateFile] {
    override val link: Link[String] = Link.fromListUrl(contents_url)
  }

  val labels2 = new Boomer[Label, String](labels)
    with CanGetAndCreate[Label, String, CreateLabel]
  // https://developer.github.com/v3/issues/labels/#get-a-single-label
  // https://developer.github.com/v3/issues/labels/#create-a-label

  def combinedStatusFor(ref: String)(implicit g: GitHub, ec: EC): FR[CombinedStatus] = {
    g.executeAndReadJson(g.addAuthAndCaching(new Builder().url(commits.urlFor(ref) + "/status")))
  }

  val settingsUrl = s"$html_url/settings"

  val collaborationSettingsUrl = s"$settingsUrl/collaboration"
}

import GitHub._

trait Reader[T] {
  implicit val readsT: Reads[T] = implicitly[Reads[T]]
}

trait Writer[T, CC] extends Reader[T] {
  implicit val writesCC: Writes[CC] = implicitly[Writes[CC]]
}

class Boomer[T, ID](val link: Link[ID]) extends CanList[T, ID]

trait CanGetAndCreate[T, ID, CC] extends CanCreate[T, ID, CC] with CanGet[T, ID]

trait CanCreate[T, ID, CC] extends Writer[T, CC] {

  val link: Link[ID]

  def create(cc: CC)(implicit g: GitHub, ec: EC): FR[T] = {
    g.executeAndReadJson[T](g.addAuth(new Builder().url(link.listUrl).post(toJson(cc))).build())
  }
}

trait CanPut[T, ID, CC] extends Writer[T, CC] {

  val link: Link[ID]

  def put(id: ID, cc: CC)(implicit g: GitHub, ec: EC): FR[T] = {
    g.executeAndReadJson[T](g.addAuth(new Builder().url(link.urlFor(id)).post(toJson(cc))).build())
  }
}

trait CanList[T, ID] extends Reader[T] {

  val link: Link[ID]

  def list()(implicit g: GitHub, ec: EC): GitHub.FR[Seq[T]] = {
    g.executeAndReadJson[Seq[T]](g.addAuthAndCaching(new Builder().url(link.listUrl)))
  }
}

trait CanGet[T, ID] extends Reader[T] {

  val link: Link[ID]

  def get(id: ID)(implicit g: GitHub, ec: EC): FR[T] = {
    g.executeAndReadJson(g.addAuthAndCaching(new Builder().url(link.urlFor(id))))
  }
}

trait CanReplace[T, ID] extends Reader[T] {

  val link: Link[ID]

  def replace(ids: Seq[ID])(implicit g: GitHub, ec: EC, w: Writes[ID]): FR[Seq[T]] = {
    g.executeAndReadJson[Seq[T]](g.addAuth(new Builder().url(link.listUrl).put(toJson(ids))).build())
  }
}

trait Link[P] {
  def urlFor(p: P): String
  val listUrl: String
}

object Link {
  def fromSuffixedUrl[P](suffixedUrl: String, suffix: String): Link[P] =
    SuffixedEndpointHandler[P](suffixedUrl, suffix)

  def fromListUrl[P](suppliedListUrl: String) = new Link[P] {
    override def urlFor(p: P): String = s"$listUrl/$p"

    override val listUrl: String = suppliedListUrl
  }
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

  class Trees(suppliedLink: Link[String]) extends CanGetAndCreate[Tree, String, CreateTree] {

    override val link: Link[String] = suppliedLink

    // https://developer.github.com/v3/git/trees/#get-a-tree
    // https://developer.github.com/v3/git/trees/#create-a-tree

    def getRecursively(sha: String)(implicit g: GitHub, ec: EC) = get(sha) // TODO actually recurse

  }

  implicit val readsRepo = Json.reads[Repo]
}