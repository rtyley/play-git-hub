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

import com.madgag.rfc5988link.LinkParser.linkValues
import fastparse._
import okhttp3.HttpUrl
import org.scalatest.Inside
import org.scalatest.flatspec._
import org.scalatest.matchers._

class LinkParserTest extends AnyFlatSpec with should.Matchers with Inside {
  "FastParse" should "parse examples from RFC 5988 Section 5.5" in {
    inside(parse("<http://example.com/TheBook/chapter2>; rel=\"previous\"; title=\"previous chapter\"", linkValues(_))) {
      case Parsed.Success(value, _) =>
        value should contain only LinkTarget(
          HttpUrl.parse("http://example.com/TheBook/chapter2"),
          Seq(
            "rel" -> "previous",
            "title" -> "previous chapter"
          )
        )
    }
  }

  it should "parse a typical GitHub pagination response" in {
    inside(parse("<https://api.github.com/user/52038/repos?page=2>; rel=\"next\", <https://api.github.com/user/52038/repos?page=4>; rel=\"last\"", linkValues(_))) {
      case Parsed.Success(value, _) =>
        value should contain inOrderOnly(
          LinkTarget(
            HttpUrl.parse("https://api.github.com/user/52038/repos?page=2"),
            Seq("rel" -> "next")
          ),
          LinkTarget(
            HttpUrl.parse("https://api.github.com/user/52038/repos?page=4"),
            Seq("rel" -> "last")
          )
        )
    }
  }
}
