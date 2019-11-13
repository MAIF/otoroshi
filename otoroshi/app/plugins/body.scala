package otoroshi.plugins.loggers

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import env.Env
import events._
import org.joda.time.DateTime
import otoroshi.script.{RequestTransformer, TransformerRequestBodyContext, TransformerResponseBodyContext}
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.RequestHeader
import utils.JsonImplicits._
import utils.{Regex, RegexPool}

import utils.RequestImplicits._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

case class BodyLoggerFilterConfig(json: JsValue) {
  lazy val statuses: Seq[Int] = (json \ "statuses").asOpt[Seq[Int]].getOrElse(Seq.empty)
  lazy val methods: Seq[String] = (json \ "methods").asOpt[Seq[String]].getOrElse(Seq.empty)
  lazy val paths: Seq[String] = (json \ "paths").asOpt[Seq[String]].getOrElse(Seq.empty)
}

case class BodyLoggerConfig(json: JsValue) {
  lazy val log: Boolean = (json \ "log").asOpt[Boolean].getOrElse(true)
  lazy val store: Boolean = (json \ "store").asOpt[Boolean].getOrElse(false)
  lazy val ttl: Long = (json \ "ttl").asOpt[Long].getOrElse(5.minutes.toMillis)
  lazy val sendToAnalytics: Boolean = (json \ "sendToAnalytics").asOpt[Boolean].getOrElse(false)
  lazy val filter: Option[BodyLoggerFilterConfig] = (json \ "filter").asOpt[JsObject].map(o => BodyLoggerFilterConfig(o))
  lazy val hasFilter: Boolean = filter.isDefined
  lazy val maxSize: Long = (json \ "maxSize").asOpt[Long].getOrElse(5L * 1024L * 1024L)
}

case class RequestBodyEvent(
  `@id`: String,
  `@timestamp`: DateTime,
  `@serviceId`: String,
  `@service`: String,
  reqId: String,
  method: String,
  url: String,
  headers: Map[String, String],
  body: ByteString
) extends AnalyticEvent {

  override def `@type`: String = "RequestBodyEvent"

  def toJson(implicit _env: Env): JsValue = Json.obj(
    "@type"           -> "RequestBodyEvent",
    "@id"             -> `@id`,
    "@timestamp"      -> `@timestamp`,
    "@serviceId"      -> `@serviceId`,
    "@service"        -> `@service`,
    "reqId"           -> reqId,
    "method"          -> method,
    "url"             -> url,
    "headers"         -> headers,
    "body"            -> BodyLogger.base64Encoder.encodeToString(body.toArray)
  )
}

case class ResponseBodyEvent(
  `@id`: String,
  `@timestamp`: DateTime,
  `@serviceId`: String,
  `@service`: String,
  reqId: String,
  method: String,
  url: String,
  headers: Map[String, String],
  status: Int,
  body: ByteString
) extends AnalyticEvent {

  override def `@type`: String = "ResponseBodyEvent"

  def toJson(implicit _env: Env): JsValue = Json.obj(
    "@type"           -> "ResponseBodyEvent",
    "@id"             -> `@id`,
    "@timestamp"      -> `@timestamp`,
    "@serviceId"      -> `@serviceId`,
    "@service"        -> `@service`,
    "reqId"           -> reqId,
    "method"          -> method,
    "url"             -> url,
    "headers"         -> headers,
    "status"          -> status,
    "body"            -> BodyLogger.base64Encoder.encodeToString(body.toArray)
  )
}

object BodyLogger {
  val base64Encoder = java.util.Base64.getEncoder
}

class BodyLogger extends RequestTransformer {

  private def filter(req: RequestHeader, config: BodyLoggerConfig, statusOpt: Option[Int] = None): Boolean = {
    config.filter match {
      case None => true
      case Some(filter) => {
        val matchPath = if (filter.paths.isEmpty) true else filter.paths.exists(p => RegexPool.regex(p).matches(req.relativeUri))
        val methodMatch = if (filter.methods.isEmpty) true else filter.methods.map(_.toLowerCase()).contains(req.method.toLowerCase())
        val statusMatch = if (filter.statuses.isEmpty) true else statusOpt match {
          case None => true
          case Some(status) => filter.statuses.contains(status)
        }
        matchPath && methodMatch && statusMatch
      }
    }
  }

  override def transformRequestBodyWithCtx(ctx: TransformerRequestBodyContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    val config = BodyLoggerConfig((ctx.config \ "BodyLogger").asOpt[JsValue].getOrElse(Json.obj()))
    if (filter(ctx.request, config)) {
      val size = new AtomicLong(0L)
      val ref = new AtomicReference[ByteString](ByteString.empty)
      ctx.body.wireTap(bs => ref.updateAndGet { (t: ByteString) =>
        val currentSize = size.addAndGet(bs.size.toLong)
        if (currentSize <= config.maxSize) {
          t ++ bs
        } else {
          t
        }
      }).alsoTo(Sink.onComplete {
        case _ => {
          val event = RequestBodyEvent(
            `@id` = env.snowflakeGenerator.nextIdStr(),
            `@timestamp` = DateTime.now(),
            `@serviceId` = ctx.descriptor.id,
            `@service` = ctx.descriptor.name,
            reqId = ctx.snowflake,
            method = ctx.rawRequest.method,
            url = ctx.rawRequest.url,
            headers = ctx.rawRequest.headers,
            body = ref.get()
          )
          if (config.log) {
            event.log()
          }
          if (config.sendToAnalytics) {
            event.toAnalytics()
          }
          if (config.store) {
            env.datastores.rawDataStore.set(s"${env.storageRoot}:bodies:${ctx.descriptor.id}:${ctx.snowflake}:request", ByteString(Json.stringify(event.toJson)), Some(config.ttl))
          }
        }
      })
    } else {
      ctx.body
    }
  }

  override def transformResponseBodyWithCtx(ctx: TransformerResponseBodyContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    val config = BodyLoggerConfig((ctx.config \ "BodyLogger").asOpt[JsValue].getOrElse(Json.obj()))
    if (filter(ctx.request, config, Some(ctx.rawResponse.status))) {
      val size = new AtomicLong(0L)
      val ref = new AtomicReference[ByteString](ByteString.empty)
      ctx.body.wireTap(bs => ref.updateAndGet { (t: ByteString) =>
        val currentSize = size.addAndGet(bs.size.toLong)
        if (currentSize <= config.maxSize) {
          t ++ bs
        } else {
          t
        }
      }).alsoTo(Sink.onComplete {
        case _ => {
          val event = ResponseBodyEvent(
            `@id` = env.snowflakeGenerator.nextIdStr(),
            `@timestamp` = DateTime.now(),
            `@serviceId` = ctx.descriptor.id,
            `@service` = ctx.descriptor.name,
            reqId = ctx.snowflake,
            method = ctx.request.method,
            url = ctx.request.uri,
            headers = ctx.rawResponse.headers,
            status = ctx.rawResponse.status,
            body = ref.get()
          )
          if (config.log) {
            event.log()
          }
          if (config.sendToAnalytics) {
            event.toAnalytics()
          }
          if (config.store) {
            env.datastores.rawDataStore.set(s"${env.storageRoot}:bodies:${ctx.descriptor.id}:${ctx.snowflake}:response", ByteString(Json.stringify(event.toJson)), Some(config.ttl))
          }
        }
      })
    } else {
      ctx.body
    }
  }
}
