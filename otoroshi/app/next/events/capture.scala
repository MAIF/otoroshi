package otoroshi.next.events

import akka.util.ByteString
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.AnalyticEvent
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api.{NgPluginHttpRequest, NgPluginHttpResponse}
import otoroshi.next.utils.JsonHelpers
import otoroshi.security.IdGenerator
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.BetterJsReadable
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.RequestHeader

object TrafficCaptureEvent {
  val strippedHeaders = Seq("Remote-Address", "Timeout-Access", "Raw-Request-URI", "Tls-Session-Info").map(_.toLowerCase())
}

case class TrafficCaptureEvent(route: NgRoute, request: RequestHeader, backendRequest: NgPluginHttpRequest, backendResponse: NgPluginHttpResponse, response: NgPluginHttpResponse, responseChunks: ByteString, attrs: TypedMap) extends AnalyticEvent {

  override def `@service`: String            = route.name
  override def `@serviceId`: String          = route.id
  def `@id`: String                          = IdGenerator.uuid
  def `@timestamp`: org.joda.time.DateTime   = timestamp
  def `@type`: String                        = "TrafficCaptureEvent"
  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  val timestamp = DateTime.now()

  def toGoReplayFormat(captureRequest: Boolean, captureResponse: Boolean, preferBackendRequest: Boolean, preferBackendResponse: Boolean): String = {
    var event = Seq.empty[String]
    val responseChunkUtf8 = responseChunks.utf8String
    if (captureRequest && !preferBackendRequest) {
      val requestEvent = Seq(
        s"1 ${attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).getOrElse("").padTo(24, "0").mkString("")} ${attrs.get(otoroshi.plugins.Keys.RequestStartKey).map(_.toString).getOrElse("").padTo(19, "0").mkString("")} 0",
        System.lineSeparator(),
        s"${request.method.toUpperCase()} ${request.uri} ${request.version}",
        System.lineSeparator(),
        request.headers.toSimpleMap
          .filterNot {
            case (key, _) => TrafficCaptureEvent.strippedHeaders.contains(key.toLowerCase)
          }
          .map {
            case (key, value) => s"${key}: ${value}${System.lineSeparator()}"
          }
          .mkString(""),
        System.lineSeparator(),
        attrs.get(otoroshi.plugins.Keys.CaptureRequestBodyKey).map(_.utf8String).getOrElse(""),
        System.lineSeparator(),
        s"🐵🙈🙉",
        System.lineSeparator()
      )
      event = event ++ requestEvent
    }
    if (captureRequest && preferBackendRequest) {
      val requestEvent = Seq(
        s"1 ${attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).getOrElse("").padTo(24, "0").mkString("")} ${attrs.get(otoroshi.plugins.Keys.RequestStartKey).map(_.toString).getOrElse("").padTo(19, "0").mkString("")} 0",
        System.lineSeparator(),
        s"${backendRequest.method.toUpperCase()} ${backendRequest.uri} ${backendRequest.version}",
        System.lineSeparator(),
        backendRequest.headers
          .filterNot {
            case (key, _) => TrafficCaptureEvent.strippedHeaders.contains(key.toLowerCase)
          }
          .map {
            case (key, value) => s"${key}: ${value}${System.lineSeparator()}"
          }
          .mkString(""),
        System.lineSeparator(),
        attrs.get(otoroshi.plugins.Keys.CaptureRequestBodyKey).map(_.utf8String).getOrElse(""), // TODO: backendRequestChunks.utf8String,
        System.lineSeparator(),
        s"🐵🙈🙉",
        System.lineSeparator()
      )
      event = event ++ requestEvent
    }
    if (captureResponse && !preferBackendResponse) {
      val responseEvent = Seq(
        s"2 ${attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).getOrElse("").padTo(24, "0").mkString("")} ${System.currentTimeMillis().toString.padTo(19, "0").mkString("")} 0", // still dont know what 0 means
        System.lineSeparator(),
        s"${request.version} ${response.status} ${response.statusText}",
        System.lineSeparator(),
        response.headers.map {
          case (key, value) => s"${key}: ${value}${System.lineSeparator()}"
        }.mkString(""),
        System.lineSeparator(),
        responseChunks.utf8String,
        System.lineSeparator(),
        s"🐵🙈🙉",
        System.lineSeparator()
      )
      event = event ++ responseEvent
    }
    if (captureResponse && preferBackendResponse) {
      val responseEvent = Seq(
        s"2 ${attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).getOrElse("").padTo(24, "0").mkString("")} ${System.currentTimeMillis().toString.padTo(19, "0").mkString("")} 0", // still dont know what 0 means
        System.lineSeparator(),
        s"${request.version} ${backendResponse.status} ${backendResponse.statusText}",
        System.lineSeparator(),
        backendResponse.headers.map {
          case (key, value) => s"${key}: ${value}${System.lineSeparator()}"
        }.mkString(""),
        System.lineSeparator(),
        responseChunkUtf8, // TODO: backendResponseChunks.utf8String,
        System.lineSeparator(),
        s"🐵🙈🙉",
        System.lineSeparator()
      )
      event = event ++ responseEvent
    }
    event.mkString("")
    // val path = Paths.get("./capture.gor")
    // if (!path.toFile.exists()) path.toFile.createNewFile()
    // Files.write(
    //   path,
    //   event.mkString("").getBytes(),
    //   StandardOpenOption.APPEND
    // )
  }

  override def toJson(implicit env: Env): JsValue = {
    val inputBody = attrs.get(otoroshi.plugins.Keys.CaptureRequestBodyKey).map(_.utf8String).getOrElse("")
    val id = attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).getOrElse("").padTo(24, "0").mkString("")
    val responseChunkUtf8 = responseChunks.utf8String
    Json.obj(
      "@id"        -> `@id`,
      "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(timestamp),
      "@type"      -> "TrafficCaptureEvent",
      "@product"   -> "otoroshi",
      "@serviceId" -> `@serviceId`,
      "@service"   -> `@service`,
      "@env"       -> "prod",
      "route"      -> Json.obj(
        "id" -> route.id,
        "name" -> route.name
      ),
      "request"     -> (JsonHelpers.requestToJson(request).asObject ++ Json.obj(
        "id" -> id,
        "int_id" -> request.id,
        "body" -> inputBody,
      )),
      "backend_request" -> (backendRequest.json.asObject - "backend" ++ Json.obj(
        "id" -> id,
        "int_id" -> request.id,
        "body" -> inputBody, // TODO: inputBackendBody,
      )),
      "backend_response" -> (backendResponse.json.asObject ++ Json.obj(
        "id" -> id,
        "status_txt" -> backendResponse.statusText,
        "http_version" -> request.version,
        "body" -> responseChunkUtf8, // TODO: outputBackendBody
      )),
      "response" -> (response.json.asObject ++ Json.obj(
        "id" -> id,
        "status_txt" -> response.statusText,
        "http_version" -> request.version,
        "body" -> responseChunkUtf8,
      ))
    )
  }
}
