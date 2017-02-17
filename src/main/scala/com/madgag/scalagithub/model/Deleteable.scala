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

import com.madgag.scalagithub.GitHub
import com.madgag.scalagithub.GitHub.FR
import okhttp3.Request.Builder

import scala.concurrent.{ExecutionContext => EC, Future}

trait Deleteable {
  val url: String

  def delete()(implicit g: GitHub, ec: EC): FR[Boolean] =
    g.executeAndCheck(g.addAuth(new Builder().url(url).delete()).build())
}
