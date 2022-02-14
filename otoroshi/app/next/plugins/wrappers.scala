package otoroshi.next.plugins.wrappers

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.next.models.NgTarget
import otoroshi.next.plugins.NgOtoroshiChallengeKeys
import otoroshi.next.plugins.api._
import otoroshi.script._
import otoroshi.security.{IdGenerator, OtoroshiClaim}
import otoroshi.utils.http.WSCookieWithSameSite
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.Json
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}

class PreRoutingWrapper extends NgPreRouting {

  override def name: String                = "Pre-routing plugin wrapper"
  override def description: Option[String] =
    "Wraps an old pre-routing plugin for the new router. The configuration is the one for the wrapped plugin.".some
  override def isPreRouteAsync: Boolean    = true

  override def preRoute(
      ctx: NgPreRoutingContext
  )(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    val pluginId = ctx.config.select("plugin").as[String]
    env.scriptManager.getAnyScript[PreRouting](pluginId) match {
      case Left(err)     =>
        NgPreRoutingErrorWithResult(
          Results.InternalServerError(Json.obj("error" -> "plugin not found", "plugin" -> pluginId))
        ).left.vfuture
      case Right(plugin) => {
        val octx = PreRoutingContext(
          ctx.snowflake,
          0,
          ctx.request,
          ctx.route.serviceDescriptor,
          ctx.config,
          ctx.attrs,
          ctx.globalConfig
        )
        plugin
          .preRoute(octx)
          .map(_ => Done.right)
          .recover {
            case PreRoutingError(body, code, contentType, headers) =>
              NgPreRoutingErrorWithResult(
                Results.Status(code)(body).as(contentType).withHeaders(headers.toSeq: _*)
              ).left
            case PreRoutingErrorWithResult(r)                      => NgPreRoutingErrorWithResult(r).left
            case t: Throwable                                      =>
              NgPreRoutingErrorWithResult(Results.InternalServerError(Json.obj("error" -> t.getMessage()))).left
          }
      }
    }
  }
}

class AccessValidatorWrapper extends NgAccessValidator {

  override def name: String                = "Access validator plugin wrapper"
  override def description: Option[String] =
    "Wraps an old access validator plugin for the new router. The configuration is the one for the wrapped plugin.".some
  override def isAccessAsync: Boolean      = true

  def newContextToOld(ctx: NgAccessContext, plugin: AccessValidator): AccessContext = {
    AccessContext(
      snowflake = ctx.snowflake,
      index = 0,
      request = ctx.request,
      config = ctx.config,
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
      case Left(err)     =>
        NgAccess
          .NgDenied(Results.InternalServerError(Json.obj("error" -> "plugin not found", "plugin" -> pluginId)))
          .vfuture
      case Right(plugin) => {
        val octx = newContextToOld(ctx, plugin)
        plugin.access(octx).map {
          case Allowed   => NgAccess.NgAllowed
          case Denied(r) => NgAccess.NgDenied(r)
        }
      }
    }
  }
}

class RequestSinkWrapper extends NgRequestSink {

  override def isSinkAsync: Boolean        = true
  override def name: String                = "Request sink plugin wrapper"
  override def description: Option[String] =
    "Wraps an old request sink plugin for the new router. The configuration is the one for the wrapped plugin.".some

  def newContextToOld(ctx: NgRequestSinkContext, plugin: RequestSink): RequestSinkContext = {
    RequestSinkContext(
      snowflake = ctx.snowflake,
      index = 0,
      request = ctx.request,
      config = ctx.config,
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
      case Left(err)     => false
      case Right(plugin) => {
        val octx = newContextToOld(ctx, plugin)
        plugin.matches(octx)
      }
    }
  }
  override def handle(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    val pluginId = ctx.config.select("plugin").as[String]
    env.scriptManager.getAnyScript[RequestSink](pluginId) match {
      case Left(err)     =>
        Results.InternalServerError(Json.obj("error" -> "plugin not found", "plugin" -> pluginId)).vfuture
      case Right(plugin) => {
        val octx = newContextToOld(ctx, plugin)
        plugin.handle(octx)
      }
    }
  }
}

