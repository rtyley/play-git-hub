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

package com.madgag.scalagithub.model

import java.time.ZonedDateTime

import com.madgag.scalagithub.GitHub
import com.madgag.scalagithub.commands.CreateFile
import com.squareup.okhttp.HttpUrl
import play.api.libs.json.{Reads, Writes, Json}
import GitHub._

/*
https://developer.github.com/v3/orgs/#get-an-organization

{
  "login": "guardian",
  "id": 164318,
  "url": "https://api.github.com/orgs/guardian",
  "repos_url": "https://api.github.com/orgs/guardian/repos",
  "events_url": "https://api.github.com/orgs/guardian/events",
  "members_url": "https://api.github.com/orgs/guardian/members{/member}",
  "public_members_url": "https://api.github.com/orgs/guardian/public_members{/member}",
  "avatar_url": "https://avatars.githubusercontent.com/u/164318?v=3",
  "description": "The source code of the world's leading liberal voice",
  "name": "The Guardian",
  "company": null,
  "blog": "http://www.theguardian.com/",
  "location": "London",
  "email": "",
  "public_repos": 547,
  "public_gists": 0,
  "followers": 0,
  "following": 0,
  "html_url": "https://github.com/guardian",
  "created_at": "2009-12-08T10:52:47Z",
  "updated_at": "2015-12-24T09:58:15Z",
  "type": "Organization"
}

 */
case class Org(
  login: String,
  id: Long,
  url: String,
  repos_url: String,
  avatar_url: String,
  description: Option[String],
  name: Option[String],
  created_at: Option[ZonedDateTime],
  html_url: String
) extends Account {
  lazy val membersAdminUrl = s"https://github.com/orgs/$login/members"

  // GET /orgs/:org/members
  // GET /orgs/:org/members/:username
  val members = new CanList[User, String] with CanCheck[String] {
    override val link: Link[String] = Link.fromListUrl(s"$url/members")
    override implicit val readsT: Reads[User] = User.readsUser
  }

  // GET /orgs/:org/public_members
  // GET /orgs/:org/public_members/:username
  val publicMembers = new CanList[User, String] with CanCheck[String] {
    override val link: Link[String] = Link.fromListUrl(s"$url/public_members")
    override implicit val readsT: Reads[User] = User.readsUser
  }
}

object Org {
  implicit val readsUser = Json.reads[Org]
}
