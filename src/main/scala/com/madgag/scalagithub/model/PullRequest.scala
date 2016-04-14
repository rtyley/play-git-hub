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

import java.time.ZonedDateTime

import com.madgag.git._
import com.madgag.scalagithub.GitHub
import com.madgag.scalagithub.GitHub.{FR, _}
import com.madgag.scalagithub.commands.{CreateComment, MergePullRequest}
import com.madgag.scalagithub.model.Link.fromListUrl
import com.madgag.scalagithub.model.PullRequest.CommitOverview
import com.squareup.okhttp.Request.Builder
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.{RevCommit, RevWalk}
import play.api.libs.json.Json._
import play.api.libs.json.{Json, Reads}

import scala.concurrent.{ExecutionContext => EC}

import GitHub._
import com.madgag.scalagithub._


case class CommitPointer(
  ref: String,
  sha: ObjectId,
  user: Option[User], // git/git/pulls/113 had user null on head
  repo: Option[Repo]
) {
  def asRevCommit(implicit revWalk: RevWalk) = sha.asRevCommit
}

object CommitPointer {
  implicit val readsCommitPointer = Json.reads[CommitPointer]
}

trait Commentable {
  val comments_url: String

  val comments2 = new CCreator[Comment, Long, CreateComment](fromListUrl(comments_url))
    with CanGetAndList[Comment, Long]
  // https://developer.github.com/v3/issues/comments/#get-a-single-comment
  // https://developer.github.com/v3/issues/comments/#create-a-comment
}

trait HasLabels {
  val issue_url: String

  // You can't 'get' a label for an Issue - the label is 'got' from a Repo
  val labels = new CReader[Label, String](fromListUrl(s"$issue_url/labels"))
    with CanList[Label, String]
    with CanReplace[Label, String] // https://developer.github.com/v3/issues/labels/#replace-all-labels-for-an-issue
  // support add / remove ?
}

object PullRequestId {
  def from(slug: String) = {
    val parts = slug.split('/')
    require(parts.length == 4)
    require(parts(2) == "pull")

    PullRequestId(RepoId(parts(0), parts(1)), parts(3).toInt)
  }
}


case class PullRequestId(repo: RepoId, num: Int) {
  lazy val slug = s"${repo.fullName}/pull/$num"
}

trait Createable {
  type Creation
}

case class Issue(
  number: Int,
  url: String,
  html_url: String,
  user: User,
  title: String,
  body: Option[String],
  issue_url: String,
  created_at: Option[ZonedDateTime] = None,
  comments_url: String,
  comments: Option[Int]
) extends Commentable with HasLabels

object Issue {
  implicit val readsIssue = Json.reads[Issue]
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
  merged_at: Option[ZonedDateTime],
  merge_commit_sha: Option[ObjectId], // deprecated? https://developer.github.com/v3/pulls/#get-a-single-pull-request
  merged_by: Option[User], // Not included in 'list' responses
  head: CommitPointer,
  base: CommitPointer,
  issue_url: String,
  commits_url: String, // "https://api.github.com/repos/octocat/Hello-World/pulls/1347/commits"
  comments_url: String,
  comments: Option[Int]
) extends Commentable with HasLabels {
  val baseRepo = base.repo.get // base repo is always available, unlike head repo which might be gone

  val prId = PullRequestId(baseRepo.repoId, number)

  val mergeUrl = s"$url/merge"

  lazy val compareUrl = baseRepo.compareUrl(base.sha.name(), head.sha.name())

  /**
    * https://developer.github.com/v3/pulls/#merge-a-pull-request-merge-button
    */
  def merge(mergePullRequest: MergePullRequest)(implicit g: GitHub, ec: EC): FR[PullRequest.Merge] = {
    // PUT /repos/:owner/:repo/pulls/:number/merge
    g.executeAndReadJson(g.addAuth(new Builder().url(mergeUrl).put(toJson(mergePullRequest))).build())
  }

  /**
    * https://developer.github.com/v3/pulls/#list-commits-on-a-pull-request
    * GET /repos/:owner/:repo/pulls/:number/commits
    */
  val commits = new CReader[CommitOverview, Int](Link.fromListUrl(commits_url))
    with CanList[CommitOverview, Int] // https://developer.github.com/v3/repos/hooks/#get-single-hook


  def availableTipCommits(implicit repoThreadLocal: ThreadLocalObjectDatabaseResources): Set[RevCommit] = {
    implicit val revWalk = new RevWalk(repoThreadLocal.reader())

    val prUltimateCommitOpt = for {
      mergeCommitId <- merge_commit_sha
      mergeCommit <- mergeCommitId.asRevCommitOpt
    } yield if (mergeCommit.getParentCount == 1) mergeCommit else mergeCommit.getParent(1).asRevCommit

    val prHeadCommitOpt = head.sha.asRevCommitOpt

    prHeadCommitOpt.toSet ++ prUltimateCommitOpt
  }
}

object PullRequest {

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
      val subject: String = message.lines.next()
    }

    implicit val readsCommitOverview: Reads[CommitOverview] = Json.reads[CommitOverview]

    implicit def overview2commit(co: CommitOverview):Commit = co.commit

    object Commit {
      implicit val readsCommit: Reads[Commit] = Json.reads[Commit]
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

  implicit val readsPullRequest = Json.reads[PullRequest]
}
