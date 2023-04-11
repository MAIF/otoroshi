package otoroshi.next.plugins

import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import org.joda.time.{DateTime, DateTimeZone}
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.plugins.cache.{ResponseCache, ResponseCacheConfig}
import otoroshi.script.{HttpRequest, RequestTransformer, TransformerRequestContext, TransformerResponseBodyContext}
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.{RegexPool, SchedulerHelper}
import otoroshi.utils.syntax.implicits._
import play.api.libs.Codecs
import play.api.libs.json.JsResult.Exception
import play.api.libs.json._
import play.api.mvc.{RequestHeader, Result, Results}
import redis.{RedisClientMasterSlaves, RedisServer}

import java.util.Locale
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util._

case class NgHttpClientCacheConfig(maxAgeSeconds: Long, methods: Seq[String], status: Seq[Int], mimeTypes: Seq[String])
    extends NgPluginConfig {
  def json: JsValue = NgHttpClientCacheConfig.format.writes(this)
}

object NgHttpClientCacheConfig {
  val default = NgHttpClientCacheConfig(
    maxAgeSeconds = 86400,
    methods = Seq("GET"),
    status = Seq(200),
    mimeTypes = Seq("text/html")
  )
  val format  = new Format[NgHttpClientCacheConfig] {
    override def reads(json: JsValue): JsResult[NgHttpClientCacheConfig] = Try {
      NgHttpClientCacheConfig(
        maxAgeSeconds = json.select("max_age_seconds").asOpt[Long].getOrElse(default.maxAgeSeconds),
        methods = json.select("methods").asOpt[Seq[String]].getOrElse(default.methods),
        status = json
          .select("status")
          .asOpt[Seq[Int]]
          .orElse(json.select("status").asOpt[Seq[String]].map(_.map(_.toInt)))
          .getOrElse(default.status),
        mimeTypes = json.select("mime_types").asOpt[Seq[String]].getOrElse(default.mimeTypes)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }

    override def writes(o: NgHttpClientCacheConfig): JsValue = Json.obj(
      "max_age_seconds" -> o.maxAgeSeconds,
      "methods"         -> o.methods,
      "status"          -> o.status,
      "mime_types"      -> o.mimeTypes
    )
  }
}

class NgHttpClientCache extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean            = true
  override def core: Boolean                     = true
  override def usesCallbacks: Boolean            = false
  override def transformsRequest: Boolean        = false
  override def transformsResponse: Boolean       = true
  override def transformsError: Boolean          = false
  override def isTransformRequestAsync: Boolean  = false
  override def isTransformResponseAsync: Boolean = true

  override def name: String                                = "HTTP Client Cache"
  override def description: Option[String]                 = "This plugin add cache headers to responses".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgHttpClientCacheConfig.default.some

