package otoroshi.next.plugins

import akka.stream.Materializer
import com.github.blemale.scaffeine.Scaffeine
import otoroshi.env.Env
import otoroshi.gateway.{SnowMonkey, SnowMonkeyContext}
import otoroshi.models.ChaosConfig
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, Reads}
import play.api.libs.typedmap.TypedKey
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}

object SnowMonkeyChaos {
  val ContextKey = TypedKey[SnowMonkeyContext]("otoroshi.next.plugins.SnowMonkeyContext")
}

class SnowMonkeyChaos extends NgRequestTransformer {

  private val snowMonkeyRef = Scaffeine().maximumSize(1).build[String, SnowMonkey]()
  private val configReads: Reads[ChaosConfig] = ChaosConfig._fmt

  override def core: Boolean = true
  override def usesCallbacks: Boolean = false
  override def transformsRequest: Boolean = true
  override def transformsResponse: Boolean = true
  override def transformsError: Boolean = false
  override def name: String = "Snow Monkey Chaos"
  override def description: Option[String] = "This plugin introduce some chaos into you life".some
  override def defaultConfig: Option[JsObject] = ChaosConfig().asJson.asObject.-("enabled").some

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    // val config = ctx.cachedConfig(internalName)(configReads).getOrElse(ChaosConfig(enabled = true))
    val snowMonkey = snowMonkeyRef.get("singleton", _ => new SnowMonkey()(env))
    val globalConfig = env.datastores.globalConfigDataStore.latest()
    val reqNumber = ctx.attrs.get(otoroshi.plugins.Keys.RequestNumberKey).get
    snowMonkey.introduceChaosGen[NgPluginHttpRequest](reqNumber, globalConfig, ctx.route.serviceDescriptor, ctx.request.theHasBody) { snowMonkeyCtx =>
      ctx.attrs.put(SnowMonkeyChaos.ContextKey -> snowMonkeyCtx)
      ctx.otoroshiRequest.copy(
        headers = ctx.otoroshiRequest.headers + ("Content-Length" -> snowMonkeyCtx.trailingRequestBodySize.toString),
        body = ctx.otoroshiRequest.body.concat(snowMonkeyCtx.trailingRequestBodyStream)
      ).right.vfuture
    }
  }

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    // val config = ctx.cachedConfig(internalName)(configReads).getOrElse(ChaosConfig(enabled = true))
    ctx.attrs.get(SnowMonkeyChaos.ContextKey) match {
      case None => ctx.otoroshiResponse.right.vfuture
      case Some(snowMonkeyCtx) => {
        ctx.otoroshiResponse.copy(
          headers = ctx.otoroshiResponse.headers + ("Content-Length" -> snowMonkeyCtx.trailingResponseBodySize.toString),
          body = ctx.otoroshiResponse.body.concat(snowMonkeyCtx.trailingResponseBodyStream)
        ).right.vfuture
      }
    }
  }
}
