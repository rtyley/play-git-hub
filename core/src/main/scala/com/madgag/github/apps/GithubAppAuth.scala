package com.madgag.github.apps

import com.madgag.github.AccessToken
import com.madgag.okhttpscala._
import com.madgag.scalagithub.GitHub
import com.madgag.scalagithub.GitHub.{ReqMod, _}
import com.madgag.scalagithub.model.{Account, GitHubApp}
import okhttp3.OkHttpClient
import okhttp3.Request.Builder
import play.api.Logging
import play.api.libs.json.{Json, Reads}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

object GitHubAppAuth {
  /**
   * For a prefix 'FOO', the config will be loaded from two keys:
   * - FOO_GITHUB_APP_CLIENT_ID - https://github.blog/changelog/2024-05-01-github-apps-can-now-use-the-client-id-to-fetch-installation-tokens/
   * - FOO_GITHUB_APP_PRIVATE_KEY
   */
  def fromConfigMap(configMap: Map[String, String], prefix: String): GitHubAppAuth = {
    def getValue(suffix: String): String = {
      val keyName = s"${prefix}_GITHUB_APP_$suffix"
      require(configMap.contains(keyName), s"Missing config '$keyName'")
      configMap(keyName)
    }

    new GitHubAppAuth(new GitHubAppJWTs(
      getValue("CLIENT_ID"),
      GitHubAppJWTs.parsePrivateKeyFrom(getValue("PRIVATE_KEY")).get
    ))
  }
}

/*
 https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-json-web-token-jwt-for-a-github-app
 */
class GitHubAppAuth(jwts: GitHubAppJWTs) extends Logging {

  val okHttpClient = new OkHttpClient.Builder().build()

  private val addHeaders: ReqMod =
    _.addHeader("Accept", "application/vnd.github+json")
      .addHeader("Authorization", s"Bearer ${jwts.currentJWT()}")
      .addHeader("X-GitHub-Api-Version", "2022-11-28")

  def request[T: Reads](reqMod: ReqMod, successStatusCode: Int)(implicit ec: ExecutionContext): Future[T] = {
    val req = addHeaders(reqMod(new Builder())).build()
    okHttpClient.execute(req) { response =>
      if (response.code == successStatusCode) readAndResolve(req, response) else {
        logger.error(s"Failed calling ${req.url().encodedPath()}: ${response.code} - ${response.body}")
        throw new RuntimeException(s"Failed to get installation access token: ${response.code()}")
      }
    }
  }

  /**
   * https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/authenticating-as-a-github-app-installation#generating-an-installation-access-token
   * https://docs.github.com/en/rest/apps/apps?apiVersion=2022-11-28#create-an-installation-access-token-for-an-app
   */
  def getInstallationAccessToken(installationId: Long)(implicit ec: ExecutionContext): Future[InstallationTokenResponse] =
    request[InstallationTokenResponse](_.url(path("app", "installations", installationId.toString, "access_tokens")).post(EmptyRequestBody), 201)

  def accessInstallations()(implicit ec: ExecutionContext):Future[Map[Long, (Account, GitHub)]] = for {
    installations <- getInstallations()
  } yield (for {
    installation <- installations
  } yield installation.id -> (installation.account, accessInstallation(installation.id))).toMap

  def accessInstallation(installationId: Long)(implicit ec: ExecutionContext): GitHub =
    new GitHub(new AccessToken.Cache(new InstallationAccessTokenProvider(this, installationId)))

  def accessSoleInstallation(installationId: Option[Long] = None)(implicit ec: ExecutionContext): Future[GitHub] =
    installationId.fold(for {
      installations <- accessInstallations()
    } yield {
      require(installations.size == 1, s"Found ${installations.size} installations of this GitHub App, should be precisely ONE.")
      installations.head._2._2
    }
  )(id => Future.successful(accessInstallation(id)))


  /**
   * https://docs.github.com/en/rest/apps/apps?apiVersion=2022-11-28#get-the-authenticated-app
   */
  def getAuthenticatedApp()(implicit ec: ExecutionContext): Future[GitHubApp] =
    request[GitHubApp](_.url(path("app")).get(), 200)

  /**
   * https://docs.github.com/en/rest/apps/apps?apiVersion=2022-11-28#list-installations-for-the-authenticated-app
   */
  def getInstallations()(implicit ec: ExecutionContext): Future[Seq[Installation]] =
    request[Seq[Installation]](_.url(path("app", "installations")).get(), 200)

}

case class Installation(id: Long, account: Account)

object Installation {
  implicit val reads: Reads[Installation] = Json.reads
}

case class InstallationTokenResponse(
  token: AccessToken,
  expires_at: Instant
)

object InstallationTokenResponse {
  implicit val reads: Reads[InstallationTokenResponse] = Json.reads
}
