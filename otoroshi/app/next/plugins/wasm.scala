package otoroshi.next.plugins

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.models.{NgMatchedRoute, NgRoute}
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.next.utils.JsonHelpers
import otoroshi.script._
import otoroshi.utils.TypedMap
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm._
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.libs.ws.WSCookie
import play.api.mvc.{Request, Result, Results}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class WasmRouteMatcher extends NgRouteMatcher {

  private val logger = Logger("otoroshi-plugins-wasm-route-matcher")

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Wasm Route Matcher"
  override def description: Option[String]                 = "This plugin can be used to use a wasm plugin as route matcher".some
  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig().some
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Wasm)
  override def steps: Seq[NgStep]                          = Seq(NgStep.MatchRoute)

  override def matches(ctx: NgRouteMatcherContext)(implicit env: Env): Boolean = {
    implicit val ec = WasmUtils.executor
    val config      = ctx
      .cachedConfig(internalName)(WasmConfig.format)
      .getOrElse(WasmConfig())
    val res         =
      Await.result(WasmUtils.execute(config, "matches_route", ctx.wasmJson, ctx.some, ctx.attrs.some), 10.seconds)
    res match {
      case Right(res) => {
        val response = Json.parse(res)
        (response \ "result").asOpt[Boolean].getOrElse(false)
      }
      case Left(err)  =>
        logger.error(s"error while calling wasm route matcher: ${err.prettify}")
        false
    }
  }
}

class WasmPreRoute extends NgPreRouting {

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Wasm pre-route"
  override def description: Option[String]                 = "This plugin can be used to use a wasm plugin as in pre-route phase".some
  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig().some
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Wasm)
  override def steps: Seq[NgStep]                          = Seq(NgStep.PreRoute)
  override def isPreRouteAsync: Boolean                    = true

  override def preRoute(
      ctx: NgPreRoutingContext
  )(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    val config = ctx
      .cachedConfig(internalName)(WasmConfig.format)
      .getOrElse(WasmConfig())
    val input  = ctx.wasmJson
    WasmUtils.execute(config, "pre_route", input, ctx.some, ctx.attrs.some).map {
      case Left(err)     => Left(NgPreRoutingErrorWithResult(Results.InternalServerError(err)))
      case Right(resStr) => {
        Try(Json.parse(resStr)) match {
          case Failure(e)        =>
            Left(NgPreRoutingErrorWithResult(Results.InternalServerError(Json.obj("error" -> e.getMessage))))
          case Success(response) => {
            val error = response.select("error").asOpt[Boolean].getOrElse(false)
            if (error) {
              val bodyAsBytes                  = response.select("body_bytes").asOpt[Array[Byte]].map(bytes => ByteString(bytes))
              val bodyBase64                   = response.select("body_base64").asOpt[String].map(str => ByteString(str).decodeBase64)
              val bodyJson                     = response
                .select("body_json")
                .asOpt[JsValue]
                .filter {
                  case JsNull => false
                  case _      => true
                }
                .map(str => ByteString(str.stringify))
              val bodyStr                      = response
                .select("body_str")
                .asOpt[String]
                .orElse(response.select("body").asOpt[String])
                .map(str => ByteString(str))
              val body: ByteString             =
                bodyStr.orElse(bodyJson).orElse(bodyBase64).orElse(bodyAsBytes).getOrElse(ByteString.empty)
              val headers: Map[String, String] = response
                .select("headers")
                .asOpt[Map[String, String]]
                .getOrElse(Map("Content-Type" -> "application/json"))
              val contentType                  = headers.getIgnoreCase("Content-Type").getOrElse("application/json")
              Left(
                NgPreRoutingErrorRaw(
                  code = response.select("status").asOpt[Int].getOrElse(200),
                  headers = headers,
                  contentType = contentType,
                  body = body
                )
              )
            } else {
              // TODO: handle attrs
              Right(Done)
            }
          }
        }
      }
    }
  }
}

class WasmBackend extends NgBackendCall {

