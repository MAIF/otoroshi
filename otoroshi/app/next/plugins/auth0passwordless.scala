package otoroshi.next.plugins

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import otoroshi.auth.{GenericOauth2Module, GenericOauth2ModuleConfig}
import otoroshi.env.Env
import otoroshi.models.PrivateAppsUser
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits._
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
  case object Sms extends Auth0PasswordlessConnectionKind { def name: String = "sms" }
  def apply(str: String): Auth0PasswordlessConnectionKind = str.toLowerCase() match {
    case "email" => Email
    case _ => Sms
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
    case _ => Code
  }
}

case class Auth0PasswordlessAuthConfig(ref: String, connection: Auth0PasswordlessConnectionKind, send: Auth0PasswordlessSendKind, audience: Option[String]) extends NgPluginConfig {
  override def json: JsValue = Auth0PasswordlessAuthConfig.format.writes(this)
}

object Auth0PasswordlessAuthConfig {
  val default = Auth0PasswordlessAuthConfig("", Auth0PasswordlessConnectionKind.Email, Auth0PasswordlessSendKind.Code, None)
  val format = new Format[Auth0PasswordlessAuthConfig] {
    override def writes(o: Auth0PasswordlessAuthConfig): JsValue = Json.obj(
      "ref" -> o.ref,
      "connection" -> o.connection.name,
      "send" -> o.send.name,
      "audience" -> o.audience.map(_.json).getOrElse(JsNull).asValue,
    )
    override def reads(json: JsValue): JsResult[Auth0PasswordlessAuthConfig] = Try {
      Auth0PasswordlessAuthConfig(
        ref = json.select("ref").asString,
        connection = Auth0PasswordlessConnectionKind.apply(json.select("connection").asOpt[String].getOrElse(Auth0PasswordlessAuthConfig.default.connection.name)),
        send = Auth0PasswordlessSendKind.apply(json.select("send").asOpt[String].getOrElse(Auth0PasswordlessAuthConfig.default.send.name)),
        audience = json.select("audience").asOpt[String]
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
  }
  val configFlow: Seq[String] = Seq("ref", "connection", "send", "audience")
  val configSchema: Option[JsObject] = Some(Json.obj(
    "ref" -> Json.obj(
      "type" -> "select",
      "label" -> s"Auth. module",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/api/auths",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    ),
    "connection" -> Json.obj(
      "type" -> "select",
      "label" -> s"Connection",
      "props" -> Json.obj(
        "options" -> Json.arr(
          Json.obj("label" -> "Email", "value" -> "email"),
          Json.obj("label" -> "SMS", "value" -> "sms")
        )
      ),
    ),
    "send" -> Json.obj(
      "type" -> "select",
      "label" -> s"Send",
      "props" -> Json.obj(
        "options" -> Json.arr(
          Json.obj("label" -> "Code", "value" -> "code"),
          Json.obj("label" -> "Link", "value" -> "link")
        )
      ),
    ),
    "audience" -> Json.obj(
      "type" -> "string",
      "label" -> "audience"
    )
  ))
}

class Auth0PasswordlessAuthValidator extends NgAccessValidator {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean            = true
  override def core: Boolean                     = true
  override def name: String                      = "Auth0 Passwordless login validator"
  override def description: Option[String]       = "This plugin ensure a user is present".some
  override def defaultConfigObject: Option[NgPluginConfig] = Some(Auth0PasswordlessAuthConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = Auth0PasswordlessAuthConfig.configFlow
  override def configSchema: Option[JsObject] = Auth0PasswordlessAuthConfig.configSchema

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    ctx.user match {
      case None => NgAccess.NgDenied(Results.Unauthorized(Json.obj("error" -> "unauthorized"))).vfuture
      case Some(user) => {
        val config = ctx.cachedConfig(internalName)(Auth0PasswordlessAuthConfig.format).getOrElse(Auth0PasswordlessAuthConfig.default)
        env.proxyState.authModule(config.ref) match {
          case None => NgAccess.NgDenied(Results.Unauthorized(Json.obj("error" -> "unauthorized"))).vfuture
          case Some(authModule) => {
            if (user.authConfigId == authModule.id) {
              NgAccess.NgAllowed.vfuture
            } else {
              NgAccess.NgDenied(Results.Unauthorized(Json.obj("error" -> "unauthorized"))).vfuture
            }
          }
        }
      }
    }
  }
}

class Auth0PasswordlessStartFlowEndpoint extends NgBackendCall {

  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean            = true
  override def core: Boolean                     = true
  override def name: String                      = "Auth0 Passwordless start flow endpoint"
  override def description: Option[String]       = "This plugin provide an endpoint to start a passwordless flow".some
  override def defaultConfigObject: Option[NgPluginConfig] = Some(Auth0PasswordlessAuthConfig.default)
  override def useDelegates: Boolean = false
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = Auth0PasswordlessAuthConfig.configFlow
  override def configSchema: Option[JsObject] = Auth0PasswordlessAuthConfig.configSchema

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(Auth0PasswordlessAuthConfig.format).getOrElse(Auth0PasswordlessAuthConfig.default)
    env.proxyState.authModule(config.ref) match {
      case Some(authModule) if authModule.isInstanceOf[GenericOauth2ModuleConfig] => {
        val oautConfig = authModule.asInstanceOf[GenericOauth2ModuleConfig]
        val domain = Uri(oautConfig.authorizeUrl).authority.host.toString()
        val state = IdGenerator.uuid
        val payload = Json.obj(
          "client_id" -> oautConfig.clientId,
          "client_secret" -> oautConfig.clientSecret, // For Regular Web Applications
          "connection" -> config.connection.name,
          "send" -> config.send.name,
          "authParams" -> Json.obj(
            "scope" -> "openid",
            "state" -> state
          )
        )
        .applyOn { obj =>
          config.connection match {
            case Auth0PasswordlessConnectionKind.Email =>
              val email = ctx.request.queryParam("email").getOrElse("")
              obj ++ Json.obj("email" -> email)
            case Auth0PasswordlessConnectionKind.Sms =>
              val phoneNumber = ctx.request.queryParam("phone_number").getOrElse("")
              obj ++ Json.obj("phone_number" -> phoneNumber)
          }
        }
        env.Ws.url(s"https://${domain}/passwordless/start")
          .withRequestTimeout(10.seconds)
          .post(payload)
          .map { response =>
            if (response.status == 200) {
              val username = ctx.request.queryParam("email").orElse(ctx.request.queryParam("phone_number")).getOrElse("")
              BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(Json.obj(
                "auth0_response" -> response.json,
                "state" -> state,
                "username" -> username,
              ))), None).right
            } else {
              BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Status(response.status).apply(response.json)), None).right
            }
          }
      }
      case None => BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Unauthorized(Json.obj("error" -> "unauthorized"))), None).rightf
    }
  }

}

