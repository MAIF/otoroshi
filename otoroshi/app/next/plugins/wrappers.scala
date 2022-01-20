package otoroshi.next.plugins

import akka.Done
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._

import scala.concurrent.{ExecutionContext, Future}
import otoroshi.script.PreRouting
import otoroshi.next.proxy.ProxyEngineError
import play.api.mvc.Results
import otoroshi.next.plugins.api._
import play.api.libs.json.Json
import otoroshi.script._
import play.api.libs.json.JsValue
import play.api.mvc.Result

class PreRoutingWrapper extends NgPreRouting {
  
  override def name: String = "Pre-routing plugin wrapper"
  override def description: Option[String] = "Wraps an old pre-routing plugin for the new router. The configuration is the one for the wrapped plugin. If the wrapped is supposed to listen to otoroshi events, then it won't work".some
  
  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    val pluginId = ctx.config.select("plugin").as[String]
    env.scriptManager.getAnyScript[PreRouting](pluginId) match {
      case Left(err) => NgPreRoutingErrorWithResult(Results.InternalServerError(Json.obj("error" -> "plugin not found", "plugin" -> pluginId))).left.vfuture
      case Right(plugin) => {
        val octx = PreRoutingContext(
          ctx.snowflake,
          0,
          ctx.request,
          ctx.route.serviceDescriptor,
          plugin.configRoot.map(r => Json.obj(r -> ctx.config)).getOrElse(ctx.config).as[JsValue],
          ctx.attrs,
          env.datastores.globalConfigDataStore.latest().plugins.config.select(plugin.configRoot.getOrElse("--")).asOpt[JsValue].getOrElse(Json.obj())
        )
        plugin.preRoute(octx)
          .map(_ => Done.right)
          .recover {
            case PreRoutingError(body, code, contentType, headers) => NgPreRoutingErrorWithResult(Results.Status(code)(body).as(contentType).withHeaders(headers.toSeq: _*)).left
            case PreRoutingErrorWithResult(r) => NgPreRoutingErrorWithResult(r).left
            case t: Throwable => NgPreRoutingErrorWithResult(Results.InternalServerError(Json.obj("error" -> t.getMessage()))).left
          }
      }
    }
  }
}

class AccessValidatorWrapper extends NgAccessValidator {

  override def name: String = "Access validator plugin wrapper"
  override def description: Option[String] = "Wraps an old access validator plugin for the new router. The configuration is the one for the wrapped plugin. If the wrapped is supposed to listen to otoroshi events, then it won't work".some
  
  def newContextToOld(ctx: NgAccessContext, plugin: AccessValidator): AccessContext = {
    AccessContext(
      snowflake = ctx.snowflake,
      index = 0,
      request = ctx.request,
      config = plugin.configRoot.map(r => Json.obj(r -> ctx.config)).getOrElse(ctx.config).as[JsValue],
      attrs = ctx.attrs,
      descriptor = ctx.route.serviceDescriptor,
      user = ctx.user,
      apikey = ctx.apikey,
      globalConfig = ctx.globalConfig
    )
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val pluginId = ctx.config.select("plugin").as[String]
    env.scriptManager.getAnyScript[AccessValidator](pluginId) match {
      case Left(err) => NgAccess.NgDenied(Results.InternalServerError(Json.obj("error" -> "plugin not found", "plugin" -> pluginId))).vfuture
      case Right(plugin) => {
        val octx = newContextToOld(ctx, plugin)
        plugin.access(octx).map {
          case Allowed => NgAccess.NgAllowed
          case Denied(r) => NgAccess.NgDenied(r)
        }
      }
    }
  }
}

class RequestSinkWrapper extends NgRequestSink {

  override def name: String = "Request sink plugin wrapper"
  override def description: Option[String] = "Wraps an old request sink plugin for the new router. The configuration is the one for the wrapped plugin. If the wrapped is supposed to listen to otoroshi events, then it won't work".some
  
  def newContextToOld(ctx: NgRequestSinkContext, plugin: RequestSink): RequestSinkContext = {
    RequestSinkContext(
      snowflake = ctx.snowflake,
      index = 0,
      request = ctx.request,
      config = plugin.configRoot.map(r => Json.obj(r -> ctx.config)).getOrElse(ctx.config).as[JsValue],
      attrs = ctx.attrs,
      origin = ctx.origin match {
        case NgRequestOrigin.NgErrorHandler => RequestOrigin.ErrorHandler
        case NgRequestOrigin.NgReverseProxy => RequestOrigin.ReverseProxy
      },
      status = ctx.status,
      message = ctx.message,
      body = ctx.body
    )
  }
  override def matches(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean = {
    val pluginId = ctx.config.select("plugin").as[String]
    env.scriptManager.getAnyScript[RequestSink](pluginId) match {
      case Left(err) => false
      case Right(plugin) => {
        val octx = newContextToOld(ctx, plugin)
        plugin.matches(octx)
      }
    }
  }
  override def handle(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    val pluginId = ctx.config.select("plugin").as[String]
    env.scriptManager.getAnyScript[RequestSink](pluginId) match {
      case Left(err) => Results.InternalServerError(Json.obj("error" -> "plugin not found", "plugin" -> pluginId)).vfuture
      case Right(plugin) => {
        val octx = newContextToOld(ctx, plugin)
        plugin.handle(octx)
      }
    }
  }
}

class RequestTransformerWrapper extends NgRequestTransformer {}

class CompositeWrapper extends NgPreRouting with NgAccessValidator with NgRequestTransformer {}