  private val logger = Logger("otoroshi-plugins-wasm-backend")
  override def useDelegates: Boolean                       = false
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Wasm Backend"
  override def description: Option[String]                 = "This plugin can be used to use a wasm plugin as backend".some
  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig().some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Wasm)
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx
      .cachedConfig(internalName)(WasmConfig.format)
      .getOrElse(WasmConfig())
    ctx.wasmJson
      .flatMap(input => WasmUtils.execute(config, "call_backend", input, ctx.some, ctx.attrs.some))
      .map {
        case Right(output) =>
          val response                    =
            try {
              Json.parse(output)
            } catch {
              case e: Exception =>
                logger.error("error during json parsing", e)
                Json.obj()
            }
          val bodyAsBytes                 = response.select("body_bytes").asOpt[Array[Byte]].map(bytes => ByteString(bytes))
          val bodyBase64                  = response.select("body_base64").asOpt[String].map(str => ByteString(str).decodeBase64)
          val bodyJson                    = response
            .select("body_json")
            .asOpt[JsValue]
            .filter {
              case JsNull => false
              case _      => true
            }
            .map(str => ByteString(str.stringify))
          val bodyStr                     = response
            .select("body_str")
            .asOpt[String]
            .orElse(response.select("body").asOpt[String])
            .map(str => ByteString(str))
          val body: Source[ByteString, _] = bodyStr
            .orElse(bodyJson)
            .orElse(bodyBase64)
            .orElse(bodyAsBytes)
            .getOrElse(ByteString.empty)
            .chunks(16 * 1024)
          bodyResponse(
            status = response.select("status").asOpt[Int].getOrElse(200),
            headers = response
              .select("headers")
              .asOpt[Map[String, String]]
              .getOrElse(Map("Content-Type" -> "application/json")),
            body = body
          )
        case Left(value)   =>
          bodyResponse(
            status = 400,
            headers = Map.empty,
            body = Json.stringify(value).byteString.chunks(16 * 1024)
          )
      }
  }
}

class WasmAccessValidator extends NgAccessValidator {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Wasm)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Wasm Access control"
  override def description: Option[String]                 = "Delegate route access to a wasm plugin".some
  override def isAccessAsync: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig().some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx
      .cachedConfig(internalName)(WasmConfig.format)
      .getOrElse(WasmConfig())

    WasmUtils
      .execute(config, "access", ctx.wasmJson, ctx.some, ctx.attrs.some)
      .flatMap {
        case Right(res) =>
          val response = Json.parse(res)
          val result   = (response \ "result").asOpt[Boolean].getOrElse(false)
          if (result) {
            NgAccess.NgAllowed.vfuture
          } else {
            val error = (response \ "error").asOpt[JsObject].getOrElse(Json.obj())
            Errors
              .craftResponseResult(
                (error \ "message").asOpt[String].getOrElse("An error occured"),
                Results.Status((error \ "status").asOpt[Int].getOrElse(403)),
                ctx.request,
                None,
                None,
                attrs = ctx.attrs,
                maybeRoute = ctx.route.some
              )
              .map(r => NgAccess.NgDenied(r))
          }
        case Left(err)  =>
          Errors
            .craftResponseResult(
              (err \ "error").asOpt[String].getOrElse("An error occured"),
              Results.Status(400),
              ctx.request,
              None,
              None,
              attrs = ctx.attrs,
              maybeRoute = ctx.route.some
            )
            .map(r => NgAccess.NgDenied(r))
      }
  }
}

class WasmRequestTransformer extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Wasm, NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = false
  override def name: String                                = "Wasm Request Transformer"
  override def description: Option[String]                 =
    "Transform the content of the request with a wasm plugin".some
  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig().some

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx
      .cachedConfig(internalName)(WasmConfig.format)
      .getOrElse(WasmConfig())
    ctx.wasmJson
      .flatMap(input => {
        WasmUtils
          .execute(config, "transform_request", input, ctx.some, ctx.attrs.some)
          .map {
            case Right(res)  =>
              val response = Json.parse(res)

              Right(
                ctx.otoroshiRequest.copy(
                  headers = (response \ "headers").asOpt[Map[String, String]].getOrElse(ctx.otoroshiRequest.headers),
                  cookies = WasmUtils.convertJsonCookies(response).getOrElse(ctx.otoroshiRequest.cookies),
                  body = response.select("body").asOpt[String].map(b => ByteString(b)) match {
                    case None    => ctx.otoroshiRequest.body
                    case Some(b) => Source.single(b)
                  }
                )
              )
            case Left(value) => Left(Results.BadRequest(value))
          }
      })
  }
}

