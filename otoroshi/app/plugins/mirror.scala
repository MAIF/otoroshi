package otoroshi.plugins.mirror

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import env.Env
import events.AuditEvent
import models.{ServiceDescriptor, Target}
import org.joda.time.DateTime
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.{EmptyBody, InMemoryBody, WSRequest, WSResponse}
import play.api.mvc.{RequestHeader, Result}
import utils.RequestImplicits._
import utils.http.Implicits._
import utils.{HeadersHelper, UrlSanitizer}

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Success

case class RequestContext(
 id: String,
 request: RequestHeader,
 started: AtomicBoolean,
 otoRequest: AtomicReference[HttpRequest],
 mirroredRequest: AtomicReference[HttpRequest],
 otoResponse: AtomicReference[HttpResponse],
 input: AtomicReference[ByteString],
 output: AtomicReference[ByteString],
 mirroredBody: AtomicReference[ByteString],
 mirroredResp: AtomicReference[WSResponse],
 done: Promise[Unit],
 mirrorDone: Promise[Unit],
 descriptor: ServiceDescriptor,
 config: MirroringPluginConfig
) {

  def generateEvent(env: Env): Unit = {
    if (config.generateEvents) {
      val e = MirroringEvent(env.snowflakeGenerator.nextIdStr(), env.env, this)
      e.toAnalytics()(env)
    }
  }

  def runMirrorRequest(env: Env): Unit = {
    started.compareAndSet(false, true)
    implicit val ec = env.otoroshiExecutionContext
    implicit val ev = env
    implicit val mat = env.otoroshiMaterializer
    val req = request
    val currentReqHasBody = req.theHasBody
    val httpRequest = otoRequest.get()
    val uri = Uri(config.to)
    val url = httpRequest.uri.copy(scheme = uri.scheme, authority = uri.authority.copy(
      host = uri.authority.host,
      port = uri.authority.port
    ))
    val mReq = httpRequest.copy(
      url = url.toString(),
      headers = httpRequest.headers.filterNot(_._1 == "Host") ++ Seq("Host" -> url.authority.host.toString())
    )
    mirroredRequest.set(mReq)
    val finalTarget: Target = Target(host = url.authority.host.toString(), scheme = url.scheme)
    val globalConfig = env.datastores.globalConfigDataStore.latest()(env.otoroshiExecutionContext, env)
    val clientReq = descriptor.useAkkaHttpClient match {
      case _ if finalTarget.mtlsConfig.mtls =>
        env.gatewayClient.akkaUrlWithTarget(
          UrlSanitizer.sanitize(url.toString()),
          finalTarget,
          descriptor.clientConfig
        )
      case true =>
        env.gatewayClient.akkaUrlWithTarget(
          UrlSanitizer.sanitize(url.toString()),
          finalTarget,
          descriptor.clientConfig
        )
      case false =>
        env.gatewayClient.urlWithTarget(
          UrlSanitizer.sanitize(url.toString()),
          finalTarget,
          descriptor.clientConfig
        )
    }
    val body =
      if (currentReqHasBody) InMemoryBody(input.get())
      else EmptyBody
    val builder: WSRequest = clientReq
      .withRequestTimeout(
        descriptor.clientConfig.extractTimeout(req.relativeUri,
          _.callAndStreamTimeout,
          _.callAndStreamTimeout)
      )
      .withMethod(httpRequest.method)
      .withHttpHeaders(
        (HeadersHelper
          .addClaims(httpRequest.headers, httpRequest.claims, descriptor)
          .filterNot(_._1 == "Host")
          .filterNot(_._1 == "Cookie") ++ Seq("Host" -> url.authority.host.toString())): _*
      )
      .withCookies(httpRequest.cookies: _*)
      .withFollowRedirects(false)
      .withMaybeProxyServer(
        descriptor.clientConfig.proxy.orElse(globalConfig.proxies.services)
      )
    val builderWithBody = if (currentReqHasBody) {
      builder.withBody(body)
    } else {
      builder
    }
    builderWithBody.stream().map { resp =>
      if (config.shouldCaptureResponse) {
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

case class MirroringEvent(`@id`: String, `@env`: String, ctx: RequestContext, `@timestamp`: DateTime = DateTime.now())
  extends AuditEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"

  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"        -> `@id`,
    "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"      -> `@type`,
    "@product"   -> _env.eventsName,
    "@serviceId" -> `@serviceId`,
    "@service"   -> `@service`,
    "@env"       -> `@env`,
    "audit"      -> "MirroringEvent",
    "mirroring"    -> Json.obj(
      "reqId" -> ctx.id,
      "requestToOto" -> Json.obj(
        "url" -> ctx.request.theUrl,
        "method" -> ctx.request.method,
        "headers" -> ctx.request.headers.toSimpleMap,
        "version" -> ctx.request.version,
        "cookies" -> JsArray(ctx.request.cookies.toSeq.map(c => Json.obj(
          "name" -> c.name,
          "value" -> c.value,
          "domain" -> c.domain.map(JsString.apply).getOrElse(JsNull).as[JsValue],
          "path" -> c.path,
          "maxAge" -> c.maxAge.map(v => JsNumber(BigDecimal(v))).getOrElse(JsNull).as[JsValue],
          "secure" -> c.secure,
          "httpOnly" -> c.httpOnly,
        )))
      ),
      "requestToTarget" -> ctx.otoRequest.get().json,
      "requestToMirroredTarget" -> ctx.mirroredRequest.get().json,
      "response" -> ctx.otoResponse.get().json,
      "inputBody" -> ctx.input.get().utf8String,
      "outputBody" -> ctx.output.get().utf8String,
      "mirroredBody" -> ctx.mirroredBody.get().utf8String,
      "mirroredResponse" -> Json.obj(
        "status" -> ctx.mirroredResp.get().status,
        "headers" -> ctx.mirroredResp.get().headers.mapValues(_.last),
        "cookies" -> JsArray(ctx.mirroredResp.get().cookies.map(c => Json.obj(
          "name" -> c.name,
          "value" -> c.value,
          "domain" -> c.domain.map(JsString.apply).getOrElse(JsNull).as[JsValue],
          "path" -> c.path.map(JsString.apply).getOrElse(JsNull).as[JsValue],
          "maxAge" -> c.maxAge.map(v => JsNumber(BigDecimal(v))).getOrElse(JsNull).as[JsValue],
          "secure" -> c.secure,
          "httpOnly" -> c.httpOnly,
        )))
      ),
      "serviceId" -> ctx.descriptor.id,
      "config" -> ctx.config.conf,
    )
  )
}

class MirroringPluginConfig(val conf: JsValue) {

  def shouldBeMirrored(request: RequestHeader): Boolean = {
    enabled // TODO: filter by path and method
  }

  lazy val to: String = (conf \ "to").as[String]
  lazy val enabled: Boolean = (conf \ "enabled").asOpt[Boolean].getOrElse(true)
  lazy val shouldCaptureResponse: Boolean = (conf \ "captureResponse").asOpt[Boolean].getOrElse(false)
  lazy val generateEvents: Boolean = (conf \ "generateEvents").asOpt[Boolean].getOrElse(false)
}

object MirroringPluginConfig {
  def apply(conf: JsValue) = new MirroringPluginConfig(conf)
}

class MirroringPlugin extends RequestTransformer {

  override def name: String = "Mirroring plugin"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "MirroringPlugin" -> Json.obj(
          "enabled" -> true,
          "to" -> "https://foo.bar.dev",
          "captureResponse" -> false,
          "generateEvents" -> false
        )
      )
    )

  override def description: Option[String] =
    Some("""This plugin will mirror every request to other targets
           |
           |This plugin can accept the following configuration
           |
           |```json
           |{
           |  "MirroringPlugin": {
           |    "enabled": true, // enabled mirroring
           |    "to": "https://foo.bar.dev", // the url of the service to mirror
           |  }
           |}
           |```
         """.stripMargin)

  private val inFlightRequests = new TrieMap[String, RequestContext]()

  override def afterRequest(ctx: AfterRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    inFlightRequests.remove(ctx.snowflake)
    ().future
  }

  override def beforeRequest(ctx: BeforeRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    val cfg = MirroringPluginConfig(ctx.configFor("MirroringPlugin"))
    if (cfg.shouldBeMirrored(ctx.request)) {
      val done = Promise[Unit]
      val mirrorDone = Promise[Unit]
      val context = RequestContext(
        id = ctx.snowflake,
        request = ctx.request,
        started = new AtomicBoolean(false),
        otoRequest = new AtomicReference[HttpRequest],
        mirroredRequest = new AtomicReference[HttpRequest],
        otoResponse = new AtomicReference[HttpResponse],
        input = new AtomicReference[ByteString](ByteString.empty),
        output = new AtomicReference[ByteString](ByteString.empty),
        mirroredBody = new AtomicReference[ByteString](ByteString.empty),
        mirroredResp = new AtomicReference[WSResponse](),
        done = done,
        mirrorDone = mirrorDone,
        descriptor = ctx.descriptor,
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

  override def transformErrorWithCtx(ctx: TransformerErrorContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    inFlightRequests.get(ctx.snowflake) match {
      case None =>
      case Some(context) =>
        context.otoResponse.set(ctx.otoroshiResponse)
        context.done.trySuccess(())
    }
    ctx.otoroshiResult.future
  }

  override def transformRequestWithCtx(ctx: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    inFlightRequests.get(ctx.snowflake) match {
      case None =>
      case Some(context) =>
        context.otoRequest.set(ctx.otoroshiRequest)
    }
    ctx.otoroshiRequest.right.future
  }

  override def transformResponseWithCtx(ctx: TransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {
    inFlightRequests.get(ctx.snowflake) match {
      case None =>
      case Some(context) =>
        context.otoResponse.set(ctx.otoroshiResponse)
    }
    ctx.otoroshiResponse.right.future
  }

  override def transformRequestBodyWithCtx(ctx: TransformerRequestBodyContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    ctx.body
      .alsoTo(Sink.foreach(bs => inFlightRequests.get(ctx.snowflake).foreach(_.input.getAndUpdate(v => v.concat(bs)))))
      .alsoTo(Sink.onComplete(t => ec.execute(() => inFlightRequests.get(ctx.snowflake).foreach(_.runMirrorRequest(env)))))
  }

  override def transformResponseBodyWithCtx(ctx: TransformerResponseBodyContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    val cfg = MirroringPluginConfig(ctx.configFor("MirroringPlugin"))
    inFlightRequests.get(ctx.snowflake).foreach { c =>
      if (!c.started.get()) {
        ec.execute(() => c.runMirrorRequest(env))
      }
    }
    if (cfg.shouldCaptureResponse) {
      inFlightRequests.get(ctx.snowflake) match {
        case None => ctx.body
        case Some(context) =>
          ctx.body
            .alsoTo(Sink.foreach(bs => context.output.getAndUpdate(v => v.concat(bs))))
            .alsoTo(Sink.onComplete { t =>
              context.otoResponse.set(ctx.otoroshiResponse)
              context.done.trySuccess(())
            })
      }
    } else {
      ctx.body
    }
  }
}
