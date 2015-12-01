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

import play.api.libs.json.Json

/*
{
  "id": 1,
  "url": "https://api.github.com/repos/octocat/Hello-World/hooks/1",
  "test_url": "https://api.github.com/repos/octocat/Hello-World/hooks/1/test",
  "ping_url": "https://api.github.com/repos/octocat/Hello-World/hooks/1/pings",
  "name": "web",
  "events": [
    "push",
    "pull_request"
  ],
  "active": true,
  "config": {
    "url": "http://example.com/webhook",
    "content_type": "json"
  },
  "updated_at": "2011-09-06T20:39:23Z",
  "created_at": "2011-09-06T17:26:27Z"
}
 */
case class Hook(
  id: Int,
  url: String,
  active: Boolean,
  config: Map[String, String]
) extends Deleteable // https://developer.github.com/v3/repos/hooks/#delete-a-hook

object Hook {
  implicit val readsHook = Json.reads[Hook]
}
