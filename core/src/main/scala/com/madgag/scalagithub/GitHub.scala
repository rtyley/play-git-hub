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

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.unsafe.IORuntime
import com.gu.etagcaching.FreshnessPolicy.AlwaysWaitForRefreshedValue
import com.gu.etagcaching.fetching.Fetching
import com.gu.etagcaching.{ETagCache, FreshnessPolicy}
import com.madgag.ratelimitstatus.RateLimit
import com.madgag.scalagithub.TolerantParsingOfIntermittentListWrapper.tolerantlyParse
import com.madgag.scalagithub.commands.*
import com.madgag.scalagithub.model.*
import fs2.Chunk
import fs2.Stream.unfoldChunkLoopEval
import play.api.Logger
import play.api.libs.json.Json.toJson
import play.api.libs.json.*
import sttp.client4.httpclient.cats.HttpClientCatsBackend
import sttp.client4.{UriContext, *}
import sttp.model.*
import sttp.model.Uri.*

import scala.concurrent.ExecutionContext as EC
import scala.concurrent.duration.*
import scala.language.implicitConversions

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

  val JsonMediaType = MediaType.parse("application/json; charset=utf-8")

  def logAndGetMeta(response: Response[String]): ResponseMeta = {
    val meta = ResponseMeta.from(response)
    val mess = s"${meta.quota.hitOrMiss} ${response.code} ${response.request.method} ${response.request.uri}"
    meta.quota.statusOpt.filter(_.consumptionIsDangerous).fold {
      logger.debug(mess)
    } { status =>
      logger.warn(s"$mess ${status.summary}")
    }
    meta
  }

  def readAndResolve[T](request: Request[String], response: Response[String])(implicit ev: Reads[T]): T = {
    val responseBody = response.body

    val json = Json.parse(responseBody)

    json.validate[T] match {
      case error: JsError =>
        val message = s"Error decoding ${request.method} ${request.uri} (etag={${response.header("etag")}}) : $error"
        val mess = s"$message\n\n$json\n\n"
        println(mess)
        logger.warn(mess)
        throw new RuntimeException(message)
      case JsSuccess(result, _) => result
    }
  }

  type ListStream[T] = fs2.Stream[IO, T]

  def reqWithBody[CC : Writes](cc: CC): PartialRequest[String] = quickRequest.body(toJson(cc).toString)

  val apiUri: Uri = uri"https://api.github.com"

  val PermanentHeaders: Seq[Header] = Seq(
    Header("Accept", "application/vnd.github+json"),
    Header("X-GitHub-Api-Version", "2022-11-28")
  )
  
  case class UrlAndParser(uri: Uri, parser: Reads[_])
}

/**
 * A GitHub client holds a cache, but for an async loading cache to work, it also needs to asynchronously do both
 * fetching & parsing - so needs an execution context.
 */
class GitHub(ghCredentials: GitHubCredentials.Provider)(using EC, IORuntime) {
  import GitHub.*

  val httpClient: Resource[IO, WebSocketBackend[IO]] = HttpClientCatsBackend.resource[IO]()

  private val authHeader: IO[Header] =
    ghCredentials.map { creds => Header("Authorization", s"Bearer ${creds.accessToken.value}") }

  private def execute[T](req: Request[T]): IO[Response[T]] = for {
    auth <- authHeader
    resp <- httpClient.use(req.headers(PermanentHeaders :+ auth *).send)
  } yield resp

  def executeAndWrap[T](req: Request[String])(processor: Response[String] => T): FR[T] = for {
    resp <- execute(req)
  } yield GitHubResponse(logAndGetMeta(resp), processor(resp))

  def executeAndReadJson[T: Reads](req: Request[String]): FR[T] = executeAndWrap(req) {
    response => readAndResolve[T](req, response)
  }

  val fetching: Fetching[UrlAndParser, GitHubResponse[String]] = new HttpFetching[String](
    httpClient,
    quickRequest.headers(PermanentHeaders *),
    authHeader.map(Seq(_))
  ).keyOn[UrlAndParser](_.uri).mapResponse { httpResp => GitHubResponse(ResponseMeta.from(httpResp), httpResp.body) }

