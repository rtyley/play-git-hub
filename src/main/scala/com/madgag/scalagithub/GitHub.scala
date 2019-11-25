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

import java.time.Duration.ofHours
import java.time.Instant
import java.util.concurrent.TimeUnit.SECONDS

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.madgag.okhttpscala._
import com.madgag.ratelimitstatus.{QuotaUpdate, RateLimit}
import com.madgag.rfc5988link.{LinkParser, LinkTarget}
import com.madgag.scalagithub.commands._
import com.madgag.scalagithub.model._
import okhttp3.Request.Builder
import okhttp3._
import okhttp3.internal.http.HttpDate
import play.api.Logger
import play.api.http.Status
import play.api.http.Status._
import play.api.libs.json.Json.toJson
import play.api.libs.json._

import scala.concurrent.{Future, ExecutionContext => EC}
import scala.language.implicitConversions
import fastparse._, NoWhitespace._
import collection.JavaConverters._

case class Quota(
  consumed: Int,
  statusOpt: Option[RateLimit.Status]
) {
  val hitOrMiss = if (consumed > 0) "MISS" else "HIT "
}

case class RequestScopes(
  authedScopes: Set[String],
  acceptedScopes: Set[String]
)

case class ResponseMeta(quota: Quota, requestScopes: RequestScopes, links: Seq[LinkTarget]) {
  val nextOpt: Option[HttpUrl] = links.find(_.relOpt.contains("next")).map(_.url)
}

object ResponseMeta {
  val GitHubRateLimit = RateLimit(ofHours(1))

  def rateLimitStatusFrom(response: Response) = GitHubRateLimit.statusFor(QuotaUpdate(
    remaining = response.header("X-RateLimit-Remaining").toInt,
    limit = response.header("X-RateLimit-Limit").toInt,
    reset = Instant.ofEpochSecond(response.header("X-RateLimit-Reset").toLong),
    capturedAt = HttpDate.parse(response.header("Date")).toInstant
  ))

  def rateLimitFrom(response: Response): Quota = {
    val networkResponse = Option(response.networkResponse())
    Quota(
      consumed = if (networkResponse.exists(_.code != NOT_MODIFIED)) 1 else 0,
      networkResponse.map(rateLimitStatusFrom)
    )
  }

  def requestScopesFrom(response: Response) = RequestScopes(
    response.header("X-OAuth-Scopes").split(',').map(_.trim).toSet,
    response.header("X-Accepted-OAuth-Scopes").split(',').map(_.trim).toSet
  )

  def linksFrom(response: Response): Seq[LinkTarget] = for {
    linkHeader <- response.headers("Link").asScala.toSeq
    linkTargets <- parse(linkHeader, LinkParser.linkValues(_)).get.value
  } yield linkTargets

  def from(resp: Response) =
    ResponseMeta(rateLimitFrom(resp), requestScopesFrom(resp), linksFrom(resp))
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

  def logAndGetMeta(request: Request, response: Response): ResponseMeta = {
    val meta = ResponseMeta.from(response)
    val mess = s"${meta.quota.hitOrMiss} ${response.code} ${request.method} ${request.url}"
    meta.quota.statusOpt.filter(_.consumptionIsDangerous).fold {
      logger.debug(mess)
    } { status =>
      logger.warn(s"$mess ${status.summary}")
    }
    meta
  }


//  implicit class RichEnumerator[T](e: Enumerator[Seq[T]]) {
//    def all()(implicit ec: EC): Future[Seq[T]] = e(Iteratee.consume()).flatMap(_.run)
//
//    def takeUpTo(n: Int)(implicit ec: EC): Future[Seq[T]] = e(Iteratee.takeUpTo(n)).flatMap(_.run).map(_.flatten)
//  }

}

class GitHub(ghCredentials: GitHubCredentials) {
  import GitHub._

  def checkRateLimit()(implicit ec: EC): Future[RateLimit.Status] = {
    // GET /rate_limit  https://developer.github.com/v3/rate_limit/
    val url = apiUrlBuilder.addPathSegment("rate_limit").build()

    execute(addAuth(new Builder().url(url).get).build())(ResponseMeta.rateLimitStatusFrom)
  }

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
    * https://developer.github.com/v3/orgs/#get-an-organization
    */
  def getOrg(org: String)(implicit ec: EC): FR[Org] = {
    // GET /orgs/:org
    val url = apiUrlBuilder
      .addPathSegment("orgs")
      .addPathSegment(org)
      .build()

    executeAndReadJson(addAuthAndCaching(new Builder().url(url)))
  }

