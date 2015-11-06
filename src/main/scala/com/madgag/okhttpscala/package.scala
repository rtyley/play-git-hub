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

import java.io.IOException

import com.squareup.okhttp._
import play.api.libs.json.JsValue

import scala.concurrent.{ExecutionContext, Future, Promise}


package object okhttpscala {
  implicit def jsonToRequestBody(json: JsValue): RequestBody = RequestBody.create(JsonMediaType, json.toString)

  val JsonMediaType = MediaType.parse("application/json; charset=utf-8")

  val EmptyRequestBody = RequestBody.create(null, "")

  implicit class RickOkHttpClient(client: OkHttpClient) {

    def execute(request: Request)(implicit executionContext: ExecutionContext): Future[Response] = {
      val p = Promise[Response]()

      client.newCall(request).enqueue(new Callback {
        override def onFailure(request: Request, e: IOException) {
          p.failure(e)
        }

        override def onResponse(response: Response) {
          p.success(response)
        }
      })

      p.future
    }

  }
}
