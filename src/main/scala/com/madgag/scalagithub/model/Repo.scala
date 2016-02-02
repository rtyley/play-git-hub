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
import com.squareup.okhttp.HttpUrl
import com.squareup.okhttp.Request.Builder
import play.api.libs.iteratee.Enumerator
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
) extends Deleteable // https://developer.github.com/v3/repos/#delete-a-repository
{
  val repoId = RepoId.from(full_name)

  val trees = Link.fromSuffixedUrl[String](trees_url, "/sha")
  val contents = Link.fromSuffixedUrl[String](contents_url, "+path")

  val commits = Link.fromSuffixedUrl[String](commits_url, "/sha")

  val trees2 = new Repo.Trees(trees)

  val refs = new CCreator[Ref, String, CreateRef](Link.fromSuffixedUrl(git_refs_url, "/sha"))
    with CanGetAndList[Ref, String]
  // https://developer.github.com/v3/git/refs/#get-a-reference
  // https://developer.github.com/v3/git/refs/#create-a-reference

  val pullRequests = new CCreator[PullRequest, Int, CreatePullRequest](Link.fromSuffixedUrl(pulls_url, "/number"))
    with CanGetAndList[PullRequest, Int]
  // https://developer.github.com/v3/pulls/#create-a-pull-request
  // https://developer.github.com/v3/pulls/#get-a-single-pull-request

  val hooks = new CReader[Hook, Int](Link.fromListUrl(hooks_url))
    with CanGetAndList[Hook, Int] // https://developer.github.com/v3/repos/hooks/#get-single-hook

  val contents2 = new CanPut[ContentCommit, String, CreateFile] {
    override val link: Link[String] = Link.fromSuffixedUrl(contents_url, "+path")
    override implicit val writesCC: Writes[CreateFile] = CreateFile.writesCreateFile
    override implicit val readsT: Reads[ContentCommit] = ContentCommit.readsContentCommit
  }

  val labels = new CCreator[Label, String, CreateLabel](Link.fromSuffixedUrl(labels_url, "/name"))
    with CanGetAndList[Label, String]
  // https://developer.github.com/v3/issues/labels/#get-a-single-label
  // https://developer.github.com/v3/issues/labels/#create-a-label

  def combinedStatusFor(ref: String)(implicit g: GitHub, ec: EC): FR[CombinedStatus] = {
    g.executeAndReadJson(g.addAuthAndCaching(new Builder().url(commits.urlFor(ref) + "/status")))
  }

  val settingsUrl = s"$html_url/settings"

  val collaborationSettingsUrl = s"$settingsUrl/collaboration"
}

import com.madgag.scalagithub.GitHub._

trait Reader[T] {
  implicit val readsT: Reads[T]
}

trait Writer[T, CC] extends Reader[T] {
  implicit val writesCC: Writes[CC]
}

class CReader[T, ID](val link: Link[ID])(implicit override val readsT: Reads[T]) extends Reader[T]

class CCreator[T, ID, CC](val link: Link[ID])(
  implicit override val readsT: Reads[T],
  implicit val writesCC: Writes[CC]
) extends CanCreate[T, ID, CC]

trait CanGetAndCreate[T, ID, CC] extends CanCreate[T, ID, CC] with CanGet[T, ID]

trait CanGetAndList[T, ID] extends CanGet[T, ID] with CanList[T, ID]

trait CanCreate[T, ID, CC] extends Writer[T, CC] {

  val link: Link[ID]

  def create(cc: CC)(implicit g: GitHub, ec: EC): FR[T] = {
    g.executeAndReadJson[T](g.addAuth(new Builder().url(link.listUrl).post(toJson(cc))).build())
  }
}

trait CanPut[T, ID, CC] extends Writer[T, CC] {

  val link: Link[ID]

  def put(id: ID, cc: CC)(implicit g: GitHub, ec: EC): FR[T] = {
    g.executeAndReadJson[T](g.addAuth(new Builder().url(link.urlFor(id)).put(toJson(cc))).build())
  }
}

trait CanList[T, ID] extends Reader[T] {

  val link: Link[ID]

  def list(params: Map[String, String] = Map.empty)(implicit g: GitHub, ec: EC): Enumerator[Seq[T]] = {
    val initialUrl = HttpUrl.parse(link.listUrl).newBuilder()
    params.foreach { case (k, v) => initialUrl.addQueryParameter(k, v) }
    g.followAndEnumerate(initialUrl.build())
  }
}

trait CanGet[T, ID] extends Reader[T] {

  val link: Link[ID]

  def get(id: ID)(implicit g: GitHub, ec: EC): FR[T] = {
    g.executeAndReadJson[T](g.addAuthAndCaching(new Builder().url(link.urlFor(id))))
  }
}

trait CanCheck[ID] {

  val link: Link[ID]

  def check(id: ID)(implicit g: GitHub, ec: EC): FR[Boolean] = {
    g.executeAndCheck(g.addAuthAndCaching(new Builder().url(link.urlFor(id))))
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
  def fromSuffixedUrl[P](suffixedUrl: String, suffix: String): Link[P] = new Link[P] {
    val encasedSuffix = s"{$suffix}"

    assert(suffixedUrl.endsWith(encasedSuffix))

    override def urlFor(p: P) = {
      val replacement = if (suffix.startsWith("/")) s"/$p" else p.toString
      suffixedUrl.replace(encasedSuffix, replacement)
    }

    override val listUrl = suffixedUrl.stripSuffix(encasedSuffix)
  }

  def fromListUrl[P](suppliedListUrl: String) = new Link[P] {
    override def urlFor(p: P) = s"$listUrl/$p"

    override val listUrl = suppliedListUrl
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

  class Trees(suppliedLink: Link[String]) extends CCreator[Tree, String, CreateTree](suppliedLink) with CanGetAndCreate[Tree, String, CreateTree] {

    // https://developer.github.com/v3/git/trees/#get-a-tree
    // https://developer.github.com/v3/git/trees/#create-a-tree

    def getRecursively(sha: String)(implicit g: GitHub, ec: EC) = get(sha) // TODO actually recurse

  }

  implicit val readsRepo = Json.reads[Repo]
}