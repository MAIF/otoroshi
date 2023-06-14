package otoroshi.auth

import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.BodyHelper
import otoroshi.next.plugins.api.NgCachedConfigContext
import otoroshi.next.utils.JsonHelpers
import otoroshi.security.IdGenerator
import otoroshi.utils.JsonPathValidator
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm.WasmUtils
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object WasmAuthModuleConfig {
  val defaultConfig = WasmAuthModuleConfig(
    location = EntityLocation.default,
    id = IdGenerator.namedId("auth_mod", IdGenerator.uuid),
    name = "New wasm auth. module",
    description = "New wasm auth. module",
    tags = Seq.empty,
    metadata = Map.empty,
    sessionMaxAge = 86400,
    clientSideSessionEnabled = false,
    sessionCookieValues = SessionCookieValues(true, true),
    userValidators = Seq.empty,
    wasmRef = None
  )
  val format        = new Format[WasmAuthModuleConfig] {
    override def writes(o: WasmAuthModuleConfig): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "type"                     -> "wasm",
      "id"                       -> o.id,
      "name"                     -> o.name,
      "description"              -> o.description,
      "tags"                     -> o.tags,
      "metadata"                 -> o.metadata,
      "sessionMaxAge"            -> o.sessionMaxAge,
      "clientSideSessionEnabled" -> o.clientSideSessionEnabled,
      "sessionCookieValues"      -> SessionCookieValues.fmt.writes(o.sessionCookieValues),
      "userValidators"           -> JsArray(o.userValidators.map(_.json)),
      "wasmRef"                  -> o.wasmRef.map(JsString.apply).getOrElse(JsNull).asValue
    )
    override def reads(json: JsValue): JsResult[WasmAuthModuleConfig] = Try {
      WasmAuthModuleConfig(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").asOpt[String].getOrElse("--"),
        clientSideSessionEnabled = (json \ "clientSideSessionEnabled").asOpt[Boolean].getOrElse(true),
        sessionMaxAge = (json \ "sessionMaxAge").asOpt[Int].getOrElse(86400),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        sessionCookieValues =
          (json \ "sessionCookieValues").asOpt(SessionCookieValues.fmt).getOrElse(SessionCookieValues()),
        userValidators = (json \ "userValidators")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => JsonPathValidator.format.reads(v).asOpt))
          .getOrElse(Seq.empty),
        wasmRef = json.select("wasmRef").asOpt[String].filter(_.trim.nonEmpty)
      )
    } match {
      case Failure(e)   => JsError(e.getMessage)
      case Success(mod) => JsSuccess(mod)
    }
  }
}

case class WasmAuthModuleConfig(
    location: EntityLocation,
    id: String,
    name: String,
    description: String,
    tags: Seq[String],
    metadata: Map[String, String],
    sessionMaxAge: Int,
    clientSideSessionEnabled: Boolean,
    sessionCookieValues: SessionCookieValues,
    userValidators: Seq[JsonPathValidator] = Seq.empty,
    wasmRef: Option[String]
) extends AuthModuleConfig {

  override def authModule(config: GlobalConfig): AuthModule = new WasmAuthModule(this)

  override def form: Option[Form]                                               = None
  override def cookieSuffix(desc: ServiceDescriptor): String                    = s"wasm-auth-$id"
  override def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.authConfigsDataStore.set(this)
  override def withLocation(location: EntityLocation): AuthModuleConfig         = copy(location = location)
  override def asJson: JsValue                                                  = WasmAuthModuleConfig.format.writes(this)
  override def _fmt()(implicit env: Env): Format[AuthModuleConfig]              = AuthModuleConfig._fmt(env)
  override def `type`: String                                                   = "wasm"
  override def humanName: String                                                = "Wasm auth. module provider"
  override def desc: String                                                     = description
  override def theName: String                                                  = name
  override def theDescription: String                                           = description
  override def theTags: Seq[String]                                             = tags
  override def theMetadata: Map[String, String]                                 = metadata
}

case class WasmAuthModuleContext(config: JsValue, route: NgRoute, idx: Int = 0) extends NgCachedConfigContext