class RequestTransformerWrapper extends NgRequestTransformer {

  override def usesCallbacks: Boolean            = true
  override def transformsRequest: Boolean        = true
  override def transformsResponse: Boolean       = true
  override def transformsError: Boolean          = true
  override def isTransformRequestAsync: Boolean  = true
  override def isTransformResponseAsync: Boolean = true
  override def name: String                      = "Request transformer plugin wrapper"
  override def description: Option[String]       =
    "Wraps an old request transformer plugin for the new router. The configuration is the one for the wrapped plugin.".some

  override def beforeRequest(
      ctx: NgBeforeRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    val pluginId = ctx.config.select("plugin").as[String]
    env.scriptManager.getAnyScript[RequestTransformer](pluginId) match {
      case Left(err)     => ().vfuture
      case Right(plugin) => {
        val octx = BeforeRequestContext(
          index = 0,
          snowflake = ctx.snowflake,
          descriptor = ctx.route.serviceDescriptor,
          request = ctx.request,
          config = ctx.config,
          attrs = ctx.attrs,
          globalConfig = ctx.globalConfig
        )
        plugin.beforeRequest(octx)
      }
    }
  }

  override def afterRequest(
      ctx: NgAfterRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    val pluginId = ctx.config.select("plugin").as[String]
    env.scriptManager.getAnyScript[RequestTransformer](pluginId) match {
      case Left(err)     => ().vfuture
      case Right(plugin) => {
        val octx = AfterRequestContext(
          index = 0,
          snowflake = ctx.snowflake,
          descriptor = ctx.route.serviceDescriptor,
          request = ctx.request,
          config = ctx.config,
          attrs = ctx.attrs,
          globalConfig = ctx.globalConfig
        )
        plugin.afterRequest(octx)
      }
    }
  }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val pluginId = ctx.config.select("plugin").as[String]
    env.scriptManager.getAnyScript[RequestTransformer](pluginId) match {
      case Left(err)     =>
        Results.InternalServerError(Json.obj("error" -> "plugin not found", "plugin" -> pluginId)).left.vfuture
      case Right(plugin) => {
        val bctx    = TransformerRequestBodyContext(
          rawRequest = HttpRequest(
            url = ctx.rawRequest.url,
            method = ctx.rawRequest.method,
            headers = ctx.rawRequest.headers,
            cookies = ctx.rawRequest.cookies,
            version = ctx.rawRequest.version,
            clientCertificateChain = ctx.rawRequest.clientCertificateChain,
            target =
              ctx.attrs.get(otoroshi.next.plugins.Keys.BackendKey).orElse(ctx.rawRequest.backend).map(_.toTarget),
            claims = ctx.attrs
              .get(NgOtoroshiChallengeKeys.ClaimKey)
              .getOrElse(
                OtoroshiClaim(
                  iss = env.Headers.OtoroshiIssuer,
                  sub = env.Headers.OtoroshiIssuer,
                  aud = ctx.route.name,
                  exp = DateTime.now().plus(30000).toDate.getTime,
                  iat = DateTime.now().toDate.getTime,
                  jti = IdGenerator.uuid
                )
              ),
            body = () => ctx.rawRequest.body
          ),
          otoroshiRequest = HttpRequest(
            url = ctx.otoroshiRequest.url,
            method = ctx.otoroshiRequest.method,
            headers = ctx.otoroshiRequest.headers,
            cookies = ctx.otoroshiRequest.cookies,
            version = ctx.otoroshiRequest.version,
            clientCertificateChain = ctx.otoroshiRequest.clientCertificateChain,
            target =
              ctx.attrs.get(otoroshi.next.plugins.Keys.BackendKey).orElse(ctx.rawRequest.backend).map(_.toTarget),
            claims = ctx.attrs
              .get(NgOtoroshiChallengeKeys.ClaimKey)
              .getOrElse(
                OtoroshiClaim(
                  iss = env.Headers.OtoroshiIssuer,
                  sub = env.Headers.OtoroshiIssuer,
                  aud = ctx.route.name,
                  exp = DateTime.now().plus(30000).toDate.getTime,
                  iat = DateTime.now().toDate.getTime,
                  jti = IdGenerator.uuid
                )
              ),
            body = () => ctx.otoroshiRequest.body
          ),
          body = ctx.rawRequest.body,
          apikey = ctx.apikey,
          user = ctx.user,
          index = 0,
          snowflake = ctx.snowflake,
          descriptor = ctx.route.serviceDescriptor,
          request = ctx.request,
          config = ctx.config,
          attrs = ctx.attrs,
          globalConfig = ctx.globalConfig
        )
        val newBody = plugin.transformRequestBodyWithCtx(bctx)
        val octx    = TransformerRequestContext(
          rawRequest = HttpRequest(
            url = ctx.rawRequest.url,
            method = ctx.rawRequest.method,
            headers = ctx.rawRequest.headers,
            cookies = ctx.rawRequest.cookies,
            version = ctx.rawRequest.version,
            clientCertificateChain = ctx.rawRequest.clientCertificateChain,
            target =
              ctx.attrs.get(otoroshi.next.plugins.Keys.BackendKey).orElse(ctx.otoroshiRequest.backend).map(_.toTarget),
            claims = ctx.attrs
              .get(NgOtoroshiChallengeKeys.ClaimKey)
              .getOrElse(
                OtoroshiClaim(
                  iss = env.Headers.OtoroshiIssuer,
                  sub = env.Headers.OtoroshiIssuer,
                  aud = ctx.route.name,
                  exp = DateTime.now().plus(30000).toDate.getTime,
                  iat = DateTime.now().toDate.getTime,
                  jti = IdGenerator.uuid
                )
              ),
            body = () => newBody
          ),
          otoroshiRequest = HttpRequest(
            url = ctx.otoroshiRequest.url,
            method = ctx.otoroshiRequest.method,
            headers = ctx.otoroshiRequest.headers,
            cookies = ctx.otoroshiRequest.cookies,
            version = ctx.otoroshiRequest.version,
            clientCertificateChain = ctx.otoroshiRequest.clientCertificateChain,
            target =
              ctx.attrs.get(otoroshi.next.plugins.Keys.BackendKey).orElse(ctx.otoroshiRequest.backend).map(_.toTarget),
            claims = ctx.attrs
              .get(NgOtoroshiChallengeKeys.ClaimKey)
              .getOrElse(
                OtoroshiClaim(
                  iss = env.Headers.OtoroshiIssuer,
                  sub = env.Headers.OtoroshiIssuer,
                  aud = ctx.route.name,
                  exp = DateTime.now().plus(30000).toDate.getTime,
                  iat = DateTime.now().toDate.getTime,
                  jti = IdGenerator.uuid
                )
              ),
            body = () => newBody
          ),
          apikey = ctx.apikey,
          user = ctx.user,
          index = 0,
          snowflake = ctx.snowflake,
          descriptor = ctx.route.serviceDescriptor,
          request = ctx.request,
          config = ctx.config,
          attrs = ctx.attrs,
          globalConfig = ctx.globalConfig
        )
        plugin.transformRequestWithCtx(octx).map {
          case Left(r)    => Left(r)
          case Right(req) =>
            Right(
              NgPluginHttpRequest(
                url = req.url,
                method = req.method,
                headers = req.headers,
                cookies = req.cookies,
                version = req.version,
                clientCertificateChain = req.clientCertificateChain,
                body = newBody,
                backend = req.target.map(t => NgTarget.fromTarget(t))
              )
            )
        }
      }
    }
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val pluginId = ctx.config.select("plugin").as[String]
    env.scriptManager.getAnyScript[RequestTransformer](pluginId) match {
      case Left(err)     =>
        Results.InternalServerError(Json.obj("error" -> "plugin not found", "plugin" -> pluginId)).left.vfuture
      case Right(plugin) => {
        val bctx    = TransformerResponseBodyContext(
          rawResponse = HttpResponse(
            status = ctx.rawResponse.status,
            headers = ctx.rawResponse.headers,
            cookies = ctx.rawResponse.cookies,
            body = () => ctx.rawResponse.body
          ),
          otoroshiResponse = HttpResponse(
            status = ctx.otoroshiResponse.status,
            headers = ctx.otoroshiResponse.headers,
            cookies = ctx.otoroshiResponse.cookies,
            body = () => ctx.otoroshiResponse.body
          ),
          body = ctx.rawResponse.body,
          apikey = ctx.apikey,
          user = ctx.user,
          index = 0,
          snowflake = ctx.snowflake,
          descriptor = ctx.route.serviceDescriptor,
          request = ctx.request,
          config = ctx.config,
          attrs = ctx.attrs,
          globalConfig = ctx.globalConfig
        )
        val newBody = plugin.transformResponseBodyWithCtx(bctx)
        val octx    = TransformerResponseContext(
          rawResponse = HttpResponse(
            status = ctx.rawResponse.status,
            headers = ctx.rawResponse.headers,
            cookies = ctx.rawResponse.cookies,
            body = () => newBody
          ),
          otoroshiResponse = HttpResponse(
            status = ctx.otoroshiResponse.status,
            headers = ctx.otoroshiResponse.headers,
            cookies = ctx.otoroshiResponse.cookies,
            body = () => newBody
          ),
          apikey = ctx.apikey,
          user = ctx.user,
          index = 0,
          snowflake = ctx.snowflake,
          descriptor = ctx.route.serviceDescriptor,
          request = ctx.request,
          config = ctx.config,
          attrs = ctx.attrs,
          globalConfig = ctx.globalConfig
        )
        plugin.transformResponseWithCtx(octx).map {
          case Left(r)     => Left(r)
          case Right(resp) =>
            NgPluginHttpResponse(
              status = resp.status,
              headers = resp.headers,
              cookies = resp.cookies,
              body = newBody
            ).right
        }
      }
    }
  }

  override def transformError(
      ctx: NgTransformerErrorContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[NgPluginHttpResponse] = {
    val pluginId = ctx.config.select("plugin").as[String]
    env.scriptManager.getAnyScript[RequestTransformer](pluginId) match {
      case Left(err)     =>
        NgPluginHttpResponse(
          status = 500,
          headers = Map("Content-Type" -> "application/json"),
          cookies = Seq.empty,
          body = Source.single(ByteString(Json.obj("error" -> "plugin not found", "plugin" -> pluginId).stringify))
        ).vfuture
      case Right(plugin) => {
        val octx = TransformerErrorContext(
          index = 0,
          snowflake = ctx.snowflake,
          message = ctx.message,
          otoroshiResult = ctx.otoroshiResponse.asResult,
          otoroshiResponse = HttpResponse(
            status = ctx.otoroshiResponse.status,
            headers = ctx.otoroshiResponse.headers,
            cookies = ctx.otoroshiResponse.cookies,
            body = () => ctx.otoroshiResponse.body
          ),
          request = ctx.request,
          maybeCauseId = ctx.maybeCauseId,
          callAttempts = ctx.callAttempts,
          descriptor = ctx.route.serviceDescriptor,
          apikey = ctx.apikey,
          user = ctx.user,
          config = ctx.config,
          globalConfig = ctx.globalConfig,
          attrs = ctx.attrs
        )
        plugin.transformErrorWithCtx(octx).map { result =>
          NgPluginHttpResponse(
            status = result.header.status,
            headers = result.header.headers,
            cookies = result.newCookies.map { c =>
              WSCookieWithSameSite(
                name = c.name,
                value = c.value,
                domain = c.domain,
                path = Option(c.path),
                maxAge = c.maxAge.map(_.toLong),
                secure = c.secure,
                httpOnly = c.httpOnly,
                sameSite = c.sameSite
              )
            },
            body = result.body.dataStream
          )
        }
      }
    }
  }
}

