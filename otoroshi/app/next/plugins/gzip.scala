package otoroshi.next.plugins

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api.{NgPluginCategory, NgPluginHttpResponse, NgPluginVisibility, NgRequestTransformer, NgStep, NgTransformerResponseContext}
import otoroshi.utils.gzip.GzipConfig
import otoroshi.utils.gzip.GzipConfig.logger
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{Format, JsError, JsObject, JsResult, JsSuccess, JsValue, Json, Reads}
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class NgGzipConfig(
    excludedPatterns: Seq[String] = Seq.empty[String],
    whiteList: Seq[String] = Seq("text/*", "application/javascript", "application/json"),
    blackList: Seq[String] = Seq.empty[String],
    bufferSize: Int = 8192,
    chunkedThreshold: Int = 102400,
    compressionLevel: Int = 5
) {
  def json: JsValue           = NgGzipConfig.format.writes(this)
  lazy val legacy: GzipConfig = GzipConfig(
    enabled = true,
    excludedPatterns = excludedPatterns,
    whiteList = whiteList,
    blackList = blackList,
    bufferSize = bufferSize,
    chunkedThreshold = chunkedThreshold,
    compressionLevel = compressionLevel
  )
}

object NgGzipConfig {
  def fromLegacy(settings: GzipConfig): NgGzipConfig = NgGzipConfig(
    excludedPatterns = settings.excludedPatterns,
    whiteList = settings.whiteList,
    blackList = settings.blackList,
    bufferSize = settings.bufferSize,
    chunkedThreshold = settings.chunkedThreshold,
    compressionLevel = settings.compressionLevel
  )
  val format: Format[NgGzipConfig]                   = new Format[NgGzipConfig] {
    override def reads(json: JsValue): JsResult[NgGzipConfig] =
      Try {
        NgGzipConfig(
          excludedPatterns = (json \ "excluded_patterns").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          whiteList = (json \ "allowed_list").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          blackList = (json \ "blocked_list").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          bufferSize = (json \ "buffer_size").asOpt[Int].getOrElse(8192),
          chunkedThreshold = (json \ "chunked_threshold").asOpt[Int].getOrElse(102400),
          compressionLevel = (json \ "compression_level").asOpt[Int].getOrElse(5)
        )
      } match {
        case Success(entity) => JsSuccess(entity)
        case Failure(err)    => JsError(err.getMessage)
      }

    override def writes(o: NgGzipConfig): JsValue =
      Json.obj(
        "excluded_patterns" -> o.excludedPatterns,
        "allowed_list"      -> o.whiteList,
        "blocked_list"      -> o.blackList,
        "buffer_size"       -> o.bufferSize,
        "chunked_threshold" -> o.chunkedThreshold,
        "compression_level" -> o.compressionLevel
      )
  }
}

class GzipResponseCompressor extends NgRequestTransformer {

  private val configReads: Reads[NgGzipConfig] = NgGzipConfig.format

  override def steps: Seq[NgStep] = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand

  override def core: Boolean                     = true
  override def usesCallbacks: Boolean            = false
  override def transformsRequest: Boolean        = false
  override def transformsResponse: Boolean       = true
  override def transformsError: Boolean          = false
  override def isTransformRequestAsync: Boolean  = true
  override def isTransformResponseAsync: Boolean = false
  override def name: String                      = "Gzip compression"
  override def description: Option[String]       = "This plugin can compress responses using gzip".some
  override def defaultConfig: Option[JsObject]   = NgGzipConfig().json.asObject.some

  override def transformResponseSync(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpResponse] = {
    val config                                    = ctx.cachedConfig(internalName)(configReads).getOrElse(NgGzipConfig())
    def transform(result: Result): Future[Result] = config.legacy.handleResult(ctx.request, result)
    ctx.attrs.put(otoroshi.next.plugins.Keys.ResultTransformerKey -> transform)
    ctx.otoroshiResponse.right
  }
}
