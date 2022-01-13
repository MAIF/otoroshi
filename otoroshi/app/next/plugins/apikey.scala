package otoroshi.next.plugins

import akka.Done
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.ApiKeyHelper.PassWithApiKeyContext
import otoroshi.models.{ApiKey, ApiKeyConstraints, ApiKeyHelper, GlobalConfig, PrivateAppsUser}
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future, Promise}

class ApikeyExtractor extends NgPreRouting {
  // TODO: add name and config
  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey) match {
      case None => {
        val config = ApiKeyConstraints.format.reads(ctx.config).getOrElse(ApiKeyConstraints())
        ApiKeyHelper.detectApikeyTuple(ctx.request, config, ctx.attrs) match {
          case None => Done.right.future
          case Some(tuple) => {
            ApiKeyHelper.validateApikeyTuple(ctx.request, tuple, config, ctx.route.id) match {
              case Right(apikey) =>
                ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apikey)
                Done.right.future
            }
          }
        }
      }
      case Some(_) => Done.right.future
    }
  }
}

class ApikeyCalls extends NgAccessValidator {
  // TODO: add name and config
  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val constraints = ApiKeyConstraints.format.reads(ctx.config).getOrElse(ApiKeyConstraints())
    ApiKeyHelper.passWithApiKeyFromCache(ctx.request, constraints, ctx.attrs, ctx.route.id).map {
      case Left(result) => NgAccess.NgDenied(result)
      case Right(apikey) =>
        ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apikey)
        NgAccess.NgAllowed
    }
  }
}