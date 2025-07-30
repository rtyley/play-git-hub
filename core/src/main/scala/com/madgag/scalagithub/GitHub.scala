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

}

class GitHub(ghCredentials: () => GitHubCredentials) {
  import GitHub._

  val okHttpClient = new OkHttpClient.Builder()
    .cache(new okhttp3.Cache(Files.createTempDirectory("github-api-cache").toFile, 5 * 1024 * 1024))
    .build()

  def checkRateLimit()(implicit ec: EC): Future[Option[RateLimit.Status]] = {
    // GET /rate_limit  https://developer.github.com/v3/rate_limit/
    execute(addAuth(new Builder().url(path("rate_limit")).get).build()) {
      resp => ResponseMeta.rateLimitStatusFrom(resp.headers)
    }
  }

  /**
    * https://developer.github.com/v3/repos/#create
    */
  def createRepo(repo: CreateRepo)(implicit ec: EC): FR[Repo] =
    executeAndReadJson(addAuth(new Builder().url(path("user", "repos")).post(toJson(repo))).build)

  /**
    * https://developer.github.com/v3/repos/#create
    */
  def createOrgRepo(org: String, repo: CreateRepo)(implicit ec: EC): FR[Repo] = {
    executeAndReadJson(addAuth(new Builder().url(path("orgs", org, "repos")).post(toJson(repo))).build)
  }

  /**
    * https://developer.github.com/v3/repos/#get
    */
  def getRepo(repoId: RepoId)(implicit ec: EC): FR[Repo] = {
    // GET /repos/:owner/:repo
    executeAndReadJson(addAuthAndCaching(new Builder().url(path("repos", repoId.owner, repoId.name))))
  }

