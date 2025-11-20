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

package com.madgag.scalagithub.commands

import com.madgag.scalagithub.*
import org.eclipse.jgit.lib.ObjectId
import play.api.libs.json.*

import java.util.Base64

case class CreateOrUpdateFile(
  message: String,
  content: Base64EncodedBytes,
  sha: Option[ObjectId] = None, // Required if you are updating a file. The blob SHA of the file being replaced.
  branch: Option[String] = None
)

object CreateOrUpdateFile {
  given OWrites[CreateOrUpdateFile] = Json.writes
}

case class Base64EncodedBytes(value: Array[Byte]) {
  lazy val asBase64: String = Base64.getEncoder.encodeToString(value)
}

object Base64EncodedBytes {
  given Writes[Base64EncodedBytes] = (x: Base64EncodedBytes) => JsString(x.asBase64)
    
}