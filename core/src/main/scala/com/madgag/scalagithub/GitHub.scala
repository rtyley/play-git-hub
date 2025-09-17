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

import cats.Endo
import cats.effect.IO
import com.gu.etagcaching.FreshnessPolicy.AlwaysWaitForRefreshedValue
import com.gu.etagcaching.fetching.Fetching
import com.gu.etagcaching.{ETagCache, FreshnessPolicy}
import com.madgag.okhttpscala.*
import com.madgag.ratelimitstatus.RateLimit
import com.madgag.scalagithub.ResponseMeta.RichHeaders
import com.madgag.scalagithub.commands.*
import com.madgag.scalagithub.model.*
import fs2.Chunk
import fs2.Stream.unfoldChunkLoopEval
import okhttp3.*
import okhttp3.Request.Builder
import play.api.Logger
import play.api.http.Status
import play.api.libs.json.*
import play.api.libs.json.Json.toJson

import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import java.nio.file.Files
import java.util.concurrent.TimeUnit.SECONDS
import scala.concurrent.ExecutionContext as EC
import scala.concurrent.duration.*
import scala.language.implicitConversions

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

case class GitHubResponse[Result](
  responseMeta: ResponseMeta,
  result: Result
) {
  def map[R2](f: Result => R2): GitHubResponse[R2] = copy(result = f(result))
}

object GitHubResponse {
  // implicit def toResult[Result](resp: GitHubResponse[Result]): Result = resp.result
}

object GitHub {

  val logger = Logger(getClass)

  type FR[A] = IO[GitHubResponse[A]]

  implicit def jsonToRequestBody(json: JsValue): RequestBody =
    RequestBody.create(json.toString, JsonMediaType)

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

  def readAndResolve[T](request: Request, response: Response)(implicit ev: Reads[T]): T = {
    val responseBody = response.body()

    val json = Json.parse(responseBody.byteStream())

    json.validate[T] match {
      case error: JsError =>
        val message = s"Error decoding ${request.method} ${request.url} (etag={${response.header("etag")}}) : $error"
        val mess = s"$message\n\n$json\n\n"
        println(mess)
        logger.warn(mess)
        throw new RuntimeException(message)
      case JsSuccess(result, _) => result
    }
  }

  implicit class RichOkHttpBuilder(builder: Builder) {
    def withCaching: Builder = builder.cacheControl(AlwaysHitNetwork)
  }

  type ReqMod = Endo[Builder]

  type ListStream[T] = fs2.Stream[IO, T]

  def apiUrlBuilder: HttpUrl.Builder = new HttpUrl.Builder().scheme("https").host("api.github.com")

  def path(segments: String*): HttpUrl = segments.foldLeft(apiUrlBuilder) { case (builder, segment) =>
    builder.addPathSegment(segment)
  }.build()

  def pathWithQueryParams(queryParams: Seq[(String, String)], segments: String*): HttpUrl = {
    val builderWithPathSegments = segments.foldLeft(apiUrlBuilder) { case (builder, segment) =>
      builder.addPathSegment(segment)
    }
    queryParams.foldLeft(builderWithPathSegments) { case (builder, (key, value)) =>
      builder.addQueryParameter(key, value)
    }.build()
  }
  
  case class UrlAndParser(url: HttpUrl, parser: Reads[_])
}

/**
 * A GitHub client holds a cache, but for an async loading cache to work, it also needs to asynchronously do both
 * fetching & parsing - so needs an execution context.
 */
class GitHub(ghCredentials: GitHubCredentials.Provider)(implicit ec: EC) {
  import GitHub.*

  private val okHttpClient = new OkHttpClient.Builder()
    .cache(new okhttp3.Cache(Files.createTempDirectory("github-api-cache").toFile, 5 * 1024 * 1024))
    .build()

  private val requestHeaders: IO[Map[String, String]] = ghCredentials().map { creds =>
    Map(
      "Authorization" -> s"Bearer ${creds.accessToken.value}",
      "Accept" -> "application/vnd.github+json",
      "X-GitHub-Api-Version" -> "2022-11-28"
    )
  }

  private def addAuth[B](builder: B, f: (B, String, String) => B): IO[B] =
    requestHeaders.map(_.foldLeft(builder) { case (b, (k,v)) => f(b, k, v) })