class WasmResponseTransformer extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Wasm, NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "Wasm Response Transformer"
  override def description: Option[String]                 =
    "Transform the content of a response with a wasm plugin".some
  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig().some

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx
      .cachedConfig(internalName)(WasmConfig.format)
      .getOrElse(WasmConfig())

    ctx.wasmJson
      .flatMap(input => {
        WasmUtils
          .execute(config, "transform_response", input, ctx.some, ctx.attrs.some)
          .map {
            case Right(res)  =>
              val response = Json.parse(res)

              ctx.otoroshiResponse
                .copy(
                  headers = (response \ "headers").asOpt[Map[String, String]].getOrElse(ctx.otoroshiResponse.headers),
                  status = (response \ "status").asOpt[Int].getOrElse(200),
                  cookies = WasmUtils.convertJsonCookies(response).getOrElse(ctx.otoroshiResponse.cookies),
                  body = response.select("body").asOpt[String].map(b => ByteString(b)) match {
                    case None    => ctx.otoroshiResponse.body
                    case Some(b) => Source.single(b)
                  }
                )
                .right
            case Left(value) => Left(Results.BadRequest(value))
          }
      })
  }
}

class WasmSink extends NgRequestSink {

  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Wasm)
  override def steps: Seq[NgStep]                          = Seq(NgStep.Sink)
  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def name: String                                = "Wasm Sink"
  override def description: Option[String]                 = "Handle unmatched requests with a wasm plugin".some
  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig().some

  override def matches(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean = {
    val config = WasmConfig.format.reads(ctx.config) match {
      case JsSuccess(value, _) => value
      case JsError(_)          => WasmConfig()
    }
    val fu     = WasmUtils
      .execute(
        config.copy(functionName = "sink_matches".some),
        "matches",
        ctx.wasmJson,
        FakeWasmContext(ctx.config).some,
        ctx.attrs.some
      )
      .map {
        case Left(error) => false
        case Right(res)  => {
          val response = Json.parse(res)
          (response \ "result").asOpt[Boolean].getOrElse(false)
        }
      }
    Await.result(fu, 10.seconds)
  }

  private def requestToWasmJson(
      body: Source[ByteString, _]
  )(implicit ec: ExecutionContext, env: Env): Future[JsValue] = {
    implicit val mat = env.otoroshiMaterializer
    body.runFold(ByteString.empty)(_ ++ _).map { rawBody =>
      Writes.arrayWrites[Byte].writes(rawBody.toArray[Byte])
    }
  }

  override def handleSync(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Result =
    Await.result(this.handle(ctx), 10.seconds)

  override def handle(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    val config = WasmConfig.format.reads(ctx.config) match {
      case JsSuccess(value, _) => value
      case JsError(_)          => WasmConfig()
    }
    requestToWasmJson(ctx.body).flatMap { body =>
      val input = ctx.wasmJson.asObject ++ Json.obj("body_bytes" -> body)
      WasmUtils
        .execute(config, "sink_handle", input, FakeWasmContext(ctx.config).some, ctx.attrs.some)
        .map {
          case Left(error) => Results.InternalServerError(error)
          case Right(res)  => {
            val response = Json.parse(res)

            val status = response
              .select("status")
              .asOpt[Int]
              .getOrElse(200)

            val _headers    = response
              .select("headers")
              .asOpt[Map[String, String]]
              .getOrElse(Map("Content-Type" -> "application/json"))

            val contentType = _headers
              .get("Content-Type")
              .orElse(_headers.get("content-type"))
              .getOrElse("application/json")

            val headers = _headers
              .filterNot(_._1.toLowerCase() == "content-type")

            val bodyAsBytes      = response.select("body_bytes").asOpt[Array[Byte]].map(bytes => ByteString(bytes))
            val bodyBase64       = response.select("body_base64").asOpt[String].map(str => ByteString(str).decodeBase64)
            val bodyJson         = response
              .select("body_json")
              .asOpt[JsValue]
              .filter {
                case JsNull => false
                case _      => true
              }
              .map(str => ByteString(str.stringify))
            val bodyStr          = response
              .select("body_str")
              .asOpt[String]
              .orElse(response.select("body").asOpt[String])
              .map(str => ByteString(str))
            val body: ByteString =
              bodyStr.orElse(bodyJson).orElse(bodyBase64).orElse(bodyAsBytes).getOrElse(ByteString.empty)

            Results
              .Status(status)(body)
              .withHeaders(headers.toSeq: _*)
              .as(contentType)
          }
        }
    }
  }
}

class WasmRequestHandler extends RequestHandler {

  override def deprecated: Boolean               = false
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def steps: Seq[NgStep]                = Seq(NgStep.HandlesRequest)
  override def core: Boolean                     = true
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Wasm)
  override def description: Option[String]       = "this plugin entirely handle request with a wasm plugin".some
  override def name: String                      = "Wasm request handler"
  override def configRoot: Option[String]        = "WasmRequestHandler".some
  override def defaultConfig: Option[JsObject]   = Json
    .obj(
      configRoot.get -> Json.obj(
        "domains" -> Json.obj(
          "my.domain.tld" -> WasmConfig().json
        )
      )
    )
    .some

  override def handledDomains(implicit ec: ExecutionContext, env: Env): Seq[String] = {
    env.datastores.globalConfigDataStore
      .latest()
      .plugins
      .config
      .select(configRoot.get)
      .asOpt[JsObject]
      .map(v => v.value.keys.toSeq)
      .getOrElse(Seq.empty)
  }

  private def requestToWasmJson(
      request: Request[Source[ByteString, _]]
  )(implicit ec: ExecutionContext, env: Env): Future[JsValue] = {
    if (request.theHasBody) {
      implicit val mat = env.otoroshiMaterializer
      request.body.runFold(ByteString.empty)(_ ++ _).map { rawBody =>
        JsonHelpers.requestToJson(request).asObject ++ Json.obj(
          "request_body_bytes" -> rawBody.toArray[Byte]
        )
      }
    } else {
      JsonHelpers.requestToJson(request).vfuture
    }
  }

  override def handle(
      request: Request[Source[ByteString, _]],
      defaultRouting: Request[Source[ByteString, _]] => Future[Result]
  )(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    val configmap = env.datastores.globalConfigDataStore
      .latest()
      .plugins
      .config
      .select(configRoot.get)
      .asOpt[JsObject]
      .map(v => v.value)
      .getOrElse(Map.empty[String, JsValue])
    configmap.get(request.theDomain) match {
      case None             => defaultRouting(request)
      case Some(configJson) => {
        WasmConfig.format.reads(configJson).asOpt match {
          case None         => defaultRouting(request)
          case Some(config) => {
            requestToWasmJson(request).flatMap { json =>
              val fakeCtx = FakeWasmContext(configJson)
              WasmUtils
                .execute(config, "handle_request", Json.obj("request" -> json), fakeCtx.some, None)
                .flatMap {
                  case Right(ok) => {
                    val response                     = Json.parse(ok)
                    val headers: Map[String, String] =
                      response.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
                    val contentLength: Option[Long]  = headers.getIgnoreCase("Content-Length").map(_.toLong)
                    val contentType: Option[String]  = headers.getIgnoreCase("Content-Type")
                    val status: Int                  = (response \ "status").asOpt[Int].getOrElse(200)
                    val cookies: Seq[WSCookie]       = WasmUtils.convertJsonCookies(response).getOrElse(Seq.empty)
                    val body: Source[ByteString, _]  =
                      response.select("body").asOpt[String].map(b => ByteString(b)) match {
                        case None    => ByteString.empty.singleSource
                        case Some(b) => Source.single(b)
                      }
                    Results
                      .Status(status)
                      .sendEntity(
                        HttpEntity.Streamed(
                          data = body,
                          contentLength = contentLength,
                          contentType = contentType
                        )
                      )
                      .withHeaders(headers.toSeq: _*)
                      .withCookies(cookies.map(_.toCookie): _*)
                      .vfuture
                  }
                  case Left(bad) => Results.InternalServerError(bad).vfuture
                }
            }
          }
        }
      }
    }
  }
}