  private def methodMatch(ctx: NgTransformerResponseContext, config: NgHttpClientCacheConfig)(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Boolean = {
    config.methods.map(_.toLowerCase().trim).contains(ctx.request.method.toLowerCase())
  }

  private def statusMatch(ctx: NgTransformerResponseContext, config: NgHttpClientCacheConfig)(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Boolean = {
    config.status.contains(ctx.otoroshiResponse.status)
  }

  private def contentMatch(ctx: NgTransformerResponseContext, config: NgHttpClientCacheConfig)(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Boolean = {
    ctx.otoroshiResponse.contentType match {
      case None if config.mimeTypes.contains("*") => true
      case None                                   => false
      case Some(contentType)                      =>
        config.mimeTypes.exists(mt => contentType.startsWith(mt) || RegexPool.apply(mt).matches(contentType))
    }
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config =
      ctx.cachedConfig(internalName)(NgHttpClientCacheConfig.format).getOrElse(NgHttpClientCacheConfig.default)
    if (methodMatch(ctx, config) && statusMatch(ctx, config) && contentMatch(ctx, config)) {
      val now     = DateTime.now(DateTimeZone.UTC)
      val date    = now.toString("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
      val expires =
        now.plusSeconds(config.maxAgeSeconds.toInt).toString("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
      ctx.otoroshiResponse
        .copy(headers =
          ctx.otoroshiResponse.headers ++ Map(
            "Cache-Control" -> s"max-age=${config.maxAgeSeconds}",
            "Date"          -> date,
            "Expires"       -> expires,
            "ETag"          -> Codecs.sha1(s"$date -> ${ctx.request.domain}${ctx.request.uri}"),
            "Last-Modified" -> date,
            "Vary"          -> "Accept-Encoding"
          )
        )
        .rightf
    } else {
      ctx.otoroshiResponse.rightf
    }
  }
}


case class NgResponseCacheFilterConfig(
  statuses: Seq[Int] = Seq(200),
  methods: Seq[String] = Seq("GET"),
  paths: Seq[String] = Seq("/.*"),
  notStatuses: Seq[Int] = Seq.empty,
  notMethods: Seq[String] = Seq.empty,
  notPaths: Seq[String] = Seq.empty)

object NgResponseCacheFilterConfig {
  val format = new Format[NgResponseCacheFilterConfig] {
    override def reads(json: JsValue): JsResult[NgResponseCacheFilterConfig] = Try {
      NgResponseCacheFilterConfig(
        statuses = json.select("statuses")
          .asOpt[Seq[Int]]
          .orElse((json \ "statuses").asOpt[Seq[String]].map(_.map(_.toInt)))
          .getOrElse(Seq(200)),
        methods = json.select("methods").asOpt[Seq[String]].getOrElse(Seq("GET")),
        paths = json.select("paths").asOpt[Seq[String]].getOrElse(Seq.empty),
        notStatuses = json.select("notStatuses")
          .asOpt[Seq[Int]]
          .orElse((json \ "not" \ "statuses").asOpt[Seq[String]].map(_.map(_.toInt)))
          .getOrElse(Seq.empty),
        notMethods = json.select("notMethods")
          .asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: NgResponseCacheFilterConfig): JsValue = Json.obj(
      "statuses" -> o.statuses,
      "methods" -> o.methods,
      "paths" -> o.paths,
      "notStatuses" -> o.notStatuses,
      "notMethods" -> o.notMethods,
      "notPaths" -> o.notPaths
    )
  }
}

case class NgResponseCacheConfig (
  ttl: Long = 60.minutes.toMillis,
  maxSize: Long = 50L * 1024L * 1024L,
  autoClean: Boolean = true,
  filter: Option[NgResponseCacheFilterConfig] = None) extends NgPluginConfig {
  override def json: JsValue = NgResponseCacheConfig.format.writes(this)
}

object NgResponseCacheConfig {
  val format = new Format[NgResponseCacheConfig] {
    override def reads(json: JsValue): JsResult[NgResponseCacheConfig] = Try {
      NgResponseCacheConfig(
        ttl = json.select("ttl").asOpt[Long].getOrElse(60.minutes.toMillis),
        maxSize = json.select("maxSize").asOpt[Long].getOrElse(50L * 1024L * 1024L),
        autoClean = json.select("autoClean").asOpt[Boolean].getOrElse(true),
        filter = json.select("filter").asOpt[NgResponseCacheFilterConfig](NgResponseCacheFilterConfig.format.reads)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: NgResponseCacheConfig): JsValue = Json.obj(
      "ttl" -> o.ttl,
      "maxSize" -> o.maxSize,
      "autoClean" -> o.autoClean,
      "filter" -> o.filter.map(NgResponseCacheFilterConfig.format.writes)
    )
  }
}

class ResponseCache extends NgRequestTransformer {

  override def name: String = "Response Cache"
  override def defaultConfigObject: Option[NgPluginConfig] = NgResponseCacheConfig().some

  override def description: Option[String] =
    Some("""This plugin can cache responses from target services in the otoroshi datasstore
           |It also provides a debug UI at `/.well-known/otoroshi/bodylogger`.
    """.stripMargin)

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Other)
  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest, NgStep.TransformResponse)

  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def usesCallbacks: Boolean = false
  override def transformsError: Boolean = false
  override def transformsRequest: Boolean = true
  override def transformsResponse: Boolean = true
  override def isTransformRequestAsync: Boolean = true
  override def isTransformResponseAsync: Boolean = true

  private val ref    = new AtomicReference[(RedisClientMasterSlaves, ActorSystem)]()
  private val jobRef = new AtomicReference[Cancellable]()

  override def start(env: Env): Future[Unit] = {
    val actorSystem = ActorSystem("cache-redis")
    implicit val ec = actorSystem.dispatcher
    jobRef.set(env.otoroshiScheduler.scheduleAtFixedRate(1.minute, 10.minutes) {
      //jobRef.set(env.otoroshiScheduler.scheduleAtFixedRate(10.seconds, 10.seconds) {
      SchedulerHelper.runnable(
        try {
          cleanCache(env)
        } catch {
          case e: Throwable =>
            ResponseCache.logger.error("error while cleaning cache", e)
        }
      )
    })
    env.datastores.globalConfigDataStore.singleton()(ec, env).map { conf =>
      if ((conf.scripts.transformersConfig \ "ResponseCache").isDefined) {
        val redis: RedisClientMasterSlaves = {
          val master = RedisServer(
            host = (conf.scripts.transformersConfig \ "ResponseCache" \ "redis" \ "host")
              .asOpt[String]
              .getOrElse("localhost"),
            port = (conf.scripts.transformersConfig \ "ResponseCache" \ "redis" \ "port").asOpt[Int].getOrElse(6379),
            password = (conf.scripts.transformersConfig \ "ResponseCache" \ "redis" \ "password").asOpt[String]
          )
          val slaves = (conf.scripts.transformersConfig \ "ResponseCache" \ "redis" \ "slaves")
            .asOpt[Seq[JsObject]]
            .getOrElse(Seq.empty)
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
    Option(jobRef.get()).foreach(_.cancel())
    FastFuture.successful(())
  }

  private def cleanCache(env: Env): Future[Unit] = {
    implicit val ev  = env
    implicit val ec  = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    env.datastores.serviceDescriptorDataStore.findAll().flatMap { services =>
      val possibleServices = services.filter(s =>
        s.transformerRefs.nonEmpty && s.transformerRefs.contains("cp:otoroshi.plugins.cache.ResponseCache")
      )
      val functions        = possibleServices.map { service => () =>
      {
        val config  =
          ResponseCacheConfig(service.transformerConfig.select("ResponseCache").asOpt[JsObject].getOrElse(Json.obj()))
        val maxSize = config.maxSize
        if (config.autoClean) {
          env.datastores.rawDataStore.keys(s"${env.storageRoot}:noclustersync:cache:${service.id}:*").flatMap {
            keys =>
              if (keys.nonEmpty) {
                Source(keys.toList)
                  .mapAsync(1) { key =>
                    for {
                      size <- env.datastores.rawDataStore.strlen(key).map(_.getOrElse(0L))
                      pttl <- env.datastores.rawDataStore.pttl(key)
                    } yield (key, size, pttl)
                  }
                  .runWith(Sink.seq)
                  .flatMap { values =>
                    val globalSize = values.foldLeft(0L)((a, b) => a + b._2)
                    if (globalSize > maxSize) {
                      var acc    = 0L
                      val sorted = values
                        .sortWith((a, b) => a._3.compareTo(b._3) < 0)
                        .filter { t =>
                          if ((globalSize - acc) < maxSize) {
                            acc = acc + t._2
                            false
                          } else {
                            acc = acc + t._2
                            true
                          }
                        }
                        .map(_._1)
                      env.datastores.rawDataStore.del(sorted).map(_ => ())
                    } else {
                      ().future
                    }
                  }
              } else {
                ().future
              }
          }
        } else {
          ().future
        }
      }
      }
      Source(functions.toList)
        .mapAsync(1) { f => f() }
        .runWith(Sink.ignore)
        .map(_ => ())
    }
  }

  private def get(key: String)(implicit env: Env, ec: ExecutionContext): Future[Option[ByteString]] = {
    ref.get() match {
      case null  => env.datastores.rawDataStore.get(key)
      case redis => redis._1.get(key)
    }
  }

  private def set(key: String, value: ByteString, ttl: Option[Long])(implicit
                                                                     ec: ExecutionContext,
                                                                     env: Env
  ): Future[Boolean] = {
    ref.get() match {
      case null  => env.datastores.rawDataStore.set(key, value, ttl)
      case redis => redis._1.set(key, value, pxMilliseconds = ttl)
    }
  }

  private def filter(req: RequestHeader, config: NgResponseCacheConfig, statusOpt: Option[Int] = None): Boolean = {
    config.filter match {
      case None         => true
      case Some(filter) => {
        val matchPath      =
          if (filter.paths.isEmpty) true else filter.paths.exists(p => RegexPool.regex(p).matches(req.relativeUri))
        val matchNotPath   =
          if (filter.notPaths.isEmpty) false
          else filter.notPaths.exists(p => RegexPool.regex(p).matches(req.relativeUri))
        val methodMatch    =
          if (filter.methods.isEmpty) true else filter.methods.map(_.toLowerCase()).contains(req.method.toLowerCase())
        val methodNotMatch =
          if (filter.notMethods.isEmpty) false
          else filter.notMethods.map(_.toLowerCase()).contains(req.method.toLowerCase())
        val statusMatch    =
          if (filter.statuses.isEmpty) true
          else
            statusOpt match {
              case None         => true
              case Some(status) => filter.statuses.contains(status)
            }
        val statusNotMatch =
          if (filter.notStatuses.isEmpty) false
          else
            statusOpt match {
              case None         => true
              case Some(status) => filter.notStatuses.contains(status)
            }
        matchPath && methodMatch && statusMatch && !matchNotPath && !methodNotMatch && !statusNotMatch
      }
    }
  }

  private def couldCacheResponse(ctx: NgTransformerResponseContext, config: NgResponseCacheConfig): Boolean = {
    if (filter(ctx.request, config, Some(ctx.rawResponse.status))) {
      ctx.rawResponse.headers
        .get("Content-Length")
        .orElse(ctx.rawResponse.headers.get("content-Length"))
        .map(_.toInt) match {
        case Some(csize) if csize <= config.maxSize => true
        case Some(csize) if csize > config.maxSize  => false
        case _                                      => true
      }
    } else {
      false
    }
  }

  private def cachedResponse(
                              ctx: NgTransformerRequestContext,
                              config: NgResponseCacheConfig
                            )(implicit env: Env, ec: ExecutionContext): Future[Either[Unit, Option[JsValue]]] = {
    if (filter(ctx.request, config)) {
      get(
        s"${env.storageRoot}:noclustersync:cache:${ctx.route.id}:${ctx.request.method.toLowerCase()}-${ctx.request.relativeUri}"
      ).map {
        case None       => Right(None)
        case Some(json) => Right(Some(Json.parse(json.utf8String)))
      }
    } else {
      FastFuture.successful(Left(()))
    }
  }

  override def transformRequest(ctx: NgTransformerRequestContext)
                               (implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(NgResponseCacheConfig.format).getOrElse(NgResponseCacheConfig())

    cachedResponse(ctx, config).map {
      case Left(_)          => Right(ctx.otoroshiRequest)
      case Right(None)      =>
        Right(
          ctx.otoroshiRequest.copy(
            headers = ctx.otoroshiRequest.headers ++ Map("X-Otoroshi-Cache" -> "MISS")
          )
        )
      case Right(Some(res)) => {
        val status  = (res \ "status").as[Int]
        val body    = ByteString(ResponseCache.base64Decoder.decode((res \ "body").as[String]))
        val headers = (res \ "headers").as[Map[String, String]] ++ Map("X-Otoroshi-Cache" -> "HIT")
        val ctype   = (res \ "ctype").as[String]
        if (ResponseCache.logger.isDebugEnabled)
          ResponseCache.logger.debug(
            s"Serving '${ctx.request.method.toLowerCase()} - ${ctx.request.relativeUri}' from cache"
          )
        Left(Results.Status(status)(body).as(ctype).withHeaders(headers.toSeq: _*))
      }
    }
  }

  override def transformResponse(ctx: NgTransformerResponseContext)
                                    (implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx.cachedConfig(internalName)(NgResponseCacheConfig.format).getOrElse(NgResponseCacheConfig())

    if (couldCacheResponse(ctx, config)) {
      val size = new AtomicLong(0L)
      val ref  = new AtomicReference[ByteString](ByteString.empty)
      ctx.otoroshiResponse
        .copy(body = ctx.otoroshiResponse.body
        .wireTap(bs =>
          ref.updateAndGet { (t: ByteString) =>
            val currentSize = size.addAndGet(bs.size.toLong)
            if (currentSize <= config.maxSize) {
              t ++ bs
            } else {
              t
            }
          }
        )
        .alsoTo(Sink.onComplete {
          case _ =>
            if (size.get() < config.maxSize) {
              val ctype: String                = ctx.rawResponse.headers
                .get("Content-Type")
                .orElse(ctx.rawResponse.headers.get("content-type"))
                .getOrElse("text/plain")
              val headers: Map[String, String] = ctx.rawResponse.headers.filterNot { tuple =>
                val name = tuple._1.toLowerCase()
                name == "content-type" || name == "transfer-encoding" || name == "content-length"
              }
              val event                        = Json.obj(
                "status"  -> ctx.rawResponse.status,
                "headers" -> headers,
                "ctype"   -> ctype,
                "body"    -> ResponseCache.base64Encoder.encodeToString(ref.get().toArray)
              )
              if (ResponseCache.logger.isDebugEnabled)
                ResponseCache.logger.debug(
                  s"Storing '${ctx.request.method.toLowerCase()} - ${ctx.request.relativeUri}' in cache for the next ${config.ttl} ms."
                )
              set(
                s"${env.storageRoot}:noclustersync:cache:${ctx.route.id}:${ctx.request.method.toLowerCase()}-${ctx.request.relativeUri}",
                ByteString(Json.stringify(event)),
                Some(config.ttl)
              )
            }
        }))
        .right
        .vfuture
    } else {
      ctx
        .otoroshiResponse
        .right
        .vfuture
    }
  }
}

