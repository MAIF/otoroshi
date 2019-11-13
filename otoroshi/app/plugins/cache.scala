package otoroshi.plugins.cache

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import env.Env
import otoroshi.script.{HttpRequest, RequestTransformer, TransformerRequestContext, TransformerResponseBodyContext}
import play.api.Logger
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.{RequestHeader, Result, Results}
import utils.RegexPool
import utils.RequestImplicits._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

case class ResponseCacheFilterConfig(json: JsValue) {
  lazy val statuses: Seq[Int] = (json \ "statuses").asOpt[Seq[Int]].getOrElse(Seq(200))
  lazy val methods: Seq[String] = (json \ "methods").asOpt[Seq[String]].getOrElse(Seq("GET"))
  lazy val paths: Seq[String] = (json \ "paths").asOpt[Seq[String]].getOrElse(Seq("/.*"))
}

case class ResponseCacheConfig(json: JsValue) {
  lazy val ttl: Long = (json \ "ttl").asOpt[Long].getOrElse(60.minutes.toMillis)
  lazy val filter: Option[ResponseCacheFilterConfig] = (json \ "filter").asOpt[JsObject].map(o => ResponseCacheFilterConfig(o))
  lazy val hasFilter: Boolean = filter.isDefined
  lazy val maxSize: Long = (json \ "maxSize").asOpt[Long].getOrElse(5L * 1024L * 1024L)
}

object ResponseCache {
  val base64Encoder = java.util.Base64.getEncoder
  val base64Decoder = java.util.Base64.getDecoder
  val logger = Logger("response-cache")
}

class ResponseCache extends RequestTransformer {

  private def filter(req: RequestHeader, config: ResponseCacheConfig, statusOpt: Option[Int] = None): Boolean = {
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

  private def couldCacheResponse(ctx: TransformerResponseBodyContext, config: ResponseCacheConfig): Boolean = {
    if (filter(ctx.request, config)) {
      ctx.rawResponse.headers.get("Content-Length").orElse(ctx.rawResponse.headers.get("content-Length")).map(_.toInt) match {
        case Some(csize) if csize <= config.maxSize => true
        case Some(csize) if csize > config.maxSize => false
        case _ => true
      }
    } else {
      false
    }
  }

  private def cachedResponse(ctx: TransformerRequestContext, config: ResponseCacheConfig)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Option[JsValue]] = {
    if (filter(ctx.request, config)) {
      env.datastores.rawDataStore.get(s"${env.storageRoot}:cache:${ctx.descriptor.id}:${ctx.request.method.toLowerCase()}-${ctx.request.relativeUri}").map {
        case None => None
        case Some(json) => Some(Json.parse(json.utf8String))
      }
    } else {
      FastFuture.successful(None)
    }
  }

  override def transformRequestWithCtx(ctx: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val config = ResponseCacheConfig((ctx.config \ "ResponseCache").asOpt[JsValue].getOrElse(Json.obj()))
    cachedResponse(ctx, config).map {
      case None => Right(ctx.otoroshiRequest)
      case Some(res) => {
        val status = (res \ "status").as[Int]
        val body = new String(ResponseCache.base64Decoder.decode((res \ "body").as[String]))
        val headers = (res \ "headers").as[Map[String, String]] ++ Map("X-From-Otoroshi-Cache" -> "true")
        val ctype = (res \ "ctype").as[String]
        ResponseCache.logger.debug(s"Serving '${ctx.request.method.toLowerCase()} - ${ctx.request.relativeUri}' from cache")
        Left(Results.Status(status)(body).as(ctype).withHeaders(headers.toSeq: _*))
      }
    }
  }

  override def transformResponseBodyWithCtx(ctx: TransformerResponseBodyContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    val config = ResponseCacheConfig((ctx.config \ "ResponseCache").asOpt[JsValue].getOrElse(Json.obj()))
    if (couldCacheResponse(ctx, config)) {
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
          if (size.get() < config.maxSize) {
            val ctype: String = ctx.rawResponse.headers.get("Content-Type").orElse(ctx.rawResponse.headers.get("content-type")).getOrElse("text/plain")
            val headers: Map[String, String] = ctx.rawResponse.headers.filterNot { tuple =>
              val name = tuple._1.toLowerCase()
              name == "content-type" || name == "transfer-encoding" || name == "content-length"
            }
            val event = Json.obj(
              "status" -> ctx.rawResponse.status,
              "headers" -> headers,
              "ctype" -> ctype,
              "body" -> ResponseCache.base64Encoder.encodeToString(ref.get().toArray)
            )
            ResponseCache.logger.debug(s"Storing '${ctx.request.method.toLowerCase()} - ${ctx.request.relativeUri}' in cache for the next ${config.ttl} ms.")
            env.datastores.rawDataStore.set(s"${env.storageRoot}:cache:${ctx.descriptor.id}:${ctx.request.method.toLowerCase()}-${ctx.request.relativeUri}", ByteString(Json.stringify(event)), Some(config.ttl))
          }
        }
      })
    } else {
      ResponseCache.logger.info("nope")
      ctx.body
    }
  }
}
