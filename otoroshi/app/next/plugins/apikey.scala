package otoroshi.next.plugins

import akka.Done
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import otoroshi.env.Env
import otoroshi.models.{ApiKeyConstraints, ApiKeyHelper}
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.JsObject

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

/*class ApikeyExtractor extends NgPreRouting {
  // TODO: add name and config
  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey) match {
      case None => {
        val config = ApiKeyConstraints.format.reads(ctx.config).getOrElse(ApiKeyConstraints())
        ApiKeyHelper.detectApikeyTuple(ctx.request, config, ctx.attrs) match {
          case None => Done.right.vfuture
          case Some(tuple) => {
            ApiKeyHelper.validateApikeyTuple(ctx.request, tuple, config, ctx.route.id) match {
              case Left(_) => Done.right.vfuture
              case Right(apikey) =>
                ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apikey)
                Done.right.vfuture
            }
          }
        }
      }
      case Some(_) => Done.right.vfuture
    }
  }
}*/

class ApikeyCalls extends NgAccessValidator {

  private val configCache: Cache[String, ApiKeyConstraints] = Scaffeine()
    .expireAfterWrite(5.seconds)
    .maximumSize(1000)
    .build()

  // TODO: add name and config
  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    // TODO: detectApiKeySooner
    ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey) match {
      case None => {
        val constraints = configCache.get(ctx.route.id, _ => ApiKeyConstraints.format.reads(ctx.config).getOrElse(ApiKeyConstraints()))
        // Here are 2 + 12 datastore calls to handle quotas
        ApiKeyHelper.passWithApiKeyFromCache(ctx.request, constraints, ctx.attrs, ctx.route.id).map {
          case Left(result) => NgAccess.NgDenied(result)
          case Right(apikey) =>
            ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apikey)
            NgAccess.NgAllowed
        }
      }
      case Some(_) => NgAccess.NgAllowed.vfuture
    }
  }
  // TODO: remove apikey header in reqtrans
}