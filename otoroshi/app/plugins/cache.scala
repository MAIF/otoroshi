package otoroshi.plugins.cache

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import env.Env
import otoroshi.script.{HttpRequest, RequestTransformer, TransformerRequestContext, TransformerResponseBodyContext}
import play.api.Logger
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.{RequestHeader, Result, Results}
import redis.{RedisClientMasterSlaves, RedisServer}
import utils.RegexPool
import utils.RequestImplicits._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

case class ResponseCacheFilterConfig(json: JsValue) {
  lazy val statuses: Seq[Int] = (json \ "statuses").asOpt[Seq[Int]].getOrElse(Seq(200))
  lazy val methods: Seq[String] = (json \ "methods").asOpt[Seq[String]].getOrElse(Seq("GET"))
  lazy val paths: Seq[String] = (json \ "paths").asOpt[Seq[String]].getOrElse(Seq("/.*"))
  lazy val notStatuses: Seq[Int] = (json \ "not" \ "statuses").asOpt[Seq[Int]].getOrElse(Seq(200))
  lazy val notMethods: Seq[String] = (json \ "not" \ "methods").asOpt[Seq[String]].getOrElse(Seq("GET"))
  lazy val notPaths: Seq[String] = (json \ "not" \ "paths").asOpt[Seq[String]].getOrElse(Seq("/.*"))
}

