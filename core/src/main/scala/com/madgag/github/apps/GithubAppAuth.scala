package com.madgag.github.apps

import com.madgag.github.AccessToken
import com.madgag.okhttpscala._
import com.madgag.scalagithub.GitHub.{ReqMod, _}
import com.madgag.scalagithub.model.GitHubApp
import okhttp3.OkHttpClient
import okhttp3.Request.Builder
import play.api.Logging
import play.api.libs.json.{Json, Reads}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

/*
 https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-json-web-token-jwt-for-a-github-app
 */
class GithubAppAuth(jwts: GitHubAppJWTs) extends Logging {

  val okHttpClient = new OkHttpClient.Builder()
    // .cache(new okhttp3.Cache(Files.createTempDirectory("github-api-cache").toFile, 5 * 1024 * 1024))
    .build()

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
  def getInstallationAccessToken(installationId: String)(implicit ec: ExecutionContext): Future[InstallationTokenResponse] =
    request[InstallationTokenResponse](_.url(path("app", "installations", installationId, "access_tokens")).post(EmptyRequestBody), 201)

  /**
   * https://docs.github.com/en/rest/apps/apps?apiVersion=2022-11-28#get-the-authenticated-app
   */
  def getAuthenticatedApp()(implicit ec: ExecutionContext): Future[GitHubApp] =
    request[GitHubApp](_.url(path("app")).get(), 200)

}

case class InstallationTokenResponse(
  token: AccessToken,
  expires_at: Instant
)

object InstallationTokenResponse {
  implicit val reads: Reads[InstallationTokenResponse] = Json.reads
}
