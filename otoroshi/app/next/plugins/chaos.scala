package otoroshi.next.plugins

import akka.stream.Materializer
import com.github.blemale.scaffeine.Scaffeine
import otoroshi.env.Env
import otoroshi.gateway.{SnowMonkey, SnowMonkeyContext}
import otoroshi.models.SnowMonkeyConfig.logger
import otoroshi.models.{
  BadResponse,
  BadResponsesFaultConfig,
  ChaosConfig,
  LargeRequestFaultConfig,
  LargeResponseFaultConfig,
  LatencyInjectionFaultConfig,
  SnowMonkeyConfig
}
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{Format, JsArray, JsError, JsNull, JsObject, JsResult, JsSuccess, JsValue, Json, Reads}
import play.api.libs.typedmap.TypedKey
import play.api.mvc.Result

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class NgBadResponse(status: Int, body: String, headers: Map[String, String]) {
  def json: JsValue            = NgBadResponse.format.writes(this)
  lazy val legacy: BadResponse = BadResponse(status, body, headers)
}

object NgBadResponse {
  def fromLegacy(settings: BadResponse): NgBadResponse = NgBadResponse(settings.status, settings.body, settings.headers)
  val format: Format[NgBadResponse]                    = new Format[NgBadResponse] {
    override def reads(json: JsValue): JsResult[NgBadResponse] = {
      Try {
        NgBadResponse(
          status = (json \ "status").asOpt[Int].orElse((json \ "status").asOpt[String].map(_.toInt)).getOrElse(500),
          body = (json \ "body").asOpt[String].getOrElse("""{"error":"..."}"""),
          headers = (json \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty[String, String])
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(c) => JsSuccess(c)
      }
    }
    override def writes(o: NgBadResponse): JsValue = {
      Json.obj(
        "status"  -> o.status,
        "body"    -> o.body,
        "headers" -> o.headers
      )
    }
  }
}

sealed trait NgFaultConfig {
  def ratio: Double
  def json: JsValue
}
case class NgLargeRequestFaultConfig(ratio: Double, additionalRequestSize: Int)    extends NgFaultConfig {
  def json: JsValue                   = NgLargeRequestFaultConfig.format.writes(this)
  def legacy: LargeRequestFaultConfig = LargeRequestFaultConfig(ratio, additionalRequestSize)
}
object NgLargeRequestFaultConfig {
  def fromLegacy(s: LargeRequestFaultConfig): NgLargeRequestFaultConfig =
    NgLargeRequestFaultConfig(s.ratio, s.additionalRequestSize)
  val format: Format[NgLargeRequestFaultConfig]                         = new Format[NgLargeRequestFaultConfig] {
    override def reads(json: JsValue): JsResult[NgLargeRequestFaultConfig] = {
      Try {
        NgLargeRequestFaultConfig(
          ratio = (json \ "ratio").as[Double],
          additionalRequestSize = (json \ "additionalRequestSize").as[Int]
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(c) => JsSuccess(c)
      }
    }
    override def writes(o: NgLargeRequestFaultConfig): JsValue = {
      Json.obj(
        "ratio"                 -> o.ratio,
        "additionalRequestSize" -> o.additionalRequestSize
      )
    }
  }
}
case class NgLargeResponseFaultConfig(ratio: Double, additionalResponseSize: Int)  extends NgFaultConfig {
  def json: JsValue                    = NgLargeResponseFaultConfig.format.writes(this)
  def legacy: LargeResponseFaultConfig = LargeResponseFaultConfig(ratio, additionalResponseSize)
}
object NgLargeResponseFaultConfig {
  def fromLegacy(s: LargeResponseFaultConfig): NgLargeResponseFaultConfig =
    NgLargeResponseFaultConfig(s.ratio, s.additionalResponseSize)
  val format: Format[NgLargeResponseFaultConfig]                          = new Format[NgLargeResponseFaultConfig] {
    override def reads(json: JsValue): JsResult[NgLargeResponseFaultConfig] = {
      Try {
        NgLargeResponseFaultConfig(
          ratio = (json \ "ratio").as[Double],
          additionalResponseSize = (json \ "additionalResponseSize").as[Int]
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(c) => JsSuccess(c)
      }
    }
    override def writes(o: NgLargeResponseFaultConfig): JsValue = {
      Json.obj(
        "ratio"                  -> o.ratio,
        "additionalResponseSize" -> o.additionalResponseSize
      )
    }
  }
}
case class NgLatencyInjectionFaultConfig(ratio: Double, from: FiniteDuration, to: FiniteDuration)
  extends NgFaultConfig {
  def json: JsValue                       = NgLatencyInjectionFaultConfig.format.writes(this)
  def legacy: LatencyInjectionFaultConfig = LatencyInjectionFaultConfig(ratio, from, to)
}
object NgLatencyInjectionFaultConfig {
  def fromLegacy(s: LatencyInjectionFaultConfig): NgLatencyInjectionFaultConfig =
    NgLatencyInjectionFaultConfig(s.ratio, s.from, s.to)
  val format: Format[NgLatencyInjectionFaultConfig]                             = new Format[NgLatencyInjectionFaultConfig] {
    override def reads(json: JsValue): JsResult[NgLatencyInjectionFaultConfig] = {
      Try {
        NgLatencyInjectionFaultConfig(
          ratio = (json \ "ratio").as[Double],
          from = (json \ "from").as(SnowMonkeyConfig.durationFmt),
          to = (json \ "to").as(SnowMonkeyConfig.durationFmt)
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(c) => JsSuccess(c)
      }
    }
    override def writes(o: NgLatencyInjectionFaultConfig): JsValue = {
      Json.obj(
        "ratio" -> o.ratio,
        "from"  -> SnowMonkeyConfig.durationFmt.writes(o.from),
        "to"    -> SnowMonkeyConfig.durationFmt.writes(o.to)
      )
    }
  }
}
case class NgBadResponsesFaultConfig(ratio: Double, responses: Seq[NgBadResponse]) extends NgFaultConfig {
  def json: JsValue                   = NgBadResponsesFaultConfig.format.writes(this)
  def legacy: BadResponsesFaultConfig = BadResponsesFaultConfig(ratio, responses.map(_.legacy))
}
object NgBadResponsesFaultConfig {
  def fromLegacy(s: BadResponsesFaultConfig): NgBadResponsesFaultConfig =
    NgBadResponsesFaultConfig(s.ratio, s.responses.map(NgBadResponse.fromLegacy))
  val format: Format[NgBadResponsesFaultConfig]                         = new Format[NgBadResponsesFaultConfig] {
    override def reads(json: JsValue): JsResult[NgBadResponsesFaultConfig] = {
      Try {
        NgBadResponsesFaultConfig(
          ratio = (json \ "ratio").as[Double],
          responses = (json \ "responses").as(Reads.seq(NgBadResponse.format))
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(c) => JsSuccess(c)
      }
    }
    override def writes(o: NgBadResponsesFaultConfig): JsValue = {
      Json.obj(
        "ratio"     -> o.ratio,
        "responses" -> JsArray(o.responses.map(_.json))
      )
    }
  }
}

case class NgChaosConfig(
                          largeRequestFaultConfig: Option[NgLargeRequestFaultConfig] = None,
                          largeResponseFaultConfig: Option[NgLargeResponseFaultConfig] = None,
                          latencyInjectionFaultConfig: Option[NgLatencyInjectionFaultConfig] = None,
                          badResponsesFaultConfig: Option[NgBadResponsesFaultConfig] = None
                        ) {
  def json: JsValue       = NgChaosConfig.format.writes(this)
  def legacy: ChaosConfig = ChaosConfig(
    enabled = true,
    largeRequestFaultConfig = largeRequestFaultConfig.map(_.legacy),
    largeResponseFaultConfig = largeResponseFaultConfig.map(_.legacy),
    latencyInjectionFaultConfig = latencyInjectionFaultConfig.map(_.legacy),
    badResponsesFaultConfig = badResponsesFaultConfig.map(_.legacy)
  )
}

object NgChaosConfig {
  def fromLegacy(s: ChaosConfig): NgChaosConfig = NgChaosConfig(
    largeRequestFaultConfig = s.largeRequestFaultConfig.map(NgLargeRequestFaultConfig.fromLegacy),
    largeResponseFaultConfig = s.largeResponseFaultConfig.map(NgLargeResponseFaultConfig.fromLegacy),
    latencyInjectionFaultConfig = s.latencyInjectionFaultConfig.map(NgLatencyInjectionFaultConfig.fromLegacy),
    badResponsesFaultConfig = s.badResponsesFaultConfig.map(NgBadResponsesFaultConfig.fromLegacy)
  )
  val format: Format[NgChaosConfig]             = new Format[NgChaosConfig] {
    override def reads(json: JsValue): JsResult[NgChaosConfig] = {
      Try {
        NgChaosConfig(
          largeRequestFaultConfig =
            (json \ "large_request_fault").asOpt[NgLargeRequestFaultConfig](NgLargeRequestFaultConfig.format),
          largeResponseFaultConfig =
            (json \ "large_response_fault").asOpt[NgLargeResponseFaultConfig](NgLargeResponseFaultConfig.format),
          latencyInjectionFaultConfig = (json \ "latency_injection_fault")
            .asOpt[NgLatencyInjectionFaultConfig](NgLatencyInjectionFaultConfig.format),
          badResponsesFaultConfig =
            (json \ "bad_responses_fault").asOpt[NgBadResponsesFaultConfig](NgBadResponsesFaultConfig.format)
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(c) => JsSuccess(c)
      }
    }
    override def writes(o: NgChaosConfig): JsValue = {
      Json.obj(
        "large_request_fault"     -> o.largeRequestFaultConfig.map(_.json).getOrElse(JsNull).as[JsValue],
        "large_response_fault"    -> o.largeResponseFaultConfig.map(_.json).getOrElse(JsNull).as[JsValue],
        "latency_injection_fault" -> o.latencyInjectionFaultConfig.map(_.json).getOrElse(JsNull).as[JsValue],
        "bad_responses_fault"     -> o.badResponsesFaultConfig.map(_.json).getOrElse(JsNull).as[JsValue]
      )
    }
  }
}

object SnowMonkeyChaos {
  val ContextKey = TypedKey[SnowMonkeyContext]("otoroshi.next.plugins.SnowMonkeyContext")
}

class SnowMonkeyChaos extends NgRequestTransformer {

