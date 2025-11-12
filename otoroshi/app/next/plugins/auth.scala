package otoroshi.next.plugins

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.util.ByteString
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import com.google.common.base.Charsets
import org.mindrot.jbcrypt.BCrypt
import otoroshi.auth.{AuthModuleConfig, BasicAuthModule, BasicAuthModuleConfig, LdapAuthModule, LdapAuthModuleConfig}
import otoroshi.auth.implicits.ResultWithPrivateAppSession
import otoroshi.controllers.routes
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.{PrivateAppsUser, PrivateAppsUserHelper}
import otoroshi.next.plugins.BasicAuthWithAuthModule.alreadyLoggedIn
import otoroshi.next.plugins.api.NgAccess.NgAllowed
import otoroshi.next.plugins.api._
import otoroshi.security.OtoroshiClaim
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.Results.BadRequest
import play.api.mvc.{Result, Results}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class NgLegacyAuthModuleCallConfig(
    publicPatterns: Seq[String] = Seq.empty,
    privatePatterns: Seq[String] = Seq.empty,
    config: NgAuthModuleConfig
) extends NgPluginConfig {
  override def json: JsValue = NgLegacyAuthModuleCallConfig.format.writes(this)
}
object NgLegacyAuthModuleCallConfig {
  val default = NgLegacyAuthModuleCallConfig(Seq.empty, Seq.empty, NgAuthModuleConfig())
  val format  = new Format[NgLegacyAuthModuleCallConfig] {
    override def writes(o: NgLegacyAuthModuleCallConfig): JsValue             = Json.obj(
      "public_patterns"  -> o.publicPatterns,
      "private_patterns" -> o.privatePatterns
    ) ++ o.config.json.asObject
    override def reads(json: JsValue): JsResult[NgLegacyAuthModuleCallConfig] = Try {
      NgLegacyAuthModuleCallConfig(
        publicPatterns = json.select("public_patterns").asOpt[Seq[String]].getOrElse(Seq.empty),
        privatePatterns = json.select("private_patterns").asOpt[Seq[String]].getOrElse(Seq.empty),
        config = NgAuthModuleConfig.format.reads(json).asOpt.getOrElse(NgAuthModuleConfig())
      )
    } match {
      case Failure(e)     => JsError(e.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

class NgLegacyAuthModuleCall extends NgAccessValidator {
  private val configReads: Reads[NgLegacyAuthModuleCallConfig] = NgLegacyAuthModuleCallConfig.format

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Authentication, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Legacy Authentication"
  override def description: Option[String]                 =
    "This plugin applies an authentication module the same way service descriptor does".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgLegacyAuthModuleCallConfig.default.some

  override def isAccessAsync: Boolean = true

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val authPlugin   = env.scriptManager
      .getAnyScript[NgAccessValidator](NgPluginHelper.pluginId[AuthModule])(env.otoroshiExecutionContext)
      .right
      .get
    val apikeyPlugin = env.scriptManager
      .getAnyScript[NgAccessValidator](NgPluginHelper.pluginId[NgLegacyApikeyCall])(env.otoroshiExecutionContext)
      .right
      .get
    val config       = ctx.cachedConfig(internalName)(configReads).getOrElse(NgLegacyAuthModuleCallConfig.default)
    val apikeyConfig = NgLegacyApikeyCallConfig(
      config.publicPatterns,
      config.privatePatterns,
      NgApikeyCallsConfig.fromLegacy(ctx.route.legacy.apiKeyConstraints)
    )
    val descriptor   =
      ctx.route.legacy.copy(publicPatterns = config.publicPatterns, privatePatterns = config.privatePatterns)
    val req          = ctx.request
    if (descriptor.isUriPublic(req.path)) {
      authPlugin.access(ctx)(env, ec)
    } else {
      PrivateAppsUserHelper.isPrivateAppsSessionValid(req, descriptor, ctx.attrs).flatMap {
        case Some(_) if descriptor.strictlyPrivate => apikeyPlugin.access(ctx.copy(config = apikeyConfig.json))
        case Some(user)                            =>
          ctx.attrs.put(otoroshi.plugins.Keys.UserKey -> user)
          NgAccess.NgAllowed.vfuture
        case None                                  =>
          apikeyPlugin.access(ctx.copy(config = apikeyConfig.json))
      }
    }
  }
}

case class NgAuthModuleConfig(module: Option[String] = None, passWithApikey: Boolean = false) extends NgPluginConfig {
  def json: JsValue = NgAuthModuleConfig.format.writes(this)
}

object NgAuthModuleConfig {
  val format = new Format[NgAuthModuleConfig] {
    override def reads(json: JsValue): JsResult[NgAuthModuleConfig] = Try {
      NgAuthModuleConfig(
        module =
          json.select("auth_module").asOpt[String].orElse(json.select("module").asOpt[String]).filter(_.nonEmpty),
        passWithApikey = json.select("pass_with_apikey").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: NgAuthModuleConfig): JsValue             = Json.obj(
      "pass_with_apikey" -> o.passWithApikey,
      "auth_module"      -> o.module.map(JsString.apply).getOrElse(JsNull).as[JsValue]
    )
  }
}

case class NgMultiAuthModuleConfig(
    modules: Seq[String] = Seq.empty,
    passWithApikey: Boolean = false,
    useEmailPrompt: Boolean = false,
    usersGroups: JsObject = Json.obj()
) extends NgPluginConfig {
  def json: JsValue = NgMultiAuthModuleConfig.format.writes(this)
}

object NgMultiAuthModuleConfig {
  val format = new Format[NgMultiAuthModuleConfig] {
    override def reads(json: JsValue): JsResult[NgMultiAuthModuleConfig] = Try {
      NgMultiAuthModuleConfig(
        modules = json
          .select("auth_modules")
          .asOpt[Seq[String]]
          .orElse(json.select("modules").asOpt[Seq[String]])
          .getOrElse(Seq.empty[String])
          .filter(_.nonEmpty),
        passWithApikey = json
          .select("pass_with_apikey")
          .asOpt[Boolean]
          .getOrElse(false),
        useEmailPrompt = json
          .select("use_email_prompt")
          .asOpt[Boolean]
          .getOrElse(false),
        usersGroups = json
          .select("users_groups")
          .asOpt[JsObject]
          .getOrElse(Json.obj())
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }

    override def writes(o: NgMultiAuthModuleConfig): JsValue = Json.obj(
      "pass_with_apikey"  -> o.passWithApikey,
      "auth_modules"      -> o.modules,
      "use_email_prompt"  -> o.useEmailPrompt,
      "users_groups"      -> o.usersGroups,
    )
  }
}

class MultiAuthModule extends NgAccessValidator {

  private val logger                                      = Logger("otoroshi-next-plugins-multi-auth-module")
  private val configReads: Reads[NgMultiAuthModuleConfig] = NgMultiAuthModuleConfig.format

  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def name: String = "Multi Authentication"

  override def description: Option[String] =
    "This plugin applies an authentication module from a list of selected modules".some

  override def defaultConfigObject: Option[NgPluginConfig] = NgMultiAuthModuleConfig().some

  override def isAccessAsync: Boolean = true

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val NgMultiAuthModuleConfig(modules, passWithApikey, useEmailPrompt, usersGroups) =
      ctx.cachedConfig(internalName)(configReads).getOrElse(NgMultiAuthModuleConfig())

    val maybeApikey = ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val pass        = passWithApikey match {
      case true  => maybeApikey.isDefined
      case false => false
    }

    ctx.attrs.get(otoroshi.plugins.Keys.UserKey) match {
      case None if !pass =>
        if (modules.isEmpty) {
          Errors
            .craftResponseResult(
              "Auth. config. refs not found on the descriptor",
              Results.InternalServerError,
              ctx.request,
              None,
              "errors.auth.config.refs.not.found".some,
              attrs = ctx.attrs,
              maybeRoute = ctx.route.some
            )
            .map(NgAccess.NgDenied.apply)
        } else if (modules.length == 1) {
          passWithAuthModule(modules.head, ctx)
        } else {
          ctx.request.cookies.filter(cookie => cookie.name.startsWith("oto-papps")) match {
            case cookies if cookies.nonEmpty =>
              modules
                .flatMap(module => env.proxyState.authModule(module))
                .find(module =>
                  cookies.exists(cookie => cookie.name == s"oto-papps-${module.routeCookieSuffix(ctx.route)}")
                ) match {
                case Some(module) => passWithAuthModule(module.id, ctx)
                case None         => redirectToAuthModule(ctx, useEmailPrompt)
              }
            case _                           => redirectToAuthModule(ctx, useEmailPrompt)
          }
        }
      case _             => NgAccess.NgAllowed.vfuture
    }
  }

  private def getHashAndRedirectURI(ctx: NgAccessContext)(implicit env: Env) = {
    val req             = ctx.request
    val baseRedirect    = s"${req.theProtocol}://${req.theHost}${req.relativeUri}"
    val redirect        = {
      if (env.allowRedirectQueryParamOnLogin) req.getQueryString("redirect").getOrElse(baseRedirect)
      else baseRedirect
    }
    val encodedRedirect = Base64.getUrlEncoder.encodeToString(redirect.getBytes(StandardCharsets.UTF_8))
    val descriptorId    = ctx.route.legacy.id
    val hash            = env.sign(s"route=${descriptorId}&redirect=${encodedRedirect}")

    (hash, encodedRedirect)
  }

  private def redirectToAuthModule(ctx: NgAccessContext, useEmailPrompt: Boolean)(implicit env: Env) = {
    val (hash, encodedRedirect) = getHashAndRedirectURI(ctx)

    if (useEmailPrompt) {
      NgAccess
        .NgDenied(
          Results.Redirect(
            s"${env.rootScheme + env.privateAppsHost + env.privateAppsPort}/privateapps/generic/simple-login?route=${ctx.route.id}&redirect=$encodedRedirect&hash=$hash"
          )
        )
        .vfuture
    } else {
      NgAccess
        .NgDenied(
          Results.Redirect(
            s"${env.rootScheme + env.privateAppsHost + env.privateAppsPort}/privateapps/generic/choose-provider?route=${ctx.route.id}&redirect=$encodedRedirect&hash=$hash"
          )
        )
        .vfuture
    }
  }

  private def passWithAuthModule(authModuleId: String, ctx: NgAccessContext)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[NgAccess] = {
    env.proxyState.authModule(authModuleId) match {
      case None       =>
        Errors
          .craftResponseResult(
            "Auth. config. not found on the descriptor",
            Results.InternalServerError,
            ctx.request,
            None,
            "errors.auth.config.not.found".some,
            attrs = ctx.attrs,
            maybeRoute = ctx.route.some
          )
          .map(NgAccess.NgDenied.apply)
      case Some(auth) => {
        // here there is a datastore access (by key) to get the user session
        PrivateAppsUserHelper.isPrivateAppsSessionValidWithAuth(ctx.request, ctx.route.legacy, auth).flatMap {
          case Some(paUsr) =>
            ctx.attrs.put(otoroshi.plugins.Keys.UserKey -> paUsr)
            NgAccess.NgAllowed.vfuture
          case None        => {
            val (hash, encodedRedirect) = getHashAndRedirectURI(ctx)
            val redirectTo      =
              env.rootScheme + env.privateAppsHost + env.privateAppsPort + otoroshi.controllers.routes.AuthController
                .confidentialAppLoginPage()
                .url + s"?route=${ctx.route.id}&redirect=$encodedRedirect&hash=$hash&ref=${authModuleId}"
            if (logger.isTraceEnabled) logger.trace("should redirect to " + redirectTo)
            NgAccess
              .NgDenied(
                Results
                  .Redirect(redirectTo)
                  .discardingCookies(
                    env.removePrivateSessionCookies(
                      ctx.request.theDomain,
                      ctx.route.legacy,
                      auth
                    ): _*
                  )
              )
              .vfuture
          }
        }
      }
    }
  }
}

class AuthModule extends NgAccessValidator {

  private val logger                                 = Logger("otoroshi-next-plugins-auth-module")
  private val configReads: Reads[NgAuthModuleConfig] = NgAuthModuleConfig.format

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Authentication"
  override def description: Option[String]                 = "This plugin applies an authentication module".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgAuthModuleConfig().some

  override def isAccessAsync: Boolean = true

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val NgAuthModuleConfig(module, passWithApikey) =
      ctx.cachedConfig(internalName)(configReads).getOrElse(NgAuthModuleConfig())
    val req                                        = ctx.request
    val descriptor                                 = ctx.route.serviceDescriptor
    val maybeApikey                                = ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val pass                                       = passWithApikey match {
      case true  => maybeApikey.isDefined
      case false => false
    }
    ctx.attrs.get(otoroshi.plugins.Keys.UserKey) match {
      case None if !pass => {
        module match {
          case None      =>
            Errors
              .craftResponseResult(
                "Auth. config. ref not found on the descriptor",
                Results.InternalServerError,
                ctx.request,
                None,
                "errors.auth.config.ref.not.found".some,
                attrs = ctx.attrs,
                maybeRoute = ctx.route.some
              )
              .map(NgAccess.NgDenied.apply)
          case Some(ref) => {
            // env.datastores.authConfigsDataStore.findById(ref).flatMap {
            env.proxyState.authModule(ref) match {
              case None       =>
                Errors
                  .craftResponseResult(
                    "Auth. config. not found on the descriptor",
                    Results.InternalServerError,
                    ctx.request,
                    None,
                    "errors.auth.config.not.found".some,
                    attrs = ctx.attrs,
                    maybeRoute = ctx.route.some
                  )
                  .map(NgAccess.NgDenied.apply)
              case Some(auth) => {
                // here there is a datastore access (by key) to get the user session
                PrivateAppsUserHelper.isPrivateAppsSessionValidWithAuth(ctx.request, descriptor, auth).flatMap {
                  case Some(paUsr) =>
                    ctx.attrs.put(otoroshi.plugins.Keys.UserKey -> paUsr)
                    NgAccess.NgAllowed.vfuture
                  case None        => {
                    val baseRedirect    = s"${req.theProtocol}://${req.theHost}${req.relativeUri}"
                    val redirect        =
                      if (env.allowRedirectQueryParamOnLogin) req.getQueryString("redirect").getOrElse(baseRedirect)
                      else baseRedirect
                    val encodedRedirect = Base64.getUrlEncoder.encodeToString(redirect.getBytes(StandardCharsets.UTF_8))
                    val descriptorId    = descriptor.id
                    val hash            = env.sign(s"desc=${descriptorId}&redirect=${encodedRedirect}")
                    val redirectTo      =
                      env.rootScheme + env.privateAppsHost + env.privateAppsPort + otoroshi.controllers.routes.AuthController
                        .confidentialAppLoginPage()
                        .url + s"?desc=${descriptorId}&redirect=${encodedRedirect}&hash=${hash}"
                    if (logger.isTraceEnabled) logger.trace("should redirect to " + redirectTo)
                    NgAccess
                      .NgDenied(
                        Results
                          .Redirect(redirectTo)
                          .discardingCookies(
                            env.removePrivateSessionCookies(
                              req.theDomain,
                              descriptor,
                              auth
                            ): _*
                          )
                      )
                      .vfuture
                  }
                }
              }
            }
          }
        }
      }
      case _             => NgAccess.NgAllowed.vfuture
    }
  }
}

case class NgAuthModuleUserExtractorConfig(module: Option[String] = None) extends NgPluginConfig {
  def json: JsValue = NgAuthModuleUserExtractorConfig.format.writes(this)
}

object NgAuthModuleUserExtractorConfig {
  val format = new Format[NgAuthModuleUserExtractorConfig] {
    override def reads(json: JsValue): JsResult[NgAuthModuleUserExtractorConfig] = Try {
      NgAuthModuleUserExtractorConfig(
        module = json.select("auth_module").asOpt[String].orElse(json.select("module").asOpt[String]).filter(_.nonEmpty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: NgAuthModuleUserExtractorConfig): JsValue             = Json.obj(
      "auth_module" -> o.module.map(JsString.apply).getOrElse(JsNull).as[JsValue]
    )
  }
}

class NgAuthModuleUserExtractor extends NgAccessValidator {

  private val configReads: Reads[NgAuthModuleUserExtractorConfig] = NgAuthModuleUserExtractorConfig.format

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def isAccessAsync: Boolean                                       = true
  override def multiInstance: Boolean                                       = true
  override def core: Boolean                                                = true
  override def name: String                                                 = "User extraction from auth. module"
  override def description: Option[String]                                  =
    "This plugin extracts users from an authentication module without enforcing login".some
  override def defaultConfigObject: Option[NgAuthModuleUserExtractorConfig] = NgAuthModuleUserExtractorConfig().some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {

    def error(status: Results.Status, msg: String, code: String): Future[NgAccess] = {
      Errors
        .craftResponseResult(
          msg,
          status,
          ctx.request,
          None,
          code.some,
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some
        )
        .map(NgAccess.NgDenied.apply)
    }

    val NgAuthModuleUserExtractorConfig(module) =
      ctx.cachedConfig(internalName)(configReads).getOrElse(NgAuthModuleUserExtractorConfig())
    val descriptor                              = ctx.route.serviceDescriptor
    ctx.attrs.get(otoroshi.plugins.Keys.UserKey) match {
      case None => {
        module match {
          case None      =>
            error(
              Results.InternalServerError,
              "Auth. config. ref not found on the descriptor",
              "errors.auth.config.ref.not.found"
            )
          case Some(ref) => {
            env.proxyState.authModule(ref) match {
              case None       =>
                error(
                  Results.InternalServerError,
                  "Auth. config. not found on the descriptor",
                  "errors.auth.config.not.found"
                )
              case Some(auth) => {
                // here there is a datastore access (by key) to get the user session
                PrivateAppsUserHelper.isPrivateAppsSessionValidWithAuth(ctx.request, descriptor, auth).flatMap {
                  case Some(paUsr) =>
                    ctx.attrs.put(otoroshi.plugins.Keys.UserKey -> paUsr)
                    NgAccess.NgAllowed.vfuture
                  case None        => {
                    NgAccess.NgAllowed.vfuture
                  }
                }
              }
            }
          }
        }
      }
      case _    => NgAccess.NgAllowed.vfuture
    }
  }
}

case class NgAuthModuleExpectedUserConfig(onlyFrom: Seq[String] = Seq.empty) extends NgPluginConfig {
  def json: JsValue = NgAuthModuleExpectedUserConfig.format.writes(this)
}

object NgAuthModuleExpectedUserConfig {
  val format = new Format[NgAuthModuleExpectedUserConfig] {
    override def reads(json: JsValue): JsResult[NgAuthModuleExpectedUserConfig] = Try {
      NgAuthModuleExpectedUserConfig(
        onlyFrom = json.select("only_from").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: NgAuthModuleExpectedUserConfig): JsValue             = Json.obj(
      "only_from" -> JsArray(o.onlyFrom.map(JsString.apply))
    )
  }
}

class NgAuthModuleExpectedUser extends NgAccessValidator {

  private val configReads: Reads[NgAuthModuleExpectedUserConfig] = NgAuthModuleExpectedUserConfig.format

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def isAccessAsync: Boolean                                      = true
  override def multiInstance: Boolean                                      = true
  override def core: Boolean                                               = true
  override def name: String                                                = "User logged in expected"
  override def description: Option[String]                                 = "This plugin enforce that a user from any auth. module is logged in".some
  override def defaultConfigObject: Option[NgAuthModuleExpectedUserConfig] = NgAuthModuleExpectedUserConfig().some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {

    def error(status: Results.Status, msg: String, code: String): Future[NgAccess] = {
      Errors
        .craftResponseResult(
          msg,
          status,
          ctx.request,
          None,
          code.some,
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some
        )
        .map(NgAccess.NgDenied.apply)
    }

    val NgAuthModuleExpectedUserConfig(onlyFrom) =
      ctx.cachedConfig(internalName)(configReads).getOrElse(NgAuthModuleExpectedUserConfig())

    ctx.attrs.get(otoroshi.plugins.Keys.UserKey) match {
      case None                            => error(Results.Unauthorized, "You're not authorized here !", "errors.auth.unauthorized")
      case Some(user) if onlyFrom.nonEmpty => {
        if (onlyFrom.contains(user.authConfigId)) {
          NgAccess.NgAllowed.vfuture
        } else {
          error(Results.Unauthorized, "You're not authorized here !", "errors.auth.unauthorized")
        }
      }
      case Some(_)                         => NgAccess.NgAllowed.vfuture
    }
  }
}

case class BasicAuthCallerConfig(
    username: Option[String] = None,
    password: Option[String] = None,
    headerName: String = "Authorization",
    headerValueFormat: String = "Basic %s"
) extends NgPluginConfig {
  override def json: JsValue = BasicAuthCallerConfig.format.writes(this)
}

object BasicAuthCallerConfig {
  val format: Format[BasicAuthCallerConfig] = new Format[BasicAuthCallerConfig] {
    override def writes(o: BasicAuthCallerConfig): JsValue = Json.obj(
      "username"          -> o.username,
      "password"          -> o.password,
      "headerName"        -> o.headerName,
      "headerValueFormat" -> o.headerValueFormat
    )

    override def reads(json: JsValue): JsResult[BasicAuthCallerConfig] = Try {
      BasicAuthCallerConfig(
        username = json.select("username").asOpt[String].filter(_.nonEmpty),
        password = json.select("password").asOpt[String].filter(_.nonEmpty),
        headerName = json.select("headerName").asOpt[String].getOrElse("Authorization"),
        headerValueFormat = json.select("headerValueFormat").asOpt[String].getOrElse("Basic %s")
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class BasicAuthCaller extends NgRequestTransformer {

  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = false
  override def name: String                                = "Basic Auth. caller"
  override def description: Option[String]                 =
    "This plugin can be used to call api that are authenticated using basic auth.".some
  override def defaultConfigObject: Option[NgPluginConfig] = BasicAuthCallerConfig().some

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val config = ctx.cachedConfig(internalName)(BasicAuthCallerConfig.format.reads).getOrElse(BasicAuthCallerConfig())

    (config.username, config.password) match {
      case (Some(username), Some(password)) if username.nonEmpty && password.nonEmpty =>
        val token: String = ByteString(s"$username:$password").encodeBase64.utf8String
        Right(
          ctx.otoroshiRequest.copy(headers =
            ctx.otoroshiRequest.headers + (config.headerName -> config.headerValueFormat.format(token))
          )
        )
      case _                                                                          => BadRequest(Json.obj("error" -> "Bad configuration")).left
    }

  }
}

case class SimpleBasicAuthConfig(realm: String = "authentication", users: Map[String, String] = Map.empty)
    extends NgPluginConfig   {
  override def json: JsValue = SimpleBasicAuthConfig.format.writes(this)
}
object SimpleBasicAuthConfig {
  val format                         = new Format[SimpleBasicAuthConfig] {
    override def reads(json: JsValue): JsResult[SimpleBasicAuthConfig] = Try {
      SimpleBasicAuthConfig(
        realm = json.select("realm").asOptString.getOrElse("authentication"),
        users = json.select("users").asOpt[Map[String, String]].getOrElse(Map.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: SimpleBasicAuthConfig): JsValue             = Json.obj(
      "realm" -> o.realm,
      "users" -> o.users
    )
  }
  val configFlow: Seq[String]        = Seq("realm", "users")
  def configSchema: Option[JsObject] = Some(
    Json.obj(
      "realm" -> Json.obj(
        "type"  -> "string",
        "label" -> s"Realm",
        "help"  -> "A unique realm name to avoid weird browser behaviors",
        "props" -> Json.obj(
          "placeholder" -> "A unique realm name to avoid weird browser behaviors",
          "help"        -> "A unique realm name to avoid weird browser behaviors"
        )
      ),
      "users" -> Json.obj(
        "type"  -> "object",
        "label" -> "Users",
        "props" -> Json.obj(
          "bcryptable" -> true
        )
      )
    )
  )
}

class SimpleBasicAuth extends NgAccessValidator {

  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def noJsForm: Boolean                           = true
  override def name: String                                = "Basic Auth"
  override def description: Option[String]                 =
    "This plugin can be used to protect a route with basic auth. You can use clear text passwords (not recommended for production usage) or Bcryt hashed password as password values".some
  override def defaultConfigObject: Option[NgPluginConfig] = SimpleBasicAuthConfig().some

  override def configFlow: Seq[String]        = SimpleBasicAuthConfig.configFlow
  override def configSchema: Option[JsObject] = SimpleBasicAuthConfig.configSchema

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config                = ctx.cachedConfig(internalName)(SimpleBasicAuthConfig.format.reads).getOrElse(SimpleBasicAuthConfig())
    val globalUsers           = env.datastores.globalConfigDataStore
      .latest()
      .plugins
      .config
      .select("simple_basic_auth_global_users")
      .asOpt[Map[String, String]]
      .getOrElse(Map.empty)
    val authorization: String = ctx.request.headers
      .get("Authorization")
      .filter(_.startsWith("Basic "))
      .map(_.replace("Basic ", ""))
      .map(v => new String(Base64.getDecoder.decode(v), StandardCharsets.UTF_8))
      .getOrElse("")
    val parts                 = authorization.split(":").toSeq
    if (authorization.contains(":") && parts.length > 1) {
      val username = parts.head
      val password = parts.tail.mkString(":")
      (config.users ++ globalUsers).get(username) match {
        case Some(pwd) if password == pwd               => NgAccess.NgAllowed.vfuture
        case Some(pwd) if BCrypt.checkpw(password, pwd) => NgAccess.NgAllowed.vfuture
        case _                                          => {
          NgAccess
            .NgDenied(
              Results
                .Unauthorized("")
                .withHeaders("WWW-Authenticate" -> s"""Basic realm="${config.realm}"""")
            )
            .vfuture
        }
      }
    } else {
      NgAccess
        .NgDenied(
          Results
            .Unauthorized("")
            .withHeaders("WWW-Authenticate" -> s"""Basic realm="${config.realm}"""")
        )
        .vfuture
    }
  }
}

class NgExpectedConsumer extends NgAccessValidator {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def isAccessAsync: Boolean                      = true
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Expected consumer"
  override def description: Option[String]                 = "This plugin expect that a user or an apikey made the call".some
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def noJsForm: Boolean                           = true

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {

    def error(status: Results.Status, msg: String, code: String): Future[NgAccess] = {
      Errors
        .craftResponseResult(
          msg,
          status,
          ctx.request,
          None,
          code.some,
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some
        )
        .map(NgAccess.NgDenied.apply)
    }

    ctx.attrs.get(otoroshi.plugins.Keys.UserKey) match {
      case None    =>
        ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey) match {
          case None    => error(Results.Unauthorized, "You're not authorized here !", "errors.auth.unauthorized")
          case Some(_) => NgAccess.NgAllowed.vfuture
        }
      case Some(_) => NgAccess.NgAllowed.vfuture
    }
  }
}

case class BasicAuthWithAuthModuleConfig(ref: String = "", addAuthenticateHeader: Boolean = true)
    extends NgPluginConfig {
  def json: JsValue = BasicAuthWithAuthModuleConfig.format.writes(this)
}

object BasicAuthWithAuthModuleConfig {
  val format = new Format[BasicAuthWithAuthModuleConfig] {

    override def reads(json: JsValue): JsResult[BasicAuthWithAuthModuleConfig] = Try {
      BasicAuthWithAuthModuleConfig(
        ref = json.select("ref").asString,
        addAuthenticateHeader = json.select("add_authenticate_header").asOptBoolean.getOrElse(true)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }

    override def writes(o: BasicAuthWithAuthModuleConfig): JsValue = Json.obj(
      "ref"                     -> o.ref,
      "add_authenticate_header" -> o.addAuthenticateHeader
    )
  }
  val configFlow: Seq[String]        = Seq("ref", "add_authenticate_header")
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "add_authenticate_header" -> Json.obj(
        "type"  -> "boolean",
        "label" -> "Add WWW-Authenticate header"
      ),
      "ref"                     -> Json.obj(
        "type"  -> "select",
        "label" -> "Auth. module",
        "props" -> Json.obj(
          "optionsFrom"        -> "/bo/api/proxy/api/auths?types=basic&types=ldap",
          "optionsTransformer" -> Json.obj(
            "label" -> "name",
            "value" -> "id"
          )
        )
      )
    )
  )
}

object BasicAuthWithAuthModule {

  val cache: Cache[String, PrivateAppsUser] = Scaffeine().expireAfterWrite(1.minutes).build()

  def alreadyLoggedIn(username: String, config: AuthModuleConfig): Option[PrivateAppsUser] = {
    cache.getIfPresent(s"${config.id}:${username.sha256}")
  }

  def addLoggedIn(username: String, user: PrivateAppsUser, config: AuthModuleConfig): Unit = {
    cache.put(s"${config.id}:${username.sha256}", user)
  }
}

class BasicAuthWithAuthModule extends NgAccessValidator {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def isAccessAsync: Boolean                      = true
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Basic auth. from auth. module"
  override def description: Option[String]                 =
    "This plugin enforces basic auth. authentication with users coming from LDAP and In-memory auth. modules".some
  override def defaultConfigObject: Option[NgPluginConfig] = BasicAuthWithAuthModuleConfig().some
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = BasicAuthWithAuthModuleConfig.configFlow
  override def configSchema: Option[JsObject]              = BasicAuthWithAuthModuleConfig.configSchema

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {

    val config     =
      ctx.cachedConfig(internalName)(BasicAuthWithAuthModuleConfig.format).getOrElse(BasicAuthWithAuthModuleConfig())
    val descriptor = ctx.route.legacy

    def unauthorized(opt: Option[AuthModuleConfig]): Result = {
      Results
        .Unauthorized(Json.obj("error" -> "forbidden"))
        .applyOnWithOpt(opt) {
          case (r, authConfig) if config.addAuthenticateHeader =>
            r.withHeaders("WWW-Authenticate" -> s"""Basic realm="bawam-${authConfig.cookieSuffix(descriptor)}"""")
          case (r, _)                                          => r
        }
    }

    def decodeBase64(encoded: String): String = new String(OtoroshiClaim.decoder.decode(encoded), Charsets.UTF_8)

    def extractUsernamePassword(header: String): Option[(String, String)] = {
      val base64 = header.replace("Basic ", "").replace("basic ", "")
      Option(base64)
        .map(decodeBase64)
        .map(_.split(":").toSeq)
        .filter(v => v.nonEmpty && v.length > 1)
        .flatMap(a => a.headOption.map(head => (head, a.tail.mkString(":"))))

    }

    val authModuleOpt = env.proxyState.authModule(config.ref)

    ctx.request.headers.get("Authorization") match {
      case Some(auth) if auth.startsWith("Basic ") =>
        extractUsernamePassword(auth) match {
          case None                       => NgAccess.NgDenied(unauthorized(authModuleOpt)).vfuture
          case Some((username, password)) => {
            authModuleOpt match {
              case None                                    =>
                NgAccess
                  .NgDenied(
                    Results.InternalServerError(
                      Json.obj("error" -> "internal_server_error", "error_description" -> "auth. module not found")
                    )
                  )
                  .vfuture
              case Some(authModule: BasicAuthModuleConfig) => {
                BasicAuthModule(authModule).bindUser(username, password, descriptor).map {
                  case Left(_)     => NgAccess.NgDenied(unauthorized(authModuleOpt))
                  case Right(user) => {
                    ctx.attrs.put(otoroshi.plugins.Keys.UserKey -> user)
                    NgAllowed
                  }
                }
              }
              case Some(authModule: LdapAuthModuleConfig)  => {
                BasicAuthWithAuthModule.alreadyLoggedIn(username, authModule) match {
                  case Some(user) => {
                    ctx.attrs.put(otoroshi.plugins.Keys.UserKey -> user)
                    NgAllowed.vfuture
                  }
                  case None       => {
                    LdapAuthModule(authModule).bindUser(username, password, descriptor).map {
                      case Left(_)     => NgAccess.NgDenied(unauthorized(authModuleOpt))
                      case Right(user) => {
                        BasicAuthWithAuthModule.addLoggedIn(username, user, authModule)
                        ctx.attrs.put(otoroshi.plugins.Keys.UserKey -> user)
                        NgAllowed
                      }
                    }
                  }
                }
              }
              case _                                       =>
                NgAccess
                  .NgDenied(
                    Results.InternalServerError(
                      Json
                        .obj("error" -> "internal_server_error", "error_description" -> "unsupported auth. module kind")
                    )
                  )
                  .vfuture
            }
          }
        }
      case _                                       => NgAccess.NgDenied(unauthorized(authModuleOpt)).vfuture
    }
  }
}
