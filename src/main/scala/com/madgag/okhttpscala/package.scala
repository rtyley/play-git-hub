/*
 * play-git-hub - a group of library code for Play, Git, and GitHub
 * Copyright (C) 2015 Roberto Tyley
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.madgag

import java.io.IOException

import com.squareup.okhttp._

import scala.concurrent.{ExecutionContext, Future, Promise}


package object okhttpscala {

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