object WasmAuthModule {
  val logger = Logger("otoroshi-wasm-auth-module")
}

class WasmAuthModule(val authConfig: WasmAuthModuleConfig) extends AuthModule {

  def this() = this(WasmAuthModuleConfig.defaultConfig)

  override def paLoginPage(
      request: RequestHeader,
      config: GlobalConfig,
      descriptor: ServiceDescriptor,
      isRoute: Boolean
  )(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    authConfig.wasmRef.flatMap(env.proxyState.wasmPlugin).map { plugin =>
      val route = NgRoute.fromServiceDescriptor(descriptor, false)
      val input = Json.obj(
        "request"       -> JsonHelpers.requestToJson(request),
        "global_config" -> config.json,
        "service"       -> descriptor.json,
        "route"         -> route.json,
        "is_route"      -> isRoute
      )
      val ctx   = WasmAuthModuleContext(authConfig.json, route)
      WasmUtils.execute(plugin.config, "pa_login_page", input, None, None).map {
        case Left(err)     => Results.InternalServerError(err)
        case Right(output) => {
          val response    =
            try {
              Json.parse(output)
            } catch {
              case e: Exception =>
                WasmAuthModule.logger.error("error during json parsing", e)
                Json.obj()
            }
          val body        = BodyHelper.extractBodyFrom(response)
          val headers     = response
            .select("headers")
            .asOpt[Map[String, String]]
            .getOrElse(Map("Content-Type" -> "text/html"))
          val contentType = headers.getIgnoreCase("Content-Type").getOrElse("text/html")
          Results
            .Status(response.select("status").asOpt[Int].getOrElse(200))
            .apply(body)
            .withHeaders(headers.toSeq: _*)
            .as(contentType)
        }
      }
    } getOrElse {
      Results
        .InternalServerError(
          Json.obj("error" -> "internal_server_error", "error_description" -> "wasm module not found")
        )
        .vfuture
    }
  }

