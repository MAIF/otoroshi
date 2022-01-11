package otoroshi.next.plugins

import akka.http.scaladsl.util.FastFuture
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api.{NgAccess, NgAccessContext, NgAccessValidator}
import otoroshi.utils.syntax.implicits.BetterJsValue
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

class ReadOnlyCalls extends NgAccessValidator {
  // TODO: add name and config
  private val methods = Seq("get", "head", "options")
  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val method = ctx.request.method.toLowerCase
    if (!methods.contains(method)) {
      Errors
        .craftResponseResult(
          s"Method not allowed",
          Results.MethodNotAllowed,
          ctx.request,
          None,
          Some("errors.method.not.allowed"),
          attrs = ctx.attrs
        ).map(r => NgAccess.NgDenied(r))
    } else {
      FastFuture.successful(NgAccess.NgAllowed)
    }
  }
}

class AllowHttpMethods extends NgAccessValidator {
  // TODO: add name and config
  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val method = ctx.request.method.toLowerCase
    val allowed_methods = ctx.config.select("allowed").asOpt[Seq[String]].getOrElse(Seq.empty).map(_.toLowerCase)
    val forbidden_methods = ctx.config.select("forbidden").asOpt[Seq[String]].getOrElse(Seq.empty).map(_.toLowerCase)
    if (!allowed_methods.contains(method) || forbidden_methods.contains(method)) {
      Errors
        .craftResponseResult(
          s"Method not allowed",
          Results.MethodNotAllowed,
          ctx.request,
          None,
          Some("errors.method.not.allowed"),
          attrs = ctx.attrs
        ).map(r => NgAccess.NgDenied(r))
    } else {
      FastFuture.successful(NgAccess.NgAllowed)
    }
  }
}