  private val snowMonkeyRef                     = Scaffeine().maximumSize(1).build[String, SnowMonkey]()
  private val configReads: Reads[NgChaosConfig] = NgChaosConfig.format

  override def steps: Seq[NgStep] = Seq(NgStep.TransformRequest, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.TrafficControl)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean = false
  override def core: Boolean                     = true
  override def usesCallbacks: Boolean            = false
  override def transformsRequest: Boolean        = true
  override def transformsResponse: Boolean       = true
  override def transformsError: Boolean          = false
  override def isTransformRequestAsync: Boolean  = true
  override def isTransformResponseAsync: Boolean = false
  override def name: String                      = "Snow Monkey Chaos"
  override def description: Option[String]       = "This plugin introduce some chaos into you life".some
  override def defaultConfig: Option[JsObject]   = NgChaosConfig().json.asObject.some
  override def configSchema: Option[JsObject] = Json.obj(
    "large_request_fault" -> Json.obj(
      "type" -> "object",
      "format" -> "form",
      "schema" -> Json.obj(
        "ratio" -> Json.obj("type" -> "number", "value" -> "null", "constraints" -> Json.arr(Json.obj("type" -> "nullable"))),
        "additionalRequestSize" -> Json.obj("type" -> "number")
      )
    ),
    "large_response_fault" -> Json.obj(
      "type" -> "object",
      "format" -> "form",
      "schema" -> Json.obj(
        "ratio" -> Json.obj("type" -> "number", "value" -> "null", "constraints" -> Json.arr(Json.obj("type" -> "nullable"))),
        "additionalResponseSize" -> Json.obj("type" -> "number")
      )
    ),
    "latency_injection_fault" -> Json.obj(
      "type" -> "object",
      "format" -> "form",
      "schema" -> Json.obj(
        "ratio" -> Json.obj("type" -> "number", "value" -> "null", "constraints" -> Json.arr(Json.obj("type" -> "nullable"))),
        "from" -> Json.obj("type" -> "number"),
        "to" -> Json.obj("type" -> "number")
      )
    ),
    "bad_responses_fault" -> Json.obj(
      "type" -> "object",
      "format" -> "form",
      "schema" -> Json.obj(
        "ratio" -> Json.obj("type" -> "number", "value" -> "null", "constraints" -> Json.arr(Json.obj("type" -> "nullable"))),
        "responses" -> Json.obj(
          "label" -> "Responses",
          "type" -> "object",
          "array" -> "true",
          "createOption" -> true,
          /*"schema" -> Json.obj(
            "status" -> Json.obj("type" -> "number", "value" -> 500),
            "body" -> Json.obj("type" -> "string", "value" -> """{"error":"..."}"""),
            "headers" -> Json.obj(
              "array" -> "true",
              "createOption" -> true,
              "type" -> "object"
            )
          )*/
        )
      )
    )
  ).some