  def executeAndWrap[T](settings: ReqMod)(processor: (Request, Response) => T): FR[T] = for {
    builderWithAuth <- addAuth(settings(new Builder()), _ addHeader(_, _))
    request = builderWithAuth.build()
    response <- IO.fromFuture(IO(okHttpClient.execute(request) {
      resp => GitHubResponse(logAndGetMeta(request, resp), processor(request, resp))
    }))
  } yield response

  def executeAndReadJson[T: Reads](settings: ReqMod): FR[T] = executeAndWrap(settings) {
    case (req, response) => readAndResolve[T](req, response)
  }

  val httpClient: HttpClient = HttpClient.newBuilder().build()

  val fetching: Fetching[UrlAndParser, GitHubResponse[String]] = new HttpFetching[String](
    httpClient,
    BodyHandlers.ofString(),
    builder => addAuth(builder, _.header(_, _)).unsafeToFuture()(cats.effect.unsafe.implicits.global)
  ).keyOn[UrlAndParser](_.url.uri).mapResponse {
    httpResp => GitHubResponse(ResponseMeta.from(httpResp), httpResp.body)
  }

  val etagCache: ETagCache[UrlAndParser, GitHubResponse[_]] = new ETagCache(
    fetching.thenParsingWithKey { (urlAndParser, response) =>
      response.map(resp => urlAndParser.parser.reads(Json.parse(resp)).get)
    },
    AlwaysWaitForRefreshedValue,
    _.expireAfterWrite(1.minutes)
  )

  def getAndCache[T: Reads](url: HttpUrl): FR[T] = 
    IO.fromFuture(IO(etagCache.get(UrlAndParser(url, implicitly[Reads[T]])).map(_.get.asInstanceOf[GitHubResponse[T]])))
    // executeAndReadJson[T](_.url(url).withCaching)

  def create[CC : Writes, Res: Reads](url: HttpUrl, cc: CC)(implicit ec: EC) : FR[Res] =
    executeAndReadJson[Res](_.url(url).post(toJson(cc)))

  def put[CC : Writes, Res: Reads](url: HttpUrl, cc: CC)(implicit ec: EC) : FR[Res] =
    executeAndReadJson[Res](_.url(url).put(toJson(cc)))

  def executeAndReadOptionalJson[T : Reads](settings: ReqMod): FR[Option[T]] = executeAndWrap(settings) {
    case (req, response) => Option.when(response.code() != 404)(readAndResolve[T](req, response))
  }

  def executeAndCheck(settings: ReqMod): FR[Boolean] = executeAndWrap(settings) { case (req, resp) =>
    val allGood = resp.code() == Status.NO_CONTENT
    if (!allGood) {
      logger.warn(s"Non-OK response code to ${req.method} ${req.url} : ${resp.code()}\n\n${resp.body()}\n\n" )
    }
    allGood
  }

  /**
   * https://docs.github.com/en/rest/rate-limit/rate-limit?apiVersion=2022-11-28#get-rate-limit-status-for-the-authenticated-user
   *
   * Note that actually, accessing this endpoint does not count against your REST API rate limit, so the
   * ResponseMeta.Quota would be misleading.
   */
  def checkRateLimit(): IO[Option[RateLimit.Status]] = for {
    builderWithAuth <- addAuth(new Builder().url(path("rate_limit")), _.header(_, _))
    resp <- IO.fromFuture(IO(okHttpClient.execute(builderWithAuth.build())(identity)))
  } yield ResponseMeta.rateLimitStatusFrom(resp.headers.toJdkHeaders)

  /**
    * https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#create-a-repository-for-the-authenticated-user
    */
  def createRepo(repo: CreateRepo): FR[Repo] = create(path("user", "repos"), repo)

  /**
    * https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#create-an-organization-repository
    */
  def createOrgRepo(org: String, repo: CreateRepo): FR[Repo] = create(path("orgs", org, "repos"), repo)

  /**
    * https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#get-a-repository
    * GET /repos/{owner}/{repo}
    */
  def getRepo(repoId: RepoId): FR[Repo] = getAndCache(path("repos", repoId.owner, repoId.name))

