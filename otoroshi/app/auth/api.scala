package auth

import env.Env
import models._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{AnyContent, Request, RequestHeader, Result}
import storage.BasicStore

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

trait AuthModule {

  def paLoginPage(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(
      implicit ec: ExecutionContext,
      env: Env
  ): Future[Result]
  def paLogout(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(
      implicit ec: ExecutionContext,
      env: Env
  ): Future[Option[String]]
  def paCallback(request: Request[AnyContent], config: GlobalConfig, descriptor: ServiceDescriptor)(
      implicit ec: ExecutionContext,
      env: Env
  ): Future[Either[String, PrivateAppsUser]]

  def boLoginPage(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext, env: Env): Future[Result]
  def boLogout(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext,
                                                             env: Env): Future[Option[String]]
  def boCallback(request: Request[AnyContent], config: GlobalConfig)(implicit ec: ExecutionContext,
                                                                     env: Env): Future[Either[String, BackOfficeUser]]
}

trait AuthModuleConfig extends AsJson {
  def `type`: String
  def id: String
  def name: String
  def desc: String
  def authModule(config: GlobalConfig): AuthModule
  def cookieSuffix(desc: ServiceDescriptor): String
  def sessionMaxAge: Int
  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean]
}

object AuthModuleConfig {

  lazy val logger = Logger("otoroshi-auth-module-config")

  def fromJsons(value: JsValue) =
    try {
      _fmt.reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }

  val _fmt: Format[AuthModuleConfig] = new Format[AuthModuleConfig] {
    override def reads(json: JsValue): JsResult[AuthModuleConfig] = (json \ "type").as[String] match {
      case "oauth2"        => GenericOauth2ModuleConfig._fmt.reads(json)
      case "oauth2-global" => GenericOauth2ModuleConfig._fmt.reads(json)
      case "basic"         => BasicAuthModuleConfig._fmt.reads(json)
      case "ldap"          => LdapAuthModuleConfig._fmt.reads(json)
      case _               => JsError("Unknown auth. config type")
    }
    override def writes(o: AuthModuleConfig): JsValue = o.asJson
  }
}

trait OAuth2ModuleConfig extends AuthModuleConfig {
  def clientId: String
  def clientSecret: String
  def authorizeUrl: String
  def tokenUrl: String
  def userInfoUrl: String
  def loginUrl: String
  def logoutUrl: String
  def accessTokenField: String
  def scope: String
  def useJson: Boolean
  def useCookie: Boolean
  def readProfileFromToken: Boolean
  def jwtVerifier: Option[AlgoSettings]
  def nameField: String
  def emailField: String
  def otoroshiDataField: String
  def callbackUrl: String
}

trait AuthConfigsDataStore extends BasicStore[AuthModuleConfig] {
  def generateLoginToken()(implicit ec: ExecutionContext): Future[String]
  def validateLoginToken(token: String)(implicit ec: ExecutionContext): Future[Boolean]

  def setUserForToken(token: String, user: JsValue)(implicit ec: ExecutionContext): Future[Unit]
  def getUserForToken(token: String)(implicit ec: ExecutionContext): Future[Option[JsValue]]
}