case class ResponseCacheConfig(json: JsValue) {
  lazy val enabled: Boolean = (json \ "enabled").asOpt[Boolean].getOrElse(true)
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

  override def name: String = "Response Cache"

  override def defaultConfig: Option[JsObject] = Some(Json.obj(
    "ResponseCache" -> Json.obj(
      "enabled" -> true,
      "ttl" -> 60.minutes.toMillis,
      "maxSize" -> 5L * 1024L * 1024L,
      "filter" -> Json.obj(
        "statuses" -> Json.arr(),
        "methods" -> Json.arr(),
        "paths" -> Json.arr(),
        "not" -> Json.obj(
          "statuses" -> Json.arr(),
          "methods" -> Json.arr(),
          "paths" -> Json.arr(),
        )
      )
    )
  ))

  override def description: Option[String] = Some(
    """This plugin can cache responses from target services in the otoroshi datasstore
      |It also provides a debug UI at `/.well-known/otoroshi/bodylogger`.
      |
      |This plugin can accept the following configuration
      |
      |```json
      |{
      |  "BodyLogger": {
      |    "enabled": true, // enabled cache
      |    "ttl": 300000,  // store it for some times (5 minutes by default)
      |    "maxSize": 5242880, // max body size (body will be cut after that)
      |    "filter": { // cacge only for some status, method and paths
      |      "statuses": [],
      |      "methods": [],
      |      "paths": [],
      |      "not": {
      |        "statuses": [],
      |        "methods": [],
      |        "paths": []
      |      }
      |    }
      |  }
      |}
      |```
    """.stripMargin)

  private val ref = new AtomicReference[(RedisClientMasterSlaves, ActorSystem)]()

  override def start(env: Env): Future[Unit] = {
    val actorSystem = ActorSystem("cache-redis")
    implicit val ec = actorSystem.dispatcher
    env.datastores.globalConfigDataStore.singleton()(ec, env).map { conf =>
      if ((conf.scripts.transformersConfig \ "ResponseCache").isDefined) {
        val redis: RedisClientMasterSlaves = {
          val master = RedisServer(
            host = (conf.scripts.transformersConfig \ "ResponseCache" \ "redis" \ "host").asOpt[String].getOrElse("localhost"),
            port = (conf.scripts.transformersConfig \ "ResponseCache" \ "redis" \ "port").asOpt[Int].getOrElse(6379),
            password = (conf.scripts.transformersConfig \ "ResponseCache" \ "redis" \ "password").asOpt[String]
          )
          val slaves = (conf.scripts.transformersConfig \ "ResponseCache" \ "redis" \ "slaves").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
            .map { config =>
              RedisServer(
                host = (config \ "host").asOpt[String].getOrElse("localhost"),
                port = (config \ "port").asOpt[Int].getOrElse(6379),
                password = (config \ "password").asOpt[String]
              )
            }
          RedisClientMasterSlaves(master, slaves)(actorSystem)
        }
        ref.set((redis, actorSystem))
      }
      ()
    }
  }

  override def stop(env: Env): Future[Unit] = {
    Option(ref.get()).foreach(_._2.terminate())
    FastFuture.successful(())
  }

  private def get(key: String)(implicit env: Env, ec: ExecutionContext): Future[Option[ByteString]] = {
    ref.get() match {
      case null => env.datastores.rawDataStore.get(key)
      case redis => redis._1.get(key)
    }
  }

  private def set(key: String, value: ByteString, ttl: Option[Long])(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    ref.get() match {
      case null => env.datastores.rawDataStore.set(key, value, ttl)
      case redis => redis._1.set(key, value, pxMilliseconds = ttl)
    }
  }

  private def filter(req: RequestHeader, config: ResponseCacheConfig, statusOpt: Option[Int] = None): Boolean = {
    config.filter match {
      case None => true
      case Some(filter) => {
        val matchPath = if (filter.paths.isEmpty) true else filter.paths.exists(p => RegexPool.regex(p).matches(req.relativeUri))
        val matchNotPath = if (filter.notPaths.isEmpty) true else filter.notPaths.exists(p => RegexPool.regex(p).matches(req.relativeUri))
        val methodMatch = if (filter.methods.isEmpty) true else filter.methods.map(_.toLowerCase()).contains(req.method.toLowerCase())
        val methodNotMatch = if (filter.notMethods.isEmpty) true else filter.notMethods.map(_.toLowerCase()).contains(req.method.toLowerCase())
        val statusMatch = if (filter.statuses.isEmpty) true else statusOpt match {
          case None => true
          case Some(status) => filter.statuses.contains(status)
        }
        val statusNotMatch = if (filter.notStatuses.isEmpty) true else statusOpt match {
          case None => true
          case Some(status) => filter.notStatuses.contains(status)
        }
        matchPath && methodMatch && statusMatch && !matchNotPath && !methodNotMatch && !statusNotMatch
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

  private def cachedResponse(ctx: TransformerRequestContext, config: ResponseCacheConfig)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Unit, Option[JsValue]]] = {
    if (filter(ctx.request, config)) {
      get(s"${env.storageRoot}:cache:${ctx.descriptor.id}:${ctx.request.method.toLowerCase()}-${ctx.request.relativeUri}").map {
        case None => Right(None)
        case Some(json) => Right(Some(Json.parse(json.utf8String)))
      }
    } else {
      FastFuture.successful(Left(()))
    }
  }

  override def transformRequestWithCtx(ctx: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val config = ResponseCacheConfig((ctx.config \ "ResponseCache").asOpt[JsValue].getOrElse(Json.obj()))
    if (config.enabled) {
      cachedResponse(ctx, config).map {
        case Left(_) => Right(ctx.otoroshiRequest)
        case Right(None) => Right(ctx.otoroshiRequest.copy(
          headers = ctx.otoroshiRequest.headers ++ Map("X-Otoroshi-Cache" -> "MISS")
        ))
        case Right(Some(res)) => {
          val status = (res \ "status").as[Int]
          val body = new String(ResponseCache.base64Decoder.decode((res \ "body").as[String]))
          val headers = (res \ "headers").as[Map[String, String]] ++ Map("X-Otoroshi-Cache" -> "HIT")
          val ctype = (res \ "ctype").as[String]
          ResponseCache.logger.debug(s"Serving '${ctx.request.method.toLowerCase()} - ${ctx.request.relativeUri}' from cache")
          Left(Results.Status(status)(body).as(ctype).withHeaders(headers.toSeq: _*))
        }
      }
    } else {
      FastFuture.successful(Right(ctx.otoroshiRequest))
    }
  }

  override def transformResponseBodyWithCtx(ctx: TransformerResponseBodyContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    val config = ResponseCacheConfig((ctx.config \ "ResponseCache").asOpt[JsValue].getOrElse(Json.obj()))
    if (config.enabled && couldCacheResponse(ctx, config)) {
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
            set(s"${env.storageRoot}:cache:${ctx.descriptor.id}:${ctx.request.method.toLowerCase()}-${ctx.request.relativeUri}", ByteString(Json.stringify(event)), Some(config.ttl))
          }
        }
      })
    } else {
      ctx.body
    }
  }
}
