package otoroshi.next.plugins

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.RemainingQuotas
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}

class OverrideHost extends NgRequestTransformer {
  // TODO: add name and config
  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, PluginHttpRequest]] = {
    ctx.attrs.get(Keys.BackendKey) match {
      case None => Right(ctx.otoroshiRequest).vfuture
      case Some(backend) =>
        val host = backend.hostname
        val headers = ctx.otoroshiRequest.headers.-("Host").-("host").+("Host" -> host)
        val request = ctx.otoroshiRequest.copy(headers = headers)
        Right(request).vfuture
    }
  }
}

class HeadersValidation extends NgAccessValidator {
  // TODO: add name and config
  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val validationHeaders = ctx.config.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty).map {
      case (key, value) => (key.toLowerCase, value)
    }
    val headers = ctx.request.headers.toSimpleMap.map {
      case (key, value) => (key.toLowerCase, value)
    }
    if (validationHeaders.forall {
      case (key, value) => headers.get(key).contains(value)
    }) {
      NgAccess.NgAllowed.vfuture
    } else {
      Errors
        .craftResponseResult(
          "bad request",
          Results.BadRequest,
          ctx.request,
          ctx.route.serviceDescriptor.some,
          None,
          attrs = ctx.attrs
        )
        .map(NgAccess.NgDenied.apply)
    }
  }
}

class AdditionalHeadersOut extends NgRequestTransformer {
  // TODO: add name and config
  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, PluginHttpResponse]] = {
    // TODO: add expression language
    val additionalHeaders = ctx.config.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
    Right(ctx.otoroshiResponse.copy(headers = ctx.otoroshiResponse.headers ++ additionalHeaders)).vfuture
  }
}

class AdditionalHeadersIn extends NgRequestTransformer {
  // TODO: add name and config
  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, PluginHttpRequest]] = {
    // TODO: add expression language
    val additionalHeaders = ctx.config.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
    Right(ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers ++ additionalHeaders)).vfuture
  }
}

class MissingHeadersIn extends NgRequestTransformer {
  // TODO: add name and config
  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, PluginHttpRequest]] = {
    // TODO: add expression language
    val additionalHeaders = ctx.config.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty).filter {
      case (key, _) => !ctx.otoroshiRequest.headers.contains(key) && !ctx.otoroshiRequest.headers.contains(key.toLowerCase)
    }
    Right(ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers ++ additionalHeaders)).vfuture
  }
}

class MissingHeadersOut extends NgRequestTransformer {
  // TODO: add name and config
  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, PluginHttpResponse]] = {
    // TODO: add expression language
    val additionalHeaders = ctx.config.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty).filter {
      case (key, _) => !ctx.otoroshiResponse.headers.contains(key) && !ctx.otoroshiResponse.headers.contains(key.toLowerCase)
    }
    Right(ctx.otoroshiResponse.copy(headers = ctx.otoroshiResponse.headers ++ additionalHeaders)).vfuture
  }
}

class RemoveHeadersOut extends NgRequestTransformer {
  // TODO: add name and config
  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, PluginHttpResponse]] = {
    val headers = ctx.config.select("header_names").asOpt[Seq[String]].getOrElse(Seq.empty).map(_.toLowerCase)
    Right(ctx.otoroshiResponse.copy(headers = ctx.otoroshiResponse.headers.filterNot {
      case (key, _) => headers.contains(key.toLowerCase)
    })).vfuture
  }
}

class RemoveHeadersIn extends NgRequestTransformer {
  // TODO: add name and config
  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, PluginHttpRequest]] = {
    val headers = ctx.config.select("header_names").asOpt[Seq[String]].getOrElse(Seq.empty).map(_.toLowerCase)
    Right(ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers.filterNot {
      case (key, _) => headers.contains(key.toLowerCase)
    })).vfuture
  }
}

