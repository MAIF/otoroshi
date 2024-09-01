package otoroshi.next.plugins

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.util.ByteString
import otoroshi.auth.{GenericOauth2Module, GenericOauth2ModuleConfig}
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.{PrivateAppsUser, PrivateAppsUserHelper}
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.Results

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{ExecutionContext, Future}
import scala.util._

sealed trait Auth0PasswordlessConnectionKind {
  def name: String
}

object Auth0PasswordlessConnectionKind {
  case object Email extends Auth0PasswordlessConnectionKind { def name: String = "email" }
  case object Sms   extends Auth0PasswordlessConnectionKind { def name: String = "sms"   }
  def apply(str: String): Auth0PasswordlessConnectionKind = str.toLowerCase() match {
    case "email" => Email
    case _       => Sms
  }
}

sealed trait Auth0PasswordlessSendKind {
  def name: String
}

object Auth0PasswordlessSendKind {
  case object Link extends Auth0PasswordlessSendKind { def name: String = "link" }
  case object Code extends Auth0PasswordlessSendKind { def name: String = "code" }
  def apply(str: String): Auth0PasswordlessSendKind = str.toLowerCase() match {
    case "link" => Link
    case _      => Code
  }
}

case class Auth0PasswordlessAuthConfig(
    ref: String,
    connection: Auth0PasswordlessConnectionKind,
    send: Auth0PasswordlessSendKind,
    audience: Option[String]
) extends NgPluginConfig {
  override def json: JsValue = Auth0PasswordlessAuthConfig.format.writes(this)
}

object Auth0PasswordlessAuthConfig {
  val default                        =
    Auth0PasswordlessAuthConfig("", Auth0PasswordlessConnectionKind.Email, Auth0PasswordlessSendKind.Code, None)
  val format                         = new Format[Auth0PasswordlessAuthConfig] {
    override def writes(o: Auth0PasswordlessAuthConfig): JsValue             = Json.obj(
      "ref"        -> o.ref,
      "connection" -> o.connection.name,
      "send"       -> o.send.name,
      "audience"   -> o.audience.map(_.json).getOrElse(JsNull).asValue
    )
    override def reads(json: JsValue): JsResult[Auth0PasswordlessAuthConfig] = Try {
      Auth0PasswordlessAuthConfig(
        ref = json.select("ref").asString,
        connection = Auth0PasswordlessConnectionKind.apply(
          json.select("connection").asOpt[String].getOrElse(Auth0PasswordlessAuthConfig.default.connection.name)
        ),
        send = Auth0PasswordlessSendKind.apply(
          json.select("send").asOpt[String].getOrElse(Auth0PasswordlessAuthConfig.default.send.name)
        ),
        audience = json.select("audience").asOpt[String]
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
  }
  val configFlow: Seq[String]        = Seq("ref", "connection", "send", "audience")
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "ref"        -> Json.obj(
        "type"  -> "select",
        "label" -> s"Auth. module",
        "props" -> Json.obj(
          "optionsFrom"        -> s"/bo/api/proxy/api/auths",
          "optionsTransformer" -> Json.obj(
            "label" -> "name",
            "value" -> "id"
          )
        )
      ),
      "connection" -> Json.obj(
        "type"  -> "select",
        "label" -> s"Connection",
        "props" -> Json.obj(
          "options" -> Json.arr(
            Json.obj("label" -> "Email", "value" -> "email"),
            Json.obj("label" -> "SMS", "value"   -> "sms")
          )
        )
      ),
      "send"       -> Json.obj(
        "type"  -> "select",
        "label" -> s"Send",
        "props" -> Json.obj(
          "options" -> Json.arr(
            Json.obj("label" -> "Code", "value" -> "code"),
            Json.obj("label" -> "Link", "value" -> "link")
          )
        )
      ),
      "audience"   -> Json.obj(
        "type"  -> "string",
        "label" -> "audience"
      )
    )
  )
}

class Auth0PasswordlessStartFlowEndpoint extends NgBackendCall {

  override def steps: Seq[NgStep]                          = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Auth0 Passwordless start flow endpoint"
  override def description: Option[String]                 = "This plugin provide an endpoint to start a passwordless flow".some
  override def defaultConfigObject: Option[NgPluginConfig] = Some(Auth0PasswordlessAuthConfig.default)
  override def useDelegates: Boolean                       = false
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = Auth0PasswordlessAuthConfig.configFlow
  override def configSchema: Option[JsObject]              = Auth0PasswordlessAuthConfig.configSchema

