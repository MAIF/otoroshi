package otoroshi.next.plugins

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.util.ByteString
import org.joda.time.DateTime
import org.jsoup.Jsoup
import otoroshi.env.Env
import otoroshi.events.AlertEvent
import otoroshi.next.plugins.api._
import otoroshi.next.utils.JsonHelpers
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.Result

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

object PolyfillIoResources {
  lazy val cloudflareUri = Uri("https://cdnjs.cloudflare.com/polyfill/v3/polyfill.min.js")
  lazy val urls = Seq(
    "https://cdn.polyfill.io/v3/polyfill.js",
    "https://cdn.polyfill.io/v3/polyfill.min.js",
    "http://cdn.polyfill.io/v3/polyfill.js",
    "http://cdn.polyfill.io/v3/polyfill.min.js",
    "https://cdn.polyfill.io/v2/polyfill.js",
    "https://cdn.polyfill.io/v2/polyfill.min.js",
    "http://cdn.polyfill.io/v2/polyfill.js",
    "http://cdn.polyfill.io/v2/polyfill.min.js",
    "https://polyfill.io/v3/polyfill.js",
    "https://polyfill.io/v3/polyfill.min.js",
    "http://polyfill.io/v3/polyfill.js",
    "http://polyfill.io/v3/polyfill.min.js",
    "https://polyfill.io/v2/polyfill.js",
    "https://polyfill.io/v2/polyfill.min.js",
    "http://polyfill.io/v2/polyfill.js",
    "http://polyfill.io/v2/polyfill.min.js",
  )
}

class PolyfillIoReplacer extends NgRequestTransformer {

  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "cdn.polyfill.io mitigation"
  override def description: Option[String]                 = "This plugin replaces compromised cdn.polyfill.io script tags in html resource with the cloudflare equivalent. For each occurence of a cdn.polyfill.io script tag, a CdnPolyfillIoReplacedAlert will be sent".some
  override def noJsForm: Boolean = true

  private def sendAlert(ctx: NgTransformerResponseContext, payload: String, occurences: Seq[String])(implicit env: Env): Unit = {
    CdnPolyfillIoReplacedAlert(UUID.randomUUID().toString, ctx, payload, occurences).toAnalytics()
  }

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val isHtml = ctx.otoroshiResponse.header("Content-Type").exists(_.toLowerCase().contains("text/html"))
    val isNoCsp = ctx.otoroshiResponse.header("Content-Security-Policy").isEmpty
    if (isHtml && isNoCsp) {
      val newHeaders = ctx.otoroshiResponse.headers.-("Content-Length").-("content-length").+("Transfer-Encoding" -> "chunked")
      ctx.otoroshiResponse.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
        val htmlStr = bodyRaw.utf8String
        val parsedHtml = Jsoup.parse(htmlStr)
        val badSrcs = parsedHtml.select("script").asScala
          .flatMap(elem => Option(elem.attributes().get("src")).map(src => (src, elem)))
          .filter {
            case (src, _) => (src.contains("cdn.polyfill.io") || src.contains("polyfill.io")) && PolyfillIoResources.urls.exists(url => src.startsWith(url))
          }
        if (badSrcs.nonEmpty) {
          sendAlert(ctx, htmlStr, badSrcs.map(_._1))
        }
        badSrcs.foreach {
          case (src, elem) =>
            val sourceUri = Uri(src)
            val targetUri = PolyfillIoResources.cloudflareUri.copy(
              rawQueryString = sourceUri.rawQueryString
            )
            elem.attributes().put("src", targetUri.toString())
        }
        val outHtml = parsedHtml.toString
        val source = outHtml.byteString.chunks(16 * 32)
        ctx.otoroshiResponse.copy(body = source, headers = newHeaders).right
      }
    } else {
      ctx.otoroshiResponse.rightf
    }
  }
}

class PolyfillIoDetector extends NgRequestTransformer {

  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "cdn.polyfill.io detector"
  override def description: Option[String]                 = "This plugin detects compromised cdn.polyfill.io script tags in html resource with the cloudflare equivalent and send an alert event. For each occurence of a cdn.polyfill.io script tag, a CdnPolyfillIoDetectedAlert will be sent".some
  override def noJsForm: Boolean = true

  private def sendAlert(ctx: NgTransformerResponseContext, payload: String, occurences: Seq[String])(implicit env: Env): Unit = {
    CdnPolyfillIoDetectedAlert(UUID.randomUUID().toString, ctx, payload, occurences).toAnalytics()
  }

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val isHtml = ctx.otoroshiResponse.header("Content-Type").exists(_.toLowerCase().contains("text/html"))
    val isNoCsp = ctx.otoroshiResponse.header("Content-Security-Policy").isEmpty
    if (isHtml && isNoCsp) {
      ctx.otoroshiResponse.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
        val htmlStr = bodyRaw.utf8String
        val parsedHtml = Jsoup.parse(htmlStr)
        val badSrcs = parsedHtml.select("script").asScala
          .flatMap(elem => Option(elem.attributes().get("src")))
          .filter(src => (src.contains("cdn.polyfill.io") || src.contains("polyfill.io")) && PolyfillIoResources.urls.exists(url => src.startsWith(url)))
        if (badSrcs.nonEmpty) {
          sendAlert(ctx, htmlStr, badSrcs)
        }
        ctx.otoroshiResponse.copy(body = bodyRaw.chunks(16 * 32)).right
      }
    } else {
      ctx.otoroshiResponse.rightf
    }
  }
}

case class CdnPolyfillIoDetectedAlert(
  `@id`: String,
  ctx: NgTransformerResponseContext,
  payload: String,
  occurences: Seq[String],
) extends AlertEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"
  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  val `@timestamp`: DateTime = DateTime.now()

  override def toJson(implicit _env: Env): JsValue =
    Json.obj(
      "@id"           -> `@id`,
      "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
      "@type"         -> `@type`,
      "@product"      -> _env.eventsName,
      "@serviceId"    -> `@serviceId`,
      "@service"      -> `@service`,
      "@env"          -> "prod",
      "alert"         -> "CdnPolyfillIoDetectedAlert",
      "route"         -> ctx.route.json,
      "payload"       -> payload,
      "occurences_nbr"    -> occurences.size,
      "occurences"    -> occurences,
      "request"       -> JsonHelpers.requestToJson(ctx.request)
    )
}

case class CdnPolyfillIoReplacedAlert(
  `@id`: String,
  ctx: NgTransformerResponseContext,
  payload: String,
  occurences: Seq[String],
) extends AlertEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"
  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None
  val `@timestamp`: DateTime = DateTime.now()

  override def toJson(implicit _env: Env): JsValue =
    Json.obj(
      "@id"           -> `@id`,
      "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
      "@type"         -> `@type`,
      "@product"      -> _env.eventsName,
      "@serviceId"    -> `@serviceId`,
      "@service"      -> `@service`,
      "@env"          -> "prod",
      "alert"         -> "CdnPolyfillIoReplacedAlert",
      "route"         -> ctx.route.json,
      "payload"       -> payload,
      "occurences_nbr"    -> occurences.size,
      "occurences"    -> occurences,
      "request"       -> JsonHelpers.requestToJson(ctx.request)
    )
}
