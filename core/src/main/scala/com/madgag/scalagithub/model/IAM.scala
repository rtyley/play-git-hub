package com.madgag.scalagithub.model

import com.madgag.scalagithub.GitHub
import com.madgag.scalagithub.GitHub.{FR, ListStream}
import com.madgag.scalagithub.commands.CreateRepo
import play.api.libs.json.{Json, Reads}
import sttp.model.*

import java.time.ZonedDateTime

/**
 * The GitHub API allows you to authenticate as two different kinds of Principal:
 *
 * - [[User]]: [[https://docs.github.com/en/rest/authentication/authenticating-to-the-rest-api?apiVersion=2022-11-28#authenticating-with-a-personal-access-token]]
 * - [[GitHubApp]]: [[https://docs.github.com/en/rest/authentication/authenticating-to-the-rest-api?apiVersion=2022-11-28#authenticating-with-a-token-generated-by-an-app]]
 */
sealed trait Principal {
  val slug: String
  val id: Long
  val html_url: String
  val name: Option[String]
  val created_at: Option[ZonedDateTime]
}

/**
 * GitHub has two main kinds of Account:
 *
 * - [[User]]
 * - [[Org]]
 *
 * [[https://docs.github.com/en/get-started/learning-about-github/types-of-github-accounts]]
 * 
 * The third kind of account is 'Enterprise', but currently we don't have Enterprise-specific support.
 */
sealed trait Account {
  type Self <: Account

  val login: String
  val id: Long
  val url: String
  val avatar_url: String
  val name: Option[String]
  val created_at: Option[ZonedDateTime]

  val atLogin = s"@$login"

  lazy val displayName: String = name.filter(_.nonEmpty).getOrElse(atLogin)

  def reFetch()(using g: GitHub, ev: Reads[Self]): FR[Self]  = g.gitHubHttp.getAndCache[Self](Uri.unsafeParse(url))

  def createRepo(cr: CreateRepo)(using g: GitHub): FR[Repo]

  def listRepos(queryParams: (String, String)*)(using g: GitHub): ListStream[Repo]
}

object Account {
  given Reads[Account] = Org.given_Reads_Org.widen[Account].orElse(User.given_Reads_User.widen[Account])
}

case class GitHubApp(
  slug: String,
  id: Long,
  html_url: String,
  name: Option[String] = None,
  created_at: Option[ZonedDateTime] = None
) extends Principal

object GitHubApp {
  given Reads[GitHubApp] = Json.reads
}

case class User(
  login: String,
  id: Long,
  avatar_url: String,
  url: String,
  html_url: String,
  name: Option[String] = None,
  created_at: Option[ZonedDateTime] = None
) extends Account with Principal {
  override type Self = User

  val slug: String = login

  override def createRepo(cr: CreateRepo)(using g: GitHub): FR[Repo] = g.createRepo(cr)

  override def listRepos(queryParams: (String, String)*)(using g: GitHub): ListStream[Repo] =
    g.listRepos(queryParams*)
}

object User {
  given Reads[User] = Json.reads
}

case class Org(
  login: String,
  id: Long,
  url: String,
  repos_url: String,
  avatar_url: String,
  description: Option[String],
  name: Option[String],
  created_at: Option[ZonedDateTime],
  html_url: String
) extends Account {
  lazy val membersAdminUrl = s"https://github.com/orgs/$login/members"

  private def userField(suffix: String) =
    new CanList[User, String] with CanCheck[String] with CanDelete[String] {
      override val link: Link[String] = Link.fromListUrl(s"$url/$suffix")
      override implicit val readsT: Reads[User] = User.given_Reads_User
    }

  // GET /orgs/:org/members
  // GET /orgs/:org/members/:username
  val members = userField("members")

  // GET /orgs/:org/public_members
  // GET /orgs/:org/public_members/:username
  val publicMembers = userField("public_members")

  override def createRepo(cr: CreateRepo)(using g: GitHub): FR[Repo] =
    g.createOrgRepo(login, cr)

  override def listRepos(queryParams: (String, String)*)(using g: GitHub): ListStream[Repo] =
    g.listOrgRepos(login, queryParams*)
}

object Org {
  given Reads[Org] = Json.reads[Org]
}
