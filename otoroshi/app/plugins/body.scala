package otoroshi.plugins.loggers

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.ByteString
import com.google.common.base.Charsets
import otoroshi.env.Env
import otoroshi.events._
import models.ServiceDescriptor
import org.joda.time.DateTime
import otoroshi.script._
import play.api.libs.json._
import play.api.mvc.{RequestHeader, Result, Results}
import redis.{RedisClientMasterSlaves, RedisServer}
import otoroshi.security.OtoroshiClaim
import otoroshi.utils.json.JsonImplicits._
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.future.Implicits._
import kaleidoscope._
import otoroshi.utils.RegexPool

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

case class BodyLoggerFilterConfig(json: JsValue) {
  lazy val statuses: Seq[Int] = (json \ "statuses")
    .asOpt[Seq[Int]]
    .orElse((json \ "statuses").asOpt[Seq[String]].map(_.map(_.toInt)))
    .getOrElse(Seq.empty)
  lazy val methods: Seq[String] = (json \ "methods").asOpt[Seq[String]].getOrElse(Seq.empty)
  lazy val paths: Seq[String]   = (json \ "paths").asOpt[Seq[String]].getOrElse(Seq.empty)
  lazy val notStatuses: Seq[Int] = (json \ "not" \ "statuses")
    .asOpt[Seq[Int]]
    .orElse((json \ "not" \ "statuses").asOpt[Seq[String]].map(_.map(_.toInt)))
    .getOrElse(Seq.empty)
  lazy val notMethods: Seq[String] = (json \ "not" \ "methods").asOpt[Seq[String]].getOrElse(Seq.empty)
  lazy val notPaths: Seq[String] = (json \ "not" \ "paths")
    .asOpt[Seq[String]]
    .getOrElse(Seq.empty) :+ "\\/\\.well-known\\/otoroshi\\/bodylogge.*"
}

case class BodyLoggerConfig(json: JsValue) {
  lazy val enabled: Boolean         = (json \ "enabled").asOpt[Boolean].getOrElse(true)
  lazy val log: Boolean             = (json \ "log").asOpt[Boolean].getOrElse(true)
  lazy val store: Boolean           = (json \ "store").asOpt[Boolean].getOrElse(false)
  lazy val ttl: Long                = (json \ "ttl").asOpt[Long].getOrElse(5.minutes.toMillis)
  lazy val sendToAnalytics: Boolean = (json \ "sendToAnalytics").asOpt[Boolean].getOrElse(false)
  lazy val filter: Option[BodyLoggerFilterConfig] =
    (json \ "filter").asOpt[JsObject].map(o => BodyLoggerFilterConfig(o))
  lazy val hasFilter: Boolean = filter.isDefined
  lazy val maxSize: Long      = (json \ "maxSize").asOpt[Long].getOrElse(5L * 1024L * 1024L)
  lazy val password: String   = (json \ "password").asOpt[String].getOrElse("password")
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
    body: ByteString,
    from: String,
    ua: String
) extends AnalyticEvent {

  override def `@type`: String = "RequestBodyEvent"

  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)

  def toJson(implicit _env: Env): JsValue = Json.obj(
    "@type"      -> "RequestBodyEvent",
    "@id"        -> `@id`,
    "@timestamp" -> `@timestamp`,
    "@serviceId" -> `@serviceId`,
    "@service"   -> `@service`,
    "reqId"      -> reqId,
    "method"     -> method,
    "url"        -> url,
    "headers"    -> headers,
    "body"       -> BodyLogger.base64Encoder.encodeToString(body.toArray)
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
    body: ByteString,
    from: String,
    ua: String
) extends AnalyticEvent {

  override def `@type`: String = "ResponseBodyEvent"

  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)

  def toJson(implicit _env: Env): JsValue = Json.obj(
    "@type"      -> "ResponseBodyEvent",
    "@id"        -> `@id`,
    "@timestamp" -> `@timestamp`,
    "@serviceId" -> `@serviceId`,
    "@service"   -> `@service`,
    "reqId"      -> reqId,
    "method"     -> method,
    "url"        -> url,
    "headers"    -> headers,
    "status"     -> status,
    "body"       -> BodyLogger.base64Encoder.encodeToString(body.toArray)
  )
}

