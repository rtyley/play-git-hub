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

package com.madgag.scalagithub.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should
import play.api.libs.json.{JsString, JsSuccess}

class IssueTest extends AnyFlatSpec with should.Matchers {
  "Issue State" should "be successfully parsed" in {
    Issue.State.Open.name shouldBe "open"
    
    Issue.State.formatState.reads(JsString("open")) shouldBe JsSuccess(Issue.State.Open)
  }
}
