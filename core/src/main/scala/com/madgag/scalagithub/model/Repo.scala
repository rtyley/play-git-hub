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
import com.madgag.scalagithub.GitHub.{FR, ListStream, reqWithBody}
import com.madgag.scalagithub.commands.*
import org.eclipse.jgit
import play.api.libs.json.{Json, Reads, Writes}
import sttp.client4.*
import sttp.model.Uri

import java.time.ZonedDateTime
import scala.concurrent.ExecutionContext as EC

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

class RepoRefs(git_refs_url: String) extends CCreator[Ref, String, CreateRef](Link.fromSuffixedUrl(git_refs_url, "/sha"))
  with CanGetAndList[Ref, String] {

  def get(ref: jgit.lib.Ref)(using GitHub): FR[Ref] = get(ref.getName.stripPrefix("refs/"))
}

case class Repo(
  id: Long,
  name: String,
  url: String,
  full_name: String,
  html_url: String,
  clone_url: String,
  hooks_url: String,
  labels_url: String,
  teams_url: String,
  git_refs_url: String,
  issues_url: String,
  pulls_url: String,
  commits_url: String,
  contents_url: String,
  git_tags_url: String,
  trees_url: String,
  default_branch: String,
  `private`: Boolean,
  created_at: ZonedDateTime,
  updated_at: ZonedDateTime,
  permissions: Option[Permissions]
) extends Deletable // https://developer.github.com/v3/repos/#delete-a-repository
{
  val repoId = RepoId.from(full_name)

  val trees = Link.fromSuffixedUrl[String](trees_url, "/sha")
  val contents = Link.fromSuffixedUrl[String](contents_url, "+path")

  val commits = Link.fromSuffixedUrl[String](commits_url, "/sha")

  val trees2 = new Repo.Trees(trees)

  // https://docs.github.com/en/rest/repos/contents?apiVersion=2022-11-28#get-repository-content
  val contentsFile = new Repo.Contents(contents)

  val refs = new RepoRefs(git_refs_url)
  // https://developer.github.com/v3/git/refs/#get-a-reference
  // https://developer.github.com/v3/git/refs/#create-a-reference

  val tags = new CCreator[Tag, String, Tag.Create](Link.fromSuffixedUrl[String](git_tags_url, "/sha"))
    with CanGet[Tag, String]

  val pullRequests = new CCreator[PullRequest, Int, CreatePullRequest](Link.fromSuffixedUrl(pulls_url, "/number"))
    with CanGetAndList[PullRequest, Int]
  // https://developer.github.com/v3/pulls/#create-a-pull-request
  // https://developer.github.com/v3/pulls/#get-a-single-pull-request

  val issues = new CCreator[Issue, Int, CreateOrUpdateIssue](Link.fromSuffixedUrl(issues_url, "/number"))
    with CanGetAndList[Issue, Int]

  def createIssue(
    title: String,
    body: String,
    labels: Option[Seq[String]] = None,
    assignee: Option[String] = None
  )(implicit g: GitHub, ec: EC): FR[Issue] =
    issues.create(CreateOrUpdateIssue(Some(title), Some(body), labels = labels, assignees = assignee.map(Seq(_))))

  val teams = new CanList[Repo.Team, Int] {
    override val link: Link[Int] = Link.fromListUrl(teams_url)
    override implicit val readsT: Reads[Repo.Team] = Repo.Team.readsTeam
  }

  val hooks = new CReader[Hook, Int](Link.fromListUrl(hooks_url))
    with CanGetAndList[Hook, Int] // https://developer.github.com/v3/repos/hooks/#get-single-hook

  val labels = new CCreator[Label, String, CreateLabel](Link.fromSuffixedUrl(labels_url, "/name"))
    with CanGetAndList[Label, String]
  // https://developer.github.com/v3/issues/labels/#get-a-single-label
  // https://developer.github.com/v3/issues/labels/#create-a-label

  def combinedStatusFor(ref: String)(using g: GitHub): FR[CombinedStatus] =
    g.gitHubHttp.getAndCache(commits.urlFor(ref).addPath("status"))

  def statusesFor(ref: String)(using g: GitHub): FR[Seq[Status]] =
    g.gitHubHttp.getAndCache(commits.urlFor(ref).addPath("statuses"))

  val settingsUrl = s"$html_url/settings"

  val collaborationSettingsUrl = s"$settingsUrl/collaboration"

  // lazy way of constructing https://github.com/submitgit/pretend-git/compare/7c597ef345aed345576de616c51f27e6f4b342b3...f90334356a304bc0acad01ab4fc64c49a3afd371
  def compareUrl(base: String, head: String) = s"$html_url/compare/$base...$head"
}

trait Reader[T] {
  implicit val readsT: Reads[T]
}

trait Writer[T, CC] extends Reader[T] {
  implicit val writesCC: Writes[CC]
}

class CReader[T, ID](val link: Link[ID])(implicit override val readsT: Reads[T]) extends Reader[T]

class CCreator[T, ID, CC](val link: Link[ID])(
  implicit override val readsT: Reads[T],
  val writesCC: Writes[CC]
) extends CanCreate[T, ID, CC]

trait CanGetAndCreate[T, ID, CC] extends CanCreate[T, ID, CC] with CanGet[T, ID]

