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

package com.madgag.github

import cats.*
import cats.effect.*
import com.madgag.scalagithub.GitHub

import scala.concurrent.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Success, Try}

object Implicits {

  implicit class RichItemSource[T](s: GitHub.ListStream[T]) {
    def allItems(): IO[Seq[T]] = s.compile.toList
  }

  implicit class RichSeqSource[T](s: GitHub.ListStream[Seq[T]]) {
    def all(): IO[Seq[T]] = s.compile.foldMonoid
  }

  implicit class RichFuture[S](f: Future[S]) {
    lazy val trying = {
      val p = Promise[Try[S]]()
      f.onComplete { case t => p.complete(Success(t)) }
      p.future
    }
  }
}
