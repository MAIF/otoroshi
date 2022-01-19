package otoroshi.next.plugins

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api.{NgPluginHttpResponse, NgRequestTransformer, NgTransformerResponseContext}
import otoroshi.utils.gzip.GzipConfig
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, Reads}
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}

class GzipResponseCompressor extends NgRequestTransformer {

  private val configReads: Reads[GzipConfig] = GzipConfig._fmt
  override def core: Boolean = true
  override def name: String = "Gzip compression"
  override def description: Option[String] = "This plugin can compress responses using gzip".some
  override def defaultConfig: Option[JsObject] = GzipConfig().asJson.asObject.-("enabled").-("excludedPatterns").some

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx.cachedConfig(internalName)(configReads).getOrElse(GzipConfig(enabled = true))
    def transform(result: Result): Future[Result] = config.handleResult(ctx.request, result)
    ctx.attrs.put(otoroshi.next.plugins.Keys.ResultTransformerKey -> transform)
    ctx.otoroshiResponse.right.vfuture
  }
}
