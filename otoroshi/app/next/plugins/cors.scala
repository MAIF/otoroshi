package otoroshi.next.plugins

import akka.Done
import akka.stream.Materializer
import otoroshi.el.HeadersExpressionLanguage
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.{CorsSettings, RedirectionSettings}
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, Reads}
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}

class Cors extends NgRequestTransformer with NgPreRouting {
  private val configReads: Reads[CorsSettings] = CorsSettings.format
  override def core: Boolean = true
  override def name: String = "CORS"
  override def description: Option[String] = "This plugin applies CORS rules".some
  override def defaultConfig: Option[JsObject] = CorsSettings(enabled = true).asJson.asObject.-("enabled").some
  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    val req = ctx.request
    // val cors = CorsSettings.fromJson(ctx.config).getOrElse(CorsSettings()).copy(enabled = true)
    val cors = ctx.cachedConfig(internalName)(configReads).getOrElse(CorsSettings(enabled = true))
    if (req.method == "OPTIONS" && req.headers.get("Access-Control-Request-Method").isDefined) {
      // handle cors preflight request
      if (cors.enabled && cors.shouldNotPass(req)) {
        Errors.craftResponseResult(
          "Cors error",
          Results.NotFound,
          ctx.request,
          None,
          "errors.cors.error".some,
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some,
        ).map(r => Left(NgPreRoutingErrorWithResult(r)))
      } else {
        NgPreRoutingErrorWithResult(Results
          .NoContent
          .withHeaders(cors.asHeaders(req): _*))
          .left
          .vfuture
      }
    } else {
      Done.right.vfuture
    }
  }

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val req = ctx.request
    val cors = CorsSettings.fromJson(ctx.config).getOrElse(CorsSettings()).copy(enabled = true, excludedPatterns = Seq.empty)
    val corsHeaders = cors
      .asHeaders(req)
      .filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
      .map(v =>
        (v._1, HeadersExpressionLanguage(
          v._2, Some(req),
          ctx.route.serviceDescriptor.some,
          ctx.apikey,
          ctx.user,
          ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
          ctx.attrs,
          env
        ))
      )
      .filterNot(h => h._2 == "null")
    ctx.otoroshiResponse.copy(headers = ctx.otoroshiResponse.headers ++ corsHeaders).right.vfuture
  }
}