  /**
    * https://developer.github.com/v3/repos/#delete-a-repository
    */
  def deleteRepo(repo: Repo)(implicit ec: EC): Future[Boolean] = {
    // DELETE /repos/:owner/:repo
    execute(addAuth(new Builder().url(repo.url).delete()).build())(_.code() == 204)
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

  def followAndEnumerate[T](url: HttpUrl)(implicit ev: Reads[T], ec: EC): Source[Seq[T], NotUsed] = Source.unfoldAsync[Option[HttpUrl],Seq[T]](Some(url)) {
    case Some(nextUrl) =>
      logger.debug(s"Following $nextUrl")
      for {
        response <- executeAndReadJson[Seq[T]](addAuthAndCaching(new Builder().url(nextUrl)))
      } yield Some((response.responseMeta.nextOpt, response.result))
    case None => Future.successful(None)
  }

  /**
    * https://developer.github.com/v3/repos/#list-your-repositories
    */
  def listRepos(sort: String, direction: String)(implicit ec: EC): Source[Seq[Repo], NotUsed] = {
    // GET /user/repos
    followAndEnumerate[Repo](apiUrlBuilder
      .addPathSegment("user")
      .addPathSegment("repos")
      .build())
  }

  def checkMembership(org: String, username: String)(implicit ec: EC): Future[Boolean] = {
    //GET /orgs/:org/members/:username
    val url = apiUrlBuilder
      .addPathSegment("orgs")
      .addPathSegment(org)
      .addPathSegment("members")
      .addPathSegment(username)
      .build()

    execute(addAuthAndCaching(new Builder().url(url).get))(_.code == Status.NO_CONTENT)
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
    * https://developer.github.com/v3/users/emails/#list-email-addresses-for-a-user
    */
  def getUserEmails()(implicit ec: EC): FR[Seq[Email]] = {
    // GET /user/emails
    val url = apiUrlBuilder
      .addPathSegment("user")
      .addPathSegment("emails")
      .build()

    // TODO Pagination: https://developer.github.com/guides/traversing-with-pagination/
    executeAndReadJson[Seq[Email]](addAuthAndCaching(new Builder().url(url)))
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
    * https://developer.github.com/v3/orgs/teams/#add-or-update-team-repository
    * PUT /teams/:id/repos/:org/:repo
    */
  def addTeamRepo(teamId: Long, org: String, repoName: String)(implicit ec: EC) = {
    val url = apiUrlBuilder
      .addPathSegment("teams")
      .addPathSegment(teamId.toString)
      .addPathSegment("repos")
      .addPathSegment(org)
      .addPathSegment(repoName)
      .build()

    executeAndCheck(addAuthAndCaching(new Builder().url(url).put(Json.obj("permission" -> "admin"))))
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

  def executeAndCheck(request: Request)(implicit ec: EC): FR[Boolean] = execute(request) { response =>
    val allGood = response.code() == 204
    if (!allGood) {
      logger.warn(s"Non-OK response code to ${request.method} ${request.url} : ${response.code()}\n\n${response.body()}\n\n" )
    }
    GitHubResponse(logAndGetMeta(request, response), allGood)
  }

  def executeAndReadJson[T](request: Request)(implicit ev: Reads[T], ec: EC): FR[T] = execute(request) {
    response =>

      val meta = logAndGetMeta(request, response)

    val responseBody = response.body()

    val json = Json.parse(responseBody.byteStream())

    json.validate[T] match {
      case error: JsError =>
        val message = s"Error decoding ${request.method} ${request.url} : $error"
        logger.warn(s"$message\n\n$json\n\n" )
        throw new RuntimeException(message)
      case JsSuccess(result, _) =>
        GitHubResponse(meta, result)
    }
  }

  def execute[T](request: Request)(processor: Response => T)(implicit ec: EC): Future[T] =
    ghCredentials.okHttpClient.execute(request)(processor)

  def apiUrlBuilder: HttpUrl.Builder = new HttpUrl.Builder().scheme("https").host("api.github.com")

}