  private val etagCache: ETagCache[UrlAndParser, GitHubResponse[_]] = new ETagCache(
    fetching.thenParsingWithKey { (urlAndParser, response) =>
      response.map(resp => {
        val jsResult: JsResult[_] = {
          val jsValue = Json.parse(resp)
          tolerantlyParse(jsValue)(urlAndParser.parser)
        }
        if (jsResult.isError) {
          println(urlAndParser.uri)
          println(resp)
          println(jsResult)
          println(urlAndParser.parser)
        }
        jsResult.get
      })
    },
    AlwaysWaitForRefreshedValue,
    _.expireAfterWrite(1.minutes)
  )

  def getAndCache[T: Reads](uri: Uri): FR[T] =
    IO.fromFuture(IO(etagCache.get(UrlAndParser(uri, implicitly[Reads[T]])).map(_.get.asInstanceOf[GitHubResponse[T]])))

  def create[CC : Writes, Res: Reads](uri: Uri, cc: CC) : FR[Res] = executeAndReadJson[Res](reqWithBody(cc).post(uri))

  def put[CC : Writes, Res: Reads](uri: Uri, cc: CC) : FR[Res] = executeAndReadJson[Res](reqWithBody(cc).put(uri))

  def executeAndReadOptionalJson[T : Reads](req: Request[String]): FR[Option[T]] = executeAndWrap(req) {
    response => Option.when(response.code != StatusCode.NotFound)(readAndResolve[T](req, response))
  }

  def executeAndCheck(req: Request[String]): FR[Boolean] = executeAndWrap(req) { resp =>
    val allGood = resp.code == StatusCode.NoContent
    if (!allGood) {
      logger.warn(s"Non-OK response code to ${req.method} ${req.uri.pathToString} : ${resp.code}\n\n${resp.body}\n\n" )
    }
    allGood
  }

  /**
   * https://docs.github.com/en/rest/rate-limit/rate-limit?apiVersion=2022-11-28#get-rate-limit-status-for-the-authenticated-user
   *
   * Note that actually, accessing this endpoint does not count against your REST API rate limit, so the
   * ResponseMeta.Quota would be misleading.
   */
  def checkRateLimit(): IO[Option[RateLimit.Status]] =
    execute(quickRequest.get(apiUri.withPath("rate_limit"))).map(ResponseMeta.rateLimitStatusFrom)

  /**
    * https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#create-a-repository-for-the-authenticated-user
    */
  def createRepo(repo: CreateRepo): FR[Repo] = create(apiUri.addPath("user", "repos"), repo)

  /**
    * https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#create-an-organization-repository
    */
  def createOrgRepo(org: String, repo: CreateRepo): FR[Repo] =
    create(apiUri.addPath("orgs", org, "repos"), repo)

  /**
    * https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#get-a-repository
    * GET /repos/{owner}/{repo}
    */
  def getRepo(repoId: RepoId): FR[Repo] = getAndCache(apiUri.addPath("repos", repoId.owner, repoId.name))

  /**
    * https://docs.github.com/en/rest/orgs/orgs?apiVersion=2022-11-28#get-an-organization
    * GET /orgs/{org}
    */
  def getOrg(org: String): FR[Org] = getAndCache(apiUri.addPath("orgs", org))

  /**
    * https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#delete-a-repository
    * DELETE /repos/{owner}/{repo}
    */
  def deleteRepo(repo: Repo): FR[Boolean] = executeAndCheck(quickRequest.delete(Uri.unsafeParse(repo.url)))

  /**
    * https://docs.github.com/en/rest/repos/contents?apiVersion=2022-11-28#create-or-update-file-contents
    * PUT /repos/{owner}/{repo}/contents/{path}
    */
  def createFile(repo: Repo, path: String, createFile: CreateFile): FR[ContentCommit] =
    create(repo.contents.urlFor(path), createFile)

