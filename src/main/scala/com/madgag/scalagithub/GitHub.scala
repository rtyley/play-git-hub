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

import java.time.Instant
import java.util.concurrent.TimeUnit.SECONDS

import com.madgag.okhttpscala._
import com.madgag.scalagithub.commands._
import com.madgag.scalagithub.model._
import com.squareup.okhttp.Request.Builder
import com.squareup.okhttp._
import play.api.Logger
import play.api.http.Status._
import play.api.libs.json.Json.toJson
import play.api.libs.json._

import scala.RuntimeException
import scala.concurrent.{ExecutionContext => EC, Future}
import scala.util.{Failure, Try}

object RateLimit {
  case class Status(
    remaining: Int,
    reset: Instant
  )
}

case class RateLimit(
  consumed: Int,
  statusOpt: Option[RateLimit.Status]
)

case class RequestScopes(
                          authedScopes: Set[String],
                          acceptedScopes: Set[String]
)

case class ResponseMeta(rateLimit: RateLimit, requestScopes: RequestScopes)

object ResponseMeta {

  def rateLimitStatusFrom(response: Response) = RateLimit.Status(
    response.header("X-RateLimit-Remaining").toInt,
    Instant.ofEpochSecond(response.header("X-RateLimit-Reset").toLong)
  )

  def rateLimitFrom(response: Response): RateLimit = {
    val networkResponse = Option(response.networkResponse())
    RateLimit(
      consumed = if (networkResponse.exists(_.code != NOT_MODIFIED)) 1 else 0,
      networkResponse.map(rateLimitStatusFrom)
    )
  }

  def requestScopesFrom(response: Response) = RequestScopes(
    response.header("X-OAuth-Scopes").split(',').map(_.trim).toSet,
    response.header("X-Accepted-OAuth-Scopes").split(',').map(_.trim).toSet
  )

  def from(response: Response) = {
    val rateLimit = rateLimitFrom(response)
    val requestScopes = requestScopesFrom(response)
    ResponseMeta(rateLimit, requestScopes)
  }
}

case class GitHubResponse[Result](
  responseMeta: ResponseMeta,
  result: Result
)

object GitHubResponse {
  implicit def toResult[Result](resp: GitHubResponse[Result]): Result = resp.result
}

object GitHub {

  val logger = Logger(getClass)

  type FR[A] = Future[GitHubResponse[A]]

  implicit def jsonToRequestBody(json: JsValue): RequestBody =
    RequestBody.create(JsonMediaType, json.toString)

  val JsonMediaType = MediaType.parse("application/json; charset=utf-8")

  private val AlwaysHitNetwork = new CacheControl.Builder().maxAge(0, SECONDS).build()

  private val IronmanPreview = "application/vnd.github.ironman-preview+json"

}

class GitHub(ghCredentials: GitHubCredentials) {
  import GitHub._

  /**
    * https://developer.github.com/v3/repos/#create
    */
  def createRepo(repo: CreateRepo)(implicit ec: EC): FR[Repo] = {
    val url = apiUrlBuilder
      .addPathSegment("user")
      .addPathSegment("repos")
      .build()

    executeAndReadJson(addAuth(new Builder().url(url).post(toJson(repo))).build)
  }

  /**
    * https://developer.github.com/v3/repos/#create
    */
  def createOrgRepo(org: String, repo: CreateRepo)(implicit ec: EC): FR[Repo] = {
    val url = apiUrlBuilder
      .addPathSegment("orgs")
      .addPathSegment(org)
      .addPathSegment("repos")
      .build()

    executeAndReadJson(addAuth(new Builder().url(url).post(toJson(repo))).build)
  }

  /**
    * https://developer.github.com/v3/repos/#get
    */
  def getRepo(repoId: RepoId)(implicit ec: EC): FR[Repo] = {
    // GET /repos/:owner/:repo
    val url = apiUrlBuilder
      .addPathSegment("repos")
      .addPathSegment(repoId.owner)
      .addPathSegment(repoId.name)
      .build()

    executeAndReadJson(addAuthAndCaching(new Builder().url(url)))
  }

