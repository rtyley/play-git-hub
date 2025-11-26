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

package com.madgag.scalagithub.model

import org.eclipse.jgit.lib.ObjectId
import play.api.libs.json.{JsResult, JsValue, Json, Reads}
import com.madgag.scalagithub.*
import com.madgag.scalagithub.GitHub.{FR, reqWithBody}
import com.madgag.scalagithub.commands.DeleteFile
import sttp.model.Uri

/*
{
  "content": {
    "name": "hello.txt",
    "path": "notes/hello.txt",
    "sha": "95b966ae1c166bd92f8ae7d1c313e738c731dfc3",
    "size": 9,
    "url": "https://api.github.com/repos/octocat/Hello-World/contents/notes/hello.txt",
    "html_url": "https://github.com/octocat/Hello-World/blob/master/notes/hello.txt",
    "git_url": "https://api.github.com/repos/octocat/Hello-World/git/blobs/95b966ae1c166bd92f8ae7d1c313e738c731dfc3",
    "download_url": "https://raw.githubusercontent.com/octocat/HelloWorld/master/notes/hello.txt",
    "type": "file",
    "_links": {
      "self": "https://api.github.com/repos/octocat/Hello-World/contents/notes/hello.txt",
      "git": "https://api.github.com/repos/octocat/Hello-World/git/blobs/95b966ae1c166bd92f8ae7d1c313e738c731dfc3",
      "html": "https://github.com/octocat/Hello-World/blob/master/notes/hello.txt"
    }
  },
  "commit": {
    "sha": "7638417db6d59f3c431d3e1f261cc637155684cd",
    "url": "https://api.github.com/repos/octocat/Hello-World/git/commits/7638417db6d59f3c431d3e1f261cc637155684cd",
    "html_url": "https://github.com/octocat/Hello-World/git/commit/7638417db6d59f3c431d3e1f261cc637155684cd",
    "author": {
      "date": "2014-11-07T22:01:45Z",
      "name": "Scott Chacon",
      "email": "schacon@gmail.com"
    },
    "committer": {
      "date": "2014-11-07T22:01:45Z",
      "name": "Scott Chacon",
      "email": "schacon@gmail.com"
    },
    "message": "my commit message",
    "tree": {
      "url": "https://api.github.com/repos/octocat/Hello-World/git/trees/691272480426f78a0138979dd3ce63b77f706feb",
      "sha": "691272480426f78a0138979dd3ce63b77f706feb"
    },
    "parents": [
      {
        "url": "https://api.github.com/repos/octocat/Hello-World/git/commits/1acc419d4d6a9ce985db7be48c6349a0475975b5",
        "html_url": "https://github.com/octocat/Hello-World/git/commit/1acc419d4d6a9ce985db7be48c6349a0475975b5",
        "sha": "1acc419d4d6a9ce985db7be48c6349a0475975b5"
      }
    ]
  }
}
 */

case class Content(
  name: String,
  path: String,
  sha: ObjectId,
  size: Long,
  url: Uri,
  html_url: Uri,
  download_url: Uri,
  `type`: String
) {
  def delete(message: String, branch: Option[String] = None)(using g: GitHub): FR[DeletionCommit] =
    g.executeAndReadJson(reqWithBody(DeleteFile(message, sha, branch)).delete(url))
}

given Reads[Uri] = Reads.JsStringReads.map(s => Uri.unsafeParse(s.value))

object Content {
  given Reads[Content] = Json.reads
}

case class Commit(
  sha: ObjectId,
  url: String,
  html_url: String,
  message: String
)

case class ContentCommit(content: Content, commit: Commit)

case class DeletionCommit(commit: Commit)

object Commit {
  given Reads[Commit] = Json.reads[Commit]
}

object ContentCommit {
  given Reads[ContentCommit] = Json.reads[ContentCommit]
}

object DeletionCommit {
  given Reads[DeletionCommit] = Json.reads[DeletionCommit]
}