  /**
    * https://docs.github.com/en/rest/git/trees?apiVersion=2022-11-28#get-a-tree
    * GET /repos/{owner}/{repo}/git/trees/{tree_sha}
    */
  def getTreeRecursively(repo: Repo, sha: String): FR[Tree] =
    getAndCache(repo.trees.urlFor(sha).addParam("recursive", "1"))

  def followAndEnumerate[T: Reads](uri: Uri): ListStream[T] = follow[Seq[T], T](uri)(Chunk.from)

  def followAndEnumerateChunky[C: Reads](uri: Uri): ListStream[C] = follow[C, C](uri)(Chunk.singleton)

  private def follow[S: Reads, T](uri: Uri)(f: S => Chunk[T]) = unfoldChunkLoopEval(uri)(getAndCache[S](_).map {
    resp => (f(resp.result), resp.responseMeta.nextOpt)
  })

  /**
   * [[https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#list-repositories-for-the-authenticated-user]]
   * GET /user/repos
   */
  def listRepos(queryParams: (String, String)*): ListStream[Repo] =
    followAndEnumerate[Repo](apiUri.addPath("user", "repos").addParams(queryParams*))

  /**
   * [[https://docs.github.com/en/rest/apps/installations?apiVersion=2022-11-28#list-repositories-accessible-to-the-app-installation]]
   * GET /installation/repositories
   */
  def listReposAccessibleToTheApp(): ListStream[InstallationRepos] =
    followAndEnumerateChunky[InstallationRepos](apiUri.addPath("installation", "repositories"))

  /**
   * [[https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#list-organization-repositories]]
   * GET /orgs/{org}/repos
   */
  def listOrgRepos(org: String, queryParams: (String, String)*): ListStream[Repo] =
    followAndEnumerate[Repo](apiUri.addPath("orgs", org, "repos").addParams(queryParams*))

  /**
   * https://docs.github.com/en/rest/orgs/members?apiVersion=2022-11-28#check-organization-membership-for-a-user
   * GET /orgs/{org}/members/{username}
   */
  def checkMembership(org: String, username: String): FR[Boolean] =
    executeAndCheck(quickRequest.get(apiUri.addPath("orgs", org, "members", username)))

  /**
    * https://docs.github.com/en/rest/teams/teams?apiVersion=2022-11-28#list-teams-for-the-authenticated-user
    * GET /user/teams
    */
  def getUserTeams(): ListStream[Team] = followAndEnumerate(apiUri.addPath("user", "teams"))

  /**
    * https://docs.github.com/en/rest/users/emails?apiVersion=2022-11-28#list-email-addresses-for-the-authenticated-user
    * GET /user/emails
    */
  def getUserEmails(): ListStream[Email] = followAndEnumerate(apiUri.addPath("user", "emails"))

  /**
    * https://docs.github.com/en/rest/repos/webhooks?apiVersion=2022-11-28#list-repository-webhooks
    * GET /repos/{owner}/{repo}/hooks
    */
  def listHooks(repo: RepoId): ListStream[Hook] =
    followAndEnumerate(apiUri.addPath("repos", repo.owner, repo.name, "hooks"))

  /**
   * https://docs.github.com/en/rest/repos/webhooks?apiVersion=2022-11-28#list-repository-webhooks
   * GET /repos/{owner}/{repo}/hooks
   */
  def listHooks(repo: Repo): ListStream[Hook] = followAndEnumerate(Uri.unsafeParse(repo.hooks_url))

  /**
    * https://docs.github.com/en/rest/teams/teams?apiVersion=2022-11-28#get-a-team-legacy
    * GET /teams/{team_id}
    */
  @deprecated("We recommend migrating your existing code to use the 'Get a team by name' endpoint.")
  def getTeam(teamId: Long): FR[Team] = getAndCache(apiUri.addPath("teams", teamId.toString))

