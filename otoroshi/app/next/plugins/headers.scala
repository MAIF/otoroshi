package otoroshi.next.plugins

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}

class OverrideHost extends NgRequestTransformer {

  // TODO: add name and config

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, PluginHttpRequest]] = {
    ctx.attrs.get(Keys.BackendKey) match {
      case None => FastFuture.successful(Right(ctx.otoroshiRequest))
      case Some(backend) =>
        val host = backend.hostname
        val headers = ctx.otoroshiRequest.headers.-("Host").-("host").+("Host" -> host)
        val request = ctx.otoroshiRequest.copy(headers = headers)
        FastFuture.successful(Right(request))
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
      FastFuture.successful(NgAccess.NgAllowed)
    } else {
      Errors
        .craftResponseResult(
          "bad request",
          Results.BadRequest,
          ctx.request,
          None,
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
    val additionalHeaders = ctx.config.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
    FastFuture.successful(Right(ctx.otoroshiResponse.copy(headers = ctx.otoroshiResponse.headers ++ additionalHeaders)))
  }
}