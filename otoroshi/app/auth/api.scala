package otoroshi.auth

import auth.saml.{SAMLModule, SamlAuthModuleConfig}
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.models.{UserRight, UserRights}
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.WSProxyServer
import play.api.mvc.{AnyContent, Request, RequestHeader, Result}
import otoroshi.security.IdGenerator
import otoroshi.storage.BasicStore
import otoroshi.utils.http.MtlsConfig

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait AuthModule {

  def paLoginPage(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Result]
  def paLogout(request: RequestHeader, user: Option[PrivateAppsUser], config: GlobalConfig, descriptor: ServiceDescriptor)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[Result, Option[String]]]
  def paCallback(request: Request[AnyContent], config: GlobalConfig, descriptor: ServiceDescriptor)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[String, PrivateAppsUser]]

  def boLoginPage(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext, env: Env): Future[Result]
  def boLogout(request: RequestHeader, user: BackOfficeUser, config: GlobalConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[Result, Option[String]]]
  def boCallback(request: Request[AnyContent], config: GlobalConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[String, BackOfficeUser]]
}

object SessionCookieValues {
  def fmt =
    new Format[SessionCookieValues] {
      override def writes(o: SessionCookieValues) =
        Json.obj(
          "httpOnly" -> o.httpOnly,
          "secure"   -> o.secure
        )

      override def reads(json: JsValue) =
        Try {
          JsSuccess(
            SessionCookieValues(
              httpOnly = (json \ "httpOnly").asOpt[Boolean].getOrElse(true),
              secure = (json \ "secure").asOpt[Boolean].getOrElse(true)
            )
          )
        } recover { case e =>
          JsError(e.getMessage)
        } get
    }
}

//todo: move max-age here when it won't be a problem
case class SessionCookieValues(httpOnly: Boolean = true, secure: Boolean = true) {
  def asJson: JsValue = SessionCookieValues.fmt.writes(this)
}

trait AuthModuleConfig extends AsJson with otoroshi.models.EntityLocationSupport {
  def `type`: String
  def id: String
  def name: String
  def desc: String
  def authModule(config: GlobalConfig): AuthModule
  def cookieSuffix(desc: ServiceDescriptor): String
  def sessionMaxAge: Int
  def metadata: Map[String, String]
  def sessionCookieValues: SessionCookieValues
  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  override def internalId: String = id
  override def json: JsValue      = asJson
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
    override def reads(json: JsValue): JsResult[AuthModuleConfig] =
      (json \ "type").as[String] match {
        case "oauth2"        => GenericOauth2ModuleConfig._fmt.reads(json)
        case "oauth2-global" => GenericOauth2ModuleConfig._fmt.reads(json)
        case "basic"         => BasicAuthModuleConfig._fmt.reads(json)
        case "ldap"          => LdapAuthModuleConfig._fmt.reads(json)
        case "saml"          => SamlAuthModuleConfig._fmt.reads(json)
        case _               => JsError("Unknown auth. config type")
      }
    override def writes(o: AuthModuleConfig): JsValue             = o.asJson
  }
}

trait OAuth2ModuleConfig extends AuthModuleConfig {
  def clientId: String
  def clientSecret: String
  def authorizeUrl: String
  def tokenUrl: String
  def userInfoUrl: String
  def introspectionUrl: String
  def loginUrl: String
  def logoutUrl: String
  def accessTokenField: String
  def scope: String
  def claims: String
  def useJson: Boolean
  def useCookie: Boolean
  def readProfileFromToken: Boolean
  def refreshTokens: Boolean
  def jwtVerifier: Option[AlgoSettings]
  def nameField: String
  def emailField: String
  def otoroshiDataField: String
  def apiKeyMetaField: String
  def apiKeyTagsField: String
  def callbackUrl: String
  def oidConfig: Option[String]
  def proxy: Option[WSProxyServer]
  def extraMetadata: JsObject
  def mtlsConfig: MtlsConfig
  def superAdmins: Boolean
  def rightsOverride: Map[String, UserRights]
  def dataOverride: Map[String, JsObject]
}

trait AuthConfigsDataStore extends BasicStore[AuthModuleConfig] {
  def generateLoginToken(maybeTokenValue: Option[String] = None)(implicit ec: ExecutionContext): Future[String]
  def validateLoginToken(token: String)(implicit ec: ExecutionContext): Future[Boolean]

  def setUserForToken(token: String, user: JsValue)(implicit ec: ExecutionContext): Future[Unit]
  def getUserForToken(token: String)(implicit ec: ExecutionContext): Future[Option[JsValue]]

  def template(modType: Option[String]): AuthModuleConfig = {
    modType match {
      case Some("oauth2")        =>
        GenericOauth2ModuleConfig(
          id = IdGenerator.token,
          name = "New auth. module",
          desc = "New auth. module",
          tags = Seq.empty,
          metadata = Map.empty,
          sessionCookieValues = SessionCookieValues()
        )
      case Some("oauth2-global") =>
        GenericOauth2ModuleConfig(
          id = IdGenerator.token,
          name = "New auth. module",
          desc = "New auth. module",
          tags = Seq.empty,
          metadata = Map.empty,
          sessionCookieValues = SessionCookieValues()
        )
      case Some("basic")         =>
        BasicAuthModuleConfig(
          id = IdGenerator.token,
          name = "New auth. module",
          desc = "New auth. module",
          tags = Seq.empty,
          metadata = Map.empty,
          sessionCookieValues = SessionCookieValues()
        )
      case Some("ldap")          =>
        LdapAuthModuleConfig(
          id = IdGenerator.token,
          name = "New auth. module",
          desc = "New auth. module",
          serverUrls = Seq("ldap://ldap.forumsys.com:389"),
          searchBase = "dc=example,dc=com",
          searchFilter = "(uid=${username})",
          adminUsername = Some("cn=read-only-admin,dc=example,dc=com"),
          adminPassword = Some("password"),
          tags = Seq.empty,
          metadata = Map.empty,
          sessionCookieValues = SessionCookieValues()
        )
      case Some("saml")          =>
        SamlAuthModuleConfig(
          id    = IdGenerator.token,
          name  = "New auth. module",
          desc  = "New auth. module",
          tags  = Seq.empty,
          metadata  = Map.empty,
          singleSignOnUrl = "",
          singleLogoutUrl = "",
          issuer = "",
          sessionCookieValues = SessionCookieValues()
        )
      case _                     =>
        BasicAuthModuleConfig(
          id = IdGenerator.token,
          name = "New auth. module",
          desc = "New auth. module",
          tags = Seq.empty,
          metadata = Map.empty,
          sessionCookieValues = SessionCookieValues()
        )
    }
  }
}
