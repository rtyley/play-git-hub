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
import play.api.libs.json.{Json, Reads}

case class Comment(
  id: Long,
  url: String,
  html_url: String,
  body: String,
  user: User,
  created_at: ZonedDateTime,
  updated_at: ZonedDateTime
) extends Deletable // https://developer.github.com/v3/issues/comments/#delete-a-comment

object Comment {
  implicit val readsComment: Reads[Comment] = Json.reads[Comment]
}
