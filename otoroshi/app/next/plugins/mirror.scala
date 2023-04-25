package otoroshi.next.plugins

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.AuditEvent
import otoroshi.models.Target
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api._
import otoroshi.plugins.mirror.MirroringPluginConfig
import otoroshi.utils.UrlSanitizer
import otoroshi.utils.cache.types.LegitTrieMap
import otoroshi.utils.http.Implicits._
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.{EmptyBody, InMemoryBody, WSRequest, WSResponse}
import play.api.mvc.{RequestHeader, Result}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util._

case class NgTrafficMirroringConfig(
    legacy: MirroringPluginConfig = MirroringPluginConfig(
      Json.obj(
        "to"               -> "https://foo.bar.dev",
        "enabled"          -> true,
        "capture_response" -> false,
        "generate_events"  -> false
      )
    )
) extends NgPluginConfig {
  override def json: JsValue = Json.obj(
    "to"               -> legacy.to,
    "enabled"          -> legacy.enabled,
    "capture_response" -> legacy.shouldCaptureResponse,
    "generate_events"  -> legacy.generateEvents
  )
}

object NgTrafficMirroringConfig {
  val format = new Format[NgTrafficMirroringConfig] {
    override def writes(o: NgTrafficMirroringConfig): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgTrafficMirroringConfig] = Try {
      NgTrafficMirroringConfig(
        legacy = MirroringPluginConfig(json)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

case class NgMirroringEvent(
    `@id`: String,
    `@env`: String,
    ctx: NgRequestContext,
    `@timestamp`: DateTime = DateTime.now()
) extends AuditEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"

  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  override def toJson(implicit _env: Env): JsValue =
    Json.obj(
      "@id"        -> `@id`,
      "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
      "@type"      -> `@type`,
      "@product"   -> _env.eventsName,
      "@serviceId" -> `@serviceId`,
      "@service"   -> `@service`,
      "@env"       -> `@env`,
      "audit"      -> "MirroringEvent",
      "mirroring"  -> Json.obj(
        "reqId"                   -> ctx.id,
        "requestToOto"            -> Json.obj(
          "url"     -> ctx.request.theUrl,
          "method"  -> ctx.request.method,
          "headers" -> ctx.request.headers.toSimpleMap,
          "version" -> ctx.request.version,
          "cookies" -> JsArray(
            ctx.request.cookies.toSeq.map(c =>
              Json.obj(
                "name"     -> c.name,
                "value"    -> c.value,
                "domain"   -> c.domain.map(JsString.apply).getOrElse(JsNull).as[JsValue],
                "path"     -> c.path,
                "maxAge"   -> c.maxAge.map(v => JsNumber(BigDecimal(v))).getOrElse(JsNull).as[JsValue],
                "secure"   -> c.secure,
                "httpOnly" -> c.httpOnly
              )
            )
          )
        ),
        "requestToTarget"         -> ctx.otoRequest.get().json,
        "requestToMirroredTarget" -> ctx.mirroredRequest.get().json,
        "response"                -> ctx.otoResponse.get().json,
        "inputBody"               -> ctx.input.get().utf8String,
        "outputBody"              -> ctx.output.get().utf8String,
        "mirroredBody"            -> ctx.mirroredBody.get().utf8String,
        "mirroredResponse"        -> Json.obj(
          "status"  -> ctx.mirroredResp.get().status,
          "headers" -> ctx.mirroredResp.get().headers.mapValues(_.last),
          "cookies" -> JsArray(
            ctx.mirroredResp
              .get()
              .cookies
              .map(c =>
                Json.obj(
                  "name"     -> c.name,
                  "value"    -> c.value,
                  "domain"   -> c.domain.map(JsString.apply).getOrElse(JsNull).as[JsValue],
                  "path"     -> c.path.map(JsString.apply).getOrElse(JsNull).as[JsValue],
                  "maxAge"   -> c.maxAge.map(v => JsNumber(BigDecimal(v))).getOrElse(JsNull).as[JsValue],
                  "secure"   -> c.secure,
                  "httpOnly" -> c.httpOnly
                )
              )
          )
        ),
        "serviceId"               -> ctx.route.id,
        "config"                  -> ctx.config.legacy.conf
      )
    )
}

case class NgRequestContext(
    id: String,
    request: RequestHeader,
    started: AtomicBoolean,
    otoRequest: AtomicReference[NgPluginHttpRequest],
    mirroredRequest: AtomicReference[NgPluginHttpRequest],
    otoResponse: AtomicReference[NgPluginHttpResponse],
    input: AtomicReference[ByteString],
    output: AtomicReference[ByteString],
    mirroredBody: AtomicReference[ByteString],
    mirroredResp: AtomicReference[WSResponse],
    done: Promise[Unit],
    mirrorDone: Promise[Unit],
    route: NgRoute,
    config: NgTrafficMirroringConfig
) {

  def generateEvent(env: Env): Unit = {
    if (config.legacy.generateEvents) {
      val e = NgMirroringEvent(env.snowflakeGenerator.nextIdStr(), env.env, this)
      e.toAnalytics()(env)
    }
  }

  def runMirrorRequest(env: Env): Unit = {
    started.compareAndSet(false, true)
    implicit val ec         = env.otoroshiExecutionContext
    implicit val ev         = env
    implicit val mat        = env.otoroshiMaterializer
    val req                 = request
    val currentReqHasBody   = req.theHasBody
    val httpRequest         = otoRequest.get()
    val uri                 = Uri(config.legacy.to)
    val url                 = httpRequest.uri.copy(
      scheme = uri.scheme,
      authority = uri.authority.copy(
        host = uri.authority.host,
        port = uri.authority.port
      )
    )
    val mReq                = httpRequest.copy(
      url = url.toString(),
      headers = httpRequest.headers.filterNot(_._1 == "Host") ++ Seq("Host" -> url.authority.host.toString())
    )
    mirroredRequest.set(mReq)
    val finalTarget: Target = Target(host = url.authority.host.toString(), scheme = url.scheme)
    val globalConfig        = env.datastores.globalConfigDataStore.latest()(env.otoroshiExecutionContext, env)
    val clientReq           = route.useAkkaHttpClient match {
      case _ if finalTarget.mtlsConfig.mtls =>
        env.gatewayClient.akkaUrlWithTarget(
          UrlSanitizer.sanitize(url.toString()),
          finalTarget,
          route.backend.client.legacy
        )
      case true                             =>
        env.gatewayClient.akkaUrlWithTarget(
          UrlSanitizer.sanitize(url.toString()),
          finalTarget,
          route.backend.client.legacy
        )
      case false                            =>
        env.gatewayClient.urlWithTarget(
          UrlSanitizer.sanitize(url.toString()),
          finalTarget,
          route.backend.client.legacy
        )
    }
    val body                =
      if (currentReqHasBody) InMemoryBody(input.get())
      else EmptyBody
    val builder: WSRequest  = clientReq
      .withRequestTimeout(
        route.backend.client.legacy.extractTimeout(req.relativeUri, _.callAndStreamTimeout, _.callAndStreamTimeout)
      )
      .withMethod(httpRequest.method)
      .withHttpHeaders(
        (httpRequest.headers.toSeq ++ Seq("Host" -> url.authority.host.toString())): _*
      )
      .withCookies(httpRequest.cookies: _*)
      .withFollowRedirects(false)
      .withMaybeProxyServer(
        route.backend.client.legacy.proxy.orElse(globalConfig.proxies.services)
      )
    val builderWithBody     = if (currentReqHasBody) {
      builder.withBody(body)
    } else {
      builder
    }
    builderWithBody.stream().map { resp =>
      if (config.legacy.shouldCaptureResponse) {
        resp.bodyAsSource.runFold(ByteString.empty)(_ ++ _).map { mb =>
          mirroredBody.set(mb)
          mirroredResp.set(resp)
          mirrorDone.trySuccess(())
        }
      } else {
        resp.ignore()
        mirroredBody.set(ByteString.empty)
        mirroredResp.set(resp)
        mirrorDone.trySuccess(())
      }
    }
  }
}

class NgTrafficMirroring extends NgRequestTransformer {

  override def name: String                                = "Traffic Mirroring"
  override def description: Option[String]                 = "This plugin will mirror every request to other targets".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgTrafficMirroringConfig().some
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Other)
  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest, NgStep.TransformResponse)

