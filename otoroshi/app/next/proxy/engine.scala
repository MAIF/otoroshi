package otoroshi.next.proxy

import akka.Done
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.models.RemainingQuotas
import otoroshi.next.models.{Backend, Route}
import otoroshi.next.proxy.ProxyEngineError.ResultProxyEngineError
import otoroshi.script.{HttpRequest, HttpResponse, RequestHandler}
import otoroshi.security.{IdGenerator, OtoroshiClaim}
import otoroshi.utils.UrlSanitizer
import otoroshi.utils.http.Implicits.{BetterStandaloneWSRequest, BetterStandaloneWSResponse}
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.http.WSCookieWithSameSite
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.libs.ws.WSResponse
import play.api.mvc.Results.Status
import play.api.mvc._

import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

class ProxyEngine() extends RequestHandler {

  private val logger = Logger("otoroshi-next-gen-proxy-engine")
  private val fakeFailureIndicator = new AtomicBoolean(false)
  private val routes = TrieMap.newBuilder[String, Route]
    .+=(Route.fake.id -> Route.fake)
    .result()

  override def name: String = "Otoroshi newest proxy engine"

  override def description: Option[String] = "This plugin holds the next generation otoroshi proxy engine implementation".some

  override def configRoot: Option[String] = "NextGenProxyEngine".some

  override def defaultConfig: Option[JsObject] = {
    Json.obj(
      configRoot.get -> Json.obj(
        "enabled" -> true,
        "domains" -> Json.arr()
      )
    ).some
  }

  override def handledDomains(implicit ec: ExecutionContext, env: Env): Seq[String] = {
    val config = env.datastores.globalConfigDataStore.latest().plugins.config.select(configRoot.get)
    config.select("domains").asOpt[Seq[String]].getOrElse(Seq("*-next-gen.oto.tools"))
  }

