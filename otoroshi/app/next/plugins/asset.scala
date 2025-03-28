package otoroshi.next.plugins

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.models.NgTarget
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}
import scala.util._

case class StaticAssetEndpointConfiguration(url: Option[String] = None) extends NgPluginConfig {
  def json: JsValue = StaticAssetEndpointConfiguration.format.writes(this)
}

object StaticAssetEndpointConfiguration {
  val default                        = StaticAssetEndpointConfiguration()
  val format                         = new Format[StaticAssetEndpointConfiguration] {
    override def reads(json: JsValue): JsResult[StaticAssetEndpointConfiguration] = Try {
      StaticAssetEndpointConfiguration(
        url = json.select("url").asOpt[String].filter(_.nonEmpty)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(v)         => JsSuccess(v)
    }
    override def writes(o: StaticAssetEndpointConfiguration): JsValue             = Json.obj(
      "url" -> o.url.map(_.json).getOrElse(JsNull).asValue
    )
  }
  val configFlow: Seq[String]        = Seq("url")
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "url" -> Json.obj(
        "type"  -> "string",
        "label" -> s"Asset url"
      )
    )
  )
}

class StaticAssetEndpoint extends NgRequestTransformer {

  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Http static asset"
  override def description: Option[String]                 = "Serve any static http asset for the current request".some
  override def defaultConfigObject: Option[NgPluginConfig] = Some(StaticAssetEndpointConfiguration.default)
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = StaticAssetEndpointConfiguration.configFlow
  override def configSchema: Option[JsObject]              = StaticAssetEndpointConfiguration.configSchema
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = true
  override def isTransformResponseAsync: Boolean           = false

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx
      .cachedConfig(internalName)(StaticAssetEndpointConfiguration.format)
      .getOrElse(StaticAssetEndpointConfiguration.default)
    config.url match {
      case None      => ctx.otoroshiRequest.rightf
      case Some(url) => {
        val uri    = Uri(url)
        val target = NgTarget(
          id = uri.authority.host.toString(),
          hostname = uri.authority.host.toString(),
          port = uri.effectivePort,
          tls = url.startsWith("https://"),
          backup = false,
        )
        ctx.otoroshiRequest
          .copy(
            backend = target.some,
            url = url,
            headers = ctx.otoroshiRequest.headers.removeIgnoreCase("Host").put("Host", uri.authority.host.toString())
          )
          .rightf
      }
    }
  }
}