case class FakeWasmContext(config: JsValue, id: Int = 0) extends NgCachedConfigContext {
  override def route: NgRoute = NgRoute.empty
}

case class WasmJobsConfig(
    uniqueId: String = "1",
    config: WasmConfig = WasmConfig(),
    kind: JobKind = JobKind.ScheduledEvery,
    instantiation: JobInstantiation = JobInstantiation.OneInstancePerOtoroshiInstance,
    initialDelay: Option[FiniteDuration] = None,
    interval: Option[FiniteDuration] = None,
    cronExpression: Option[String] = None,
    rawConfig: JsValue = Json.obj()
) {
  def json: JsValue = Json.obj(
    "unique_id"       -> uniqueId,
    "config"          -> config.json,
    "kind"            -> kind.name,
    "instantiation"   -> instantiation.name,
    "initial_delay"   -> initialDelay.map(_.toMillis).map(v => JsNumber(BigDecimal(v))).getOrElse(JsNull).asValue,
    "interval"        -> interval.map(_.toMillis).map(v => JsNumber(BigDecimal(v))).getOrElse(JsNull).asValue,
    "cron_expression" -> cronExpression.map(JsString.apply).getOrElse(JsNull).asValue
  )
}

object WasmJobsConfig {
  val default = WasmJobsConfig()
}