  override def handle(request: Request[Source[ByteString, _]], defaultRouting: Request[Source[ByteString, _]] => Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    val handleStart = System.currentTimeMillis()
    val requestId = IdGenerator.uuid
    implicit val report = ExecutionReport(requestId)
    report.start("check-concurrent-requests")
    (for {
      _               <- handleConcurrentRequest()
      _               =  report.markDoneAndStart("find-route")
      route           <- findRoute(request)
      _               =  report.markDoneAndStart("tenant-check")
      _               <- handleTenantCheck(route)
      _               =  report.markDoneAndStart("check-global-maintenance")
      _               <- checkGlobalMaintenance(route)
      _               =  report.markDoneAndStart("call-before-request-callbacks")
      _               <- callPluginsBeforeRequestCallback(request, route)
      _               =  report.markDoneAndStart("call-pre-route-plugins")
      _               <- callPreRoutePlugins(request, route)
      // TODO: handle tcp/udp tunneling if not possible as plugin
      _               =  report.markDoneAndStart("call-access-validator-plugins")
      _               <- callAccessValidatorPlugins(request, route)
      _               =  report.markDoneAndStart("enforce-global-limits")
      remQuotas       <- checkGlobalLimits(request, route) // generic.scala (1269)
      _               =  report.markDoneAndStart("choose-backend")
      result          <- callTarget(request, route) { backend =>
        report.markDoneAndStart("check-high-overhead")
        for {
          _             <- handleHighOverhead(request, route)
          _             =  report.markDoneAndStart("transform-requests")
          finalRequest  <- callRequestTransformer(request, route, backend)
          _             =  report.markDoneAndStart("transform-request-body")
          _             =  report.markDoneAndStart("call-backend")
          response      <- callBackend(request, finalRequest, route, backend)
          _             =  report.markDoneAndStart("transform-response")
          finalResp     <- callResponseTransformer(request, response, remQuotas, route, backend)
          _             =  report.markDoneAndStart("transform-response-body")
          _             =  report.markDoneAndStart("stream-response")
          clientResp    <- streamResponse(request, response, finalResp, route, backend)
          _             =  report.markDoneAndStart("call-after-request-callbacks")
          _             <- callPluginsAfterRequestCallback(request, route)
          _             =  report.markDoneAndStart("trigger-analytics")
          _             <- triggerProxyDone(request, response, route, backend)
        } yield clientResp
      }
    } yield {
      result
    }).value.flatMap {
      case Left(error)   =>
        report.markDoneAndStart("rendering intermediate result")
        report.markSuccess()
        error.asResult()
      case Right(result) =>
        report.markSuccess()
        result.future
    }.recover {
      case t: Throwable =>
        report.markFailure("last-recover", t)
        Results.InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> t.getMessage, "report" -> report.json))
    }.andThen {
      case _ =>
        report.markOverheadOut()
        logger.info(report.json.prettify)
        // logger.info(report.json.asObject.-("steps").prettify)
    }
  }

  def handleHighOverhead(value: Request[Source[ByteString, _]], route: Route)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Done] = {
    // TODO: handle high overhead alerting
    FEither.right(Done)
  }

  def handleConcurrentRequest()(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Done] = {
    // TODO: handle concurrent requests counter, limiting and alerting
    FEither.right(Done)
  }

  def handleTenantCheck(route: Route)(implicit ec: ExecutionContext, env: Env, report: ExecutionReport): FEither[ProxyEngineError, Done] = {
    // TODO: handle tenant per worker routing
    if (env.clusterConfig.mode.isWorker
        && env.clusterConfig.worker.tenants.nonEmpty
        && !env.clusterConfig.worker.tenants.contains(route.location.tenant)) {
      report.markFailure(s"this worker cannot serve tenant '${route.location.tenant.value}'")
      FEither.left(ResultProxyEngineError(Results.NotFound(Json.obj("error" -> "not_found", "error_description" -> "no route found !"))))
    } else {
      FEither.right(Done)
    }
  }

  def findRoute(request: Request[Source[ByteString, _]])(implicit ec: ExecutionContext, env: Env, report: ExecutionReport): FEither[ProxyEngineError, Route] = {
    // TODO: we need something smarter, sort paths by length when there is a wildcard, then same for domains. We need to aggregate on domains
    routes.values.find(r => r.matches(request)) match {
      case Some(route) => FEither.right(route)
      case None =>
        report.markFailure(s"route not found for domain: '${request.theDomain}${request.thePath}'")
        FEither.left(ResultProxyEngineError(Results.NotFound(Json.obj("error" -> "not_found", "error_description" -> "no route found !"))))
    }
  }

  def checkGlobalMaintenance(route: Route)(implicit ec: ExecutionContext, env: Env, report: ExecutionReport): FEither[ProxyEngineError, Done] = {
    if (route.id != env.backOfficeServiceId && env.datastores.globalConfigDataStore.latest().maintenanceMode) {
      report.markFailure(s"global maintenance activated")
      FEither.left(ResultProxyEngineError(Results.ServiceUnavailable(Json.obj("error" -> "service_unavailable", "error_description" -> "Service in maintenance mode"))))
    } else {
      FEither.right(Done)
    }
  }

  def callPluginsBeforeRequestCallback(request: Request[Source[ByteString, _]], route: Route)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Done] = {
    // TODO: implements
    FEither.right(Done)
  }
  def callPluginsAfterRequestCallback(request: Request[Source[ByteString, _]], route: Route)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Done] =  {
    // TODO: implements
    FEither.right(Done)
  }
  def callPreRoutePlugins(request: Request[Source[ByteString, _]], route: Route)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Done] = {
    // TODO: implements
    FEither.right(Done)
  }
  def callAccessValidatorPlugins(request: Request[Source[ByteString, _]], route: Route)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Done] = {
    // TODO: implements
    FEither.right(Done)
  }
  def checkGlobalLimits(request: Request[Source[ByteString, _]], route: Route) (implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, RemainingQuotas] = {
    // TODO: implements
    FEither.right(RemainingQuotas())
  }
  def callTarget(request: Request[Source[ByteString, _]], route: Route)(f: Backend => FEither[ProxyEngineError, Result])(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Result] = {
    // TODO: implements
    // TODO: handle circuit breaker and target stuff
    val backend = route.target.backends.head
    f(backend)
  }
  def maybeStrippedUri(req: RequestHeader, rawUri: String, route: Route): String = {
    val allPaths = route.frontend.domains.map(_.path)
    val root        = req.relativeUri
    val rootMatched = allPaths match { //rootMatched was this.matchingRoot
      case ps if ps.isEmpty => None
      case ps               => ps.find(p => root.startsWith(p))
    }
    rootMatched
      .filter(m => route.frontend.stripPath && root.startsWith(m))
      .map(m => root.replaceFirst(m.replace(".", "\\."), ""))
      .getOrElse(rawUri)
  }
  def callRequestTransformer(request: Request[Source[ByteString, _]], route: Route, backend: Backend)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, HttpRequest] = {
    // TODO: implements
    val wsCookiesIn = request.cookies.toSeq.map(c =>
      WSCookieWithSameSite(
        name = c.name,
        value = c.value,
        domain = c.domain,
        path = Option(c.path),
        maxAge = c.maxAge.map(_.toLong),
        secure = c.secure,
        httpOnly = c.httpOnly,
        sameSite = c.sameSite
      )
    )
    val target = backend.toTarget
    val root   = route.target.root
    val rawUri = request.relativeUri.substring(1)
    val uri    = maybeStrippedUri(request, rawUri, route)
    FEither.right(HttpRequest(
      // url = s"${request.theProtocol}://${request.theHost}${request.relativeUri}",
      url = s"${target.scheme}://${target.host}$root$uri",
      method = request.method,
      headers = request.headers.toSimpleMap - "Host" - "host" + ("Host" -> backend.hostname), // TODO: this will be a plugin
      cookies = wsCookiesIn,
      version = request.version,
      clientCertificateChain = request.clientCertificateChain,
      target = backend.toTarget.some,
      claims = OtoroshiClaim( // TODO: change
        iss = "otoroshi",
        sub = "route",
        aud = "client",
        exp = DateTime.now().plusSeconds(30).getMillis,
        iat = DateTime.now().getMillis,
        jti = IdGenerator.uuid,
      ),
      body = () => request.body
    ))
  }
  def callBackend(rawRequest: Request[Source[ByteString, _]], request : HttpRequest, route: Route, backend: Backend)(implicit ec: ExecutionContext, env: Env, report: ExecutionReport): FEither[ProxyEngineError, WSResponse] = {
    // TODO: implements
    val currentReqHasBody = rawRequest.theHasBody
    val wsCookiesIn = request.cookies
    val clientReq = env.gatewayClient.urlWithTarget(
      UrlSanitizer.sanitize(request.url),
      backend.toTarget,
      route.client
    )
    val extractedTimeout = route.client.extractTimeout(rawRequest.relativeUri, _.callAndStreamTimeout, _.callAndStreamTimeout)
    val builder          = clientReq
      .withRequestTimeout(extractedTimeout)
      .withFailureIndicator(fakeFailureIndicator)
      .withMethod(request.method)
      .withHttpHeaders(request.headers.filterNot(_._1 == "Cookie").toSeq: _*)
      .withCookies(wsCookiesIn: _*)
      .withFollowRedirects(false)
      .withMaybeProxyServer(
        route.client.proxy.orElse(env.datastores.globalConfigDataStore.latest().proxies.services)
      )

    // because writeableOf_WsBody always add a 'Content-Type: application/octet-stream' header
    val builderWithBody = if (currentReqHasBody) {
      builder.withBody(rawRequest.body)
    } else {
      builder
    }

    report.markOverheadIn()
    FEither.fright(builderWithBody.stream().andThen {
      case Success(_) => report.startOverheadOut()
    })
  }
  def callResponseTransformer(rawRequest: Request[Source[ByteString, _]], response: WSResponse, quotas: RemainingQuotas, route: Route, backend: Backend)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, HttpResponse] = {
    // TODO: implements
    FEither.right(HttpResponse(
      status = response.status,
      headers = response.headers.mapValues(_.head),
      cookies = response.cookies,
      body = () => response.bodyAsSource
    ))
  }
  def streamResponse(rawRequest: Request[Source[ByteString, _]], rawResponse: WSResponse, response: HttpResponse, route: Route, backend: Backend)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Result] = {
    // TODO: implements
    val contentType: Option[String] = response.headers
      .get("Content-Type")
      .orElse(response.headers.get("content-type"))
    val contentLength: Option[Long] = response.headers
      .get("Content-Length")
      .orElse(response.headers.get("content-length"))
      .orElse(rawResponse.contentLengthStr)
      .map(_.toLong)
    val cookies = response.cookies.map {
      case c: WSCookieWithSameSite =>
        Cookie(
          name = c.name,
          value = c.value,
          maxAge = c.maxAge.map(_.toInt),
          path = c.path.getOrElse("/"),
          domain = c.domain,
          secure = c.secure,
          httpOnly = c.httpOnly,
          sameSite = c.sameSite
        )
      case c                       => {
        val sameSite: Option[Cookie.SameSite] = rawResponse.headers.get("Set-Cookie").flatMap { values =>
          values
            .find { sc =>
              sc.startsWith(s"${c.name}=${c.value}")
            }
            .flatMap { sc =>
              sc.split(";")
                .map(_.trim)
                .find(p => p.toLowerCase.startsWith("samesite="))
                .map(_.replace("samesite=", "").replace("SameSite=", ""))
                .flatMap(Cookie.SameSite.parse)
            }
        }
        Cookie(
          name = c.name,
          value = c.value,
          maxAge = c.maxAge.map(_.toInt),
          path = c.path.getOrElse("/"),
          domain = c.domain,
          secure = c.secure,
          httpOnly = c.httpOnly,
          sameSite = sameSite
        )
      }
    }
    val res = Status(response.status)
      .sendEntity(
        HttpEntity.Streamed(
          response.body(),
          contentLength,
          contentType
        )
      )
      .withHeaders(
        response.headers.filterNot { h =>
          val lower = h._1.toLowerCase()
          lower == "content-type" || lower == "set-cookie" || lower == "transfer-encoding"
        }.toSeq: _*
      )
      .withCookies(cookies: _*)
    contentType match {
      case None      => FEither.right(res)
      case Some(ctp) => FEither.right(res.as(ctp))
    }
  }
  def triggerProxyDone(rawRequest: Request[Source[ByteString, _]], rawResponse: WSResponse, route: Route, backend: Backend)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Done] = {
    // TODO: implements
    FEither.right(Done)
  }
}

