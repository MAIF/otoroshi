package otoroshi.auth

import akka.stream.scaladsl.{Sink, Source}
import otoroshi.actions.ApiActionContext
import otoroshi.env.Env
import otoroshi.models.{UserRights, _}
import otoroshi.next.models.{NgRoute, NgTlsConfig}
import otoroshi.security.IdGenerator
import otoroshi.storage.BasicStore
import otoroshi.utils.{JsonPathValidator, JsonValidator, RegexPool}
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.WSProxyServer
import play.api.mvc.{AnyContent, Request, RequestHeader, Result}

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class RemoteUserValidatorSettings(
    url: String,
    headers: Map[String, String],
    timeout: FiniteDuration,
    tlsSettings: NgTlsConfig
) {

  def json: JsValue = RemoteUserValidatorSettings.format.writes(this)

  def validate(jsonuser: JsValue, desc: ServiceDescriptor, isRoute: Boolean, authModuleConfig: AuthModuleConfig)(
      implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[ErrorReason, JsValue]] = {
    env.MtlsWs
      .url(url, tlsSettings.legacy)
      .withRequestTimeout(timeout)
      .withHttpHeaders(headers.toSeq: _*)
      .post(
        Json
          .obj(
            "user"        -> jsonuser,
            "auth_module" -> authModuleConfig.json
          )
          .applyOnIf(isRoute) { payload =>
            payload ++ Json.obj("route" -> NgRoute.fromServiceDescriptor(desc, false).json)
          }
          .applyOnIf(!isRoute) { payload =>
            payload ++ Json.obj("service_descriptor" -> desc.json)
          }
      )
      .map { response =>
        if (response.status == 200) {
          val valid = response.json.select("valid").asOpt[Boolean].getOrElse(false)
          if (valid) {
            Right(response.json)
          } else {
            val reason = response.json
              .select("reason")
              .asOpt[JsObject]
              .flatMap(o => ErrorReason.format.reads(o).asOpt)
              .getOrElse(ErrorReason("invalid user"))
            Left(reason)
          }
        } else {
          Left(ErrorReason(s"bad status code: ${response.status}"))
        }
      }
  }
}

object RemoteUserValidatorSettings {
  val format = new Format[RemoteUserValidatorSettings] {
    override def reads(json: JsValue): JsResult[RemoteUserValidatorSettings] = Try {
      RemoteUserValidatorSettings(
        url = json.select("url").asString,
        headers = json.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        timeout = json.select("timeout").asOpt[Long].map(_.millis).getOrElse(10.seconds),
        tlsSettings = json
          .select("tls_settings")
          .asOpt[JsObject]
          .flatMap(js => NgTlsConfig.format.reads(js).asOpt)
          .getOrElse(NgTlsConfig())
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: RemoteUserValidatorSettings): JsValue             = Json.obj(
      "url"          -> o.url,
      "headers"      -> o.headers,
      "timeout"      -> o.timeout.toMillis,
      "tls_settings" -> o.tlsSettings.json
    )
  }
}

trait ValidableUser { self =>

  def json: JsValue

  def email: String

  def validate(
      desc: ServiceDescriptor,
      isRoute: Boolean,
      authModuleConfig: AuthModuleConfig
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ErrorReason, self.type]] = {
    val jsonuser     = json
    val allowedUsers = authModuleConfig.allowedUsers
    val deniedUsers  = authModuleConfig.deniedUsers
    if (allowedUsers.nonEmpty && !allowedUsers.exists(str => strMatch(email, str))) {
      Left(
        ErrorReason("User not allowed", Json.obj("error" -> "user blocked by allowed list of auth module").some)
      ).vfuture
    } else if (deniedUsers.nonEmpty && deniedUsers.exists(str => strMatch(email, str))) {
      Left(
        ErrorReason("User not allowed", Json.obj("error" -> "user blocked by denied list of auth module").some)
      ).vfuture
    } else {
      val validators = authModuleConfig.userValidators
      jsonPathValidate(jsonuser, validators) match {
        case Left(err) => Left(err).vfuture
        case Right(_)  =>
          val remoteValidators = authModuleConfig.remoteValidators
          remoteValidation(jsonuser, remoteValidators, desc, isRoute, authModuleConfig)
      }
    }
  }