  private val inFlightRequests = new LegitTrieMap[String, NgRequestContext]()

  override def beforeRequest(
      ctx: NgBeforeRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    val cfg = ctx.cachedConfig(internalName)(NgTrafficMirroringConfig.format).getOrElse(NgTrafficMirroringConfig())
    if (cfg.legacy.shouldBeMirrored(ctx.request)) {
      val done       = Promise[Unit]
      val mirrorDone = Promise[Unit]
      val context    = NgRequestContext(
        id = ctx.snowflake,
        request = ctx.request,
        started = new AtomicBoolean(false),
        otoRequest = new AtomicReference[NgPluginHttpRequest],
        mirroredRequest = new AtomicReference[NgPluginHttpRequest],
        otoResponse = new AtomicReference[NgPluginHttpResponse],
        input = new AtomicReference[ByteString](ByteString.empty),
        output = new AtomicReference[ByteString](ByteString.empty),
        mirroredBody = new AtomicReference[ByteString](ByteString.empty),
        mirroredResp = new AtomicReference[WSResponse](),
        done = done,
        mirrorDone = mirrorDone,
        route = ctx.route,
        config = cfg
      )
      inFlightRequests.putIfAbsent(ctx.snowflake, context)
      done.future.andThen {
        case Success(_) => {
          mirrorDone.future.andThen {
            case Success(_) => {
              context.generateEvent(env)
            }
          }
        }
      }
    }
    ().future
  }

