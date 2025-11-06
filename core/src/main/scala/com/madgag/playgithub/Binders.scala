/*
 * Copyright 2016 Roberto Tyley
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

package com.madgag.playgithub

import com.madgag.scalagithub.model.{PullRequest, RepoId}
import org.eclipse.jgit.lib.ObjectId
import play.api.mvc.PathBindable.Parsing

object Binders {

  implicit object bindableObjectId extends Parsing[ObjectId](
    ObjectId.fromString, _.name, (key: String, e: Exception) => s"Cannot parse parameter '$key' as a commit id: ${e.getMessage}"
  )

  /*
   * e.g. GET  /api/$repo<[^/]+/[^/]+>/message-lookup  @controllers.Api.messageLookup(repo: RepoId)
   */
  implicit object bindableRepoId extends Parsing[RepoId](
    RepoId.from, _.fullName, (key: String, e: Exception) => s"Cannot parse repo name '$key'"
  )

  /*
   * e.g.GET  /$pr<[^/]+/[^/]+/pull/[\d]+>  controllers.Application.reviewPullRequest(pr: PullRequestId)
   */
  implicit object bindablePullRequestId extends Parsing[PullRequest.Id](
    PullRequest.Id.from, _.slug, (key: String, e: Exception) => s"Cannot parse pull request '$key'"
  )
}