  /**
    * https://developer.github.com/v3/repos/#delete-a-repository
    */
  def deleteRepo(repo: Repo)(implicit ec: EC): Future[Boolean] = {
    // DELETE /repos/:owner/:repo
    ghCredentials.okHttpClient.execute(addAuth(new Builder().url(repo.url).delete()).build()).map(_.code() == 204)
  }

  /**
    * https://developer.github.com/v3/repos/contents/#create-a-file
    */
  def createFile(repo: Repo, path: String, createFile: CreateFile)(implicit ec: EC): FR[ContentCommit] = {
    // PUT /repos/:owner/:repo/contents/:path
    executeAndReadJson[ContentCommit](addAuthAndCaching(new Builder().url(repo.contents.urlFor(path)).put(toJson(createFile))))
  }


  /**
    * https://developer.github.com/v3/git/trees/#get-a-tree-recursively
    *
    */
  def getTreeRecursively(repo: Repo, sha: String)(implicit ec: EC): FR[Tree] = {
    // GET /repos/:owner/:repo/git/trees/:sha?recursive=1
    // GET /repos/guardian/membership-frontend/git/trees/heads/master?recursive=1 - undocumented, but works

    executeAndReadJson(addAuthAndCaching(new Builder().url(repo.trees.urlFor(sha)+"?recursive=1")))
  }

  /**
    * https://developer.github.com/v3/repos/#list-your-repositories
    */
  def listRepos(sort: String, direction: String)(implicit ec: EC): FR[Seq[Repo]] = {
    // GET /user/repos
    val url = apiUrlBuilder
      .addPathSegment("user")
      .addPathSegment("repos")
      .build()

    executeAndReadJson(addAuthAndCaching(new Builder().url(url)))
  }

  def checkMembership(org: String, username: String)(implicit ec: EC): Future[Boolean] = {
    //GET /orgs/:org/members/:username
    val url = apiUrlBuilder
      .addPathSegment("orgs")
      .addPathSegment(org)
      .addPathSegment("members")
      .addPathSegment(username)
      .build()

    ghCredentials.okHttpClient.execute(addAuthAndCaching(new Builder().url(url)
      .addHeader("Accept", IronmanPreview)
      .get)).map(_.code() == 204)
  }

  /**
    * https://developer.github.com/v3/orgs/teams/#list-user-teams
    */
  def getUserTeams()(implicit ec: EC): FR[Seq[Team]] = {
    // GET /user/teams
    val url = apiUrlBuilder
      .addPathSegment("user")
      .addPathSegment("teams")
      .build()

    // TODO Pagination: https://developer.github.com/guides/traversing-with-pagination/
    executeAndReadJson(addAuthAndCaching(new Builder().url(url)))
  }

  /**
    * https://developer.github.com/v3/repos/hooks/#list-hooks
    */
  def listHooks(repo: RepoId)(implicit ec: EC): FR[Seq[Hook]] = {
    // GET /repos/:owner/:repo/hooks
    val url = apiUrlBuilder
      .addPathSegment("repos")
      .addPathSegment(repo.owner)
      .addPathSegment(repo.name)
      .addPathSegment("hooks")
      .build()

    // TODO Pagination: https://developer.github.com/guides/traversing-with-pagination/
    executeAndReadJson(addAuthAndCaching(new Builder().url(url)))
  }

  /**
    * https://developer.github.com/v3/repos/hooks/#list-hooks
    */
  def listHooks(repo: Repo)(implicit ec: EC): FR[Seq[Hook]] = {
    // TODO Pagination: https://developer.github.com/guides/traversing-with-pagination/
    executeAndReadJson(addAuthAndCaching(new Builder().url(repo.hooks_url)))
  }

  /**
    * https://developer.github.com/v3/orgs/teams/#get-team
    */
  def getTeam(teamId: Long)(implicit ec: EC): FR[Team] = {
    // GET /teams/:id
    val url = apiUrlBuilder
      .addPathSegment("teams")
      .addPathSegment(teamId.toString)
      .build()

    executeAndReadJson(addAuthAndCaching(new Builder().url(url)))
  }

