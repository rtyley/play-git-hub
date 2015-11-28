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
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import play.api.libs.json.Json

import com.madgag.scalagithub._

case class CommitPointer(
  ref: String,
  sha: ObjectId,
  user: User,
  repo: Repo
) {
  def asRevCommit(implicit revWalk: RevWalk) = sha.asRevCommit
}

object CommitPointer {
  implicit val readsCommitPointer = Json.reads[CommitPointer]
}

trait Commentable {
  val comments_url: String
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

case class PullRequest(
  number: Int,
  url: String,
  html_url: String,
  user: User,
  title: String,
  body: String,
  merged_at: Option[ZonedDateTime],
  merged_by: Option[User],
  head: CommitPointer,
  base: CommitPointer,
  issue_url: String,
  comments_url: String,
  comments: Int
) extends Commentable with HasLabelsList {
  val prId = PullRequestId(base.repo.repoId, number)

  val labelsListUrl = s"$issue_url/labels"

  val mergeUrl = s"$url/merge"
}

object PullRequest {

  /**
    * https://developer.github.com/v3/pulls/#response-if-merge-was-successful
    */
  case class Merge(
    sha: ObjectId,
    merged: Boolean,
    message: String
  )

  object Merge {
    implicit val readsMerge = Json.reads[Merge]
  }

  implicit val readsPullRequest = Json.reads[PullRequest]
}
