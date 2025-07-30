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

package com.madgag.scalagithub

import com.madgag.github.AccessToken
import com.madgag.scalagithub.BearerAuthTransportConfig.bearerAuth
import org.eclipse.jgit.api.{TransportCommand, TransportConfigCallback}
import org.eclipse.jgit.transport.{CredentialsProvider, TransportHttp, UsernamePasswordCredentialsProvider}

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.jdk.CollectionConverters._

case class GitHubCredentials(accessToken: AccessToken) {
  lazy val git: CredentialsProvider = new UsernamePasswordCredentialsProvider("x-access-token", accessToken.value)

  /**
   * Is this necessary or not?!
   *
   * The tests passing on https://github.com/guardian/prout/pull/141 - where I seem to have lost any indication that
   * we're calling `setTransportConfigCallback` indicates that we **don't** need it.
   *
   * Yet my experience with `gha-micropython-logic-capture-workflow` seems to indicate that it is necessary...
   * https://github.com/rtyley/gha-micropython-logic-capture-workflow/blob/2bf20e9671410d9b08bd86daf02799ac4e1f669c/worker/src/main/scala/com/madgag/micropython/logiccapture/worker/LogicCaptureWorker.scala#L29
   *
   * See also https://github.com/eclipse-jgit/jgit/issues/94
   */
  def applyAuthTo[C <: TransportCommand[C, T], T](transportCommand: C): C =
    transportCommand.setCredentialsProvider(git).setTransportConfigCallback(bearerAuth(accessToken.value))
}

object BearerAuthTransportConfig {
  def bearerAuth(token: String): TransportConfigCallback = {
    case http: TransportHttp =>
      http.setAdditionalHeaders(Map("Authorization" -> s"Bearer ${Base64.getEncoder.encodeToString(token.getBytes(StandardCharsets.UTF_8))}").asJava)
  }
}