trait CanGetAndList[T, ID] extends CanGet[T, ID] with CanList[T, ID]

trait CanCreate[T, ID, CC] extends Writer[T, CC] {

  val link: Link[ID]

  def create(cc: CC)(using g: GitHub): FR[T] = g.create(Uri.unsafeParse(link.listUrl), cc)
}

trait CanPut[T, ID, CC] extends Writer[T, CC] {

  val link: Link[ID]

  def put(id: ID, cc: CC)(using g: GitHub): FR[T] = g.put[CC, T](link.urlFor(id), cc)
}

trait CanList[T, ID] extends Reader[T] {

  val link: Link[ID]

  def list(params: Map[String, String] = Map.empty)(implicit g: GitHub, ec: EC): ListStream[T] =
    g.followAndEnumerate(Uri.unsafeParse(link.listUrl).addParams(params))
}

trait CanGet[T, ID] extends Reader[T] {

  val link: Link[ID]

  def get(id: ID)(using g: GitHub): FR[T] = g.gitHubHttp.getAndCache[T](link.urlFor(id))
}

trait CanCheck[ID] {

  val link: Link[ID]

  def check(id: ID)(using g: GitHub): FR[Boolean] =
    g.executeAndCheck(quickRequest.get(link.urlFor(id)))
}

trait CanDelete[ID] {

  val link: Link[ID]

  def delete(id: ID)(using g: GitHub): FR[Boolean] =
    g.executeAndCheck(quickRequest.delete(link.urlFor(id)))
}

trait CanReplace[T, ID] extends Reader[T] {

  val link: Link[ID]

  /**
   * Eg
   * https://docs.github.com/en/rest/issues/labels?apiVersion=2022-11-28#set-labels-for-an-issue
   */
  def replace(ids: Seq[ID])(implicit g: GitHub, ec: EC, w: Writes[ID]): FR[Seq[T]] =
    g.put(Uri.unsafeParse(link.listUrl), ids)
}

trait Link[P] {
  def urlFor(p: P): Uri
  val listUrl: String
}

object Link {
  def fromSuffixedUrl[P](suffixedUrl: String, suffix: String): Link[P] = new Link[P] {
    val encasedSuffix = s"{$suffix}"

    assert(suffixedUrl.endsWith(encasedSuffix))

    override def urlFor(p: P) = {
      val replacement = if (suffix.startsWith("/")) s"/$p" else p.toString
      Uri.unsafeParse(suffixedUrl.replace(encasedSuffix, replacement))
    }

    override val listUrl = suffixedUrl.stripSuffix(encasedSuffix)
  }

  def fromListUrl[P](suppliedListUrl: String) = new Link[P] {
    override def urlFor(p: P) = Uri.unsafeParse(s"$listUrl/$p")

    override val listUrl = suppliedListUrl
  }
}

case class SuffixedEndpointHandler[P](suffixedUrl: String, suffix: String) extends Link[P] {
  val encasedSuffix = s"{$suffix}"

  def urlFor(p: P): Uri = Uri.unsafeParse(suffixedUrl.replace(encasedSuffix, s"/$p"))

  val listUrl = suffixedUrl.stripSuffix(encasedSuffix)
}

case class Permissions(
  pull: Boolean,
  push: Boolean,
  admin: Boolean
)

object Permissions {
  implicit val readsPermissions: Reads[Permissions] = Json.reads[Permissions]
}

object Repo {

  case class Team(
    id: Long,
    url: String,
    name: String,
    slug: String,
    description: String,
    privacy: String,
    permission: String
  ) extends Deletable // https://developer.github.com/v3/orgs/teams/#delete-team
  {
    val atSlug: String = "@" + slug
  }

  object Team {
    implicit val readsTeam: Reads[Team] = Json.reads[Team]
  }

  class Trees(suppliedLink: Link[String]) extends CCreator[Tree, String, CreateTree](suppliedLink) with CanGetAndCreate[Tree, String, CreateTree] {

    // https://developer.github.com/v3/git/trees/#get-a-tree
    // https://developer.github.com/v3/git/trees/#create-a-tree

    def getRecursively(sha: String)(implicit g: GitHub): FR[Tree] =
      g.gitHubHttp.getAndCache(link.urlFor(sha).addParam("recursive", "1"))

  }

  class Contents(suppliedLink: Link[String]) extends CCreator[Content, String, CreateOrUpdateFile](suppliedLink) with CanGetAndCreate[Content, String, CreateOrUpdateFile] {

    /**
     * https://docs.github.com/en/rest/repos/contents?apiVersion=2022-11-28#delete-a-file
     *
     * DELETE /repos/{owner}/{repo}/contents/{path}
     */
    def delete(filePath: String, deleteFile: DeleteFile)(using g: GitHub): FR[DeletionCommit] =
      g.executeAndReadJson(reqWithBody(deleteFile).delete(link.urlFor(filePath)))


    /**
     * https://docs.github.com/en/rest/repos/contents?apiVersion=2022-11-28#create-or-update-file-contents
     *
     * PUT /repos/{owner}/{repo}/contents/{path}
     */
    def createOrUpdate(filePath: String, createFile: CreateOrUpdateFile)(using g: GitHub): FR[ContentCommit] =
      g.put(link.urlFor(filePath), createFile)
  }

  implicit val readsRepo: Reads[Repo] = Json.reads[Repo]
}