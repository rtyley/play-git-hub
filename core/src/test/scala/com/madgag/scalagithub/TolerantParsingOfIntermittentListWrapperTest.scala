/*
 * Copyright 2025 Roberto Tyley
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

import com.madgag.scalagithub.TolerantParsingOfIntermittentListWrapper
import com.madgag.scalagithub.TolerantParsingOfIntermittentListWrapper.tolerantlyParse
import com.madgag.scalagithub.model.Ref
import org.scalatest.Inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should
import play.api.libs.json.*

class TolerantParsingOfIntermittentListWrapperTest extends AnyFlatSpec with should.Matchers with Inside {
  def jsonFrom(path: String): JsValue =
    Json.parse(getClass.getResource(s"/github-api-intermittently-returns-list-wrapping-requested-object/$path").openStream())
  
  "TolerantParsingOfIntermittentListWrapper" should "successfully parse the expected case when an object is correctly supplied without a list wrapper" in {
    inside (tolerantlyParse[Ref](jsonFrom("ref.correctly-given-without-list-wrapper.json"))) {
      case JsSuccess(ref, _) => ref.ref shouldBe "refs/heads/feature-1"
    }
  }

  it should "successfully parse the abnormal case when an object is wrapped with a list" in {
    inside (tolerantlyParse[Ref](jsonFrom("ref.abnormally-as-list-with-single-item.json"))) {
      case JsSuccess(ref, _) => ref.ref shouldBe "refs/heads/feature-1"
    }
  }

  it should "error for 'not found' json" in {
    tolerantlyParse[Ref](jsonFrom("ref.not-found.json")).isError shouldBe true
  }
}