sealed trait ProxyEngineError {
  def asResult()(implicit ec: ExecutionContext, env: Env): Future[Result]
}
object ProxyEngineError {
  // TODO: we need something better that will handler default error return of otoroshi Errors.craftResponseResult
  case class ResultProxyEngineError(result: Result) extends ProxyEngineError {
    override def asResult()(implicit ec: ExecutionContext, env: Env): Future[Result] = result.future
  }
}

object ExecutionReport {
  def apply(id: String): ExecutionReport = new ExecutionReport(id, DateTime.now())
}

sealed trait ExecutionReportState {
  def name: String
  def json: JsValue = JsString(name)
}
object ExecutionReportState {
  case object Created    extends ExecutionReportState { def name: String = "Created"    }
  case object Running    extends ExecutionReportState { def name: String = "Running"    }
  case object Successful extends ExecutionReportState { def name: String = "Successful" }
  case object Failed     extends ExecutionReportState { def name: String = "Failed"     }
}

case class ExecutionReportStep(task: String, start: Long, stop: Long, duration: Long, ctx: JsValue = JsNull) {
  def json: JsValue = Json.obj(
    "task" -> task,
    "start" -> start,
    "start_fmt" -> new DateTime(start).toString(),
    "stop" -> stop,
    "stop_fmt" -> new DateTime(stop).toString(),
    "duration" -> duration,
    "ctx" -> ctx
  )
}

