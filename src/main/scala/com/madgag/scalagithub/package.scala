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

package com.madgag

import org.eclipse.jgit.lib.ObjectId
import play.api.data.validation.ValidationError
import play.api.libs.json._

package object scalagithub {

  implicit val formatsObjectId = new Format[ObjectId] {
    override def reads(json: JsValue): JsResult[ObjectId] = json match {
      case JsString(s) => try JsSuccess(ObjectId.fromString(s)) catch {
        case e: RuntimeException => JsError(Seq(JsPath() -> Seq(ValidationError(e.getMessage))))
      }
      case o: JsValue => JsError(Seq(JsPath() -> Seq(ValidationError(s"Expected string (not $o) for ObjectId"))))
    }

    override def writes(o: ObjectId) = JsString(o.name)
  }
}
