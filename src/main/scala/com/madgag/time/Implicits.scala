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

import java.util.concurrent.TimeUnit

import java.{time => java}
import org.joda.{time => joda}

import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

object Implicits {

  implicit def javaZone2JodaDateTimeZone(zoneId: java.ZoneId): joda.DateTimeZone =
    if (zoneId.getId == "Z") joda.DateTimeZone.UTC else joda.DateTimeZone.forID(zoneId.getId)

  implicit def javaZonedDateTime2JodaDateTime(zonedDateTime: java.ZonedDateTime): joda.DateTime =
    new joda.DateTime(zonedDateTime.toInstant.toEpochMilli, zonedDateTime.getZone)

  implicit def jodaInstant2javaInstant(instant: joda.Instant): java.Instant =
    java.Instant.ofEpochMilli(instant.getMillis)

  implicit def jodaDateTimeZone2javaZoneId(dateTimeZone: joda.DateTimeZone): java.ZoneId =
    java.ZoneId.of(dateTimeZone.getID)

  implicit def jodaDateTime2JavaZonedDateTime(dateTime: joda.DateTime): java.ZonedDateTime =
    java.ZonedDateTime.ofInstant(dateTime.toInstant, dateTime.getZone)

  implicit def scalaDuration2javaDuration(dur: scala.concurrent.duration.Duration) = java.Duration.ofNanos(dur.toNanos)

  implicit def duration2SDuration(dur: joda.Duration) = FiniteDuration(dur.getMillis, TimeUnit.MILLISECONDS)

  implicit def javaDuration2SDuration(dur: java.Duration) = FiniteDuration(dur.toMillis, TimeUnit.MILLISECONDS)

  implicit def javaDuration2jodaDuration(dur: java.Duration) = joda.Duration.millis(dur.toMillis)

}
