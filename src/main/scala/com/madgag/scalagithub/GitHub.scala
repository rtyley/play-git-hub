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

import java.time.Duration.{ZERO, ofHours, ofSeconds}
import java.time.ZoneOffset.UTC
import java.time.format.DateTimeFormatter.ISO_TIME
import java.time.{Duration, Instant, ZonedDateTime}
import java.util.concurrent.TimeUnit.SECONDS

import com.madgag.okhttpscala._
import com.madgag.rfc5988link.{LinkParser, LinkTarget}
import com.madgag.scalagithub.RateLimit.Status.{ReasonableSampleTime, Window}
import com.madgag.scalagithub.commands._
import com.madgag.scalagithub.model._
import com.squareup.okhttp.Request.Builder
import com.squareup.okhttp._
import com.squareup.okhttp.internal.http.HttpDate
import play.api.Logger
import play.api.http.Status._
import play.api.libs.iteratee.Enumerator.unfoldM
import play.api.libs.iteratee.{Enumerator, Iteratee}
import play.api.libs.json.Json.toJson
import play.api.libs.json._

import scala.collection.convert.wrapAsScala._
import scala.concurrent.{ExecutionContext => EC, Future}
import scala.language.implicitConversions
import scala.math.round

object RateLimit {
  object Status {
    val Window = ofHours(1L)
    val ReasonableSampleTime = ofSeconds(30)
  }

  case class Status(
    remaining: Int,
    limit: Int,
    reset: Instant,
    capturedAt: Instant
  ) {

    val dateOrdering = implicitly[Ordering[Duration]]
    import dateOrdering._

    val consumed = limit - remaining
    val previousReset = reset.minus(Window)
    val elapsedWindowDuration = Duration.between(previousReset, capturedAt)
    lazy val resetTimeString = ISO_TIME.format(ZonedDateTime.ofInstant(reset, UTC))

    val reasonableSampleTimeElapsed = elapsedWindowDuration > ReasonableSampleTime

    val significantQuotaConsumed = consumed > limit * 0.2

    case class StarvationProjection(nonZeroConsumed: Int) {
      assert(nonZeroConsumed > 0)

      val averageTimeToConsumeOneUnitOfQuota = elapsedWindowDuration dividedBy nonZeroConsumed

      val projectedTimeToExceedingLimit = averageTimeToConsumeOneUnitOfQuota multipliedBy remaining

      val projectedInstantLimitWouldBeExceededWithoutReset = capturedAt plus projectedTimeToExceedingLimit

      // the smaller this number, the worse things are - negative numbers indicate starvation
      val bufferDurationBetweenResetAndLimitBeingExceeded =
        Duration.between(reset, projectedInstantLimitWouldBeExceededWithoutReset)

      val bufferAsProportionOfWindow =
        bufferDurationBetweenResetAndLimitBeingExceeded.toMillis.toFloat / Window.toMillis

      lazy val summary = {
        val mins = s"${bufferDurationBetweenResetAndLimitBeingExceeded.abs.toMinutes} mins"
        if (bufferDurationBetweenResetAndLimitBeingExceeded.isNegative) {
          s"will exceed quota $mins before reset occurs at $resetTimeString"
        } else s"would exceed quota $mins after reset"
      }
    }

    val projectedConsumptionOverEntireQuotaWindow: Option[Int] = if (elapsedWindowDuration <= ZERO) None else {
      Some(round(consumed * (Window.toNanos.toFloat / elapsedWindowDuration.toNanos)))
    }

    lazy val starvationProjection: Option[StarvationProjection] =
      if (consumed > 0) Some(StarvationProjection(consumed)) else None

    val consumptionIsDangerous = (reasonableSampleTimeElapsed || significantQuotaConsumed) &&
      starvationProjection.exists(_.bufferAsProportionOfWindow < 0.2)

    val summary = (
      Some(s"Consumed $consumed/$limit over ${elapsedWindowDuration.toMinutes} mins") ++
        projectedConsumptionOverEntireQuotaWindow.map(p => s"projected consumption over window: $p") ++
        starvationProjection.filter(_ => consumptionIsDangerous).map(_.summary)
    ).mkString(", ")
  }
}

