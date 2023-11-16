/*
 * Copyright 2014 The Guardian
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

package com.madgag.time

import _root_.java.time.{Instant, Duration, Clock}
import _root_.java.time.temporal.Temporal
import java.util.concurrent.TimeUnit

import java.{time => java}

import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

object Implicits {

  implicit class RichTemporal(temporal: Temporal) {
    def age()(implicit clock: Clock = Clock.systemUTC) =
      Duration.between(Instant.from(temporal), clock.instant())
  }

  implicit def scalaDuration2javaDuration(dur: scala.concurrent.duration.Duration) = java.Duration.ofNanos(dur.toNanos)

  implicit def javaDuration2SDuration(dur: java.Duration) = FiniteDuration(dur.toMillis, TimeUnit.MILLISECONDS)

}