class WasmJob(config: WasmJobsConfig) extends Job {

  private val logger = Logger("otoroshi-wasm-job")
  private val attrs  = TypedMap.empty

  override def core: Boolean                     = true
  override def name: String                      = "Wasm Job"
  override def description: Option[String]       = "this job execute any given Wasm plugin".some
  override def defaultConfig: Option[JsObject]   = WasmJobsConfig.default.json.asObject.some
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Wasm)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def steps: Seq[NgStep]                = Seq(NgStep.Job)
  override def jobVisibility: JobVisibility      = JobVisibility.UserLand
  override def starting: JobStarting             = JobStarting.Automatically

  override def uniqueId: JobId                                                 = JobId(s"io.otoroshi.next.plugins.wasm.WasmJob#${config.uniqueId}")
  override def kind: JobKind                                                   = config.kind
  override def instantiation(ctx: JobContext, env: Env): JobInstantiation      = config.instantiation
  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = config.initialDelay
  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration]     = config.interval
  override def cronExpression(ctx: JobContext, env: Env): Option[String]       = config.cronExpression
  override def predicate(ctx: JobContext, env: Env): Option[Boolean]           = None // TODO: make it configurable base on global env ???

  override def jobStart(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = Try {
    WasmUtils
      .execute(
        config.config.copy(functionName = "job_start".some),
        "job_start",
        ctx.wasmJson,
        FakeWasmContext(config.config.json).some,
        attrs.some
      )
      .map {
        case Left(err) => logger.error(s"error while starting wasm job ${config.uniqueId}: ${err.stringify}")
        case Right(_)  => ()
      }
  } match {
    case Failure(e) =>
      logger.error("error during wasm job start", e)
      funit
    case Success(s) => s
  }
  override def jobStop(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit]  = Try {
    WasmUtils
      .execute(
        config.config.copy(functionName = "job_stop".some),
        "job_stop",
        ctx.wasmJson,
        FakeWasmContext(config.config.json).some,
        attrs.some
      )
      .map {
        case Left(err) => logger.error(s"error while stopping wasm job ${config.uniqueId}: ${err.stringify}")
        case Right(_)  => ()
      }
  } match {
    case Failure(e) =>
      logger.error("error during wasm job stop", e)
      funit
    case Success(s) => s
  }
  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit]   = Try {
    WasmUtils
      .execute(config.config, "job_run", ctx.wasmJson, FakeWasmContext(config.config.json).some, attrs.some)
      .map {
        case Left(err) => logger.error(s"error while running wasm job ${config.uniqueId}: ${err.stringify}")
        case Right(_)  => ()
      }
  } match {
    case Failure(e) =>
      logger.error("error during wasm job run", e)
      funit
    case Success(s) => s
  }
}

class WasmJobsLauncher extends Job {

