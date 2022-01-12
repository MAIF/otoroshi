package otoroshi.next.plugins

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.models.RefJwtVerifier
import otoroshi.next.plugins.Keys.JwtInjectionKey
import otoroshi.next.plugins.api.{NgAccess, NgAccessContext, NgAccessValidator, NgRequestTransformer, NgTransformerRequestContext, PluginHttpRequest}
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import play.api.libs.ws.DefaultWSCookie
import play.api.mvc.{Cookie, Result, Results}

import scala.concurrent.{ExecutionContext, Future, Promise}

class JwtVerification extends NgAccessValidator with NgRequestTransformer {
  // TODO: add name and config
  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val verifiers = ctx.config.select("verifiers").asOpt[Seq[String]].getOrElse(Seq.empty)
    if (verifiers.nonEmpty) {
      val verifier = RefJwtVerifier(verifiers, true, Seq.empty)
      val promise = Promise[NgAccess]()
      verifier.verify(
        request = ctx.request,
        desc = ctx.route.serviceDescriptor,
        apikey = ctx.apikey,
        user = ctx.user,
        elContext = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
        attrs = ctx.attrs
      ) { injection =>
        ctx.attrs.put(JwtInjectionKey -> injection)
        promise.trySuccess(NgAccess.NgAllowed)
        Results.Ok("done").future
      }.map { result =>
        if (!promise.isCompleted && result.header.status != 200) {
          promise.trySuccess(NgAccess.NgDenied(result))
        } else {
          promise.trySuccess(NgAccess.NgAllowed)
        }
      }
      promise.future
    } else {
      NgAccess.NgAllowed.future
    }
  }

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, PluginHttpRequest]] = {
    ctx.attrs.get(JwtInjectionKey) match {
      case None => ctx.otoroshiRequest.right.future
      case Some(injection) => {
        ctx.otoroshiRequest
          .applyOnIf(injection.removeCookies.nonEmpty) { req => req.copy(cookies = req.cookies.filterNot(c => injection.removeCookies.contains(c.name))) }
          .applyOnIf(injection.removeHeaders.nonEmpty) { req => req.copy(headers = req.headers.filterNot(tuple => injection.removeHeaders.map(_.toLowerCase).contains(tuple._1.toLowerCase))) }
          .applyOnIf(injection.additionalHeaders.nonEmpty) { req => req.copy(headers = req.headers ++ injection.additionalHeaders) }
          .applyOnIf(injection.additionalCookies.nonEmpty) { req => req.copy(cookies = req.cookies ++ injection.additionalCookies.map(t => DefaultWSCookie(t._1, t._2))) }
          .right
          .future
      }
    }
  }
}
