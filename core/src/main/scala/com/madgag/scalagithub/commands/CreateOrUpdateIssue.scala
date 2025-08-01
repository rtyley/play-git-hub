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

package com.madgag.scalagithub.commands

import play.api.libs.json.{Json, OWrites}

/**
 * https://docs.github.com/en/rest/issues/issues?apiVersion=2022-11-28#create-an-issue
 */
case class CreateOrUpdateIssue(
  title: Option[String] = None, // Required for Create
  body: Option[String] = None,
  state: Option[String] = None, // Not allowed for Create
  labels: Option[Seq[String]] = None,
  assignees: Option[Seq[String]] = None
)

object CreateOrUpdateIssue {
  implicit val writesCreateIssue: OWrites[CreateOrUpdateIssue] = Json.writes[CreateOrUpdateIssue]
}