  def doStartFlow(
      ctx: NgbBackendCallContext,
      params: JsObject,
      config: Auth0PasswordlessAuthConfig,
      oauthConfig: GenericOauth2ModuleConfig
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val domain  = Uri(oauthConfig.authorizeUrl).authority.host.toString()
    val state   = IdGenerator.uuid
    val payload = Json
      .obj(
        "client_id"     -> oauthConfig.clientId,
        "client_secret" -> oauthConfig.clientSecret,
        "connection"    -> config.connection.name,
        "send"          -> config.send.name,
        "authParams"    -> Json.obj(
          "scope" -> "openid",
          "state" -> state
        )
      )
      .applyOn { obj =>
        config.connection match {
          case Auth0PasswordlessConnectionKind.Email =>
            val email =
              params.select("username").asOpt[String].orElse(params.select("email").asOpt[String]).getOrElse("")
            obj ++ Json.obj("email" -> email)
          case Auth0PasswordlessConnectionKind.Sms   =>
            val phoneNumber =
              params.select("username").asOpt[String].orElse(params.select("phone_number").asOpt[String]).getOrElse("")
            obj ++ Json.obj("phone_number" -> phoneNumber)
        }
      }
    env.Ws
      .url(s"https://${domain}/passwordless/start")
      .withRequestTimeout(10.seconds)
      .post(payload)
      .map { response =>
        if (response.status == 200) {
          val username = params
            .select("username")
            .asOpt[String]
            .orElse(params.select("email").asOpt[String])
            .orElse(params.select("phone_number").asOpt[String])
            .getOrElse("")
          BackendCallResponse(
            NgPluginHttpResponse.fromResult(
              Results.Ok(
                Json.obj(
                  "auth0_response" -> response.json,
                  "state"          -> state,
                  "username"       -> username
                )
              )
            ),
            None
          ).right
        } else {
          BackendCallResponse(
            NgPluginHttpResponse.fromResult(Results.Status(response.status).apply(response.json)),
            None
          ).right
        }
      }
  }

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config =
      ctx.cachedConfig(internalName)(Auth0PasswordlessAuthConfig.format).getOrElse(Auth0PasswordlessAuthConfig.default)
    env.proxyState.authModule(config.ref) match {
      case Some(authModule) if authModule.isInstanceOf[GenericOauth2ModuleConfig] => {
        val oauthConfig = authModule.asInstanceOf[GenericOauth2ModuleConfig]
        if (
          ctx.request.method == "POST" && ctx.request.hasBody && ctx.request.contentType.contains("application/json")
        ) {
          ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
            doStartFlow(ctx, Json.parse(bodyRaw.utf8String).asObject, config, oauthConfig)
          }
        } else {
          doStartFlow(ctx, JsObject(ctx.request.queryParams.mapValues(_.json)), config, oauthConfig)
        }
      }
      case None                                                                   =>
        BackendCallResponse(
          NgPluginHttpResponse.fromResult(Results.Unauthorized(Json.obj("error" -> "unauthorized"))),
          None
        ).rightf
    }
  }
}

class Auth0PasswordlessEndFlowEndpoint extends NgBackendCall {

  override def steps: Seq[NgStep]                          = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Auth0 Passwordless end flow endpoint"
  override def description: Option[String]                 = "This plugin provide an endpoint to end a passwordless flow".some
  override def defaultConfigObject: Option[NgPluginConfig] = Some(Auth0PasswordlessAuthConfig.default)
  override def useDelegates: Boolean                       = false
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = Auth0PasswordlessAuthConfig.configFlow
  override def configSchema: Option[JsObject]              = Auth0PasswordlessAuthConfig.configSchema

  private val logger = Logger("otoroshi-plugin-auth0-passwordless")

