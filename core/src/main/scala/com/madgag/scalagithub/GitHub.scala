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

import cats.effect.{IO, Resource}
import cats.effect.kernel.Resource
import cats.effect.std.Dispatcher
import com.madgag.github.AccessToken
import com.madgag.github.apps.{GitHubAppAuth, GitHubAppJWTs, InstallationAccess}
import com.madgag.ratelimitstatus.RateLimit
import com.madgag.scalagithub.commands.*
import com.madgag.scalagithub.model.*
import fs2.Chunk
import fs2.Stream.unfoldChunkLoopEval
import org.eclipse.jgit.transport.CredentialsProvider
import play.api.Logger
import play.api.libs.json.*
import play.api.libs.json.Json.toJson
import sttp.client4.httpclient.cats.HttpClientCatsBackend
import sttp.client4.{UriContext, *}
import sttp.model.*
import sttp.model.Uri.*

import scala.concurrent.ExecutionContext as EC
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

  class Factory(httpClient: Backend[IO])(using parsingEC: EC, val dispatcher: Dispatcher[IO]) {
    def clientFor(credentialsProvider: GitHubCredentials.Provider): IO[GitHub] = for {
      gitHubHttp <- GitHubHttp(credentialsProvider, httpClient, dispatcher)
      gitHub = new GitHub(gitHubHttp)
    } yield gitHub

    def accessWithUserToken(accessToken: AccessToken): IO[ClientWithAccess[UserTokenAccess]] = {
      val credsProvider = GitHubCredentials.Provider.fromStatic(accessToken)
      for {
        gitHub <- clientFor(credsProvider)
        user <- gitHub.getUser()
      } yield new ClientWithAccess(gitHub, credsProvider, UserTokenAccess(user.result))
    }

    def accessSoleAppInstallation(gitHubAppJWTs: GitHubAppJWTs): IO[ClientWithAccess[GitHubAppAccess]] = {
      val gitHubAppAuth = GitHubAppAuth(gitHubAppJWTs, httpClient)
      for {
        accountAccess <- gitHubAppAuth.accessSoleInstallation()(using dispatcher)
        credsProvider = InstallationAccess.credentialsProviderFor(gitHubAppAuth, accountAccess.installation)
        gitHub <- clientFor(credsProvider)
      } yield new ClientWithAccess(gitHub, credsProvider, accountAccess)
    }
  }

  object Factory {
    def apply()(using parsingEC: EC): Resource[IO, Factory] = for {
      httpClient <- HttpClientCatsBackend.resource[IO]()
      dispatcher <- Dispatcher.parallel[IO]
    } yield new Factory(httpClient)(using parsingEC, dispatcher)
  }


  val logger = Logger(getClass)

  type FR[A] = IO[GitHubResponse[A]]

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

  def readAndResolve[T: Reads](request: Request[String], response: Response[String]): T = {
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

  def path(segments: String*): Uri = apiUri.addPath(segments.toSeq)

}

class GitHub(val gitHubHttp: GitHubHttp) {
  import GitHub.*

  def executeAndWrap[T](req: Request[String])(processor: Response[String] => T): FR[T] = for {
    resp <- gitHubHttp.execute(req)
  } yield GitHubResponse(logAndGetMeta(resp), processor(resp))

  def executeAndReadJson[T: Reads](req: Request[String]): FR[T] = executeAndWrap(req) {
    response => readAndResolve[T](req, response)
  }

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
    gitHubHttp.execute(quickRequest.get(apiUri.withPath("rate_limit"))).map(ResponseMeta.rateLimitStatusFrom)

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
  def getRepo(repoId: RepoId): FR[Repo] = gitHubHttp.getAndCache(path("repos", repoId.owner, repoId.name))

  /**
   * https://docs.github.com/en/rest/pulls/pulls?apiVersion=2022-11-28#get-a-pull-request
   * GET /repos/{owner}/{repo}/pulls/{pull_number}
   */
  def getPullRequest(prId: PullRequest.Id): FR[PullRequest] =
    gitHubHttp.getAndCache(path("repos", prId.repo.owner, prId.repo.name, "pulls", prId.num.toString))

  /**
    * https://docs.github.com/en/rest/orgs/orgs?apiVersion=2022-11-28#get-an-organization
    * GET /orgs/{org}
    */
  def getOrg(org: String): FR[Org] = gitHubHttp.getAndCache(path("orgs", org))

  /**
    * https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#delete-a-repository
    * DELETE /repos/{owner}/{repo}
    */
  def deleteRepo(repo: Repo): FR[Boolean] = executeAndCheck(quickRequest.delete(Uri.unsafeParse(repo.url)))

  /**
    * https://docs.github.com/en/rest/repos/contents?apiVersion=2022-11-28#create-or-update-file-contents
    * PUT /repos/{owner}/{repo}/contents/{path}
    */
  def createFile(repo: Repo, path: String, createFile: CreateOrUpdateFile): FR[ContentCommit] =
    create(repo.contents.urlFor(path), createFile)

  /**
    * https://docs.github.com/en/rest/git/trees?apiVersion=2022-11-28#get-a-tree
    * GET /repos/{owner}/{repo}/git/trees/{tree_sha}
    */
  def getTreeRecursively(repo: Repo, sha: String): FR[Tree] =
    gitHubHttp.getAndCache(repo.trees.urlFor(sha).addParam("recursive", "1"))