class ExecutionReport(id: String, creation: DateTime) {

  // TODO: stack based implem
  var currentTask: String = ""
  var lastStart: Long = creation.toDate.getTime
  var state: ExecutionReportState = ExecutionReportState.Created
  var steps: Seq[ExecutionReportStep] = Seq.empty
  var gduration = -1L
  var overheadIn = -1L
  var overheadOut = -1L
  var overheadOutStart = creation.toDate.getTime
  var termination = creation

  def errToJson(error: Throwable): JsValue = {
    Json.obj(
      "message" -> error.getMessage,
      "cause" -> Option(error.getCause).map(errToJson).getOrElse(JsNull).as[JsValue],
      "stack" -> JsArray(error.getStackTrace.toSeq.map(el => Json.obj(
        "class_loader_name" -> el.getClassLoaderName,
        "module_name" -> el.getModuleName,
        "module_version" -> el.getModuleVersion,
        "declaring_class" -> el.getClassName,
        "method_name" -> el.getMethodName,
        "file_name" -> el.getFileName,
      )))
    )
  }

  def json: JsValue = Json.obj(
    "id" -> id,
    "creation" -> creation.toString(),
    "termination" -> termination.toString(),
    "duration" -> gduration,
    "overhead_in" -> overheadIn,
    "overhead_out" -> overheadOut,
    "state" -> state.json,
    "steps" -> JsArray(steps.map(_.json))
  )

  def markOverheadIn(): ExecutionReport = {
    overheadIn = System.currentTimeMillis() - creation.getMillis
    this
  }

  def startOverheadOut(): ExecutionReport = {
    overheadOutStart = System.currentTimeMillis()
    this
  }

  def markOverheadOut(): ExecutionReport = {
    overheadOut = System.currentTimeMillis() - overheadOutStart
    this
  }

  def markFailure(message: String): ExecutionReport = {
    state = ExecutionReportState.Failed
    val stop = System.currentTimeMillis()
    val duration = stop - lastStart
    steps = steps :+ ExecutionReportStep(currentTask, lastStart, stop, duration) :+ ExecutionReportStep(s"failure at: ${message}", stop, stop, 0L)
    lastStart = stop
    gduration = stop - creation.getMillis
    termination = new DateTime(stop)
    this
  }