  def strMatch(v: String, expected: String): Boolean = {
    if (expected.trim.startsWith("Regex(") && expected.trim.endsWith(")")) {
      val regex = expected.substring(6).init
      RegexPool.regex(regex).matches(v)
    } else if (expected.trim.startsWith("Wildcard(") && expected.trim.endsWith(")")) {
      val regex = expected.substring(9).init
      RegexPool.apply(regex).matches(v)
    } else if (expected.trim.startsWith("RegexNot(") && expected.trim.endsWith(")")) {
      val regex = expected.substring(9).init
      !RegexPool.regex(regex).matches(v)
    } else if (expected.trim.startsWith("WildcardNot(") && expected.trim.endsWith(")")) {
      val regex = expected.substring(12).init
      !RegexPool.apply(regex).matches(v)
    } else if (expected.trim.startsWith("Contains(") && expected.trim.endsWith(")")) {
      val contained = expected.substring(9).init
      v.contains(contained)
    } else if (expected.trim.startsWith("ContainsNot(") && expected.trim.endsWith(")")) {
      val contained = expected.substring(12).init
      !v.contains(contained)
    } else if (expected.trim.startsWith("Not(") && expected.trim.endsWith(")")) {
      val contained = expected.substring(4).init
      v != contained
    } else if (expected.trim.startsWith("ContainedIn(") && expected.trim.endsWith(")")) {
      val contained = expected.substring(12).init
      contained.split(",").map(_.trim()).contains(v)
    } else if (expected.trim.startsWith("NotContainedIn(") && expected.trim.endsWith(")")) {
      val contained = expected.substring(15).init
      val values    = contained.split(",").map(_.trim())
      !values.contains(v)
    } else {
      v == expected
    }
  }

  def remoteValidation(
      jsonuser: JsValue,
      remoteValidators: Seq[RemoteUserValidatorSettings],
      desc: ServiceDescriptor,
      isRoute: Boolean,
      authModuleConfig: AuthModuleConfig
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ErrorReason, self.type]] = {
    Source(remoteValidators.toList)
      .mapAsync(1) { remoteValidator =>
        remoteValidator.validate(jsonuser, desc, isRoute, authModuleConfig)
      }
      .filter(_.isLeft)
      .take(1)
      .runWith(Sink.headOption)(env.otoroshiMaterializer)
      .map {
        case None            => Right(this)
        case Some(Left(err)) => Left(err)
        case Some(Right(_))  => Right(this)
      }
  }

  def jsonPathValidate(jsonuser: JsValue, validators: Seq[JsonPathValidator])(implicit
      env: Env
  ): Either[ErrorReason, self.type] = {
    if (validators.forall(validator => validator.validate(jsonuser))) {
      Right(this)
    } else {
      Left(ErrorReason("user is not valid"))
    }
  }
}

case class ErrorReason(display: String, internal: Option[JsObject] = None) {
  def json: JsValue = ErrorReason.format.writes(this)
}

object ErrorReason {
  val format = new Format[ErrorReason] {

    override def reads(json: JsValue): JsResult[ErrorReason] = Try {
      ErrorReason(
        display = json.select("display").asString,
        internal = json.select("internal").asOpt[JsObject]
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }

    override def writes(o: ErrorReason): JsValue = Json.obj(
      "display"  -> o.display,
      "internal" -> o.internal
    )
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
  ): Future[Either[ErrorReason, PrivateAppsUser]]

  def boLoginPage(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext, env: Env): Future[Result]
  def boLogout(request: RequestHeader, user: BackOfficeUser, config: GlobalConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[Result, Option[String]]]
  def boCallback(request: Request[AnyContent], config: GlobalConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[ErrorReason, BackOfficeUser]]
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
  def allowedUsers: Seq[String]
  def deniedUsers: Seq[String]
  def userValidators: Seq[JsonPathValidator]
  def remoteValidators: Seq[RemoteUserValidatorSettings]
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
  def adminEntityValidatorsOverride: Map[String, Map[String, Seq[JsonValidator]]]
}

trait AuthConfigsDataStore extends BasicStore[AuthModuleConfig] {
  def findById(id: String)(implicit ec: ExecutionContext, env: Env): Future[Option[AuthModuleConfig]]
  def generateLoginToken(maybeTokenValue: Option[String] = None)(implicit ec: ExecutionContext): Future[String]
  def validateLoginToken(token: String)(implicit ec: ExecutionContext): Future[Boolean]

  def setUserForToken(token: String, user: JsValue)(implicit ec: ExecutionContext): Future[Unit]
  def getUserForToken(token: String)(implicit ec: ExecutionContext): Future[Option[JsValue]]

  def templates()(implicit env: Env): Seq[AuthModuleConfig] = env.scriptManager.authModules

  def template(modType: Option[String], env: Env, ctx: Option[ApiActionContext[_]] = None)(implicit
      ec: ExecutionContext
  ): AuthModuleConfig = {

    val defaultValue = BasicAuthModuleConfig(
      id = IdGenerator.namedId("auth_mod", env),
      name = "New auth. module",
      desc = "New auth. module",
      tags = Seq.empty,
      metadata = Map.empty,
      sessionCookieValues = SessionCookieValues(),
      clientSideSessionEnabled = true
    )
      .copy(location = EntityLocation.ownEntityLocation(ctx)(env))

    val defaultModule = modType match {
      case Some(ref) =>
        templates()(env)
          .find(config => config.`type` == ref)
          .map(_.withLocation(EntityLocation.ownEntityLocation(ctx)(env)))
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