class CompositeWrapper extends NgPreRouting with NgAccessValidator with NgRequestTransformer {

  override def usesCallbacks: Boolean            = true
  override def transformsRequest: Boolean        = true
  override def transformsResponse: Boolean       = true
  override def transformsError: Boolean          = true
  override def isPreRouteAsync: Boolean          = true
  override def isAccessAsync: Boolean            = true
  override def isTransformRequestAsync: Boolean  = true
  override def isTransformResponseAsync: Boolean = true

  override def preRoute(
      ctx: NgPreRoutingContext
  )(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    val pluginId = ctx.config.select("plugin").as[String]
    env.scriptManager
      .getAnyScript[NamedPlugin](pluginId)
      .filterOrElse(_.isInstanceOf[PreRouting], Left("bad-type")) match {
      case Left("bad-type") => Done.right.vfuture
      case Left(err)        =>
        NgPreRoutingErrorWithResult(
          Results.InternalServerError(Json.obj("error" -> "plugin not found", "plugin" -> pluginId))
        ).left.vfuture
      case Right(plugin)    => {
        val octx = PreRoutingContext(
          ctx.snowflake,
          0,
          ctx.request,
          ctx.route.serviceDescriptor,
          ctx.config,
          ctx.attrs,
          ctx.globalConfig
        )
        plugin
          .asInstanceOf[PreRouting]
          .preRoute(octx)
          .map(_ => Done.right)
          .recover {
            case PreRoutingError(body, code, contentType, headers) =>
              NgPreRoutingErrorWithResult(
                Results.Status(code)(body).as(contentType).withHeaders(headers.toSeq: _*)
              ).left
            case PreRoutingErrorWithResult(r)                      => NgPreRoutingErrorWithResult(r).left
            case t: Throwable                                      =>
              NgPreRoutingErrorWithResult(Results.InternalServerError(Json.obj("error" -> t.getMessage()))).left
          }
      }
    }
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val pluginId = ctx.config.select("plugin").as[String]
    env.scriptManager
      .getAnyScript[NamedPlugin](pluginId)
      .filterOrElse(_.isInstanceOf[AccessValidator], Left("bad-type")) match {
      case Left("bad-type") => NgAccess.NgAllowed.vfuture
      case Left(err)        =>
        NgAccess
          .NgDenied(Results.InternalServerError(Json.obj("error" -> "plugin not found", "plugin" -> pluginId)))
          .vfuture
      case Right(plugin)    => {
        val octx = AccessContext(
          snowflake = ctx.snowflake,
          index = 0,
          request = ctx.request,
          config = ctx.config,
          attrs = ctx.attrs,
          descriptor = ctx.route.serviceDescriptor,
          user = ctx.user,
          apikey = ctx.apikey,
          globalConfig = ctx.globalConfig
        )
        plugin.asInstanceOf[AccessValidator].access(octx).map {
          case Allowed   => NgAccess.NgAllowed
          case Denied(r) => NgAccess.NgDenied(r)
        }
      }
    }
  }

