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

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Keep, Sink}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.util.{Success, Try}

object Implicits {

  implicit class RichItemSource[T](s: org.apache.pekko.stream.scaladsl.Source[T, NotUsed]) {
    def allItems()(implicit as: ActorSystem): Future[Seq[T]] = s.toMat(Sink.seq)(Keep.right).run()
  }

  implicit class RichSeqSource[T](s: org.apache.pekko.stream.scaladsl.Source[Seq[T], NotUsed]) {
    def all()(implicit as: ActorSystem): Future[Seq[T]] = s.toMat(Sink.reduce[Seq[T]](_ ++ _))(Keep.right).run()
  }

  implicit class RichFuture[S](f: Future[S]) {
    lazy val trying = {
      val p = Promise[Try[S]]()
      f.onComplete { case t => p.complete(Success(t)) }
      p.future
    }
  }
}
