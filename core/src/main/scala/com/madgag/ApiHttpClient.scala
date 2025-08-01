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

package com.madgag

import okhttp3.OkHttpClient
import play.api.libs.json.Reads

//class ApiHttpClient(okHttpClient: OkHttpClient, headers: () => Map[String, String]) {
//  def request[T: Reads](path: String, req: OkHttpClient.Builder => OkHttpClient.Builder, successStatusCode: Int)(implicit ec: ExecutionContext): Future[T] =
//    req(addHeaders(wsClient.url(s"https://api.github.com/$path"))).map { response =>
//      if (response.status == successStatusCode) response.json.as[T] else {
//        logger.error(s"Failed to get installation access token: ${response.status} - ${response.body}")
//        throw new RuntimeException(s"Failed to get installation access token: ${response.status}")
//      }
//    }
//}
