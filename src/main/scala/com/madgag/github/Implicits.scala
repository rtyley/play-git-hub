/*
 * play-git-hub - a group of library code for Play, Git, and GitHub
 * Copyright (C) 2015 Roberto Tyley
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.madgag.github

import com.madgag.git._
import org.joda.time.DateTime
import org.kohsuke.github._

import scala.collection.convert.wrapAsScala._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.util.{Success, Try}

object Implicits {
  implicit class RichFuture[S](f: Future[S]) {
    lazy val trying = {
      val p = Promise[Try[S]]()
      f.onComplete { case t => p.complete(Success(t)) }
      p.future
    }
  }

  implicit class RichGHMyself(myself: GHMyself) {
    private val emails = myself.getEmails2

    lazy val primaryEmail = emails.find(_.isPrimary).get

    lazy val verifiedEmails: Seq[GHEmail] = emails.filter(_.isVerified)
  }

  implicit class RichIssue(issue: GHIssue) {
    lazy val assignee = Option(issue.getAssignee)

    lazy val labelNames = issue.getLabels.map(_.getName)
  }

  implicit class RichRepository(repo: GHRepository) {
    lazy val id = RepoId(repo.getOwnerName, repo.getName)
  }

  implicit class RichPullRequest(pr: GHPullRequest) {
    lazy val id = PullRequestId(pr.getRepository.id, pr.getNumber)
  }


  // val dateTimeFormatter = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ssZ")

  implicit class RichPerson(person: GHPerson) {

    lazy val createdAt = new DateTime(person.getCreatedAt)

    lazy val atLogin = s"@${person.getLogin}"

    lazy val name = Option(person.getName)

    lazy val displayName = name.filter(_.nonEmpty).getOrElse(atLogin)

  }

  implicit class RichGHCommitPointer(commitPointer: GHCommitPointer) {
    lazy val objectId = commitPointer.getSha.asObjectId
  }

  implicit class RichGHPullRequestCommitDetail(prCommitDetail: GHPullRequestCommitDetail) {
    lazy val objectId = prCommitDetail.getSha.asObjectId
  }
}
