package otoroshi.auth

import otoroshi.env.Env
import otoroshi.models.{UserRights, _}
import otoroshi.next.models.NgRoute
import otoroshi.security.IdGenerator
import otoroshi.storage.BasicStore
import otoroshi.utils.{JsonPathValidator, RegexPool}
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.WSProxyServer
import play.api.mvc.{AnyContent, Request, RequestHeader, Result}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

trait ValidableUser { self =>
  def json: JsValue
  def validate(validators: Seq[JsonPathValidator]): Either[String, self.type] = {
    val jsonuser = json
    if (validators.forall(validator => validator.validate(jsonuser))) {
      Right(this)
    } else {
      Left("user is not valid")
    }
  }
}

trait AuthModule {
  def authConfig: AuthModuleConfig

  def paLoginPage(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor, isRoute: Boolean)(
      implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Result]
  def paLogout(
      request: RequestHeader,
      user: Option[PrivateAppsUser],
      config: GlobalConfig,
      descriptor: ServiceDescriptor
  )(implicit
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

// TODO: move max-age here when it won't be a problem
case class SessionCookieValues(httpOnly: Boolean = true, secure: Boolean = true) {
  def asJson: JsValue = SessionCookieValues.fmt.writes(this)
}

/*
case class UserValidator(path: String, value: JsValue) {
  def json: JsValue = UserValidator.format.writes(this)
  def validate(user: JsValue): Boolean = {
    // println(user.prettify)
    user.atPath(path).asOpt[JsValue] match {
      case None                                              => false
      case Some(JsString(v)) if value.isInstanceOf[JsString] => {
        val expected = value.asString
        if (expected.trim.startsWith("Regex(") && expected.trim.endsWith(")")) {
          val regex = expected.substring(6).init
          // println(regex, v, RegexPool.regex(regex).matches(v))
          RegexPool.regex(regex).matches(v)
        } else if (expected.trim.startsWith("Wildcard(") && expected.trim.endsWith(")")) {
          val regex = expected.substring(9).init
          RegexPool.apply(regex).matches(v)
        } else {
          v == expected
        }
      }
      case Some(v)                                           => v == value
    }
  }
}

object UserValidator {
  val format = new Format[UserValidator] {
    override def writes(o: UserValidator): JsValue             = Json.obj(
      "path"  -> o.path,
      "value" -> o.value
    )
    override def reads(json: JsValue): JsResult[UserValidator] = Try {
      UserValidator(
        path = json.select("path").as[String],
        value = json.select("value").asValue
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value)     => JsSuccess(value)
    }
  }
}
 */

trait AuthModuleConfig extends AsJson with otoroshi.models.EntityLocationSupport {
  def _fmt()(implicit env: Env): Format[AuthModuleConfig]
  def `type`: String
  def humanName: String
  def id: String
  def name: String
  def desc: String
  def authModule(config: GlobalConfig): AuthModule
  def cookieSuffix(desc: ServiceDescriptor): String
  def routeCookieSuffix(route: NgRoute): String = cookieSuffix(route.legacy)
  def sessionMaxAge: Int
  def metadata: Map[String, String]
  def sessionCookieValues: SessionCookieValues
  def clientSideSessionEnabled: Boolean
  def userValidators: Seq[JsonPathValidator]
  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def withLocation(location: EntityLocation): AuthModuleConfig
  override def internalId: String               = id
  override def json: JsValue                    = asJson

  def form: Option[Form]
  def loginHTMLPage: String = "otoroshi.view.oto.login.scala.html"
}

case class Form(schema: JsValue, flow: Seq[String] = Seq.empty) extends AsJson {
  def asJson: JsValue = Json.obj(
    "schema" -> schema,
    "flow"   -> flow
  )
}

object Form {
  val _fmt = new Format[Form] {
    override def writes(o: Form): JsValue = o.asJson

    override def reads(json: JsValue): JsResult[Form] = Try {

      Form(
        flow = (json \ "flow").asOpt[Seq[String]].getOrElse(Seq.empty),
        schema = (json \ "schema").asOpt[JsValue].getOrElse(Json.obj())
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value)     => JsSuccess(value)
    }
  }
}

case class AuthModuleConfigFormat(env: Env) extends Format[AuthModuleConfig] {

  lazy val unknownConfigTypeError = JsError("Unknown auth. config type")

  override def reads(json: JsValue): JsResult[AuthModuleConfig] = {
    (json \ "type").as[String] match {
      case "oauth2"        => GenericOauth2ModuleConfig._fmt.reads(json)
      case "oauth2-global" => GenericOauth2ModuleConfig._fmt.reads(json)
      case "basic"         => BasicAuthModuleConfig._fmt.reads(json)
      case "ldap"          => LdapAuthModuleConfig._fmt.reads(json)
      case "saml"          => SamlAuthModuleConfig._fmt.reads(json)
      case "oauth1"        => Oauth1ModuleConfig._fmt.reads(json)
      case "wasm"          => WasmAuthModuleConfig.format.reads(json)
      case ref             =>
        env.datastores.authConfigsDataStore
          .templates()(env)
          .find(config => config.`type` == ref) match {
          case Some(config) => config._fmt()(env).reads(json)
          case None         => unknownConfigTypeError
        }
    }
  }

  override def writes(o: AuthModuleConfig): JsValue = o.asJson
}

object AuthModuleConfig {

  lazy val logger = Logger("otoroshi-auth-module-config")

  def fromJsons(value: JsValue)(implicit env: Env) =
    try {
      _fmt(env).reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }

  def _fmt(env: Env): Format[AuthModuleConfig] = AuthModuleConfigFormat(env)
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
  def pkce: Option[PKCEConfig]
  def noWildcardRedirectURI: Boolean
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
  def otoroshiRightsField: String
}

trait AuthConfigsDataStore extends BasicStore[AuthModuleConfig] {
  def findById(id: String)(implicit ec: ExecutionContext, env: Env): Future[Option[AuthModuleConfig]]
  def generateLoginToken(maybeTokenValue: Option[String] = None)(implicit ec: ExecutionContext): Future[String]
  def validateLoginToken(token: String)(implicit ec: ExecutionContext): Future[Boolean]

  def setUserForToken(token: String, user: JsValue)(implicit ec: ExecutionContext): Future[Unit]
  def getUserForToken(token: String)(implicit ec: ExecutionContext): Future[Option[JsValue]]

  def templates()(implicit env: Env): Seq[AuthModuleConfig] = env.scriptManager.authModules

  def template(modType: Option[String], env: Env)(implicit ec: ExecutionContext): AuthModuleConfig = {

    val defaultValue = BasicAuthModuleConfig(
      id = IdGenerator.namedId("auth_mod", env),
      name = "New auth. module",
      desc = "New auth. module",
      tags = Seq.empty,
      metadata = Map.empty,
      sessionCookieValues = SessionCookieValues(),
      clientSideSessionEnabled = true
    )

    val defaultModule = modType match {
      case Some(ref) =>
        templates()(env)
          .find(config => config.`type` == ref)
          .getOrElse(defaultValue)
      case _         => defaultValue
    }

    env.datastores.globalConfigDataStore
      .latest()(env.otoroshiExecutionContext, env)
      .templates
      .authConfig
      .map { template =>
        AuthModuleConfig._fmt(env).reads(defaultModule.json.asObject.deepMerge(template)) match {
          case JsSuccess(value, _) => value
          case JsError(errors)     => defaultModule
        }
      }
      .getOrElse {
        defaultModule
      }
  }
}
