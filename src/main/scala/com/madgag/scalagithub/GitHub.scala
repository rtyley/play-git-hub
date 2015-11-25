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
import com.madgag.scalagithub.commands.{CreateComment, CreateLabel, CreateRepo}
import com.madgag.scalagithub.model._
import com.squareup.okhttp.Request.Builder
import com.squareup.okhttp._
import play.api.Logger
import play.api.http.Status._
import play.api.libs.json.Json.toJson
import play.api.libs.json._

import scala.concurrent.{ExecutionContext => EC, Future}

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

case class GitHubResponse[Result](
  rateLimit: RateLimit,
  requestScopes: RequestScopes,
  result: Result
)

object GitHubResponse {
  implicit def toResult[Result](resp: GitHubResponse[Result]): Result = resp.result
}

object GitHub {
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
    * https://developer.github.com/v3/git/refs/#get-a-reference
    */
  def getRef(repo: Repo, ref: String)(implicit ec: EC): FR[Ref] = {
    // GET /repos/:owner/:repo/git/refs/:ref
    // GET /repos/:owner/:repo/git/refs/heads/skunkworkz/featureA

    executeAndReadJson(addAuthAndCaching(new Builder().url(repo.refsListUrl + "/" + ref)))
  }

  /**
    * https://developer.github.com/v3/git/trees/#get-a-tree-recursively
    *
    */
  def getTreeRecursively(repo: Repo, sha: String)(implicit ec: EC): FR[Tree] = {
    // GET /repos/:owner/:repo/git/trees/:sha?recursive=1
    // GET /repos/guardian/membership-frontend/git/trees/heads/master?recursive=1 - undocumented, but works

    executeAndReadJson(addAuthAndCaching(new Builder().url(repo.treeUrlFor(sha)+"?recursive=1")))
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



  /*
  https://developer.github.com/v3/issues/labels/#list-all-labels-for-this-repository
   */
  def listLabels(hasLabelsList: HasLabelsList)(implicit ec: EC): FR[Seq[Label]] = {
    // GET /repos/:owner/:repo/labels
    // TODO Pagination: https://developer.github.com/guides/traversing-with-pagination/
    executeAndReadJson(addAuthAndCaching(new Builder().url(hasLabelsList.labelsListUrl)))
  }

  /*
   * https://developer.github.com/v3/issues/labels/#create-a-label
   */
  def createLabel(repo: Repo, label: CreateLabel)(implicit ec: EC): FR[Label] = {
    // POST /repos/:owner/:repo/labels
    executeAndReadJson(addAuthAndCaching(new Builder().url(repo.labelsListUrl).post(toJson(label))))
  }

  /**
    * https://developer.github.com/v3/issues/labels/#replace-all-labels-for-an-issue
    */
  def replaceLabels(pr: PullRequest, labels: Seq[String])(implicit ec: EC): FR[Seq[Label]] = {
    // PUT /repos/:owner/:repo/issues/:number/labels
    val respF = executeAndReadJson[Seq[Label]](addAuthAndCaching(new Builder().url(pr.labelsListUrl).put(toJson(labels))))
    respF.foreach(resp => println(s"Sent labels = ${labels.mkString(",")} Got = ${resp.map(_.name).mkString(",")}"))
    respF
  }


  def listPullRequests(repoId: RepoId)(implicit ec: EC): FR[Seq[PullRequest]] = {
    // https://api.github.com/repos/guardian/subscriptions-frontend/pulls?state=closed&sort=updated&direction=desc
    val url = apiUrlBuilder
      .addPathSegment(s"repos")
      .addPathSegment(repoId.owner)
      .addPathSegment(repoId.name)
      .addPathSegment(s"pulls")
      .addQueryParameter("state", "closed")
      .addQueryParameter("sort", "updated")
      .addQueryParameter("direction", "desc")
      .build()

    // TODO Pagination: https://developer.github.com/guides/traversing-with-pagination/
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

  def getTeam(teamId: Long)(implicit ec: EC): FR[Team] = {
    // GET /orgs/:org/memberships/:username
    val url = apiUrlBuilder
      .addPathSegment("teams")
      .addPathSegment(teamId.toString)
      .build()

    executeAndReadJson[Team](addAuthAndCaching(new Builder().url(url)))
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
    executeAndReadJson[Comment](addAuthAndCaching(new Builder().url(commentable.comments_url).post(toJson(CreateComment(comment)))))
  }

  def createOrgRepo(org: String, repo: CreateRepo)(implicit ec: EC): FR[Repo] = {
    val url = apiUrlBuilder
      .addPathSegment("orgs")
      .addPathSegment(org)
      .addPathSegment("repos")
      .build()

    executeAndReadJson(addAuthAndCaching(new Builder().url(url).post(toJson(repo))))
  }

  def executeAndReadJson[T](request: Request)(implicit ev: Reads[T], ec: EC): FR[T] = {
    for {
      response <- ghCredentials.okHttpClient.execute(request)
    } yield {
      val rateLimit = rateLimitFrom(response)
      val requestScopes = requestScopesFrom(response)

      println(s"$rateLimit $requestScopes ${request.method} ${request.httpUrl}")

      val json = Json.parse(response.body().byteStream())

      json.validate[T] match {
        case error: JsError =>
          val message = s"Error decoding ${request.urlString()} : $error"
          Logger.warn(s"$message\n\n$json\n\n" )
          throw new RuntimeException(message)
        case JsSuccess(result, _) =>
          GitHubResponse(rateLimit, requestScopes, result)
      }
    }
  }

  def rateLimitFrom[T](response: Response): RateLimit = {
    val networkResponse = Option(response.networkResponse())
    RateLimit(
      consumed = if (networkResponse.exists(_.code != NOT_MODIFIED)) 1 else 0,
      networkResponse.map(rateLimitStatusFrom)
    )
  }

  def apiUrlBuilder: HttpUrl.Builder = new HttpUrl.Builder().scheme("https").host("api.github.com")

  def rateLimitStatusFrom(response: Response) = RateLimit.Status(
    response.header("X-RateLimit-Remaining").toInt,
    Instant.ofEpochSecond(response.header("X-RateLimit-Reset").toLong)
  )

  def requestScopesFrom(response: Response) = RequestScopes(
    response.header("X-OAuth-Scopes").split(',').map(_.trim).toSet,
    response.header("X-Accepted-OAuth-Scopes").split(',').map(_.trim).toSet
  )
}
