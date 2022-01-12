package otoroshi.next.plugins

import akka.Done
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.ApiKeyHelper.PassWithApiKeyContext
import otoroshi.models.{ApiKey, ApiKeyHelper, GlobalConfig, PrivateAppsUser}
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future, Promise}

class ApikeyExtractor extends NgPreRouting {
  // TODO: add name and config
  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey) match {
      case None => ApiKeyHelper.extractApiKey(ctx.request, ctx.route.serviceDescriptor, ctx.attrs).map { // TODO: need to optimize here
        case None => Done.right
        case Some(apikey) => {
          ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apikey)
          Done.right
        }
      }
      case Some(_) => Done.right.future
    }
  }
}

class ApikeyCalls extends NgAccessValidator {
  // TODO: add name and config
  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val promise = Promise[NgAccess]()
    ApiKeyHelper.passWithApiKey[Result](
      ctx = PassWithApiKeyContext(
        req = ctx.request,
        descriptor = ctx.route.serviceDescriptor, // TODO: need to optimize here
        attrs = ctx.attrs,
        config = env.datastores.globalConfigDataStore.latest()
      ),
      callDownstream = (conf: GlobalConfig, apk: Option[ApiKey], usr: Option[PrivateAppsUser]) => {
        ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apk.get)
        promise.trySuccess(NgAccess.NgAllowed)
        Results.NoContent.right.future
      },
      errorResult = (status: Results.Status, message: String, code: String) => {
        Errors
          .craftResponseResult(
            message,
            status,
            ctx.request,
            ctx.route.serviceDescriptor.some,
            code.some,
            attrs = ctx.attrs
          )
          .map(err => promise.trySuccess(NgAccess.NgDenied(err)))
        Results.NoContent.right.future
      }
    )
    promise.future
  }
}

/*
class ApikeyRoutingMatcher extends NgAccessValidator {
  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ApiKeyRouteMatcher.format.reads(ctx.config).getOrElse(ApiKeyRouteMatcher())
    ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey) match {
      case Some(apikey) if config.isActive && apikey.matchRouting(config) => NgAccess.NgAllowed.future
      case _ if config.hasNoRoutingConstraints => NgAccess.NgAllowed.future
      case _ => {
        Errors
          .craftResponseResult(
            "Invalid API key",
            Results.Unauthorized,
            ctx.request,
            ctx.route.serviceDescriptor.some,
            "errors.bad.api.key".some,
            attrs = ctx.attrs
          )
          .map(NgAccess.NgDenied.apply)
      }
    }
  }
}

class ApikeyRoutingRestrictions extends NgAccessValidator {
  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    // val config = ApiKeyRouteMatcher.format.reads(ctx.config).getOrElse(ApiKeyRouteMatcher())
    ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey) match {
      case Some(apikey) if apikey.restrictions.handleRestrictions() => NgAccess.NgAllowed.future
      case _ => {
        errorResult(Unauthorized, "Invalid API key", "errors.bad.api.key")
      }
    }
  }
}
*/