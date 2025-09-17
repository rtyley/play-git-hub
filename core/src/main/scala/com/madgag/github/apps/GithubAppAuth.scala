package com.madgag.github.apps

import com.madgag.github.AccessToken
import com.madgag.okhttpscala._
import com.madgag.scalagithub.GitHub.{ReqMod, _}
import com.madgag.scalagithub.GitHubCredentials
import com.madgag.scalagithub.model.{Account, GitHubApp, Installation}
import okhttp3.Request.Builder
import okhttp3.{HttpUrl, OkHttpClient}
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

  def executeGet[T: Reads](url: HttpUrl)(implicit ec: ExecutionContext): Future[T] = request(_.url(url).get(), 200)

  /**
   * https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/authenticating-as-a-github-app-installation#generating-an-installation-access-token
   * https://docs.github.com/en/rest/apps/apps?apiVersion=2022-11-28#create-an-installation-access-token-for-an-app
   */
  def getInstallationAccessToken(installationId: Long)(implicit ec: ExecutionContext): Future[InstallationTokenResponse] =
    request[InstallationTokenResponse](_.url(path("app", "installations", installationId.toString, "access_tokens")).post(EmptyRequestBody), 201)

  /**
   * [[https://docs.github.com/en/rest/apps/apps?apiVersion=2022-11-28#get-the-authenticated-app]]
   */
  def getAuthenticatedApp()(implicit ec: ExecutionContext): Future[GitHubApp] = executeGet[GitHubApp](path("app"))

  /**
   * [[https://docs.github.com/en/rest/apps/apps?apiVersion=2022-11-28#get-an-installation-for-the-authenticated-app]]
   *
   * GET /app/installations/{installation_id}
   */
  def getInstallation(installationId: Long)(implicit ec: ExecutionContext): Future[Installation] =
    executeGet[Installation](path("app", "installations", installationId.toString))

  /**
   * [[https://docs.github.com/en/rest/apps/apps?apiVersion=2022-11-28#list-installations-for-the-authenticated-app]]
   */
  def getInstallations()(implicit ec: ExecutionContext): Future[Seq[Installation]] =
    executeGet[Seq[Installation]](path("app", "installations"))

  def getSoleInstallation(installationId: Option[Long] = None)(implicit ec: ExecutionContext): Future[Installation] =
    installationId.fold(for {
      installations <- getInstallations()
    } yield {
      require(installations.size == 1, s"Found ${installations.size} installations of this GitHub App, should be precisely ONE.")
      installations.head
    })(getInstallation)

  def accessSoleInstallation(installationId: Option[Long] = None)(implicit ec: ExecutionContext): Future[InstallationAccess] = for {
    installation <- getSoleInstallation(installationId)
  } yield InstallationAccess(
    installation,
    new AccessToken.Cache(new InstallationAccessTokenProvider(this, installation.id))
  )
}

case class InstallationAccess(
  installation: Installation,
  credentials: GitHubCredentials.Provider
) {
  val installedOnAccount: Account = installation.account
}

case class InstallationTokenResponse(
  token: AccessToken,
  expires_at: Instant
)

object InstallationTokenResponse {
  implicit val reads: Reads[InstallationTokenResponse] = Json.reads
}
