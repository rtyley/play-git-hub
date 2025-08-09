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

import com.madgag.okhttpscala._
import com.madgag.ratelimitstatus.RateLimit
import com.madgag.scalagithub.commands._
import com.madgag.scalagithub.model._
import okhttp3.Request.Builder
import okhttp3._
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import play.api.Logger
import play.api.http.Status
import play.api.libs.json.Json.toJson
import play.api.libs.json._

import java.nio.file.Files
import java.util.concurrent.TimeUnit.SECONDS
import scala.concurrent.{Future, ExecutionContext => EC}
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

  def readAndResolve[T](request: Request, response: Response)(implicit ev: Reads[T]): T = {
    val responseBody = response.body()

    val json = Json.parse(responseBody.byteStream())

    json.validate[T] match {
      case error: JsError =>
        val message = s"Error decoding ${request.method} ${request.url} : $error"
        logger.warn(s"$message\n\n$json\n\n")
        throw new RuntimeException(message)
      case JsSuccess(result, _) => result
    }
  }

  implicit class RichOkHttpBuilder(builder: Builder) {
    def withCaching: Builder = builder.cacheControl(AlwaysHitNetwork)
  }

  type ReqMod = Builder => Builder

  def apiUrlBuilder: HttpUrl.Builder = new HttpUrl.Builder().scheme("https").host("api.github.com")

  def path(segments: String*): HttpUrl = segments.foldLeft(apiUrlBuilder) { case (builder, segment) =>
    builder.addPathSegment(segment)
  }.build()
}

class GitHub(ghCredentials: GitHubCredentials.Provider) {
  import GitHub._

  val okHttpClient = new OkHttpClient.Builder()
    .cache(new okhttp3.Cache(Files.createTempDirectory("github-api-cache").toFile, 5 * 1024 * 1024))
    .build()

  def addAuth(builder: Builder)(implicit ec: EC): Future[Builder] = {
    val credsF = ghCredentials()
    for {
      creds <- credsF
    } yield builder.addHeader("Authorization", s"token ${creds.accessToken.value}")
  }

  def executeAndWrap[T](settings: ReqMod)(processor: (Request, Response) => T)(implicit ec: EC): FR[T] = for {
    builderWithAuth <- addAuth(settings(new Builder()))
    request = builderWithAuth.build()
    response <- okHttpClient.execute(request) {
      resp => GitHubResponse(logAndGetMeta(request, resp), processor(request, resp))
    }
  } yield response

  def executeAndReadJson[T: Reads](settings: ReqMod)(implicit ec: EC): FR[T] = executeAndWrap(settings) {
    case (req, response) => readAndResolve[T](req, response)
  }

  def getAndCache[T: Reads](url: HttpUrl)(implicit ec: EC): FR[T] = executeAndReadJson[T](_.url(url).withCaching)

  def create[CC : Writes, Res: Reads](url: HttpUrl, cc: CC)(implicit ec: EC) : FR[Res] =
    executeAndReadJson[Res](_.url(url).post(toJson(cc)))

  def put[CC : Writes, Res: Reads](url: HttpUrl, cc: CC)(implicit ec: EC) : FR[Res] =
    executeAndReadJson[Res](_.url(url).put(toJson(cc)))

  def executeAndReadOptionalJson[T : Reads](settings: ReqMod)(implicit ec: EC): FR[Option[T]] = executeAndWrap(settings) {
    case (req, response) => Option.when(response.code() != 404)(readAndResolve[T](req, response))
  }

