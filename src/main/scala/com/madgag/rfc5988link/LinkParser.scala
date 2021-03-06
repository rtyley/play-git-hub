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

package com.madgag.rfc5988link

import okhttp3.HttpUrl
import fastparse.all._

case class LinkTarget(url: HttpUrl, attributes: Seq[(String, String)]) {

  lazy val attributeMap = attributes.groupBy(_._1).mapValues(_.map(_._2))

  lazy val relOpt = attributeMap.get("rel").flatMap(_.headOption)
}

object LinkParser {
  val url: P[HttpUrl] = P("<" ~ CharsWhile(_ != '>', min = 1).! ~ ">").map(HttpUrl.parse)

  val linkParam: P[(String, String)] =
    P("; " ~ CharsWhile(_ != '=', min = 1).! ~ "=\"" ~ CharsWhile(_ != '"', min = 1).! ~ "\"" )

  val linkTarget: P[LinkTarget] = (url ~ linkParam.rep).map {
    case (a, b) => LinkTarget(a, b)
  }

  val linkValues: P[Seq[LinkTarget]] = linkTarget.rep(sep = ", ")

}