case class RateLimit(
  consumed: Int,
  statusOpt: Option[RateLimit.Status]
) {
  val hitOrMiss = if (consumed > 0) "MISS" else "HIT "
}

case class RequestScopes(
                          authedScopes: Set[String],
                          acceptedScopes: Set[String]
)

case class ResponseMeta(rateLimit: RateLimit, requestScopes: RequestScopes, links: Seq[LinkTarget]) {
  val nextOpt: Option[HttpUrl] = links.find(_.relOpt.contains("next")).map(_.url)
}

object ResponseMeta {

  def rateLimitStatusFrom(response: Response) = RateLimit.Status(
    remaining = response.header("X-RateLimit-Remaining").toInt,
    limit = response.header("X-RateLimit-Limit").toInt,
    reset = Instant.ofEpochSecond(response.header("X-RateLimit-Reset").toLong),
    capturedAt = HttpDate.parse(response.header("Date")).toInstant
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

  def linksFrom(response: Response): Seq[LinkTarget] = for {
    linkHeader <- response.headers("Link")
    linkTargets <- LinkParser.linkValues.parse(linkHeader).get.value
  } yield linkTargets

  def from(response: Response) = {
    val rateLimit = rateLimitFrom(response)
    val requestScopes = requestScopesFrom(response)
    ResponseMeta(rateLimit, requestScopes, linksFrom(response))
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


  def logAndGetMeta(request: Request, response: Response): ResponseMeta = {
    val meta = ResponseMeta.from(response)
    val mess = s"${meta.rateLimit.hitOrMiss} ${response.code} ${request.method} ${request.httpUrl}"
    meta.rateLimit.statusOpt.filter(_.consumptionIsDangerous).fold {
      logger.debug(mess)
    } { status =>
      logger.warn(s"$mess ${status.summary}")
    }
    meta
  }


  implicit class RichEnumerator[T](e: Enumerator[Seq[T]]) {
    def all()(implicit ec: EC): Future[Seq[T]] = e(Iteratee.consume()).flatMap(_.run)
  }

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

  def followAndEnumerate[T](url: HttpUrl)(implicit ev: Reads[T], ec: EC): Enumerator[Seq[T]] = unfoldM(Option(url)) {
    _.map { nextUrl =>
      logger.debug(s"Following $nextUrl")
      for {
        response <- executeAndReadJson[Seq[T]](addAuthAndCaching(new Builder().url(nextUrl)))
      } yield Some(response.responseMeta.nextOpt -> response.result)
    }.getOrElse(Future.successful(None))
  }

  /**
    * https://developer.github.com/v3/repos/#list-your-repositories
    */
  def listRepos(sort: String, direction: String)(implicit ec: EC): Enumerator[Seq[Repo]] = {
    // GET /user/repos
    followAndEnumerate(apiUrlBuilder
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

    execute(addAuthAndCaching(new Builder().url(url)
      .addHeader("Accept", IronmanPreview)
      .get))(_.code() == 204)
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

    executeAndCheck(addAuthAndCaching(new Builder().url(url)
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

  def executeAndCheck(request: Request)(implicit ec: EC): FR[Boolean] = execute(request) {
    response =>

    GitHubResponse(logAndGetMeta(request, response), response.code() == 204)
  }

  def executeAndReadJson[T](request: Request)(implicit ev: Reads[T], ec: EC): FR[T] = execute(request) {
    response =>

      val meta = logAndGetMeta(request, response)

    val responseBody = response.body()

    val json = Json.parse(responseBody.byteStream())

    json.validate[T] match {
      case error: JsError =>
        val message = s"Error decoding ${request.method} ${request.httpUrl} : $error"
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
