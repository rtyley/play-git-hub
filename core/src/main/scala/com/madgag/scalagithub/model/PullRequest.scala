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

import com.madgag.git.*
import com.madgag.scalagithub.GitHub
import com.madgag.scalagithub.GitHub.{FR, *}
import com.madgag.scalagithub.commands.{CreateComment, CreateOrUpdateIssue, MergePullRequest}
import com.madgag.scalagithub.model.Link.fromListUrl
import com.madgag.scalagithub.model.PullRequest.CommitOverview
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.{RevCommit, RevWalk}
import play.api.libs.json.Json.*
import play.api.libs.json.{Json, Reads}
import sttp.model.*
import sttp.model.Uri.*

import java.time.ZonedDateTime
import scala.concurrent.ExecutionContext as EC
import Issue.*
import com.madgag.scalagithub.*
import sttp.model.Uri

case class CommitPointer(
  ref: String,
  sha: ObjectId,
  user: Option[User], // git/git/pulls/113 had user null on head
  repo: Option[Repo]
) {
  def asRevCommit(implicit revWalk: RevWalk) = sha.asRevCommit
}

object CommitPointer {
  implicit val readsCommitPointer: Reads[CommitPointer] = Json.reads[CommitPointer]
}

trait Commentable {
  val comments_url: String

  val comments2 = new CCreator[Comment, Long, CreateComment](fromListUrl(comments_url))
    with CanGetAndList[Comment, Long]
  // https://developer.github.com/v3/issues/comments/#get-a-single-comment
  // https://developer.github.com/v3/issues/comments/#create-a-comment

  def createComment(text: String)(implicit g: GitHub, ec: EC): FR[Comment] = comments2.create(CreateComment(text))
}

trait HasLabels {
  val issue_url: String

  // You can't 'get' a label for an Issue - the label is 'got' from a Repo
  val labels = new CReader[Label, String](fromListUrl(s"$issue_url/labels"))
    with CanList[Label, String]
    with CanAddOrReplace[Label, String] // https://developer.github.com/v3/issues/labels/#replace-all-labels-for-an-issue
  // support add / remove ?
}

trait Createable {
  type Creation
}

case class Issue(
  number: Int,
  url: String,
  html_url: String,
  user: User,
  assignee: Option[User],
  title: String,
  body: Option[String],
  issue_url: String,
  created_at: Option[ZonedDateTime] = None,
  comments_url: String,
  comments: Option[Int]
) extends Commentable with HasLabels {
  val members = new CanList[User, String] with CanCheck[String] {
    override val link: Link[String] = Link.fromListUrl(s"$url/members")
    override implicit val readsT: Reads[User] = User.given_Reads_User
  }

  /**
   * https://docs.github.com/en/rest/issues/issues?apiVersion=2022-11-28#update-an-issue
   * PATCH /repos/{owner}/{repo}/issues/{issue_number}
   */
  def update(createOrUpdate: CreateOrUpdateIssue)(using g: GitHub): FR[Issue] =
    g.executeAndReadJson(reqWithBody(createOrUpdate).patch(Uri.unsafeParse(url)))

  def close()(using g: GitHub): FR[Issue] = update(CreateOrUpdateIssue(state = Some("closed")))
}

object Issue {
  implicit val readsIssue: Reads[Issue] = Json.reads[Issue]
}