  def doEndFlow(
      ctx: NgbBackendCallContext,
      params: JsObject,
      config: Auth0PasswordlessAuthConfig,
      oauthConfig: GenericOauth2ModuleConfig
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val username = params.select("username").asOpt[String].getOrElse("")
    val code     = params.select("code").asOpt[String].getOrElse("")
    val payload  = Json
      .obj(
        "grant_type"    -> "http://auth0.com/oauth/grant-type/passwordless/otp",
        "client_id"     -> oauthConfig.clientId,
        "client_secret" -> oauthConfig.clientSecret,
        "username"      -> username,
        "otp"           -> code,
        "realm"         -> config.connection.name,
        "audience"      -> config.audience,
        "scope"         -> oauthConfig.scope
      )
      .applyOnWithOpt(config.audience) { case (obj, audience) =>
        obj ++ Json.obj("audience" -> audience)
      }
    env.Ws
      .url(oauthConfig.tokenUrl)
      .withRequestTimeout(10.seconds)
      .post(payload)
      .flatMap { response =>
        if (response.status == 200) {
          val token       = response.json
          val accessToken = token.select("access_token").asString
          val module      = GenericOauth2Module(oauthConfig)
          module.getUserInfo(accessToken, env.datastores.globalConfigDataStore.latest()).flatMap { profile =>
            val name                   = profile.select(oauthConfig.nameField).asOpt[String].getOrElse(username)
            val email                  = profile.select(oauthConfig.emailField).asOpt[String].getOrElse(username)
            val meta: Option[JsObject] = PrivateAppsUser
              .select(profile, oauthConfig.otoroshiDataField)
              .asOpt[String]
              .map(s => Json.parse(s))
              .orElse(
                Option(PrivateAppsUser.select(profile, oauthConfig.otoroshiDataField))
              )
              .map(_.asOpt[JsObject].getOrElse(Json.obj()))
            PrivateAppsUser(
              randomId = IdGenerator.token(64),
              name = name,
              email = email,
              profile = profile,
              realm = oauthConfig.cookieSuffix(ctx.route.legacy),
              token = token,
              authConfigId = oauthConfig.id,
              otoroshiData = oauthConfig.dataOverride
                .get(email)
                .map(v => oauthConfig.extraMetadata.deepMerge(v))
                .orElse(Some(oauthConfig.extraMetadata.deepMerge(meta.getOrElse(Json.obj())))),
              tags = oauthConfig.theTags,
              metadata = oauthConfig.metadata,
              location = oauthConfig.location
            )
              .validate(
                oauthConfig.userValidators,
                oauthConfig.remoteValidators,
                ctx.route.legacy,
                isRoute = true,
                oauthConfig
              ) flatMap {
              case Left(err)   =>
                logger.error(
                  s"login remote validation failed: ${err.display} - ${err.internal.map(_.stringify).getOrElse("")}"
                )
                BackendCallResponse(
                  NgPluginHttpResponse.fromResult(
                    Results.Unauthorized(
                      Json.obj(
                        "error"             -> "unauthorized",
                        "error_description" -> err.display
                      )
                    )
                  ),
                  None
                ).rightf
              case Right(user) => {
                user
                  .save(Duration(oauthConfig.sessionMaxAge, TimeUnit.SECONDS))
                  .map { _ =>
                    val host       = ctx.rawRequest.domain
                    val cookies    =
                      env.createPrivateSessionCookies(host, user.randomId, ctx.route.legacy, oauthConfig, user.some)
                    val sessionId  = cookies.head.value
                    val cookieName = "oto-papps-" + oauthConfig.cookieSuffix(ctx.route.legacy)
                    BackendCallResponse(
                      NgPluginHttpResponse.fromResult(
                        Results
                          .Ok(
                            Json.obj(
                              "user"                   -> profile,
                              "session_id_cookie_name" -> cookieName,
                              "session_id"             -> sessionId // can be passed as cookie value, or "Otoroshi-Token" header, or "pappsToken" query params
                            )
                          )
                          .withCookies(cookies: _*)
                      ),
                      None
                    ).right
                  }
              }
            }
          }
        } else {
          BackendCallResponse(
            NgPluginHttpResponse.fromResult(Results.Status(response.status).apply(response.json)),
            None
          ).rightf
        }
      }
  }

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config =
      ctx.cachedConfig(internalName)(Auth0PasswordlessAuthConfig.format).getOrElse(Auth0PasswordlessAuthConfig.default)
    env.proxyState.authModule(config.ref) match {
      case Some(authModule) if authModule.isInstanceOf[GenericOauth2ModuleConfig] => {
        val oauthConfig = authModule.asInstanceOf[GenericOauth2ModuleConfig]
        if (
          ctx.request.method == "POST" && ctx.request.hasBody && ctx.request.contentType.contains("application/json")
        ) {
          ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
            doEndFlow(ctx, Json.parse(bodyRaw.utf8String).asObject, config, oauthConfig)
          }
        } else {
          doEndFlow(ctx, JsObject(ctx.request.queryParams.mapValues(_.json)), config, oauthConfig)
        }
      }
      case None                                                                   =>
        BackendCallResponse(
          NgPluginHttpResponse.fromResult(Results.Unauthorized(Json.obj("error" -> "unauthorized"))),
          None
        ).rightf
    }
  }
}