  def followAndEnumerate[T: Reads](uri: Uri): ListStream[T] = follow[Seq[T], T](uri)(Chunk.from)

  def followAndEnumerateChunky[C: Reads](uri: Uri): ListStream[C] = follow[C, C](uri)(Chunk.singleton)

  private def follow[S: Reads, T](uri: Uri)(f: S => Chunk[T]) = unfoldChunkLoopEval(uri)(gitHubHttp.getAndCache[S](_).map {
    resp => (f(resp.result), resp.responseMeta.nextOpt)
  })

  /**
   * [[https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#list-repositories-for-the-authenticated-user]]
   * GET /user/repos
   */
  def listRepos(queryParams: (String, String)*): ListStream[Repo] =
    followAndEnumerate(path("user", "repos").addParams(queryParams*))

  /**
   * [[https://docs.github.com/en/rest/apps/installations?apiVersion=2022-11-28#list-repositories-accessible-to-the-app-installation]]
   * GET /installation/repositories
   */
  def listReposAccessibleToTheApp(): ListStream[InstallationRepos] =
    followAndEnumerateChunky(path("installation", "repositories"))

  /**
   * [[https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#list-organization-repositories]]
   * GET /orgs/{org}/repos
   */
  def listOrgRepos(org: String, queryParams: (String, String)*): ListStream[Repo] =
    followAndEnumerate(path("orgs", org, "repos").addParams(queryParams*))

  /**
   * https://docs.github.com/en/rest/orgs/members?apiVersion=2022-11-28#check-organization-membership-for-a-user
   * GET /orgs/{org}/members/{username}
   */
  def checkMembership(org: String, username: String): FR[Boolean] =
    executeAndCheck(quickRequest.get(path("orgs", org, "members", username)))

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
  def listHooks(repo: RepoId): ListStream[Hook] =
    followAndEnumerate(path("repos", repo.owner, repo.name, "hooks"))

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
  def getTeam(teamId: Long): FR[Team] = gitHubHttp.getAndCache(path("teams", teamId.toString))

  /**
   * [[https://docs.github.com/en/rest/teams/teams?apiVersion=2022-11-28#get-a-team-by-name]]
   * GET /orgs/{org}/teams/{team_slug}
   */
  def getTeamByName(org: String, team_slug: String): FR[Option[Team]] =
    executeAndReadOptionalJson(quickRequest.get(path("orgs", org, "teams", team_slug)))

  /**
   * https://docs.github.com/en/rest/orgs/members?apiVersion=2022-11-28#get-organization-membership-for-a-user
   * GET /orgs/{org}/memberships/{username}
   */
  def getMembership(org: String, username: String): FR[Membership] =
    gitHubHttp.getAndCache(path("orgs", org, "memberships", username))

  /**
   * https://docs.github.com/en/rest/teams/members?apiVersion=2022-11-28#get-team-membership-for-a-user-legacy
   * GET /teams/{team_id}/memberships/{username}
   */
  @deprecated("We recommend migrating your existing code to use the new 'Get team membership for a user' endpoint.")
  def getTeamMembership(teamId: Long, username: String): FR[Membership] =
    gitHubHttp.getAndCache(path("teams", teamId.toString, "memberships", username))

  /**
   * https://docs.github.com/en/rest/users/users?apiVersion=2022-11-28#get-the-authenticated-user
   * GET /user
   */
  def getUser(): FR[User] = gitHubHttp.getAndCache(path("user"))

  /**
   * https://docs.github.com/en/rest/users/users?apiVersion=2022-11-28#get-a-user
   * GET /users/{username}
   */
  def getUser(username: String): FR[User] = gitHubHttp.getAndCache(path("users", username))

  /**
   * https://docs.github.com/en/rest/teams/members?apiVersion=2022-11-28#list-team-members
   * GET /orgs/{org}/teams/{team_slug}/members
   */
  def listTeamMembers(org: String, teamSlug: String): ListStream[User] =
    followAndEnumerate(path("orgs", org, "teams", teamSlug, "members"))

  /**
    * https://docs.github.com/en/rest/teams/teams?apiVersion=2022-11-28#add-or-update-team-repository-permissions-legacy
    * PUT /teams/{team_id}/repos/{owner}/{repo}
    */
  @deprecated("We recommend migrating your existing code to use the new \"Add or update team repository permissions\" endpoint.")
  def addTeamRepo(teamId: Long, org: String, repoName: String): FR[Boolean] =
    executeAndCheck(reqWithBody(Json.obj("permission" -> "admin")).put(path("teams", teamId.toString, "repos", org, repoName)))

  /**
   * https://docs.github.com/en/rest/teams/members?apiVersion=2022-11-28#add-or-update-team-membership-for-a-user
   * PUT /orgs/{org}/teams/{team_slug}/memberships/{username}
   */
  def addOrUpdateTeamMembershipForAUser(org: String, team_slug: String, username: String, role: String): FR[Boolean] =
    executeAndCheck(reqWithBody(Json.obj("role" -> role)).put(path("orgs", org, "teams", team_slug, "memberships", username)))

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
  def listComments(commentable: Commentable): FR[Seq[Comment]] = gitHubHttp.getAndCache(Uri.unsafeParse(commentable.comments_url))

}