class SendOtoroshiHeadersBack extends NgRequestTransformer {
  import otoroshi.utils.http.HeadersHelperImplicits._
  // TODO: add name and config
  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, PluginHttpResponse]] = {
    val headers = ctx.otoroshiResponse.headers.toSeq
    val snowflake = ctx.attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).get
    val requestTimestamp = ctx.attrs.get(otoroshi.plugins.Keys.RequestTimestampKey).get.toString("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
    val remainingQuotas = ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyRemainingQuotasKey).getOrElse(RemainingQuotas()) // TODO: append quotas in attrs in apikey plugin
    val upstreamLatency = ctx.report.getStep("call-backend").map(_.duration).getOrElse(-1L).toString
    val overhead = ctx.report.overheadIn.toString
    val newHeaders = headers
      .removeAllArgs(
        env.Headers.OtoroshiRequestId,
        env.Headers.OtoroshiRequestTimestamp,
        env.Headers.OtoroshiProxyLatency,
        env.Headers.OtoroshiUpstreamLatency,
        env.Headers.OtoroshiDailyCallsRemaining,
        env.Headers.OtoroshiMonthlyCallsRemaining,
        "Otoroshi-ApiKey-Rotation-At",
        "Otoroshi-ApiKey-Rotation-Remaining"
      )
      .appendAllArgs(
        env.Headers.OtoroshiRequestId        -> snowflake,
        env.Headers.OtoroshiRequestTimestamp -> requestTimestamp,
        env.Headers.OtoroshiProxyLatency     -> s"$overhead",
        env.Headers.OtoroshiUpstreamLatency  -> s"$upstreamLatency"
      )
      .appendAllArgsIf(ctx.apikey.isDefined)(
        env.Headers.OtoroshiDailyCallsRemaining   -> remainingQuotas.remainingCallsPerDay.toString,
        env.Headers.OtoroshiMonthlyCallsRemaining -> remainingQuotas.remainingCallsPerMonth.toString
      )
      .lazyAppendAllArgsIf(ctx.apikey.isDefined && ctx.apikey.get.rotation.enabled && ctx.attrs
          .get(otoroshi.plugins.Keys.ApiKeyRotationKey)
          .isDefined
      )(
        Seq(
          "Otoroshi-ApiKey-Rotation-At"        -> ctx.attrs
            .get(otoroshi.plugins.Keys.ApiKeyRotationKey)
            .get
            .rotationAt
            .toString(),
          "Otoroshi-ApiKey-Rotation-Remaining" -> ctx.attrs
            .get(otoroshi.plugins.Keys.ApiKeyRotationKey)
            .get
            .remaining
            .toString
        )
      )
    Right(ctx.otoroshiResponse.copy(headers = newHeaders.toMap)).vfuture
  }
}

class XForwardedHeaders extends NgRequestTransformer {
  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, PluginHttpRequest]] = {
    val request = ctx.request
    val additionalHeaders = if (env.datastores.globalConfigDataStore.latestSafe.exists(_.trustXForwarded)) {
      val xForwardedFor   = request.headers
        .get("X-Forwarded-For")
        .map(v => v + ", " + request.remoteAddress)
        .getOrElse(request.remoteAddress)
      val xForwardedProto = request.theProtocol
      val xForwardedHost  = request.theHost
      Seq(
        "X-Forwarded-For"   -> xForwardedFor,
        "X-Forwarded-Host"  -> xForwardedHost,
        "X-Forwarded-Proto" -> xForwardedProto
      )
    } else if (!env.datastores.globalConfigDataStore.latestSafe.exists(_.trustXForwarded)) {
      val xForwardedFor   = request.remoteAddress
      val xForwardedProto = request.theProtocol
      val xForwardedHost  = request.theHost
      Seq(
        "X-Forwarded-For"   -> xForwardedFor,
        "X-Forwarded-Host"  -> xForwardedHost,
        "X-Forwarded-Proto" -> xForwardedProto
      )
    } else {
      Seq.empty[(String, String)]
    }
    Right(ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers ++ additionalHeaders.toMap)).vfuture
  }
}

/*
class TestBodyTransformation extends NgRequestTransformer {
  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, PluginHttpRequest]] = {
    ctx.otoroshiRequest.body.runFold(ByteString.empty)(_ ++ _ ).map { bodyRaw =>
      val body = bodyRaw.utf8String
      if (body == "hello") {
        val payload = "hi !!!"
        Right(ctx.otoroshiRequest.copy(body = Source.single(ByteString(payload)), headers = ctx.otoroshiRequest.headers - "Content-Length" - "content-length" ++ Map("Content-Length" -> payload.size.toString)))
      } else {
        Left(Results.BadRequest(Json.obj("error" -> "bad body")))
      }
    }
  }
}
 */