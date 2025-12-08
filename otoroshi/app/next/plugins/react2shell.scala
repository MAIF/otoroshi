package otoroshi.next.plugins

import akka.stream.Materializer
import akka.util.ByteString
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.AlertEvent
import otoroshi.next.plugins.api._
import otoroshi.next.utils.JsonHelpers
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class React2SShellDetectorConfig(block: Boolean = false) extends NgPluginConfig {
  def json: JsValue = React2SShellDetectorConfig.format.writes(this)
}

object React2SShellDetectorConfig {
  def configFlow: Seq[String] = Seq(
    "block"
  )
  def configSchema: JsObject  = Json.obj(
    "block" -> Json.obj("type" -> "bool", "label" -> "Block request", "default" -> true)
  )
  val format                  = new Format[React2SShellDetectorConfig] {
    override def reads(json: JsValue): JsResult[React2SShellDetectorConfig] = Try {
      React2SShellDetectorConfig(
        block = (json \ "block").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Success(cfg)       => JsSuccess(cfg)
      case Failure(exception) => JsError(exception.getMessage)
    }
    override def writes(o: React2SShellDetectorConfig): JsValue = {
      Json.obj("block" -> o.block)
    }
  }
}

class React2SShellDetector extends NgRequestTransformer {

  override def defaultConfigObject: Option[NgPluginConfig] = React2SShellDetectorConfig().some
  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean            = true
  override def core: Boolean                     = true
  override def usesCallbacks: Boolean            = false
  override def transformsRequest: Boolean        = true
  override def transformsResponse: Boolean       = false
  override def transformsError: Boolean          = false
  override def isTransformRequestAsync: Boolean  = true
  override def isTransformResponseAsync: Boolean = false
  override def name: String                      = "React2Shell detector"
  override def description: Option[String]       =
    "This plugin detects (and block) React2Shell attacks".some
  override def noJsForm: Boolean                 = true
  override def configFlow: Seq[String]           = React2SShellDetectorConfig.configFlow
  override def configSchema: Option[JsObject]    = React2SShellDetectorConfig.configSchema.some

  private val rscHeaders = Set("next-action", "rsc-action-id")

  private val reconKeywords = List(
    "whoami",
    " id ",
    "uname",
    "/etc/passwd",
    "/tmp/"
  )

  private val flightPatterns = List(
    "$@",
    "\"status\":\"resolved_model\"",
    "\"status\": \"resolved_model\"",
    "constructor:constructor"
  )

  private def sendAlert(ctx: NgTransformerRequestContext, payload: String, suspiciousScore: Int)(implicit
      env: Env
  ): Unit = {
    ReactToShellDetectedAlert(UUID.randomUUID().toString, ctx, payload, suspiciousScore).toAnalytics()
  }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    if (ctx.otoroshiRequest.hasBody && ctx.otoroshiRequest.method.toLowerCase == "post") {
      val config       =
        ctx.cachedConfig(internalName)(React2SShellDetectorConfig.format).getOrElse(React2SShellDetectorConfig())
      val headers      = ctx.otoroshiRequest.headers
      val hasRscHeader = headers.keys.exists(k => rscHeaders.contains(k.toLowerCase()))
      ctx.otoroshiRequest.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
        val bodyStr           = bodyRaw.utf8String
        val hasFlightPatterns = flightPatterns.exists(p => bodyStr.contains(p))
        val hasRecon          = reconKeywords.exists(bodyStr.contains)
        val suspiciousScore   =
          (if (hasRscHeader) 1 else 0) +
          (if (hasFlightPatterns) 2 else 0) +
          (if (hasRecon) 3 else 0)
        if (suspiciousScore >= 3) {
          sendAlert(ctx, bodyStr, suspiciousScore)
          if (config.block) {
            Results.Unauthorized(Json.obj("error" -> "You're not allowed here !")).left
          } else {
            ctx.otoroshiRequest.copy(body = bodyRaw.chunks(32 * 1024)).right
          }
        } else {
          ctx.otoroshiRequest.copy(body = bodyRaw.chunks(32 * 1024)).right
        }
      }
    } else {
      ctx.otoroshiRequest.rightf
    }
  }
}

case class ReactToShellDetectedAlert(
    `@id`: String,
    ctx: NgTransformerRequestContext,
    payload: String,
    suspiciousScore: Int
) extends AlertEvent {

  override def `@service`: String            = "Otoroshi"
  override def `@serviceId`: String          = "--"
  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  val `@timestamp`: DateTime = DateTime.now()

  override def toJson(implicit _env: Env): JsValue =
    Json.obj(
      "@id"              -> `@id`,
      "@timestamp"       -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
      "@type"            -> `@type`,
      "@product"         -> _env.eventsName,
      "@serviceId"       -> `@serviceId`,
      "@service"         -> `@service`,
      "@env"             -> "prod",
      "alert"            -> "ReactToShellDetectedAlert",
      "route"            -> ctx.route.json,
      "payload"          -> payload,
      "suspicious_score" -> suspiciousScore,
      "request"          -> JsonHelpers.requestToJson(ctx.request, ctx.attrs)
    )
}