  /**
   * [[https://docs.github.com/en/rest/teams/teams?apiVersion=2022-11-28#get-a-team-by-name]]
   * GET /orgs/{org}/teams/{team_slug}
   */
  def getTeamByName(org: String, team_slug: String): FR[Option[Team]] =
    executeAndReadOptionalJson(quickRequest.get(apiUri.addPath("orgs", org, "teams", team_slug)))

  /**
   * https://docs.github.com/en/rest/orgs/members?apiVersion=2022-11-28#get-organization-membership-for-a-user
   * GET /orgs/{org}/memberships/{username}
   */
  def getMembership(org: String, username: String): FR[Membership] =
    getAndCache(apiUri.addPath("orgs", org, "memberships", username))

  /**
   * https://docs.github.com/en/rest/teams/members?apiVersion=2022-11-28#get-team-membership-for-a-user-legacy
   * GET /teams/{team_id}/memberships/{username}
   */
  @deprecated("We recommend migrating your existing code to use the new 'Get team membership for a user' endpoint.")
  def getTeamMembership(teamId: Long, username: String): FR[Membership] =
    getAndCache(apiUri.addPath("teams", teamId.toString, "memberships", username))

  /**
   * https://docs.github.com/en/rest/users/users?apiVersion=2022-11-28#get-the-authenticated-user
   * GET /user
   */
  def getUser(): FR[User] = getAndCache(apiUri.addPath("user"))

  /**
   * https://docs.github.com/en/rest/users/users?apiVersion=2022-11-28#get-a-user
   * GET /users/{username}
   */
  def getUser(username: String): FR[User] = getAndCache(apiUri.addPath("users", username))

  /**
   * https://docs.github.com/en/rest/teams/members?apiVersion=2022-11-28#list-team-members
   * GET /orgs/{org}/teams/{team_slug}/members
   */
  def listTeamMembers(org: String, teamSlug: String): ListStream[User] =
    followAndEnumerate[User](apiUri.addPath("orgs", org, "teams", teamSlug, "members"))

  /**
    * https://docs.github.com/en/rest/teams/teams?apiVersion=2022-11-28#add-or-update-team-repository-permissions-legacy
    * PUT /teams/{team_id}/repos/{owner}/{repo}
    */
  @deprecated("We recommend migrating your existing code to use the new \"Add or update team repository permissions\" endpoint.")
  def addTeamRepo(teamId: Long, org: String, repoName: String): FR[Boolean] =
    executeAndCheck(reqWithBody(Json.obj("permission" -> "admin")).put(apiUri.addPath("teams", teamId.toString, "repos", org, repoName)))

  /**
   * https://docs.github.com/en/rest/teams/members?apiVersion=2022-11-28#add-or-update-team-membership-for-a-user
   * PUT /orgs/{org}/teams/{team_slug}/memberships/{username}
   */
  def addOrUpdateTeamMembershipForAUser(org: String, team_slug: String, username: String, role: String): FR[Boolean] =
    executeAndCheck(reqWithBody(Json.obj("role" -> role)).put(apiUri.addPath("orgs", org, "teams", team_slug, "memberships", username)))

  /**
   * https://docs.github.com/en/rest/issues/comments?apiVersion=2022-11-28#create-an-issue-comment
   * POST /repos/{owner}/{repo}/issues/{issue_number}/comments
   */
  def createComment(commentable: Commentable, comment: String): FR[Comment] =
    create(Uri.unsafeParse(commentable.comments_url), CreateComment(comment))

  /**
    * https://docs.github.com/en/rest/issues/comments?apiVersion=2022-11-28#list-issue-comments
    * GET /repos/:owner/:repo/issues/:number/comments
    * TODO Pagination
    */
  def listComments(commentable: Commentable): FR[Seq[Comment]] = getAndCache(Uri.unsafeParse(commentable.comments_url))

}

case class InstallationRepos(
  total_count: Int,
  repositories: Seq[Repo]
)

object InstallationRepos {
  implicit val reads: Reads[InstallationRepos] = Json.reads
}