object BodyLogger {
  val base64Encoder = java.util.Base64.getEncoder
}

class BodyLogger extends RequestTransformer {

  override def name: String = "Body logger"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "BodyLogger" -> Json.obj(
          "enabled"         -> true,
          "log"             -> true,
          "store"           -> false,
          "ttl"             -> 5L * 60L * 1000L,
          "sendToAnalytics" -> false,
          "maxSize"         -> 5L * 1024L * 1024L,
          "password"        -> "password",
          "filter" -> Json.obj(
            "statuses" -> Json.arr(),
            "methods"  -> Json.arr(),
            "paths"    -> Json.arr(),
            "not" -> Json.obj(
              "statuses" -> Json.arr(),
              "methods"  -> Json.arr(),
              "paths"    -> Json.arr(),
            )
          )
        )
      )
    )

  override def description: Option[String] =
    Some(
      """This plugin can log body present in request and response. It can just logs it, store in in the redis store with a ttl and send it to analytics.
      |It also provides a debug UI at `/.well-known/otoroshi/bodylogger`.
      |
      |This plugin can accept the following configuration
      |
      |```json
      |{
      |  "BodyLogger": {
      |    "enabled": true, // enabled logging
      |    "log": true, // just log it
      |    "store": false, // store bodies in datastore
      |    "ttl": 300000,  // store it for some times (5 minutes by default)
      |    "sendToAnalytics": false, // send bodies to analytics
      |    "maxSize": 5242880, // max body size (body will be cut after that)
      |    "password": "password", // password for the ui, if none, it's public
      |    "filter": { // log only for some status, method and paths
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
    """.stripMargin
    )

  private val ref = new AtomicReference[(RedisClientMasterSlaves, ActorSystem)]()

  override def start(env: Env): Future[Unit] = {
    val actorSystem = ActorSystem("body-logger-redis")
    implicit val ec = actorSystem.dispatcher
    env.datastores.globalConfigDataStore.singleton()(ec, env).map { conf =>
      if ((conf.scripts.transformersConfig \ "BodyLogger").isDefined) {
        val redis: RedisClientMasterSlaves = {
          val master = RedisServer(
            host =
              (conf.scripts.transformersConfig \ "BodyLogger" \ "redis" \ "host").asOpt[String].getOrElse("localhost"),
            port = (conf.scripts.transformersConfig \ "BodyLogger" \ "redis" \ "port").asOpt[Int].getOrElse(6379),
            password = (conf.scripts.transformersConfig \ "BodyLogger" \ "redis" \ "password").asOpt[String]
          )
          val slaves = (conf.scripts.transformersConfig \ "BodyLogger" \ "redis" \ "slaves")
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
    FastFuture.successful(())
  }

  private def decodeBase64(encoded: String): String = new String(OtoroshiClaim.decoder.decode(encoded), Charsets.UTF_8)

  private def extractUsernamePassword(header: String): Option[(String, String)] = {
    val base64 = header.replace("Basic ", "").replace("basic ", "")
    Option(base64)
      .map(decodeBase64)
      .map(_.split(":").toSeq)
      .flatMap(a => a.headOption.flatMap(head => a.lastOption.map(last => (head, last))))
  }

  private def set(key: String, value: ByteString, ttl: Option[Long])(implicit ec: ExecutionContext,
                                                                     env: Env): Future[Boolean] = {
    ref.get() match {
      case null  => env.datastores.rawDataStore.set(key, value, ttl)
      case redis => redis._1.set(key, value, pxMilliseconds = ttl)
    }
  }

  private def getAllKeys(pattern: String, desc: ServiceDescriptor)(implicit ec: ExecutionContext,
                                                                   env: Env,
                                                                   mat: Materializer): Future[Seq[JsValue]] = {
    ref.get() match {
      case null =>
        Source
          .future(env.datastores.rawDataStore.keys(pattern))
          .flatMapConcat(keys => Source(keys.toList))
          .mapAsync(1)(key => env.datastores.rawDataStore.pttl(key).map(ttl => (key, ttl)))
          .map {
            case (key, ttl) =>
              Json.obj(
                "reqId" -> key
                  .replace(s"${env.storageRoot}:bodies:${desc.id}:", "")
                  .replace(":request", "")
                  .replace(":response", ""),
                "ttl" -> ttl
              )
          }
          .toMat(Sink.seq)(Keep.right)
          .run()
      case redis =>
        Source
          .future(redis._1.keys(pattern))
          .flatMapConcat(keys => Source(keys.toList))
          .mapAsync(1)(key => redis._1.pttl(key).map(ttl => (key, ttl)))
          .map {
            case (key, ttl) =>
              Json.obj(
                "reqId" -> key
                  .replace(s"${env.storageRoot}:bodies:${desc.id}:", "")
                  .replace(":request", "")
                  .replace(":response", ""),
                "ttl" -> ttl
              )
          }
          .toMat(Sink.seq)(Keep.right)
          .run()
    }
  }

  private def deleteAll(pattern: String)(implicit ec: ExecutionContext, env: Env, mat: Materializer): Future[Unit] = {
    ref.get() match {
      case null =>
        env.datastores.rawDataStore.keys(pattern).flatMap(keys => env.datastores.rawDataStore.del(keys)).map(_ => ())
      case redis => redis._1.keys(pattern).flatMap(keys => redis._1.del(keys: _*)).map(_ => ())
    }
  }

  private def getAll(pattern: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    ref.get() match {
      case null =>
        env.datastores.rawDataStore
          .keys(pattern)
          .flatMap { keys =>
            if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]])
            else env.datastores.rawDataStore.mget(keys)
          }
          .map { seq =>
            seq.filter(_.isDefined).map(_.get).map(v => Json.parse(v.utf8String))
          }
      case redis =>
        redis._1
          .keys(pattern)
          .flatMap { keys =>
            if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]])
            else redis._1.mget(keys: _*)
          }
          .map { seq =>
            seq.filter(_.isDefined).map(_.get).map(v => Json.parse(v.utf8String))
          }
    }
  }

  private def getOne(key: String)(implicit ec: ExecutionContext, env: Env): Future[JsValue] = {
    ref.get() match {
      case null =>
        env.datastores.rawDataStore.get(key).map {
          case Some(value) => Json.parse(value.utf8String)
          case None        => JsNull
        }
      case redis =>
        redis._1.get(key).map {
          case Some(value) => Json.parse(value.utf8String)
          case None        => JsNull
        }
    }
  }

  private def filter(req: RequestHeader, config: BodyLoggerConfig, statusOpt: Option[Int] = None): Boolean = {
    config.filter match {
      case None => true
      case Some(filter) => {
        val matchPath =
          if (filter.paths.isEmpty) true else filter.paths.exists(p => RegexPool.regex(p).matches(req.relativeUri))
        val matchNotPath =
          if (filter.notPaths.isEmpty) true
          else filter.notPaths.exists(p => RegexPool.regex(p).matches(req.relativeUri))
        val methodMatch =
          if (filter.methods.isEmpty) true else filter.methods.map(_.toLowerCase()).contains(req.method.toLowerCase())
        val methodNotMatch =
          if (filter.notMethods.isEmpty) true
          else filter.notMethods.map(_.toLowerCase()).contains(req.method.toLowerCase())
        val statusMatch =
          if (filter.statuses.isEmpty) true
          else
            statusOpt match {
              case None         => true
              case Some(status) => filter.statuses.contains(status)
            }
        val statusNotMatch =
          if (filter.notStatuses.isEmpty) true
          else
            statusOpt match {
              case None         => true
              case Some(status) => filter.notStatuses.contains(status)
            }
        matchPath && methodMatch && statusMatch && !matchNotPath && !methodNotMatch && !statusNotMatch
      }
    }
  }

  private def passWithAuth(config: BodyLoggerConfig, ctx: TransformerRequestContext)(
      f: => Future[Either[Result, HttpRequest]]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[Result, HttpRequest]] = {
    ctx.request.headers.get("Authorization") match {
      case Some(auth) if auth.startsWith("Basic ") =>
        extractUsernamePassword(auth) match {
          case Some((username, password)) if username == "user" && password == config.password => f
          case _ =>
            Left(
              Results
                .Unauthorized(views.html.oto.error("You are not authorized here", env))
                .withHeaders("WWW-Authenticate" -> s"""Basic realm="bodies-${ctx.descriptor.id}"""")
            ).future
          //Left(Results.Forbidden(views.html.oto.error("Forbidden access", env))).future
        }
      case _ =>
        Left(
          Results
            .Unauthorized(views.html.oto.error("You are not authorized here", env))
            .withHeaders("WWW-Authenticate" -> s"""Basic realm="bodies-${ctx.descriptor.id}"""")
        ).future
    }
  }

  override def transformRequestWithCtx(
      ctx: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val config = BodyLoggerConfig(ctx.configFor("BodyLogger"))
    (ctx.rawRequest.method.toLowerCase(), ctx.rawRequest.path) match {
      case ("get", "/.well-known/otoroshi/plugins/bodylogger") =>
        passWithAuth(config, ctx) {
          FastFuture.successful(Left(Results.Ok(s"""<html>
           |  <head>
           |    <link rel="stylesheet" media="screen" href="/__otoroshi_assets/javascripts/bundle/backoffice.css">
           |    <style>
           |      body {
           |        font-family: monospace;
           |      }
           |      .selected {
           |        background-color: #f9b000 !important;
           |      }
           |    </style>
           |  </head>
           |  <body style="margin: 0px; display: flex; flex-direction: column; width: 100vw; height: 100vh;">
           |    <div style="display: flex; justify-content: center; align-items: center; font-weight: bold; background-color: #494948; color: #b5b3b3; padding: 10px;">
           |      request body debugger
           |    </div>
           |    <div style="display: flex; width: 100%; height: 100%;">
           |      <div style="display: flex; flex-direction: column; width: 20%; height: 100vh; overflow-y: auto;" id="list"></div>
           |      <div style="display: flex; flex-direction: column; width: 80%; height: 100vh; overflow-y: auto;" id="content"></div>
           |    </div>
           |    <script src="https://cdnjs.cloudflare.com/ajax/libs/jquery/3.4.1/jquery.min.js" integrity="sha256-CSXorXvZcTkaix6Yvo6HppcZGetbYMGWSFlBw8HfCJo=" crossorigin="anonymous"></script>
           |    <script src="https://cdnjs.cloudflare.com/ajax/libs/lodash.js/4.17.15/lodash.min.js" integrity="sha256-VeNaFBVDhoX3H+gJ37DpT/nTuZTdjYro9yBruHjVmoQ=" crossorigin="anonymous"></script>
           |    <script>
           |
           |      const escapeTypes = ['text/html', 'application/xml'];
           |
           |      const jsonTypes = ['application/json'];
           |
           |      const textualTypes = ['text/html', 'text/css', 'application/json', 'application/javascript', 'application/xml', 'text/plain'];
           |
           |      const imagesTypes = ['image/png', 'image/jpg', 'image/jpeg', 'image/svg'];
           |
           |      const supportedTypes = [...textualTypes].concat([...imagesTypes]);
           |
           |      function pre(code) {
           |        return `<pre style="padding: 10px; margin: 0px; max-height: 50%; background-color: #222; color: #eaeaea; overflow: auto;">$${code}</pre>`;
           |      }
           |
           |      function readableType(contentType, types) {
           |        const found = _.find(types, t => contentType.indexOf(t) > -1);
           |        if (found) {
           |          return true;
           |        } else {
           |          return false;
           |        }
           |      }
           |
           |      function renderValue(body, ctype) {
           |        if (readableType(ctype, jsonTypes)) {
           |          return pre(JSON.stringify(JSON.parse(decodeURIComponent(escape(window.atob(body)))), null, 2));
           |        } else if (readableType(ctype, escapeTypes)) {
           |          return pre(_.escape(decodeURIComponent(escape(window.atob(body)))));
           |        } else if (readableType(ctype, textualTypes)) {
           |          return pre(decodeURIComponent(escape(window.atob(body))));
           |        } else if (readableType(ctype, imagesTypes)) {
           |          return `<img style="max-height: 300px" src="data:$${ctype};base64, $${body}" />`;
           |        } else {
           |          return pre(body.match(/.{80}/g).join('\\n'));
           |        }
           |      }
           |
           |      function format(body, req) {
           |        const ctype = req.headers['Content-Type'] || req.headers['content-type'] || 'none';
           |        const isReadable = readableType(ctype, supportedTypes);
           |        if (isReadable) {
           |          return renderValue(body, ctype);
           |        } else {
           |          return pre(body.match(/.{80}/g).join('\\n'));
           |        }
           |      }
           |
           |      function renderRequestCell(req, idx) {
           |        if (idx % 2 === 0) {
           |          return `<div class="cell" data-reqId="$${req.reqId}" style="height: 30px; width: 100%; background-color: #ddd;cursor: pointer; padding: 5px; display: flex; flex-direction: column; justify-content: center; align-items: flex-start;">
           |          <span>req: $${req.reqId}</span>
           |          <span>exp: $${(req.ttl / 1000).toFixed(0)} sec.</span>
           |          </div>`;
           |        } else {
           |          return `<div class="cell" data-reqId="$${req.reqId}" style="height: 30px; width: 100%;cursor: pointer; padding: 5px; display: flex; flex-direction: column; justify-content: center; align-items: flex-start;">
           |          <span>req: $${req.reqId}</span>
           |          <span>exp: $${(req.ttl / 1000).toFixed(0)} sec.</span>
           |          </div>`;
           |        }
           |      }
           |
           |      function renderList(reqs) {
           |        const html = reqs.map((item, idx) => renderRequestCell(item, idx)).join('\\n');
           |        $$('#list').html(`<button type="button" id="reload">reload</button><button type="button" id="cleanup">cleanup</button>`+ html);
           |      }
           |
           |      function renderRequest(req) {
           |        let html = '';
           |        if (req.request && req.request.body) {
           |          const body = req.request.body;
           |          delete req.request.body;
           |          html = html + `<span style="font-weight: bold; margin-top: 10px; margin-bottom: 10px; margin-left: 5px;">request body</span>` + format(body, req.request);
           |        }
           |        if (req.response && req.response.body) {
           |          const body = req.response.body;
           |          delete req.response.body;
           |          html = html + `<span style="font-weight: bold;  margin-top: 10px; margin-bottom: 10px; margin-left: 5px;">response body</span>` + format(body, req.response);
           |        }
           |        html = pre(JSON.stringify(req, null, 2)) + html;
           |        $$('#content').html(html);
           |      }
           |
           |      function fetchAndRenderRequest(id) {
           |        fetch(`/.well-known/otoroshi/bodylogger/requests/$${id}.json`).then(r => r.json()).then(r => {
           |          $$('.cell').removeClass('selected');
           |          $$(`[data-reqid="$${id}"`).addClass('selected');
           |          renderRequest(r);
           |        });
           |      }
           |
           |      function reload() {
           |        fetch('/.well-known/otoroshi/bodylogger/requests.json').then(r => r.json()).then(r => {
           |          const arr = _.sortBy(r, i => i.reqId);
           |          renderList(arr);
           |          const first = arr[0];
           |          if (first) {
           |            fetchAndRenderRequest(first.reqId);
           |          }
           |        });
           |      }
           |
           |      reload();
           |
           |      $$('body').on('click', '.cell', function(e) {
           |        const reqId = $$(this).data('reqid');
           |        fetchAndRenderRequest(reqId);
           |      });
           |
           |      $$('body').on('click', '#reload', function(e) {
           |        reload();
           |      });
           |
           |      $$('body').on('click', '#cleanup', function(e) {
           |        fetch('/.well-known/otoroshi/bodylogger/requests.json', {
           |          method: 'DELETE'
           |        }).then(() => {
           |          reload();
           |        })
           |      });
           |
           |    </script>
           |  </body>
           |</html>
          """.stripMargin).as("text/html")))
        }
      case ("get", "/.well-known/otoroshi/plugins/bodylogger/requests.json") =>
        passWithAuth(config, ctx) {
          for {
            requests  <- getAllKeys(s"${env.storageRoot}:bodies:${ctx.descriptor.id}:*:request", ctx.descriptor)
            responses <- getAllKeys(s"${env.storageRoot}:bodies:${ctx.descriptor.id}:*:response", ctx.descriptor)
          } yield {
            val all: Seq[JsValue] = (requests ++ responses).groupBy(v => (v \ "reqId").as[String]).map(_._2.head).toSeq
            Left(Results.Ok(JsArray(all)))
          }
        }
      case ("delete", "/.well-known/otoroshi/plugins/bodylogger/requests.json") =>
        passWithAuth(config, ctx) {
          for {
            _ <- deleteAll(s"${env.storageRoot}:bodies:${ctx.descriptor.id}:*:request")
            _ <- deleteAll(s"${env.storageRoot}:bodies:${ctx.descriptor.id}:*:response")
          } yield {
            Left(Results.Ok(Json.obj("done" -> true)))
          }
        }
      case ("get", "/.well-known/otoroshi/plugins/bodylogger/bodies.json") =>
        passWithAuth(config, ctx) {
          for {
            requests  <- getAll(s"${env.storageRoot}:bodies:${ctx.descriptor.id}:*:request")
            responses <- getAll(s"${env.storageRoot}:bodies:${ctx.descriptor.id}:*:response")
          } yield {
            Left(Results.Ok(JsArray(requests ++ responses)))
          }
        }
      case ("get", r"/.well-known/otoroshi/plugins/bodylogger/requests/${id}@(.*).json") =>
        passWithAuth(config, ctx) {
          for {
            request  <- getOne(s"${env.storageRoot}:bodies:${ctx.descriptor.id}:$id:request")
            response <- getOne(s"${env.storageRoot}:bodies:${ctx.descriptor.id}:$id:response")
          } yield {
            Left(
              Results.Ok(
                Json.obj(
                  "request"  -> request,
                  "response" -> response
                )
              )
            )
          }
        }
      case _ => FastFuture.successful(Right(ctx.otoroshiRequest))
    }
  }

  override def transformRequestBodyWithCtx(
      ctx: TransformerRequestBodyContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    val config = BodyLoggerConfig(ctx.configFor("BodyLogger"))
    if (config.enabled && filter(ctx.request, config)) {
      val size = new AtomicLong(0L)
      val ref  = new AtomicReference[ByteString](ByteString.empty)
      ctx.body
        .wireTap(
          bs =>
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
              body = ref.get(),
              from = ctx.request.theIpAddress,
              ua = ctx.request.theUserAgent
            )
            if (config.log) {
              event.log()
            }
            if (config.sendToAnalytics) {
              event.toAnalytics()
            }
            if (config.store) {
              set(s"${env.storageRoot}:bodies:${ctx.descriptor.id}:${ctx.snowflake}:request",
                  ByteString(Json.stringify(event.toJson)),
                  Some(config.ttl))
            }
          }
        })
    } else {
      ctx.body
    }
  }

  override def transformResponseBodyWithCtx(
      ctx: TransformerResponseBodyContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    val config = BodyLoggerConfig(ctx.configFor("BodyLogger"))
    if (config.enabled && filter(ctx.request, config, Some(ctx.rawResponse.status))) {
      val size = new AtomicLong(0L)
      val ref  = new AtomicReference[ByteString](ByteString.empty)
      ctx.body
        .wireTap(
          bs =>
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
              body = ref.get(),
              from = ctx.request.theIpAddress,
              ua = ctx.request.theUserAgent
            )
            if (config.log) {
              event.log()
            }
            if (config.sendToAnalytics) {
              event.toAnalytics()
            }
            if (config.store) {
              set(s"${env.storageRoot}:bodies:${ctx.descriptor.id}:${ctx.snowflake}:response",
                  ByteString(Json.stringify(event.toJson)),
                  Some(config.ttl))
            }
          }
        })
    } else {
      ctx.body
    }
  }
}
