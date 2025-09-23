/*
 * Copyright 2023 Roberto Tyley
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

package com.madgag.scalagithub

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.{Header, Headers}

import java.net.http.HttpHeaders
import java.time.Instant

class ResponseMetaTest extends AnyFlatSpec with Matchers with OptionValues {
  it should "not crash if ratelimit state response headers are missing" in {
    ResponseMeta.rateLimitStatusFrom(
      Headers(Seq(Header("nothing-useful", "whatever")))
    ) shouldBe None
  }

  it should "parse the Date header from a GitHub request" in {
    ResponseMeta.rateLimitStatusFrom(
      Headers(Seq(
        Header("date", "Thu, 02 Nov 2023 12:37:22 GMT"),
        Header("x-ratelimit-remaining", "58"),
        Header("x-ratelimit-limit", "60"),
        Header("x-ratelimit-reset", "1698932232")
      ))).value.quotaUpdate.capturedAt shouldBe Instant.parse("2023-11-02T12:37:22Z")
  }
}