  override def transformRequest(
                                 ctx: NgTransformerRequestContext
                               )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    // val config = ctx.cachedConfig(internalName)(configReads).getOrElse(ChaosConfig(enabled = true))
    val snowMonkey   = snowMonkeyRef.get("singleton", _ => new SnowMonkey()(env))
    val globalConfig = env.datastores.globalConfigDataStore.latest()
    val reqNumber    = ctx.attrs.get(otoroshi.plugins.Keys.RequestNumberKey).get
    snowMonkey.introduceChaosGen[NgPluginHttpRequest](
      reqNumber,
      globalConfig,
      ctx.route.serviceDescriptor,
      ctx.request.theHasBody
    ) { snowMonkeyCtx =>
      ctx.attrs.put(SnowMonkeyChaos.ContextKey -> snowMonkeyCtx)
      ctx.otoroshiRequest
        .copy(
          headers = ctx.otoroshiRequest.headers + ("Content-Length" -> snowMonkeyCtx.trailingRequestBodySize.toString),
          body = ctx.otoroshiRequest.body.concat(snowMonkeyCtx.trailingRequestBodyStream)
        )
        .right
        .vfuture
    }
  }

  override def transformResponseSync(
                                      ctx: NgTransformerResponseContext
                                    )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpResponse] = {
    // val config = ctx.cachedConfig(internalName)(configReads).getOrElse(ChaosConfig(enabled = true))
    ctx.attrs.get(SnowMonkeyChaos.ContextKey) match {
      case None                => ctx.otoroshiResponse.right
      case Some(snowMonkeyCtx) => {
        ctx.otoroshiResponse
          .copy(
            headers =
              ctx.otoroshiResponse.headers + ("Content-Length" -> snowMonkeyCtx.trailingResponseBodySize.toString),
            body = ctx.otoroshiResponse.body.concat(snowMonkeyCtx.trailingResponseBodyStream)
          )
          .right
      }
    }
  }
}
