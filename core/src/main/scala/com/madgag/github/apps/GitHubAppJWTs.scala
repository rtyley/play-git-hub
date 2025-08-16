package com.madgag.github.apps

import com.github.blemale.scaffeine.{LoadingCache, Scaffeine}
import com.madgag.github.apps.GitHubAppJWTs.{CacheLifeTime, MaxLifeTime}
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm.RS256
import org.bouncycastle.openssl.{PEMKeyPair, PEMParser}
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import play.api.Logging

import java.io.StringReader
import java.security.PrivateKey
import java.time.Clock.systemUTC
import java.time.Duration.{ofMinutes, ofSeconds}
import java.time.{Clock, Duration}
import java.util.Date
import scala.jdk.DurationConverters.*
import scala.util.{Try, Using}

/**
 * [[https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-json-web-token-jwt-for-a-github-app]]
 */
class GitHubAppJWTs(
  appClientId: String,
  privateKey: PrivateKey
) extends Logging {
  private val cache: LoadingCache[Unit, String] = Scaffeine()
    .refreshAfterWrite(CacheLifeTime.toScala) // https://github.com/ben-manes/caffeine/wiki/Refresh
    .expireAfterWrite(MaxLifeTime.toScala)
    .maximumSize(1)
    .build(_ => generate())

  private def generate()(implicit clock: Clock = systemUTC()): String = {
    val now = clock.instant()
    val expiration = now.plus(MaxLifeTime)
    println(s"Generating JWT: expiration=$expiration")
    Jwts.builder()
      .setIssuer(appClientId)
      .setIssuedAt(Date.from(now.minusSeconds(60))) // "To protect against clock drift, we recommend that you set this 60 seconds in the past"
      .setExpiration(Date.from(expiration))
      .signWith(privateKey, RS256)
      .compact()
  }

  def currentJWT(): String = cache.get(())
}

object GitHubAppJWTs {
  val MaxLifeTime: Duration = ofMinutes(10) // https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-json-web-token-jwt-for-a-github-app

  val CacheLifeTime: Duration = MaxLifeTime.multipliedBy(9).dividedBy(10) // Avoid race near expiry

  def parsePrivateKeyFrom(privateKeyPem: String): Try[PrivateKey] = {
    val converter = new JcaPEMKeyConverter()
    Using(new PEMParser(new StringReader(privateKeyPem))) { pemParser =>
      val keyObject = pemParser.readObject()
      converter.getPrivateKey(keyObject.asInstanceOf[PEMKeyPair].getPrivateKeyInfo)
    }
  }

  /**
   * For a prefix 'FOO', the config will be loaded from two keys:
   * - FOO_GITHUB_APP_CLIENT_ID - https://github.blog/changelog/2024-05-01-github-apps-can-now-use-the-client-id-to-fetch-installation-tokens/
   * - FOO_GITHUB_APP_PRIVATE_KEY
   */
  def fromConfigMap(configMap: Map[String, String], prefix: String): GitHubAppJWTs = {
    def getValue(suffix: String): String = {
      val keyName = s"${prefix}_GITHUB_APP_$suffix"
      require(configMap.contains(keyName), s"Missing config '$keyName'")
      configMap(keyName)
    }

    new GitHubAppJWTs(getValue("CLIENT_ID"), GitHubAppJWTs.parsePrivateKeyFrom(getValue("PRIVATE_KEY")).get)
  }
}