  /**
    * https://docs.github.com/en/rest/orgs/orgs?apiVersion=2022-11-28#get-an-organization
    * GET /orgs/{org}
    */
  def getOrg(org: String): FR[Org] = getAndCache(path("orgs", org))

  /**
    * https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#delete-a-repository
    * DELETE /repos/{owner}/{repo}
    */
  def deleteRepo(repo: Repo): FR[Boolean] = executeAndCheck(_.url(repo.url).delete())

  /**
    * https://docs.github.com/en/rest/repos/contents?apiVersion=2022-11-28#create-or-update-file-contents
    * PUT /repos/{owner}/{repo}/contents/{path}
    */
  def createFile(repo: Repo, path: String, createFile: CreateFile): FR[ContentCommit] =
    create(HttpUrl.get(repo.contents.urlFor(path)), createFile)

  /**
    * https://docs.github.com/en/rest/git/trees?apiVersion=2022-11-28#get-a-tree
    * GET /repos/{owner}/{repo}/git/trees/{tree_sha}
    */
  def getTreeRecursively(repo: Repo, sha: String): FR[Tree] =
    getAndCache(HttpUrl.get(repo.trees.urlFor(sha)+"?recursive=1"))

  def followAndEnumerate[T: Reads](url: HttpUrl): ListStream[T] = punk[Seq[T], T](url)(Chunk.from)

  def followAndEnumerateChunky[C: Reads](url: HttpUrl): ListStream[C] = punk[C, C](url)(Chunk.singleton)

  def punk[S: Reads, T](url: HttpUrl)(f: S => Chunk[T]) =
    unfoldChunkLoopEval(url)(getAndCache[S](_).map {
      resp => (f(resp.result), resp.responseMeta.nextOpt)
    })

  /**
   * [[https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#list-repositories-for-the-authenticated-user]]
   * GET /user/repos
   */
  def listRepos(queryParams: (String, String)*): ListStream[Repo] =
    followAndEnumerate[Repo](pathWithQueryParams(queryParams, "user", "repos"))

  /**
   * [[https://docs.github.com/en/rest/apps/installations?apiVersion=2022-11-28#list-repositories-accessible-to-the-app-installation]]
   * GET /installation/repositories
   */
  def listReposAccessibleToTheApp(): ListStream[InstallationRepos] =
    followAndEnumerateChunky[InstallationRepos](path("installation", "repositories"))

  /**
   * [[https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#list-organization-repositories]]
   * GET /orgs/{org}/repos
   */
  def listOrgRepos(org: String, queryParams: (String, String)*): ListStream[Repo] =
    followAndEnumerate[Repo](pathWithQueryParams(queryParams, "orgs", org, "repos"))

  /**
   * https://docs.github.com/en/rest/orgs/members?apiVersion=2022-11-28#check-organization-membership-for-a-user
   * GET /orgs/{org}/members/{username}
   */
  def checkMembership(org: String, username: String): FR[Boolean] =
    executeAndCheck(_.url(path("orgs", org, "members", username)).get.withCaching)

  /**
    * https://docs.github.com/en/rest/teams/teams?apiVersion=2022-11-28#list-teams-for-the-authenticated-user
    * GET /user/teams
    */
  def getUserTeams(): ListStream[Team] = followAndEnumerate(path("user", "teams"))

  /**
    * https://docs.github.com/en/rest/users/emails?apiVersion=2022-11-28#list-email-addresses-for-the-authenticated-user
    * GET /user/emails
    */
  def getUserEmails(): ListStream[Email] = followAndEnumerate(path("user", "emails"))

  /**
    * https://docs.github.com/en/rest/repos/webhooks?apiVersion=2022-11-28#list-repository-webhooks
    * GET /repos/{owner}/{repo}/hooks
    */
  def listHooks(repo: RepoId): ListStream[Hook] = followAndEnumerate(path("repos", repo.owner, repo.name, "hooks"))

  /**
   * https://docs.github.com/en/rest/repos/webhooks?apiVersion=2022-11-28#list-repository-webhooks
   * GET /repos/{owner}/{repo}/hooks
   */
  def listHooks(repo: Repo): ListStream[Hook] = followAndEnumerate(HttpUrl.get(repo.hooks_url))