  /**
    * https://developer.github.com/v3/orgs/#get-an-organization
    */
  def getOrg(org: String)(implicit ec: EC): FR[Org] = {
    // GET /orgs/:org
    executeAndReadJson(addAuthAndCaching(new Builder().url(path("orgs", org))))
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
      } yield Some(response.responseMeta.nextOpt -> response.result)
    case None => Future.successful(None)
  }

  /**
    * https://developer.github.com/v3/repos/#list-your-repositories
    */
  def listRepos(sort: String, direction: String)(implicit ec: EC): Source[Seq[Repo], NotUsed] = {
    // GET /user/repos
    followAndEnumerate[Repo](path("user", "repos"))
  }

  /**
   * https://docs.github.com/en/rest/repos/repos?apiVersion=2022-11-28#list-organization-repositories
   */
  def listOrgRepos(org: String, sort: String, direction: String)(implicit ec: EC): Source[Seq[Repo], NotUsed] = {
    // GET orgs/{org}/repos
    followAndEnumerate[Repo](path("orgs", org, "repos"))
  }

  def checkMembership(org: String, username: String)(implicit ec: EC): Future[Boolean] = {
    //GET /orgs/:org/members/:username
    val url = path("orgs", org, "members", username)

    execute(addAuthAndCaching(new Builder().url(url).get))(_.code == Status.NO_CONTENT)
  }

  /**
    * https://developer.github.com/v3/orgs/teams/#list-user-teams
    */
  def getUserTeams()(implicit ec: EC): FR[Seq[Team]] = {
    // GET /user/teams
    val url = path("user", "teams")

    // TODO Pagination: https://developer.github.com/guides/traversing-with-pagination/
    executeAndReadJson(addAuthAndCaching(new Builder().url(url)))
  }

  /**
    * https://developer.github.com/v3/users/emails/#list-email-addresses-for-a-user
    */
  def getUserEmails()(implicit ec: EC): FR[Seq[Email]] = {
    // GET /user/emails
    val url = path("user", "emails")

    // TODO Pagination: https://developer.github.com/guides/traversing-with-pagination/
    executeAndReadJson[Seq[Email]](addAuthAndCaching(new Builder().url(url)))
  }

  /**
    * https://developer.github.com/v3/repos/hooks/#list-hooks
    */
  def listHooks(repo: RepoId)(implicit ec: EC): FR[Seq[Hook]] = {
    // GET /repos/:owner/:repo/hooks
    val url = path("repos", repo.owner, repo.name, "hooks")
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
    executeAndReadJson(addAuthAndCaching(new Builder().url(path("teams", teamId.toString))))
  }

  /**
   * https://docs.github.com/en/rest/teams/teams?apiVersion=2022-11-28#get-a-team-by-name
   */
  def getTeamByName(org: String, team_slug: String)(implicit ec: EC): FR[Option[Team]] = {
    // GET /orgs/{org}/teams/{team_slug}
    executeAndReadOptionalJson(addAuthAndCaching(new Builder().url(path("orgs", org, "teams", team_slug))))
  }

  def getMembership(org: String, username: String)(implicit ec: EC): FR[Membership] = {
    // GET /orgs/:org/memberships/:username
    executeAndReadJson(addAuthAndCaching(new Builder().url(path("orgs", org, "memberships", username))))
  }

  def getTeamMembership(teamId: Long, username: String)(implicit ec: EC): FR[Membership] = {
    val url = path("teams", teamId.toString, "memberships", username)
    executeAndReadJson(addAuthAndCaching(new Builder().url(url)))
  }


  def addAuthAndCaching(builder: Builder): Request =
    addAuth(builder).cacheControl(AlwaysHitNetwork).build()

  def addAuth(builder: Builder) = builder
    .addHeader("Authorization", s"token ${ghCredentials().accessToken.value}")

  def getUser()(implicit ec: EC): Future[GitHubResponse[User]] =
    executeAndReadJson(addAuthAndCaching(new Builder().url(path("user"))))

  def getAuthenticatedApp()(implicit ec: EC): Future[GitHubResponse[GitHubApp]] =
    executeAndReadJson(addAuthAndCaching(new Builder().url(path("app"))))

  /**
   * https://docs.github.com/en/rest/users/users?apiVersion=2022-11-28#get-a-user
   */
  def getUser(username: String)(implicit ec: EC): Future[GitHubResponse[User]] = {
    executeAndReadJson(addAuthAndCaching(new Builder().url(path("users", username))))
  }

  def listTeamMembers(org: String, teamSlug: String)(implicit ec: EC): Source[Seq[User],NotUsed] = {
    followAndEnumerate[User](path("orgs", org, "teams", teamSlug, "members"))
  }

  /**
    * https://developer.github.com/v3/orgs/teams/#add-or-update-team-repository
    * PUT /teams/:id/repos/:org/:repo
    */
  def addTeamRepo(teamId: Long, org: String, repoName: String)(implicit ec: EC) = {
    val url = path("teams", teamId.toString, "repos", org, repoName)

    executeAndCheck(addAuthAndCaching(new Builder().url(url).put(Json.obj("permission" -> "admin"))))
  }

  /**
   * https://docs.github.com/en/rest/teams/members?apiVersion=2022-11-28#add-or-update-team-membership-for-a-user
   */
  def addOrUpdateTeamMembershipForAUser(org: String, team_slug: String, username: String, role: String)(implicit ec: EC) = {
    // PUT /orgs/{org}/teams/{team_slug}/memberships/{username}
    val url = path("orgs", org, "teams", team_slug, "memberships", username)

    executeAndCheck(addAuthAndCaching(new Builder().url(url).put(Json.obj("role" -> role))))
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

  def executeAndReadJson[T](request: Request)(implicit ev: Reads[T], ec: EC): FR[T] = executeAndWrap(request) {
    response => readAndResolve[T](request, response)
  }

  def executeAndReadOptionalJson[T](request: Request)(implicit ev: Reads[T], ec: EC): FR[Option[T]] = executeAndWrap(request) {
    response => Option.when(response.code() != 404)(readAndResolve[T](request, response))
  }

  private def readAndResolve[T](request: Request, response: Response)(implicit ev: Reads[T]): T = {
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

  def executeAndWrap[T](request: Request)(processor: Response => T)(implicit ec: EC): FR[T] = execute(request) {
    response => GitHubResponse(logAndGetMeta(request, response), processor(response))
  }

  def execute[T](request: Request)(processor: Response => T)(implicit ec: EC): Future[T] =
    okHttpClient.execute(request)(processor)

  def apiUrlBuilder: HttpUrl.Builder = new HttpUrl.Builder().scheme("https").host("api.github.com")

  def path(segments: String*): HttpUrl = segments.foldLeft(apiUrlBuilder) { case (builder, segment) =>
    builder.addPathSegment(segment)
  }.build()
}
