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

package com.madgag.slack

import com.madgag.okhttpscala._
import com.madgag.slack.Slack.Message
import com.squareup.okhttp.Request.Builder
import com.squareup.okhttp.{HttpUrl, OkHttpClient}
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
import play.api.libs.json.Json.toJson

import scala.concurrent.Future

object Slack {

  case class Attachment(fallback: String, fields: Seq[Attachment.Field])

  object Attachment {
    case class Field(title: String, value: String, short: Boolean)

    implicit val writesField = Json.writes[Field]
    implicit val writesAttachment = Json.writes[Attachment]
  }

  case class Message(text: String, username: Option[String], icon_url: Option[String], attachments: Seq[Attachment])

  implicit val writesMessage = Json.writes[Message]
}

class Slack(slackWebHook: String, okHttpClient: OkHttpClient) {

  def send(message: Message): Future[Int] = {
    okHttpClient.execute(new Builder().url(HttpUrl.parse(slackWebHook)).post(toJson(message)).build) {
      response => response.code()
    }
  }
}