  def executeAndCheck(settings: ReqMod)(implicit ec: EC): FR[Boolean] = executeAndWrap(settings) { case (req, resp) =>
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
  def checkRateLimit()(implicit ec: EC): Future[Option[RateLimit.Status]] = for {
    builderWithAuth <- addAuth(new Builder().url(path("rate_limit")))
    resp <- okHttpClient.execute(builderWithAuth.build())(identity)
  } yield ResponseMeta.rateLimitStatusFrom(resp.headers)

  /**
    * https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#create-a-repository-for-the-authenticated-user
    */
  def createRepo(repo: CreateRepo)(implicit ec: EC): FR[Repo] = create(path("user", "repos"), repo)

  /**
    * https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#create-an-organization-repository
    */
  def createOrgRepo(org: String, repo: CreateRepo)(implicit ec: EC): FR[Repo] = create(path("orgs", org, "repos"), repo)

  /**
    * https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#get-a-repository
    * GET /repos/{owner}/{repo}
    */
  def getRepo(repoId: RepoId)(implicit ec: EC): FR[Repo] = getAndCache(path("repos", repoId.owner, repoId.name))

  /**
    * https://docs.github.com/en/rest/orgs/orgs?apiVersion=2022-11-28#get-an-organization
    * GET /orgs/{org}
    */
  def getOrg(org: String)(implicit ec: EC): FR[Org] = getAndCache(path("orgs", org))

  /**
    * https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#delete-a-repository
    * DELETE /repos/{owner}/{repo}
    */
  def deleteRepo(repo: Repo)(implicit ec: EC): FR[Boolean] = executeAndCheck(_.url(repo.url).delete())

  /**
    * https://docs.github.com/en/rest/repos/contents?apiVersion=2022-11-28#create-or-update-file-contents
    * PUT /repos/{owner}/{repo}/contents/{path}
    */
  def createFile(repo: Repo, path: String, createFile: CreateFile)(implicit ec: EC): FR[ContentCommit] =
    create(HttpUrl.get(repo.contents.urlFor(path)), createFile)

  /**
    * https://docs.github.com/en/rest/git/trees?apiVersion=2022-11-28#get-a-tree
    * GET /repos/{owner}/{repo}/git/trees/{tree_sha}
    */
  def getTreeRecursively(repo: Repo, sha: String)(implicit ec: EC): FR[Tree] =
    getAndCache(HttpUrl.get(repo.trees.urlFor(sha)+"?recursive=1"))

  def followAndEnumerate[T](url: HttpUrl)(implicit ev: Reads[T], ec: EC): Source[T, NotUsed] = Source.unfoldAsync[Option[HttpUrl],T](Some(url)) {
    case Some(nextUrl) =>
      logger.debug(s"Following $nextUrl")
      for {
        response <- getAndCache[T](nextUrl)
      } yield Some(response.responseMeta.nextOpt -> response.result)
    case None => Future.successful(None)
  }

  /**
   * [[https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#list-repositories-for-the-authenticated-user]]
   * GET /user/repos
   */
  def listRepos(sort: String, direction: String)(implicit ec: EC): Source[Seq[Repo], NotUsed] =
    followAndEnumerate[Seq[Repo]](path("user", "repos"))

  /**
   * [[https://docs.github.com/en/rest/apps/installations?apiVersion=2022-11-28#list-repositories-accessible-to-the-app-installation]]
   * GET /installation/repositories
   */
  def listReposAccessibleToTheApp()(implicit ec: EC): Source[InstallationRepos, NotUsed] =
    followAndEnumerate[InstallationRepos](path("installation", "repositories"))

  /**
   * [[https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#list-organization-repositories]]
   * GET /orgs/{org}/repos
   */
  def listOrgRepos(org: String, sort: String, direction: String)(implicit ec: EC): Source[Seq[Repo], NotUsed] =
    followAndEnumerate[Seq[Repo]](path("orgs", org, "repos"))

  /**
   * https://docs.github.com/en/rest/orgs/members?apiVersion=2022-11-28#check-organization-membership-for-a-user
   * GET /orgs/{org}/members/{username}
   */
  def checkMembership(org: String, username: String)(implicit ec: EC): FR[Boolean] =
    executeAndCheck(_.url(path("orgs", org, "members", username)).get.withCaching)

  /**
    * https://docs.github.com/en/rest/teams/teams?apiVersion=2022-11-28#list-teams-for-the-authenticated-user
    * GET /user/teams
    * TODO Pagination: https://developer.github.com/guides/traversing-with-pagination/
    */
  def getUserTeams()(implicit ec: EC): FR[Seq[Team]] = getAndCache(path("user", "teams"))

  /**
    * https://docs.github.com/en/rest/users/emails?apiVersion=2022-11-28#list-email-addresses-for-the-authenticated-user
    * GET /user/emails
    * TODO Pagination: https://developer.github.com/guides/traversing-with-pagination/
    */
  def getUserEmails()(implicit ec: EC): FR[Seq[Email]] = getAndCache(path("user", "emails"))

  /**
    * https://docs.github.com/en/rest/repos/webhooks?apiVersion=2022-11-28#list-hooks
    * GET /repos/{owner}/{repo}/hooks
    * TODO Pagination: https://developer.github.com/guides/traversing-with-pagination/
    */
  def listHooks(repo: RepoId)(implicit ec: EC): FR[Seq[Hook]] =
    getAndCache(path("repos", repo.owner, repo.name, "hooks"))

  /**
   * https://docs.github.com/en/rest/repos/webhooks?apiVersion=2022-11-28#list-hooks
   * GET /repos/{owner}/{repo}/hooks
   * TODO Pagination: https://developer.github.com/guides/traversing-with-pagination/
   */
  def listHooks(repo: Repo)(implicit ec: EC): FR[Seq[Hook]] = getAndCache(HttpUrl.get(repo.hooks_url))

  /**
    * https://docs.github.com/en/rest/teams/teams?apiVersion=2022-11-28#get-a-team-legacy
    * GET /teams/{team_id}
    */
  @deprecated("We recommend migrating your existing code to use the 'Get a team by name' endpoint.")
  def getTeam(teamId: Long)(implicit ec: EC): FR[Team] = getAndCache(path("teams", teamId.toString))

  /**
   * [[https://docs.github.com/en/rest/teams/teams?apiVersion=2022-11-28#get-a-team-by-name]]
   * GET /orgs/{org}/teams/{team_slug}
   */
  def getTeamByName(org: String, team_slug: String)(implicit ec: EC): FR[Option[Team]] = {
    // GET /orgs/{org}/teams/{team_slug}
    executeAndReadOptionalJson(_.url(path("orgs", org, "teams", team_slug)).withCaching)
  }

  /**
   * https://docs.github.com/en/rest/orgs/members?apiVersion=2022-11-28#get-organization-membership-for-a-user
   * GET /orgs/{org}/memberships/{username}
   */
  def getMembership(org: String, username: String)(implicit ec: EC): FR[Membership] =
    getAndCache(path("orgs", org, "memberships", username))

  /**
   * https://docs.github.com/en/rest/teams/members?apiVersion=2022-11-28#get-team-membership-for-a-user-legacy
   * GET /teams/{team_id}/memberships/{username}
   */
  @deprecated("We recommend migrating your existing code to use the new 'Get team membership for a user' endpoint.")
  def getTeamMembership(teamId: Long, username: String)(implicit ec: EC): FR[Membership] =
    getAndCache(path("teams", teamId.toString, "memberships", username))

  def getUser()(implicit ec: EC): Future[GitHubResponse[User]] = getAndCache(path("user"))

  /**
   * https://docs.github.com/en/rest/users/users?apiVersion=2022-11-28#get-a-user
   * GET /users/{username}
   */
  def getUser(username: String)(implicit ec: EC): FR[User] = getAndCache(path("users", username))

  def listTeamMembers(org: String, teamSlug: String)(implicit ec: EC): Source[Seq[User],NotUsed] =
    followAndEnumerate[Seq[User]](path("orgs", org, "teams", teamSlug, "members"))

  /**
    * https://docs.github.com/en/rest/teams/teams?apiVersion=2022-11-28#add-or-update-team-repository-permissions-legacy
    * PUT /teams/{team_id}/repos/{owner}/{repo}
    */
  @deprecated("We recommend migrating your existing code to use the new \"Add or update team repository permissions\" endpoint.")
  def addTeamRepo(teamId: Long, org: String, repoName: String)(implicit ec: EC): FR[Boolean] =
    executeAndCheck(_.url(path("teams", teamId.toString, "repos", org, repoName)).put(Json.obj("permission" -> "admin")))

  /**
   * https://docs.github.com/en/rest/teams/members?apiVersion=2022-11-28#add-or-update-team-membership-for-a-user
   * PUT /orgs/{org}/teams/{team_slug}/memberships/{username}
   */
  def addOrUpdateTeamMembershipForAUser(org: String, team_slug: String, username: String, role: String)(implicit ec: EC): FR[Boolean] =
    executeAndCheck(_.url(path("orgs", org, "teams", team_slug, "memberships", username)).put(Json.obj("role" -> role)))

  /**
   * https://docs.github.com/en/rest/issues/comments?apiVersion=2022-11-28#create-an-issue-comment
   * POST /repos/{owner}/{repo}/issues/{issue_number}/comments
   */
  def createComment(commentable: Commentable, comment: String)(implicit ec: EC): FR[Comment] =
    create(HttpUrl.get(commentable.comments_url), CreateComment(comment))

  /**
    * https://docs.github.com/en/rest/issues/comments?apiVersion=2022-11-28#list-issue-comments
    * GET /repos/:owner/:repo/issues/:number/comments
    * TODO Pagination
    */
  def listComments(commentable: Commentable)(implicit ec: EC): FR[Seq[Comment]] =
    getAndCache(HttpUrl.get(commentable.comments_url))

}

case class InstallationRepos(
  total_count: Int,
  repositories: Seq[Repo]
)

object InstallationRepos {
  implicit val reads: Reads[InstallationRepos] = Json.reads
}