  override def beforeRequest(
      ctx: NgBeforeRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    val pluginId = ctx.config.select("plugin").as[String]
    env.scriptManager
      .getAnyScript[NamedPlugin](pluginId)
      .filterOrElse(_.isInstanceOf[RequestTransformer], Left("bad-type")) match {
      case Left(err)     => ().vfuture
      case Right(plugin) => {
        val octx = BeforeRequestContext(
          index = 0,
          snowflake = ctx.snowflake,
          descriptor = ctx.route.serviceDescriptor,
          request = ctx.request,
          config = ctx.config,
          attrs = ctx.attrs,
          globalConfig = ctx.globalConfig
        )
        plugin.asInstanceOf[RequestTransformer].beforeRequest(octx)
      }
    }
  }

  override def afterRequest(
      ctx: NgAfterRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    val pluginId = ctx.config.select("plugin").as[String]
    env.scriptManager
      .getAnyScript[NamedPlugin](pluginId)
      .filterOrElse(_.isInstanceOf[RequestTransformer], Left("bad-type")) match {
      case Left(err)     => ().vfuture
      case Right(plugin) => {
        val octx = AfterRequestContext(
          index = 0,
          snowflake = ctx.snowflake,
          descriptor = ctx.route.serviceDescriptor,
          request = ctx.request,
          config = ctx.config,
          attrs = ctx.attrs,
          globalConfig = ctx.globalConfig
        )
        plugin.asInstanceOf[RequestTransformer].afterRequest(octx)
      }
    }
  }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val pluginId = ctx.config.select("plugin").as[String]
    env.scriptManager
      .getAnyScript[NamedPlugin](pluginId)
      .filterOrElse(_.isInstanceOf[RequestTransformer], Left("bad-type")) match {
      case Left("bad-type") => ctx.otoroshiRequest.right.vfuture
      case Left(err)        =>
        Results.InternalServerError(Json.obj("error" -> "plugin not found", "plugin" -> pluginId)).left.vfuture
      case Right(plugin)    => {
        val bctx    = TransformerRequestBodyContext(
          rawRequest = HttpRequest(
            url = ctx.rawRequest.url,
            method = ctx.rawRequest.method,
            headers = ctx.rawRequest.headers,
            cookies = ctx.rawRequest.cookies,
            version = ctx.rawRequest.version,
            clientCertificateChain = ctx.rawRequest.clientCertificateChain,
            target =
              ctx.attrs.get(otoroshi.next.plugins.Keys.BackendKey).orElse(ctx.rawRequest.backend).map(_.toTarget),
            claims = ctx.attrs
              .get(NgOtoroshiChallengeKeys.ClaimKey)
              .getOrElse(
                OtoroshiClaim(
                  iss = env.Headers.OtoroshiIssuer,
                  sub = env.Headers.OtoroshiIssuer,
                  aud = ctx.route.name,
                  exp = DateTime.now().plus(30000).toDate.getTime,
                  iat = DateTime.now().toDate.getTime,
                  jti = IdGenerator.uuid
                )
              ),
            body = () => ctx.rawRequest.body
          ),
          otoroshiRequest = HttpRequest(
            url = ctx.otoroshiRequest.url,
            method = ctx.otoroshiRequest.method,
            headers = ctx.otoroshiRequest.headers,
            cookies = ctx.otoroshiRequest.cookies,
            version = ctx.otoroshiRequest.version,
            clientCertificateChain = ctx.otoroshiRequest.clientCertificateChain,
            target =
              ctx.attrs.get(otoroshi.next.plugins.Keys.BackendKey).orElse(ctx.rawRequest.backend).map(_.toTarget),
            claims = ctx.attrs
              .get(NgOtoroshiChallengeKeys.ClaimKey)
              .getOrElse(
                OtoroshiClaim(
                  iss = env.Headers.OtoroshiIssuer,
                  sub = env.Headers.OtoroshiIssuer,
                  aud = ctx.route.name,
                  exp = DateTime.now().plus(30000).toDate.getTime,
                  iat = DateTime.now().toDate.getTime,
                  jti = IdGenerator.uuid
                )
              ),
            body = () => ctx.otoroshiRequest.body
          ),
          body = ctx.rawRequest.body,
          apikey = ctx.apikey,
          user = ctx.user,
          index = 0,
          snowflake = ctx.snowflake,
          descriptor = ctx.route.serviceDescriptor,
          request = ctx.request,
          config = ctx.config,
          attrs = ctx.attrs,
          globalConfig = ctx.globalConfig
        )
        val newBody = plugin.asInstanceOf[RequestTransformer].transformRequestBodyWithCtx(bctx)
        val octx    = TransformerRequestContext(
          rawRequest = HttpRequest(
            url = ctx.rawRequest.url,
            method = ctx.rawRequest.method,
            headers = ctx.rawRequest.headers,
            cookies = ctx.rawRequest.cookies,
            version = ctx.rawRequest.version,
            clientCertificateChain = ctx.rawRequest.clientCertificateChain,
            target =
              ctx.attrs.get(otoroshi.next.plugins.Keys.BackendKey).orElse(ctx.otoroshiRequest.backend).map(_.toTarget),
            claims = ctx.attrs
              .get(NgOtoroshiChallengeKeys.ClaimKey)
              .getOrElse(
                OtoroshiClaim(
                  iss = env.Headers.OtoroshiIssuer,
                  sub = env.Headers.OtoroshiIssuer,
                  aud = ctx.route.name,
                  exp = DateTime.now().plus(30000).toDate.getTime,
                  iat = DateTime.now().toDate.getTime,
                  jti = IdGenerator.uuid
                )
              ),
            body = () => newBody
          ),
          otoroshiRequest = HttpRequest(
            url = ctx.otoroshiRequest.url,
            method = ctx.otoroshiRequest.method,
            headers = ctx.otoroshiRequest.headers,
            cookies = ctx.otoroshiRequest.cookies,
            version = ctx.otoroshiRequest.version,
            clientCertificateChain = ctx.otoroshiRequest.clientCertificateChain,
            target =
              ctx.attrs.get(otoroshi.next.plugins.Keys.BackendKey).orElse(ctx.otoroshiRequest.backend).map(_.toTarget),
            claims = ctx.attrs
              .get(NgOtoroshiChallengeKeys.ClaimKey)
              .getOrElse(
                OtoroshiClaim(
                  iss = env.Headers.OtoroshiIssuer,
                  sub = env.Headers.OtoroshiIssuer,
                  aud = ctx.route.name,
                  exp = DateTime.now().plus(30000).toDate.getTime,
                  iat = DateTime.now().toDate.getTime,
                  jti = IdGenerator.uuid
                )
              ),
            body = () => newBody
          ),
          apikey = ctx.apikey,
          user = ctx.user,
          index = 0,
          snowflake = ctx.snowflake,
          descriptor = ctx.route.serviceDescriptor,
          request = ctx.request,
          config = ctx.config,
          attrs = ctx.attrs,
          globalConfig = ctx.globalConfig
        )
        plugin.asInstanceOf[RequestTransformer].transformRequestWithCtx(octx).map {
          case Left(r)    => Left(r)
          case Right(req) =>
            Right(
              NgPluginHttpRequest(
                url = req.url,
                method = req.method,
                headers = req.headers,
                cookies = req.cookies,
                version = req.version,
                clientCertificateChain = req.clientCertificateChain,
                body = newBody,
                backend = req.target.map(t => NgTarget.fromTarget(t))
              )
            )
        }
      }
    }
  }

  override def transformError(
      ctx: NgTransformerErrorContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[NgPluginHttpResponse] = {
    val pluginId = ctx.config.select("plugin").as[String]
    env.scriptManager
      .getAnyScript[NamedPlugin](pluginId)
      .filterOrElse(_.isInstanceOf[RequestTransformer], Left("bad-type")) match {
      case Left("bad-type") => ctx.otoroshiResponse.vfuture
      case Left(err)        =>
        NgPluginHttpResponse(
          status = 500,
          headers = Map("Content-Type" -> "application/json"),
          cookies = Seq.empty,
          body = Source.single(ByteString(Json.obj("error" -> "plugin not found", "plugin" -> pluginId).stringify))
        ).vfuture
      case Right(plugin)    => {
        val octx = TransformerErrorContext(
          index = 0,
          snowflake = ctx.snowflake,
          message = ctx.message,
          otoroshiResult = ctx.otoroshiResponse.asResult,
          otoroshiResponse = HttpResponse(
            status = ctx.otoroshiResponse.status,
            headers = ctx.otoroshiResponse.headers,
            cookies = ctx.otoroshiResponse.cookies,
            body = () => ctx.otoroshiResponse.body
          ),
          request = ctx.request,
          maybeCauseId = ctx.maybeCauseId,
          callAttempts = ctx.callAttempts,
          descriptor = ctx.route.serviceDescriptor,
          apikey = ctx.apikey,
          user = ctx.user,
          config = ctx.config,
          globalConfig = ctx.globalConfig,
          attrs = ctx.attrs
        )
        plugin.asInstanceOf[RequestTransformer].transformErrorWithCtx(octx).map { result =>
          NgPluginHttpResponse(
            status = result.header.status,
            headers = result.header.headers,
            cookies = result.newCookies.map { c =>
              WSCookieWithSameSite(
                name = c.name,
                value = c.value,
                domain = c.domain,
                path = Option(c.path),
                maxAge = c.maxAge.map(_.toLong),
                secure = c.secure,
                httpOnly = c.httpOnly,
                sameSite = c.sameSite
              )
            },
            body = result.body.dataStream
          )
        }
      }
    }
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val pluginId = ctx.config.select("plugin").as[String]
    env.scriptManager
      .getAnyScript[NamedPlugin](pluginId)
      .filterOrElse(_.isInstanceOf[RequestTransformer], Left("bad-type")) match {
      case Left("bad-type") => ctx.otoroshiResponse.right.vfuture
      case Left(err)        =>
        Results.InternalServerError(Json.obj("error" -> "plugin not found", "plugin" -> pluginId)).left.vfuture
      case Right(plugin)    => {
        val bctx    = TransformerResponseBodyContext(
          rawResponse = HttpResponse(
            status = ctx.rawResponse.status,
            headers = ctx.rawResponse.headers,
            cookies = ctx.rawResponse.cookies,
            body = () => ctx.rawResponse.body
          ),
          otoroshiResponse = HttpResponse(
            status = ctx.otoroshiResponse.status,
            headers = ctx.otoroshiResponse.headers,
            cookies = ctx.otoroshiResponse.cookies,
            body = () => ctx.otoroshiResponse.body
          ),
          body = ctx.rawResponse.body,
          apikey = ctx.apikey,
          user = ctx.user,
          index = 0,
          snowflake = ctx.snowflake,
          descriptor = ctx.route.serviceDescriptor,
          request = ctx.request,
          config = ctx.config,
          attrs = ctx.attrs,
          globalConfig = ctx.globalConfig
        )
        val newBody = plugin.asInstanceOf[RequestTransformer].transformResponseBodyWithCtx(bctx)
        val octx    = TransformerResponseContext(
          rawResponse = HttpResponse(
            status = ctx.rawResponse.status,
            headers = ctx.rawResponse.headers,
            cookies = ctx.rawResponse.cookies,
            body = () => newBody
          ),
          otoroshiResponse = HttpResponse(
            status = ctx.otoroshiResponse.status,
            headers = ctx.otoroshiResponse.headers,
            cookies = ctx.otoroshiResponse.cookies,
            body = () => newBody
          ),
          apikey = ctx.apikey,
          user = ctx.user,
          index = 0,
          snowflake = ctx.snowflake,
          descriptor = ctx.route.serviceDescriptor,
          request = ctx.request,
          config = ctx.config,
          attrs = ctx.attrs,
          globalConfig = ctx.globalConfig
        )
        plugin.asInstanceOf[RequestTransformer].transformResponseWithCtx(octx).map {
          case Left(r)     => Left(r)
          case Right(resp) =>
            NgPluginHttpResponse(
              status = resp.status,
              headers = resp.headers,
              cookies = resp.cookies,
              body = newBody
            ).right
        }
      }
    }
  }
}