  def markFailure(message: String, error: Throwable): ExecutionReport = {
    state = ExecutionReportState.Failed
    val stop = System.currentTimeMillis()
    val duration = stop - lastStart
    steps = steps :+ ExecutionReportStep(currentTask, lastStart, stop, duration) :+ ExecutionReportStep(s"failure at: ${message}", stop, stop, 0L, errToJson(error))
    lastStart = stop
    gduration = stop - creation.getMillis
    termination = new DateTime(stop)
    this
  }

  def markSuccess(): ExecutionReport = {
    state = ExecutionReportState.Successful
    val stop = System.currentTimeMillis()
    val duration = stop - lastStart
    steps = steps :+ ExecutionReportStep(currentTask, lastStart, stop, duration) :+ ExecutionReportStep(s"success", stop, stop, 0L)
    lastStart = stop
    gduration = stop - creation.getMillis
    termination = new DateTime(stop)
    this
  }

  def markDoneAndStart(task: String): ExecutionReport = {
    state = ExecutionReportState.Running
    val stop = System.currentTimeMillis()
    val duration = stop - lastStart
    steps = steps :+ ExecutionReportStep(currentTask, lastStart, stop, duration)
    lastStart = stop
    currentTask = task
    this
  }

  def start(task: String): ExecutionReport = {
    state = ExecutionReportState.Running
    lastStart = System.currentTimeMillis()
    currentTask = task
    this
  }
}

/*
 TODO

 - Loader Job to keep all route in memory
 - Some kind of reporting mecanism to keep track of everything (useful for debug)
 -

 */

/*

 new entities

 - Route
 - Backend

 */

/*
 needed plugins

 - redirection plugin
 - tcp/udp tunneling (?? - if possible)
 - headers verification (access validator)
 - readonly route (access validator)
 - readonly apikey (access validator)
 - jwt verifier (access validator)
 - apikey validation with constraints (access validator)
 - auth. module validation (access validator)
 - route restrictions (access validator)
 - public/private path plugin (access validator)
 - force https traffic (pre route)
 - allow http/1.0 traffic (pre route or access validator)
 - snowmonkey (??)
 - canary (??)
 - otoroshi state plugin (transformer)
 - otoroshi claim plugin (transformer)
 - headers manipulation (transformer)
 - headers validation (access validator)
 - endless response clients (transformer)
 - maintenance mode (transformer)
 - construction mode (transformer)
 - apikey extractor (pre route)
 - send otoroshi headers back (transformer)
 - override host header (transformer)
 - send xforwarded headers (transformer)
 - CORS (transformer)
 - gzip (transformer)
 - ip blocklist (access validator)
 - ip allowed list (access validator)
 - custom error templates (transformer)
 - snow monkey (transformer)

 */

/*
 killed features

 - sidecar (handled with kube stuff now)
 - local redirection

 */

object FEither {
  def apply[L, R](value: Future[Either[L, R]]): FEither[L, R] = new FEither[L, R](value)
  def apply[L, R](value: Either[L, R]): FEither[L, R] = new FEither[L, R](value.future)
  def left[L, R](value: L): FEither[L, R] = new FEither[L, R](Left(value).future)
  def fleft[L, R](value: Future[L])(implicit ec: ExecutionContext): FEither[L, R] = new FEither[L, R](value.map(v => Left(v)))
  def right[L, R](value: R): FEither[L, R] = new FEither[L, R](Right(value).future)
  def fright[L, R](value: Future[R])(implicit ec: ExecutionContext): FEither[L, R] = new FEither[L, R](value.map(v => Right(v)))
}

class FEither[L, R](val value: Future[Either[L, R]]) {

  def map[S](f: R => S)(implicit executor: ExecutionContext): FEither[L, S] = {
    val result = value.map {
      case Right(r)    => Right(f(r))
      case Left(error) => Left(error)
    }
    new FEither[L, S](result)
  }

  def flatMap[S](f: R => FEither[L, S])(implicit executor: ExecutionContext): FEither[L, S] = {
    val result = value.flatMap {
      case Right(r)    => f(r).value
      case Left(error) => Left(error).future
    }
    new FEither(result)
  }

  // def filter(f: R => Boolean)(implicit executor: ExecutionContext): FEither[String, R] = {
  //   val result = value.flatMap {
  //     case e @ Right(r) if f(r) => e.future
  //     case Right(_) => Left("predicate does not match").future
  //     case l @ Left(_)  => l.future
  //   }
  //   new FEither[String, R](result)
  // }
}