class Auth0PasswordlessStartEndFlowEndpoints extends NgBackendCall {

  override def steps: Seq[NgStep]                          = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Auth0 Passwordless start/end flow endpoints"
  override def description: Option[String]                 = "This plugin provide endpoints to start and end a passwordless flow".some
  override def defaultConfigObject: Option[NgPluginConfig] = Some(Auth0PasswordlessAuthConfig.default)
  override def useDelegates: Boolean                       = false
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = Auth0PasswordlessAuthConfig.configFlow
  override def configSchema: Option[JsObject]              = Auth0PasswordlessAuthConfig.configSchema

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    if (ctx.request.path.endsWith("/start")) {
      env.scriptManager.getAnyScript[Auth0PasswordlessStartFlowEndpoint](
        s"cp:${classOf[Auth0PasswordlessStartFlowEndpoint].getName}"
      ) match {
        case Left(err)     =>
          BackendCallResponse(
            NgPluginHttpResponse.fromResult(Results.InternalServerError(Json.obj("error" -> "plugin not found"))),
            None
          ).rightf
        case Right(plugin) => plugin.callBackend(ctx, delegates)
      }
    } else if (ctx.request.path.endsWith("/end")) {
      env.scriptManager.getAnyScript[Auth0PasswordlessEndFlowEndpoint](
        s"cp:${classOf[Auth0PasswordlessEndFlowEndpoint].getName}"
      ) match {
        case Left(err)     =>
          BackendCallResponse(
            NgPluginHttpResponse.fromResult(Results.InternalServerError(Json.obj("error" -> "plugin not found"))),
            None
          ).rightf
        case Right(plugin) => plugin.callBackend(ctx, delegates)
      }
    } else {
      BackendCallResponse(
        NgPluginHttpResponse.fromResult(Results.NotFound(Json.obj("error" -> "not found"))),
        None
      ).rightf
    }
  }
}

class Auth0PasswordlessFlow extends NgBackendCall with NgAccessValidator {

  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess, NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Auth0 Passwordless start/end flow"
  override def description: Option[String]                 = "This plugin provide endpoints to start and end a passwordless flow".some
  override def defaultConfigObject: Option[NgPluginConfig] = Some(Auth0PasswordlessAuthConfig.default)
  override def useDelegates: Boolean                       = true
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = Auth0PasswordlessAuthConfig.configFlow
  override def configSchema: Option[JsObject]              = Auth0PasswordlessAuthConfig.configSchema

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    ctx.user match {
      case Some(_) => NgAccess.NgAllowed.vfuture
      case None    => {
        val config = ctx
          .cachedConfig(internalName)(Auth0PasswordlessAuthConfig.format)
          .getOrElse(Auth0PasswordlessAuthConfig.default)
        env.proxyState.authModule(config.ref) match {
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
                NgAccess.NgDenied(Results.Unauthorized(otoroshi.views.html.privateapps.passwordless(env))).vfuture
              }
            }
          }
        }
      }
    }
  }

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    if (ctx.request.method == "POST" && ctx.request.path.endsWith("/passwordless/start")) {
      env.scriptManager.getAnyScript[Auth0PasswordlessStartFlowEndpoint](
        s"cp:${classOf[Auth0PasswordlessStartFlowEndpoint].getName}"
      ) match {
        case Left(err)     =>
          BackendCallResponse(
            NgPluginHttpResponse.fromResult(Results.InternalServerError(Json.obj("error" -> "plugin not found"))),
            None
          ).rightf
        case Right(plugin) => plugin.callBackend(ctx, delegates)
      }
    } else if (ctx.request.method == "POST" && ctx.request.path.endsWith("/passwordless/end")) {
      env.scriptManager.getAnyScript[Auth0PasswordlessEndFlowEndpoint](
        s"cp:${classOf[Auth0PasswordlessEndFlowEndpoint].getName}"
      ) match {
        case Left(err)     =>
          BackendCallResponse(
            NgPluginHttpResponse.fromResult(Results.InternalServerError(Json.obj("error" -> "plugin not found"))),
            None
          ).rightf
        case Right(plugin) => plugin.callBackend(ctx, delegates)
      }
    } else {
      delegates()
    }
  }
}