class Auth0PasswordlessEndFlowEndpoint extends NgBackendCall {

  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Authentication)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def name: String = "Auth0 Passwordless end flow endpoint"
  override def description: Option[String] = "This plugin provide an endpoint to end a passwordless flow".some
  override def defaultConfigObject: Option[NgPluginConfig] = Some(Auth0PasswordlessAuthConfig.default)
  override def useDelegates: Boolean = false
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = Auth0PasswordlessAuthConfig.configFlow
  override def configSchema: Option[JsObject] = Auth0PasswordlessAuthConfig.configSchema

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(Auth0PasswordlessAuthConfig.format).getOrElse(Auth0PasswordlessAuthConfig.default)
    env.proxyState.authModule(config.ref) match {
      case Some(authModule) if authModule.isInstanceOf[GenericOauth2ModuleConfig] => {
        val oautConfig = authModule.asInstanceOf[GenericOauth2ModuleConfig]
        val username = ctx.request.queryParam("username").getOrElse("")
        val code = ctx.request.queryParam("code").getOrElse("")
        val payload = Json.obj(
            "grant_type" -> "http://auth0.com/oauth/grant-type/passwordless/otp",
            "client_id" -> oautConfig.clientId,
            "client_secret" -> oautConfig.clientSecret,
            "username" -> username,
            "otp" -> code,
            "realm" -> config.connection.name,
            "audience" -> config.audience,
            "scope" -> oautConfig.scope
          )
          .applyOnWithOpt(config.audience) {
            case (obj, audience) => obj ++ Json.obj("audience" -> audience)
          }
        env.Ws.url(oautConfig.tokenUrl)
          .withRequestTimeout(10.seconds)
          .post(payload)
          .flatMap { response =>
            if (response.status == 200) {

              val token = response.json
              val accessToken = token.select("access_token").asString
              val module = GenericOauth2Module(oautConfig)
              module.getUserInfo(accessToken, env.datastores.globalConfigDataStore.latest()).flatMap { profile =>
                val name = profile.select(oautConfig.nameField).asOpt[String].getOrElse(username)
                val email = profile.select(oautConfig.emailField).asOpt[String].getOrElse(username)
                val meta: Option[JsObject] = PrivateAppsUser
                  .select(profile, oautConfig.otoroshiDataField)
                  .asOpt[String]
                  .map(s => Json.parse(s))
                  .orElse(
                    Option(PrivateAppsUser.select(profile, oautConfig.otoroshiDataField))
                  )
                  .map(_.asOpt[JsObject].getOrElse(Json.obj()))
                PrivateAppsUser(
                  randomId = IdGenerator.token(64),
                  name = name,
                  email = email,
                  profile = profile,
                  realm = oautConfig.cookieSuffix(ctx.route.legacy),
                  token = token,
                  authConfigId = oautConfig.id,
                  otoroshiData = oautConfig.dataOverride
                    .get(email)
                    .map(v => oautConfig.extraMetadata.deepMerge(v))
                    .orElse(Some(oautConfig.extraMetadata.deepMerge(meta.getOrElse(Json.obj())))),
                  tags = oautConfig.theTags,
                  metadata = oautConfig.metadata,
                  location = oautConfig.location
                )
                  .validate(oautConfig.userValidators) match {
                  case Left(err) =>
                    BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Unauthorized(Json.obj(
                      "error" -> "unauthorized",
                      "error_description" -> err
                    ))), None).rightf
                  case Right(user) => {
                    user
                      .save(Duration(oautConfig.sessionMaxAge, TimeUnit.SECONDS))
                      .map { _ =>
                        val host = ctx.request.host
                        val cookies = env.createPrivateSessionCookies(host, user.randomId, ctx.route.legacy, oautConfig, user.some)
                        val sessionId = cookies.head.value
                        val cookieName = "oto-papps-" + oautConfig.cookieSuffix(ctx.route.legacy)
                        BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(Json.obj(
                          "user" -> profile,
                          "session_id_cookie_name" -> cookieName,
                          "session_id" -> sessionId, // can be passed as cookie value, or Otoroshi-Token header, or pappsToken query params
                        )).withCookies(cookies: _*)), None).right
                      }
                  }
                }
              }
            } else {
              BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Status(response.status).apply(response.json)), None).rightf
            }
          }
      }
      case None => BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Unauthorized(Json.obj("error" -> "unauthorized"))), None).rightf
    }
  }
}