  override def core: Boolean                                                   = true
  override def name: String                                                    = "Wasm Jobs Launcher"
  override def description: Option[String]                                     = "this job execute Wasm jobs".some
  override def defaultConfig: Option[JsObject]                                 = None
  override def categories: Seq[NgPluginCategory]                               = Seq(NgPluginCategory.Other)
  override def visibility: NgPluginVisibility                                  = NgPluginVisibility.NgInternal
  override def steps: Seq[NgStep]                                              = Seq(NgStep.Job)
  override def jobVisibility: JobVisibility                                    = JobVisibility.Internal
  override def starting: JobStarting                                           = JobStarting.Automatically
  override def uniqueId: JobId                                                 = JobId(s"io.otoroshi.next.plugins.wasm.WasmJobsLauncher")
  override def kind: JobKind                                                   = JobKind.ScheduledEvery
  override def instantiation(ctx: JobContext, env: Env): JobInstantiation      =
    JobInstantiation.OneInstancePerOtoroshiInstance
  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 5.seconds.some
  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration]     = 20.seconds.some
  override def cronExpression(ctx: JobContext, env: Env): Option[String]       = None
  override def predicate(ctx: JobContext, env: Env): Option[Boolean]           = None

  private val handledJobs = new TrieMap[String, Job]()

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = Try {
    val globalConfig            = env.datastores.globalConfigDataStore.latest()
    val wasmJobs                =
      globalConfig.plugins.ngPlugins().slots.filter(_.enabled).filter(_.plugin == s"cp:${classOf[WasmJob].getName}")
    val currentIds: Seq[String] = wasmJobs.map { job =>
      val actualJob        = new WasmJob(
        WasmJobsConfig(
          uniqueId = job.config.raw.select("unique_id").asString,
          config = WasmConfig.format
            .reads(job.config.raw.select("config").asOpt[JsValue].getOrElse(Json.obj()))
            .getOrElse(WasmConfig()),
          kind = JobKind(job.config.raw.select("kind").asString),
          instantiation = JobInstantiation(job.config.raw.select("instantiation").asString),
          initialDelay = job.config.raw.select("initial_delay").asOpt[Long].map(_.millis),
          interval = job.config.raw.select("interval").asOpt[Long].map(_.millis),
          cronExpression = job.config.raw.select("cron_expression").asOpt[String]
        )
      )
      val uniqueId: String = actualJob.uniqueId.id
      if (!handledJobs.contains(uniqueId)) {
        handledJobs.put(uniqueId, actualJob)
        env.jobManager.registerJob(actualJob)
      }
      uniqueId
    }
    handledJobs.values.toSeq.foreach { job =>
      val id: String = job.uniqueId.id
      if (!currentIds.contains(id)) {
        handledJobs.remove(id)
        env.jobManager.unregisterJob(job)
      }
    }
    funit
  } match {
    case Failure(e) =>
      e.printStackTrace()
      funit
    case Success(s) => s
  }
}

class WasmOPA extends NgAccessValidator {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Wasm)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Open Policy Agent (OPA)"
  override def description: Option[String]                 = "Repo policies as WASM modules".some
  override def isAccessAsync: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig(
    opa = true
  ).some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx
      .cachedConfig(internalName)(WasmConfig.format)
      .getOrElse(WasmConfig())

    WasmUtils
      .execute(config, "access", ctx.wasmJson, ctx.some, ctx.attrs.some)
      .flatMap {
        case Right(res) =>
          val result    = Json.parse(res).asOpt[JsArray].getOrElse(Json.arr())
          val canAccess = (result.value.head \ "result").asOpt[Boolean].getOrElse(false)
          if (canAccess) {
            NgAccess.NgAllowed.vfuture
          } else {
            NgAccess.NgDenied(Results.Forbidden).vfuture
          }
        case Left(err)  =>
          Errors
            .craftResponseResult(
              (err \ "error").asOpt[String].getOrElse("An error occured"),
              Results.Status(400),
              ctx.request,
              None,
              None,
              attrs = ctx.attrs,
              maybeRoute = ctx.route.some
            )
            .map(r => NgAccess.NgDenied(r))
      }
  }
}

class WasmRouter extends NgRouter {

  override def steps: Seq[NgStep]                = Seq(NgStep.Router)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Wasm)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Wasm Router"
  override def description: Option[String]                 =
    "Can decide for routing with a wasm plugin".some
  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig().some

  override def findRoute(ctx: NgRouterContext)(implicit env: Env, ec: ExecutionContext): Option[NgMatchedRoute] = {
    val config = WasmConfig.format.reads(ctx.config).getOrElse(WasmConfig())
    Await.result(
      WasmUtils.execute(config, "find_route", ctx.json, FakeWasmContext(ctx.config).some, ctx.attrs.some),
      3.seconds
    ) match {
      case Right(res) =>
        val response = Json.parse(res)
        Try {
          NgMatchedRoute(
            route = NgRoute.fmt.reads(response.select("route").asValue).get,
            path = response.select("path").asString,
            pathParams = response
              .select("path_params")
              .asOpt[Map[String, String]]
              .map(m => scala.collection.mutable.HashMap.apply(m.toSeq: _*))
              .getOrElse(scala.collection.mutable.HashMap.empty),
            noMoreSegments = response.select("no_more_segments").asOpt[Boolean].getOrElse(false)
          )
        }.toOption
      case Left(_)    => None
    }
  }
}
