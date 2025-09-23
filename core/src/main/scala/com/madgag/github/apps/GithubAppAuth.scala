package com.madgag.github.apps

import cats.*
import cats.data.*
import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.madgag.github.{AccessToken, Expirable}
import com.madgag.scalagithub.GitHub.*
import com.madgag.scalagithub.model.{Account, GitHubApp, Installation}
import com.madgag.scalagithub.{AccountAccess, GitHubCredentials}
import play.api.Logging
import play.api.libs.json.{Json, Reads}
import sttp.client4.httpclient.cats.HttpClientCatsBackend
import sttp.client4.*
import sttp.model.{Header, *}

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

  val httpClient: Resource[IO, WebSocketBackend[IO]] = HttpClientCatsBackend.resource[IO]()
  
  private def headers(): Seq[Header] = Seq(
    Header("Accept", "application/vnd.github+json"),
    Header("Authorization", s"Bearer ${jwts.currentJWT()}"),
    Header("X-GitHub-Api-Version", "2022-11-28")
  )

  private def request[T: Reads](req: sttp.client4.Request[String], successStatusCode: Int): IO[T] = for {
    response <- httpClient.use(req.withHeaders(headers()).send)
  } yield {
    println(s"Requested: ${req.uri.pathToString}")
    if (response.code.code == successStatusCode) readAndResolve(req, response) else {
      logger.error(s"Failed calling ${req.uri.pathToString}: ${response.code} - ${response.body}")
      throw new RuntimeException(s"Failed to get installation access token: ${response.code}")
    }
  }

  def executeGet[T: Reads](pathSegments: String*): IO[T] =
    request(quickRequest.get(apiUri.withPath(pathSegments)), 200)

  /**
   * https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/authenticating-as-a-github-app-installation#generating-an-installation-access-token
   * https://docs.github.com/en/rest/apps/apps?apiVersion=2022-11-28#create-an-installation-access-token-for-an-app
   */
  def getInstallationAccessToken(installationId: Long): IO[InstallationTokenResponse] =
    request[InstallationTokenResponse](quickRequest.post(apiUri.withPath("app", "installations", installationId.toString, "access_tokens")), 201)

  /**
   * [[https://docs.github.com/en/rest/apps/apps?apiVersion=2022-11-28#get-the-authenticated-app]]
   */
  def getAuthenticatedApp(): IO[GitHubApp] = executeGet[GitHubApp]("app")

  /**
   * [[https://docs.github.com/en/rest/apps/apps?apiVersion=2022-11-28#get-an-installation-for-the-authenticated-app]]
   *
   * GET /app/installations/{installation_id}
   */
  def getInstallation(installationId: Long): IO[Installation] =
    executeGet[Installation]("app", "installations", installationId.toString)

  /**
   * [[https://docs.github.com/en/rest/apps/apps?apiVersion=2022-11-28#list-installations-for-the-authenticated-app]]
   */
  def getInstallations(): IO[Seq[Installation]] = executeGet[Seq[Installation]]("app", "installations")

  def getSoleInstallation(installationId: Option[Long] = None): IO[Installation] =
    installationId.fold(for {
      installations <- getInstallations()
    } yield {
      require(installations.size == 1, s"Found ${installations.size} installations of this GitHub App, should be precisely ONE.")
      installations.head
    })(getInstallation)

  def accessSoleInstallation(installationId: Option[Long] = None)(using IORuntime): IO[InstallationAccess] = for {
    installation <- getSoleInstallation(installationId)
  } yield InstallationAccess.from(this, installation)
}

case class InstallationAccess(
  installation: Installation,
  credentials: GitHubCredentials.Provider
) {
  val installedOnAccount: Account = installation.account
  
  def accountAccess()(using ExecutionContext, IORuntime): AccountAccess =
    AccountAccess(installation.account, credentials)
}

object InstallationAccess {
  def from(gitHubAppAuth: GitHubAppAuth, installation: Installation)(using IORuntime): InstallationAccess = InstallationAccess(
    installation,
    AccessToken.cache(gitHubAppAuth.getInstallationAccessToken(installation.id).map(resp => Expirable(resp.token, resp.expires_at)))
  )
}

case class InstallationTokenResponse(
  token: AccessToken,
  expires_at: Instant
)

object InstallationTokenResponse {
  implicit val reads: Reads[InstallationTokenResponse] = Json.reads
}