  /**
    * https://docs.github.com/en/rest/teams/teams?apiVersion=2022-11-28#get-a-team-legacy
    * GET /teams/{team_id}
    */
  @deprecated("We recommend migrating your existing code to use the 'Get a team by name' endpoint.")
  def getTeam(teamId: Long): FR[Team] = getAndCache(path("teams", teamId.toString))

  /**
   * [[https://docs.github.com/en/rest/teams/teams?apiVersion=2022-11-28#get-a-team-by-name]]
   * GET /orgs/{org}/teams/{team_slug}
   */
  def getTeamByName(org: String, team_slug: String): FR[Option[Team]] = {
    // GET /orgs/{org}/teams/{team_slug}
    executeAndReadOptionalJson(_.url(path("orgs", org, "teams", team_slug)).withCaching)
  }

  /**
   * https://docs.github.com/en/rest/orgs/members?apiVersion=2022-11-28#get-organization-membership-for-a-user
   * GET /orgs/{org}/memberships/{username}
   */
  def getMembership(org: String, username: String): FR[Membership] =
    getAndCache(path("orgs", org, "memberships", username))

  /**
   * https://docs.github.com/en/rest/teams/members?apiVersion=2022-11-28#get-team-membership-for-a-user-legacy
   * GET /teams/{team_id}/memberships/{username}
   */
  @deprecated("We recommend migrating your existing code to use the new 'Get team membership for a user' endpoint.")
  def getTeamMembership(teamId: Long, username: String): FR[Membership] =
    getAndCache(path("teams", teamId.toString, "memberships", username))

  /**
   * https://docs.github.com/en/rest/users/users?apiVersion=2022-11-28#get-the-authenticated-user
   * GET /user
   */
  def getUser(): FR[User] = getAndCache(path("user"))

  /**
   * https://docs.github.com/en/rest/users/users?apiVersion=2022-11-28#get-a-user
   * GET /users/{username}
   */
  def getUser(username: String): FR[User] = getAndCache(path("users", username))


  /**
   * https://docs.github.com/en/rest/teams/members?apiVersion=2022-11-28#list-team-members
   * GET /orgs/{org}/teams/{team_slug}/members
   */
  def listTeamMembers(org: String, teamSlug: String): ListStream[User] =
    followAndEnumerate[User](path("orgs", org, "teams", teamSlug, "members"))

  /**
    * https://docs.github.com/en/rest/teams/teams?apiVersion=2022-11-28#add-or-update-team-repository-permissions-legacy
    * PUT /teams/{team_id}/repos/{owner}/{repo}
    */
  @deprecated("We recommend migrating your existing code to use the new \"Add or update team repository permissions\" endpoint.")
  def addTeamRepo(teamId: Long, org: String, repoName: String): FR[Boolean] =
    executeAndCheck(_.url(path("teams", teamId.toString, "repos", org, repoName)).put(Json.obj("permission" -> "admin")))

  /**
   * https://docs.github.com/en/rest/teams/members?apiVersion=2022-11-28#add-or-update-team-membership-for-a-user
   * PUT /orgs/{org}/teams/{team_slug}/memberships/{username}
   */
  def addOrUpdateTeamMembershipForAUser(org: String, team_slug: String, username: String, role: String): FR[Boolean] =
    executeAndCheck(_.url(path("orgs", org, "teams", team_slug, "memberships", username)).put(Json.obj("role" -> role)))

  /**
   * https://docs.github.com/en/rest/issues/comments?apiVersion=2022-11-28#create-an-issue-comment
   * POST /repos/{owner}/{repo}/issues/{issue_number}/comments
   */
  def createComment(commentable: Commentable, comment: String): FR[Comment] =
    create(HttpUrl.get(commentable.comments_url), CreateComment(comment))

  /**
    * https://docs.github.com/en/rest/issues/comments?apiVersion=2022-11-28#list-issue-comments
    * GET /repos/:owner/:repo/issues/:number/comments
    * TODO Pagination
    */
  def listComments(commentable: Commentable): FR[Seq[Comment]] =
    getAndCache(HttpUrl.get(commentable.comments_url))

}

case class InstallationRepos(
  total_count: Int,
  repositories: Seq[Repo]
)

object InstallationRepos {
  implicit val reads: Reads[InstallationRepos] = Json.reads
}