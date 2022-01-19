package otoroshi.next.plugins

import akka.stream.Materializer
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import otoroshi.env.Env
import otoroshi.models.{ApiKeyConstraints, ApiKeyHelper, ApikeyLocationKind, ApikeyTuple}
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, Reads}
import play.api.mvc.Result

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

class ApikeyCalls extends NgAccessValidator with NgRequestTransformer with NgRouteMatcher {

  private val configCache: Cache[String, ApiKeyConstraints] = Scaffeine()
    .expireAfterWrite(5.seconds)
    .maximumSize(1000)
    .build()

  private val configReads: Reads[ApiKeyConstraints] = ApiKeyConstraints.format

  override def core: Boolean = true
  override def name: String = "Apikeys"
  override def description: Option[String] = "This plugin expects to find an apikey to allow the request to pass".some
  override def defaultConfig: Option[JsObject] = ApiKeyConstraints().json.asObject.some

  override def matches(ctx: NgRouteMatcherContext)(implicit env: Env): Boolean = {
    val constraints = configCache.get(ctx.route.id, _ => configReads.reads(ctx.config).getOrElse(ApiKeyConstraints()))
    if (constraints.routing.hasNoRoutingConstraints) {
      true
    } else {
      ApiKeyHelper.detectApikeyTuple(ctx.request, constraints, ctx.attrs) match {
        case None         => true
        case Some(tuple) =>
          ctx.attrs.put(otoroshi.next.plugins.Keys.PreExtractedApikeyTupleKey -> tuple)
          ApiKeyHelper.validateApikeyTuple(ctx.request, tuple, constraints, ctx.route.id, ctx.attrs).applyOn { either =>
            ctx.attrs.put(otoroshi.next.plugins.Keys.PreExtractedApikeyKey -> either)
            either
          } match {
            case Left(_) => false
            case Right(apikey) => apikey.matchRouting(constraints.routing)
          }
      }
    }
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
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

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result,NgPluginHttpRequest]] = {
    ctx.attrs.get(otoroshi.next.plugins.Keys.PreExtractedApikeyTupleKey) match {
      case Some(ApikeyTuple(_, _, _, Some(location))) => {
        location.kind match {
          case ApikeyLocationKind.Header => ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers.filterNot(_._1.toLowerCase() == location.name.toLowerCase())).right.vfuture
          case ApikeyLocationKind.Query  => {
            val uri = ctx.otoroshiRequest.uri
            val newQuery = uri.rawQueryString.map(_ => uri.query().filterNot(_._1 == location.name).toString())
            val newUrl = uri.copy(rawQueryString = newQuery).toString()
            ctx.otoroshiRequest.copy(url = newUrl).right.vfuture
          }
          case ApikeyLocationKind.Cookie => ctx.otoroshiRequest.copy(cookies = ctx.otoroshiRequest.cookies.filterNot(_.name == location.name)).right.vfuture
        }
      }
      case _ => ctx.otoroshiRequest.right.vfuture
    }
  }
}