  override def paLogout(
      request: RequestHeader,
      user: Option[PrivateAppsUser],
      config: GlobalConfig,
      descriptor: ServiceDescriptor
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, Option[String]]] = {
    authConfig.wasmRef.flatMap(env.proxyState.wasmPlugin).map { plugin =>
      val route = NgRoute.fromServiceDescriptor(descriptor, false)
      val input = Json.obj(
        "request"       -> JsonHelpers.requestToJson(request),
        "global_config" -> config.json,
        "service"       -> descriptor.json,
        "route"         -> route.json,
        "user"          -> user.map(_.json).getOrElse(JsNull).asValue
      )
      val ctx   = WasmAuthModuleContext(authConfig.json, route)
      WasmUtils.execute(plugin.config, "pa_logout", input, None, None).map {
        case Left(err)     => Results.InternalServerError(err).left
        case Right(output) => {
          val response  =
            try {
              Json.parse(output)
            } catch {
              case e: Exception =>
                WasmAuthModule.logger.error("error during json parsing", e)
                Json.obj()
            }
          val logoutUrl = response.select("logout_url").asOpt[String]
          logoutUrl.right
        }
      }
    } getOrElse {
      Results
        .InternalServerError(
          Json.obj("error" -> "internal_server_error", "error_description" -> "wasm module not found")
        )
        .left
        .vfuture
    }
  }

  override def paCallback(request: Request[AnyContent], config: GlobalConfig, descriptor: ServiceDescriptor)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[String, PrivateAppsUser]] = {
    authConfig.wasmRef.flatMap(env.proxyState.wasmPlugin).map { plugin =>
      val route = NgRoute.fromServiceDescriptor(descriptor, false)
      val input = Json.obj(
        "request"       -> JsonHelpers.requestToJson(request),
        "global_config" -> config.json,
        "service"       -> descriptor.json,
        "route"         -> route.json
      )
      val ctx   = WasmAuthModuleContext(authConfig.json, route)
      WasmUtils.execute(plugin.config, "pa_callback", input, None, None).map {
        case Left(err)     => err.stringify.left
        case Right(output) => {
          val response = {
            try {
              Json.parse(output)
            } catch {
              case e: Exception =>
                WasmAuthModule.logger.error("error during json parsing", e)
                Json.obj()
            }
          }
          PrivateAppsUser.fmt.reads(response) match {
            case JsError(errors)    => errors.toString().left
            case JsSuccess(user, _) => user.validate(authConfig.userValidators)
          }
        }
      }
    } getOrElse {
      "wasm module not found".left.vfuture
    }
  }

  override def boLoginPage(request: RequestHeader, config: GlobalConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Result] = {
    authConfig.wasmRef.flatMap(env.proxyState.wasmPlugin).map { plugin =>
      val input = Json.obj(
        "request"       -> JsonHelpers.requestToJson(request),
        "global_config" -> config.json
      )
      val ctx   = WasmAuthModuleContext(authConfig.json, NgRoute.empty)
      WasmUtils.execute(plugin.config, "bo_login_page", input, None, None).map {
        case Left(err)     => Results.InternalServerError(err)
        case Right(output) => {
          val response    =
            try {
              Json.parse(output)
            } catch {
              case e: Exception =>
                WasmAuthModule.logger.error("error during json parsing", e)
                Json.obj()
            }
          val body        = BodyHelper.extractBodyFrom(response)
          val headers     = response
            .select("headers")
            .asOpt[Map[String, String]]
            .getOrElse(Map("Content-Type" -> "text/html"))
          val contentType = headers.getIgnoreCase("Content-Type").getOrElse("text/html")
          Results
            .Status(response.select("status").asOpt[Int].getOrElse(200))
            .apply(body)
            .withHeaders(headers.toSeq: _*)
            .as(contentType)
        }
      }
    } getOrElse {
      Results
        .InternalServerError(
          Json.obj("error" -> "internal_server_error", "error_description" -> "wasm module not found")
        )
        .vfuture
    }
  }

  override def boLogout(request: RequestHeader, user: BackOfficeUser, config: GlobalConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[Result, Option[String]]] = {
    authConfig.wasmRef.flatMap(env.proxyState.wasmPlugin).map { plugin =>
      val input = Json.obj(
        "request"       -> JsonHelpers.requestToJson(request),
        "global_config" -> config.json,
        "user"          -> user.json
      )
      val ctx   = WasmAuthModuleContext(authConfig.json, NgRoute.empty)
      WasmUtils.execute(plugin.config, "bo_logout", input, None, None).map {
        case Left(err)     => Results.InternalServerError(err).left
        case Right(output) => {
          val response  =
            try {
              Json.parse(output)
            } catch {
              case e: Exception =>
                WasmAuthModule.logger.error("error during json parsing", e)
                Json.obj()
            }
          val logoutUrl = response.select("logout_url").asOpt[String]
          logoutUrl.right
        }
      }
    } getOrElse {
      Results
        .InternalServerError(
          Json.obj("error" -> "internal_server_error", "error_description" -> "wasm module not found")
        )
        .left
        .vfuture
    }
  }

  override def boCallback(request: Request[AnyContent], config: GlobalConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[String, BackOfficeUser]] = {
    authConfig.wasmRef.flatMap(env.proxyState.wasmPlugin).map { plugin =>
      val input = Json.obj(
        "request"       -> JsonHelpers.requestToJson(request),
        "global_config" -> config.json
      )
      val ctx   = WasmAuthModuleContext(authConfig.json, NgRoute.empty)
      WasmUtils.execute(plugin.config, "bo_callback", input, None, None).map {
        case Left(err)     => err.stringify.left
        case Right(output) => {
          val response = {
            try {
              Json.parse(output)
            } catch {
              case e: Exception =>
                WasmAuthModule.logger.error("error during json parsing", e)
                Json.obj()
            }
          }
          BackOfficeUser.fmt.reads(response) match {
            case JsError(errors)    => errors.toString().left
            case JsSuccess(user, _) => user.validate(authConfig.userValidators)
          }
        }
      }
    } getOrElse {
      "wasm module not found".left.vfuture
    }
  }
}
