package otoroshi.auth

import otoroshi.env.Env
import otoroshi.utils.syntax.implicits.BetterConfiguration
import play.api.{Configuration, Logger}
import play.api.http.{JWTConfiguration, SecretConfiguration, SessionConfiguration}
import play.api.libs.crypto.CookieSignerProvider
import play.api.mvc.Cookie.SameSite
import play.api.mvc.{Cookie, DefaultSessionCookieBaker, RequestHeader, Result, Session}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class PrivateAppsSessionManager(env: Env) {

  private val logger = Logger("otoroshi-papps-session-manager")

  private val config = env.configuration.get[Configuration]("otoroshi.privateapps.session")

  private val jwtconfig = env.configuration.get[Configuration]("otoroshi.privateapps.session.jwt")

  private val enabled = config.getOptionalWithFileSupport[Boolean]("enabled").getOrElse(false)

  private val sessionConfig = SessionConfiguration(
    cookieName = config.getOptionalWithFileSupport[String]("cookieName").getOrElse("SSO_SESSION"),
    secure = config.getOptionalWithFileSupport[Boolean]("secure").getOrElse(false),
    maxAge = config.getOptionalWithFileSupport[FiniteDuration]("maxAge"),
    httpOnly = config.getOptionalWithFileSupport[Boolean]("httpOnly").getOrElse(true),
    domain = config.getOptionalWithFileSupport[String]("domain"),
    path = config.getOptionalWithFileSupport[String]("path").getOrElse("/"),
    sameSite = config.getOptionalWithFileSupport[String]("sameSite").flatMap(v => SameSite.parse(v)),
    jwt = JWTConfiguration(
      signatureAlgorithm = jwtconfig.getOptionalWithFileSupport[String]("signatureAlgorithm").getOrElse("HS256"),
      expiresAfter = jwtconfig.getOptionalWithFileSupport[FiniteDuration]("expiresAfter"),
      clockSkew = jwtconfig.getOptionalWithFileSupport[FiniteDuration]("clockSkew").getOrElse(30.seconds),
      dataClaim = jwtconfig.getOptionalWithFileSupport[String]("dataClaim").getOrElse("data")
    )
  )

  private val secretConfig = SecretConfiguration(
    secret = env.secretSession
  )

  private val backer = new DefaultSessionCookieBaker(
    sessionConfig,
    secretConfig,
    new CookieSignerProvider(secretConfig).get
  )

  def printStatus(): Unit = {
    if (enabled) {
      logger.info(s"Private apps. session is enabled !")
      if (logger.isDebugEnabled) {
        logger.debug(s"session config: ${sessionConfig}")
      }
    }
  }

  def isEnabled: Boolean = enabled

  def sessionDomain: String = sessionConfig.domain.get

  def decodeFromCookies(request: RequestHeader): Session = {
    val s = backer.decodeFromCookie(request.cookies.get(sessionConfig.cookieName))
    if (logger.isDebugEnabled) logger.debug(s"decodeFromCookies: ${s.data}")
    s
  }

  def encodeAsCookie(session: Session): Cookie = {
    val r = backer.encodeAsCookie(session)
    if (logger.isDebugEnabled) logger.debug(s"encodeAsCookie: ${r}")
    r
  }
}

object implicits {

  implicit class RequestHeaderWithPrivateAppSession(val rh: RequestHeader) extends AnyVal {
    def privateAppSession(implicit env: Env): Session = {
      if (env.privateAppsSessionManager.isEnabled) {
        env.privateAppsSessionManager.decodeFromCookies(rh)
      } else {
        rh.session
      }
    }
  }

  implicit class ResultWithPrivateAppSession(val result: Result)           extends AnyVal {
    def privateAppSession(implicit request: RequestHeader, env: Env): Session = {
      if (env.privateAppsSessionManager.isEnabled) {
        env.privateAppsSessionManager.decodeFromCookies(request)
      } else {
        request.session
      }
    }

    def withPrivateAppSession(session: Session)(implicit env: Env): Result = try {
      if (env.privateAppsSessionManager.isEnabled) {
        result.withCookies(env.privateAppsSessionManager.encodeAsCookie(session))
      } else {
        result.withSession(session)
      }
    } catch {
      case t: Throwable =>
        t.printStackTrace()
        result
    }

    def addingToPrivateAppSession(values: (String, String)*)(implicit request: RequestHeader, env: Env): Result = try {
      if (env.privateAppsSessionManager.isEnabled) {
        withPrivateAppSession(new Session(privateAppSession.data ++ values.toMap))
      } else {
        result.addingToSession(values: _*)
      }
    } catch {
      case t: Throwable =>
        t.printStackTrace()
        result
    }

    def removingFromPrivateAppSession(keys: String*)(implicit request: RequestHeader, env: Env): Result = try {
      if (env.privateAppsSessionManager.isEnabled) {
        withPrivateAppSession(new Session(privateAppSession.data -- keys))
      } else {
        result.removingFromSession(keys: _*)
      }
    } catch {
      case t: Throwable =>
        t.printStackTrace()
        result
    }
  }
}
