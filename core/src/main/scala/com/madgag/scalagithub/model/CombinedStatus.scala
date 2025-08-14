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
import org.eclipse.jgit.lib.ObjectId
import play.api.libs.json.{Json, Reads}
import com.madgag.scalagithub._

/*
{
  "state": "success",
  "sha": "6dcb09b5b57875f334f61aebed695e2e4193db5e",
  "total_count": 2,
  "statuses": [
    {
      "created_at": "2012-07-20T01:19:13Z",
      "updated_at": "2012-07-20T01:19:13Z",
      "state": "success",
      "target_url": "https://ci.example.com/1000/output",
      "description": "Build has completed successfully",
      "id": 1,
      "url": "https://api.github.com/repos/octocat/Hello-World/statuses/1",
      "context": "continuous-integration/jenkins"
    },
    {
      "created_at": "2012-08-20T01:19:13Z",
      "updated_at": "2012-08-20T01:19:13Z",
      "state": "success",
      "target_url": "https://ci.example.com/2000/output",
      "description": "Testing has completed successfully",
      "id": 2,
      "url": "https://api.github.com/repos/octocat/Hello-World/statuses/2",
      "context": "security/brakeman"
    }
  ],
  "repository": {
    "id": 1296269,
    "owner": {
      "login": "octocat",
      "id": 1,
      "avatar_url": "https://github.com/images/error/octocat_happy.gif",
      "gravatar_id": "",
      "url": "https://api.github.com/users/octocat",
      "html_url": "https://github.com/octocat",
      "followers_url": "https://api.github.com/users/octocat/followers",
      "following_url": "https://api.github.com/users/octocat/following{/other_user}",
      "gists_url": "https://api.github.com/users/octocat/gists{/gist_id}",
      "starred_url": "https://api.github.com/users/octocat/starred{/owner}{/repo}",
      "subscriptions_url": "https://api.github.com/users/octocat/subscriptions",
      "organizations_url": "https://api.github.com/users/octocat/orgs",
      "repos_url": "https://api.github.com/users/octocat/repos",
      "events_url": "https://api.github.com/users/octocat/events{/privacy}",
      "received_events_url": "https://api.github.com/users/octocat/received_events",
      "type": "User",
      "site_admin": false
    },
    "name": "Hello-World",
    "full_name": "octocat/Hello-World",
    "description": "This your first repo!",
    "private": false,
    "fork": false,
    "url": "https://api.github.com/repos/octocat/Hello-World",
    "html_url": "https://github.com/octocat/Hello-World"
  },
  "commit_url": "https://api.github.com/repos/octocat/Hello-World/6dcb09b5b57875f334f61aebed695e2e4193db5e",
  "url": "https://api.github.com/repos/octocat/Hello-World/6dcb09b5b57875f334f61aebed695e2e4193db5e/status"
}
 */

case class CombinedStatus(
  state: String,
  sha: ObjectId,
  total_count: Int,
  statuses: Seq[CombinedStatus.Status],
  commit_url: String,
  url: String
)

object CombinedStatus {
  /*
{
      "created_at": "2012-08-20T01:19:13Z",
      "updated_at": "2012-08-20T01:19:13Z",
      "state": "success",
      "target_url": "https://ci.example.com/2000/output",
      "description": "Testing has completed successfully",
      "id": 2,
      "url": "https://api.github.com/repos/octocat/Hello-World/statuses/2",
      "context": "security/brakeman"
    }
 */
  case class Status(
    created_at: ZonedDateTime,
    updated_at: ZonedDateTime,
    state: String,
    target_url: String,
    description: String,
    id: Long,
    url: String,
    context: String
  )

  object Status {
    implicit val readsStatus: Reads[Status] = Json.reads[Status]
  }

  implicit val readsCombinedState: Reads[CombinedStatus] = Json.reads[CombinedStatus]

}