  def getMembership(org: String, username: String)(implicit ec: EC): FR[Membership] = {
    // GET /orgs/:org/memberships/:username
    val url = apiUrlBuilder
      .addPathSegment("orgs")
      .addPathSegment(org)
      .addPathSegment("memberships")
      .addPathSegment(username)
      .build()

    executeAndReadJson(addAuthAndCaching(new Builder().url(url)))
  }

  def getTeamMembership(teamId: Long, username: String)(implicit ec: EC): FR[Membership] = {
    val url = apiUrlBuilder
      .addPathSegment("teams")
      .addPathSegment(teamId.toString)
      .addPathSegment("memberships")
      .addPathSegment(username)
      .build()

    executeAndReadJson(addAuthAndCaching(new Builder().url(url)))
  }

  def addAuthAndCaching(builder: Builder): Request =
    addAuth(builder).cacheControl(AlwaysHitNetwork).build()

  def addAuth(builder: Builder) = builder
    .addHeader("Authorization", s"token ${ghCredentials.accessKey}")

  def getUser()(implicit ec: EC): Future[GitHubResponse[User]] = {
    val url = apiUrlBuilder
      .addPathSegment("user")
      .build()

    executeAndReadJson(addAuthAndCaching(new Builder().url(url)))
  }

  /**
    * https://developer.github.com/v3/orgs/teams/#add-team-repo
    */
  def addTeamRepo(teamId: Long, org: String, repoName: String)(implicit ec: EC) = {
    // curl -X PUT -H "Authorization: token $REPO_MAKER_GITHUB_ACCESS_TOKEN" -H "Accept: application/vnd.github.ironman-preview+json"
    // -d@bang2.json https://api.github.com/teams/1831886/repos/gu-who-demo-org/150b89c114a

    val url = apiUrlBuilder
      .addPathSegment("teams")
      .addPathSegment(teamId.toString)
      .addPathSegment("repos")
      .addPathSegment(org)
      .addPathSegment(repoName)
      .build()

    ghCredentials.okHttpClient.execute(addAuthAndCaching(new Builder().url(url)
      .addHeader("Accept", IronmanPreview)
      .put(Json.obj("permission" -> "admin"))))
  }

  /*
   * https://developer.github.com/v3/issues/comments/#create-a-comment
   */
  def createComment(commentable: Commentable, comment: String)(implicit ec: EC): FR[Comment] = {
    // POST /repos/:owner/:repo/issues/:number/comments
    executeAndReadJson(addAuthAndCaching(new Builder().url(commentable.comments_url).post(toJson(CreateComment(comment)))))
  }

  /**
    * https://developer.github.com/v3/issues/comments/#list-comments-on-an-issue
    */
  def listComments(commentable: Commentable)(implicit ec: EC): FR[Seq[Comment]] = {
    // GET /repos/:owner/:repo/issues/:number/comments TODO Pagination
    executeAndReadJson(addAuthAndCaching(new Builder().url(commentable.comments_url).get()))
  }

  def executeAndReadJson[T](request: Request)(implicit ev: Reads[T], ec: EC): FR[T] = {
    for {
      response <- execute(request)
    } yield {
      val meta = ResponseMeta.from(response)
      logger.debug(s"${meta.rateLimit} ${response.code} ${request.method} ${request.httpUrl}")

      val responseBody = response.body()

  //    logger.debug(s"contentLength = ${responseBody.contentLength}")

      //      logger.debug(s"byteStream = $byteStream")

      val json = Json.parse(responseBody.byteStream())

      logger.debug(s"json = ${json.toString.take(40)}")

      json.validate[T] match {
        case error: JsError =>
          val message = s"Error decoding ${request.httpUrl} : $error"
          logger.warn(s"$message\n\n$json\n\n" )
          throw new RuntimeException(message)
        case JsSuccess(result, _) =>
          GitHubResponse(meta, result)
      }
    }
  }

  def execute[T](request: Request)(implicit ec: EC): Future[Response] =
    ghCredentials.okHttpClient.execute(request)

  def apiUrlBuilder: HttpUrl.Builder = new HttpUrl.Builder().scheme("https").host("api.github.com")

}