case class PullRequest(
  number: Int,
  url: String,
  html_url: String,
  patch_url: String,
  user: User,
  state: String,
  title: String,
  body: Option[String],
  created_at: ZonedDateTime,
  updated_at: ZonedDateTime,
  merged: Option[Boolean], // not present on https://docs.github.com/en/rest/pulls/pulls?apiVersion=2022-11-28#list-pull-requests
  merged_at: Option[ZonedDateTime],
  merge_commit_sha: Option[ObjectId], // deprecated? https://developer.github.com/v3/pulls/#get-a-single-pull-request
  mergeable: Option[Boolean],
  merged_by: Option[User], // Not included in 'list' responses
  head: CommitPointer,
  base: CommitPointer,
  issue_url: String,
  commits_url: String, // "https://api.github.com/repos/octocat/Hello-World/pulls/1347/commits"
  comments_url: String,
  comments: Option[Int]
) extends Commentable with HasLabels {
  val baseRepo = base.repo.get // base repo is always available, unlike head repo which might be gone

  val prId = PullRequest.Id(baseRepo.repoId, number)

  val mergeUrl = s"$url/merge"

  lazy val compareUrl = baseRepo.compareUrl(base.sha.name(), head.sha.name())

  lazy val text: PullRequest.Text = PullRequest.Text(title, body)

  /**
    * https://docs.github.com/en/rest/pulls/pulls?apiVersion=2022-11-28#merge-a-pull-request
    * PUT /repos/{owner}/{repo}/pulls/{pull_number}/merge
    */
  def merge(mergePullRequest: MergePullRequest)(using g: GitHub): FR[PullRequest.Merge] =
    g.put(Uri.unsafeParse(mergeUrl), mergePullRequest)

  /**
    * https://developer.github.com/v3/pulls/#list-commits-on-a-pull-request
    * GET /repos/:owner/:repo/pulls/:number/commits
    */
  val commits = new CReader[CommitOverview, Int](Link.fromListUrl(commits_url))
    with CanList[CommitOverview, Int] // https://developer.github.com/v3/repos/hooks/#get-single-hook
  
  def availableTipCommits(implicit revWalk: RevWalk): Set[RevCommit] = {
    val prUltimateCommitOpt = for {
      mergeCommitId <- merge_commit_sha
      mergeCommit <- mergeCommitId.asRevCommitOpt
    } yield if (mergeCommit.getParentCount == 1) mergeCommit else mergeCommit.getParent(1).asRevCommit

    val prHeadCommitOpt = head.sha.asRevCommitOpt

    prHeadCommitOpt.toSet ++ prUltimateCommitOpt
  }
}

object PullRequest {

  object Id {
    def fromTrailingPathSegments(parts: Seq[String]): Id = {
      require(parts.length == 4)
      require(parts(2) == "pull")
      Id(RepoId(parts(0), parts(1)), parts(3).toInt)
    }

    def from(slug: String): Id = fromTrailingPathSegments(slug.split('/'))

    def from(httpUri: Uri): Id = fromTrailingPathSegments(httpUri.path.takeRight(4))
  }

  case class Id(repo: RepoId, num: Int) {
    lazy val slug = s"${repo.fullName}/pull/$num" // Used in GitHub HTML UI urls, it does *not* match the REST API
  }

  case class BranchSpec(head: Branch, base: Option[String] = None)

  case class Text(title: String, body: Option[String]) {
    lazy val asCommitMessage: String = (Seq(title) ++ body).mkString("\n\n")
  }
  
  case class Metadata(text: Text, branchSpec: BranchSpec, labels: Set[String])

  object Text {
    def apply(title: String, body: String): Text = Text(title, Some(body))
  }

  case class CommitOverview(
    url: String,
    sha: ObjectId,
    html_url: String,
    commit: CommitOverview.Commit
  )

  // https://developer.github.com/v3/pulls/#list-commits-on-a-pull-request
  object CommitOverview {
    case class Commit(
      url: String,
      author: CommitIdent,
      committer: CommitIdent,
      message: String,
      comment_count: Int
    ) {
      val subject: String = message.linesIterator.next()
    }

    given Reads[CommitOverview] = Json.reads[CommitOverview]

    implicit def overview2commit(co: CommitOverview):Commit = co.commit

    object Commit {
      given Reads[Commit] = Json.reads
    }
  }

  /**
    * https://developer.github.com/v3/pulls/#response-if-merge-was-successful
    */
  case class Merge(
    sha: ObjectId,
    merged: Boolean,
    message: String
  )

  object Merge {
    implicit val readsMerge: Reads[Merge] = Json.reads[Merge]
  }

  implicit val readsPullRequest: Reads[PullRequest] = Json.reads[PullRequest]
}
