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

import com.madgag.scalagithub.ResponseMeta.RichHeaders
import okhttp3.Headers
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.http.HttpHeaders
import java.time.Instant

class ResponseMetaTest extends AnyFlatSpec with Matchers with OptionValues {
  it should "not crash if ratelimit state response headers are missing" in {
    ResponseMeta.rateLimitStatusFrom(
      Headers.of("nothing-useful", "whatever").toJdkHeaders
    ) shouldBe None
  }

  it should "parse the Date header from a GitHub request" in {
    ResponseMeta.rateLimitStatusFrom(
      new Headers.Builder()
        .add("date", "Thu, 02 Nov 2023 12:37:22 GMT")
        .add("x-ratelimit-remaining", "58")
        .add("x-ratelimit-limit", "60")
        .add("x-ratelimit-reset", "1698932232")
        .build().toJdkHeaders
    ).value.quotaUpdate.capturedAt shouldBe Instant.parse("2023-11-02T12:37:22Z")
  }
}