  override def afterRequest(
      ctx: NgAfterRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    inFlightRequests.remove(ctx.snowflake)
    ().vfuture
  }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    inFlightRequests.get(ctx.snowflake) match {
      case None          =>
      case Some(context) =>
        context.otoRequest.set(ctx.otoroshiRequest)
        ctx.otoroshiRequest.body
          .alsoTo(Sink.foreach(bs => context.input.getAndUpdate(v => v.concat(bs))))
          .alsoTo(
            Sink.onComplete(t => ec.execute(() => context.runMirrorRequest(env)))
          )
    }
    ctx.otoroshiRequest.right.vfuture
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    inFlightRequests.get(ctx.snowflake) match {
      case None          => ctx.otoroshiResponse.right.future
      case Some(context) =>
        val cfg = ctx.cachedConfig(internalName)(NgTrafficMirroringConfig.format).getOrElse(NgTrafficMirroringConfig())
        context.otoResponse.set(ctx.otoroshiResponse)
        if (!context.started.get()) {
          ec.execute(() => context.runMirrorRequest(env))
        }
        if (cfg.legacy.shouldCaptureResponse) {
          ctx.otoroshiResponse
            .copy(body =
              ctx.otoroshiResponse.body
                .alsoTo(Sink.foreach(bs => context.output.getAndUpdate(v => v.concat(bs))))
                .alsoTo(Sink.onComplete { t =>
                  context.otoResponse.set(ctx.otoroshiResponse)
                  context.done.trySuccess(())
                })
            )
            .right
            .future
        } else {
          ctx.otoroshiResponse.right.future
        }
    }
  }

  override def transformError(
      ctx: NgTransformerErrorContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[NgPluginHttpResponse] = {
    inFlightRequests.get(ctx.snowflake) match {
      case None          =>
      case Some(context) =>
        context.otoResponse.set(ctx.otoroshiResponse)
        context.done.trySuccess(())
    }
    ctx.otoroshiResponse.vfuture
  }
}
