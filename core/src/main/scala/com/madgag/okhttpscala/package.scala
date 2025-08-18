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

import okhttp3._
import play.api.libs.json.JsValue

import java.io.IOException
import _root_.scala.concurrent.{ExecutionContext, Future, Promise}
import _root_.scala.util.Try


package object okhttpscala {
  implicit def jsonToRequestBody(json: JsValue): RequestBody = RequestBody.create(json.toString, JsonMediaType)

  val JsonMediaType: MediaType = MediaType.parse("application/json; charset=utf-8")

  val EmptyRequestBody: RequestBody = RequestBody.create("", JsonMediaType)

  implicit class RickOkHttpClient(client: OkHttpClient) {

    def execute[T](request: Request)(processor: Response => T)(implicit executionContext: ExecutionContext): Future[T] = {
      val p = Promise[T]()

      client.newCall(request).enqueue(new Callback {
        override def onFailure(call: Call, e: IOException): Unit = {
          p.failure(e)
        }

        override def onResponse(call: Call, response: Response): Unit = {
          val resultTry: Try[T] = Try(processor(response))
          response.body.close()
          p.complete(resultTry)
        }
      })

      p.future
    }

  }
}
