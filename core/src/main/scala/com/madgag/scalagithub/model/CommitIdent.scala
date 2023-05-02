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

import play.api.libs.json.Json

// https://developer.github.com/v3/pulls/#list-commits-on-a-pull-request
case class CommitIdent(
 name: String,
 email: String,
 date: ZonedDateTime
) {
  lazy val addressString = s"$name <$email>"
}

object CommitIdent {
  implicit val readsCommitIdent = Json.reads[CommitIdent]
}