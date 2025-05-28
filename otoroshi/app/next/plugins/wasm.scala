package otoroshi.next.plugins

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import io.otoroshi.wasm4s.scaladsl._
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.models.{NgMatchedRoute, NgRoute}
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.next.utils.JsonHelpers
import otoroshi.script._
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.{ConcurrentMutableTypedMap, TypedMap}
import otoroshi.wasm._
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.libs.ws.WSCookie
import play.api.mvc.{Request, Result, Results}

import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object BodyHelper {
  def extractBodyFrom(doc: JsValue): ByteString = extractBodyFromOpt(doc).getOrElse(ByteString.empty)
  def extractBodyFromOpt(doc: JsValue): Option[ByteString] = {
    val bodyAsBytes = doc.select("body_bytes").asOpt[Array[Byte]].map(bytes => ByteString(bytes))
    val bodyBase64  = doc.select("body_base64").asOpt[String].map(str => ByteString(str).decodeBase64)

    val bodyJson = doc
      .select("body_json")
      .asOpt[JsValue]
      .filter {
        case JsNull => false
        case _      => true
      }
      .map(str => ByteString(str.stringify))
    val bodyStr  = doc
      .select("body_str")
      .asOpt[String]
      .orElse(doc.select("body").asOpt[String])
      .map(str => ByteString(str))
    bodyStr
      .orElse(bodyJson)
      .orElse(bodyBase64)
      .orElse(bodyAsBytes)
  }
}

object AttrsHelper {

  def updateAttrs(attrs: TypedMap, from: JsValue): Unit = try {
    from.select("attrs").asOpt[JsObject].foreach { attrsJson =>
      val setAttrs   = attrsJson.select("set").asOpt[JsObject].getOrElse(Json.obj())
      val delAttrs   = attrsJson.select("del").asOpt[Seq[String]].getOrElse(Seq.empty)
      val clearAttrs = attrsJson.select("clear").asOpt[Boolean].getOrElse(false)
      if (clearAttrs) {
        attrs.clear()
      } else {
        delAttrs.foreach { key =>
          attrs match {
            case at: ConcurrentMutableTypedMap => {
              at.m.keySet.find(_.displayName.contains(key)).foreach(tk => at.remove(tk))
            }
            case _                             =>
          }
        }
        setAttrs.value.foreach {
          case (key, value) => {
            attrs match {
              case at: ConcurrentMutableTypedMap => {
                try {
                  otoroshi.wasm.Http.possibleAttributes.get(key).foreach { setter =>
                    at.m.put(setter.key, setter.f(value))
                  }
                } catch {
                  case t: Throwable => t.printStackTrace()
                }
              }
              case _                             =>
            }
          }
        }
      }
    }
  } catch {
    case t: Throwable => t.printStackTrace()
  }
}

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
    implicit val ec = env.wasmIntegration.executionContext
    val config      = ctx
      .cachedConfig(internalName)(WasmConfig.format)
      .getOrElse(WasmConfig())
    val fu          = env.wasmIntegration.wasmVmFor(config).flatMap {
      case None                    => Left(Json.obj("error" -> "plugin not found")).vfuture
      case Some((vm, localConfig)) =>
        vm.call(
          WasmFunctionParameters.ExtismFuntionCall(
            config.functionName.orElse(localConfig.functionName).getOrElse("matches_route"),
            ctx.wasmJson.stringify
          ),
          None
        ).andThen { case _ =>
          vm.release()
        }
    }
    val res         = Await.result(fu, 10.seconds)
    res match {
      case Right(res) => {
        val response = Json.parse(res._1)
        AttrsHelper.updateAttrs(ctx.attrs, response)
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
    env.wasmIntegration.wasmVmFor(config).flatMap {
      case None                    =>
        Errors
          .craftResponseResult(
            "plugin not found !",
            Results.Status(500),
            ctx.request,
            None,
            None,
            attrs = ctx.attrs,
            maybeRoute = ctx.route.some
          )
          .map(r => NgPreRoutingErrorWithResult(r).left)
      case Some((vm, localConfig)) =>
        vm.call(
          WasmFunctionParameters.ExtismFuntionCall(
            config.functionName.orElse(localConfig.functionName).getOrElse("pre_route"),
            input.stringify
          ),
          None
        ).map {
          case Left(err)     => Left(NgPreRoutingErrorWithResult(Results.InternalServerError(err)))
          case Right(resStr) => {
            Try(Json.parse(resStr._1)) match {
              case Failure(e)        =>
                Left(NgPreRoutingErrorWithResult(Results.InternalServerError(Json.obj("error" -> e.getMessage))))
              case Success(response) => {
                AttrsHelper.updateAttrs(ctx.attrs, response)
                val error = response.select("error").asOpt[Boolean].getOrElse(false)
                if (error) {
                  val body                         = BodyHelper.extractBodyFrom(response)
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
        }.andThen { case _ =>
          vm.release()
        }
    }
  }
}

class WasmBackend extends NgBackendCall {

  private val logger                                       = Logger("otoroshi-plugins-wasm-backend")
  override def useDelegates: Boolean                       = true
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
    //WasmUtils.debugLog.debug("callBackend")

    ctx.wasmJson
      .flatMap { input =>
        env.wasmIntegration.wasmVmFor(config).flatMap {
          case None                    =>
            Errors
              .craftResponseResult(
                "plugin not found !",
                Results.Status(500),
                ctx.rawRequest,
                None,
                None,
                attrs = ctx.attrs,
                maybeRoute = ctx.route.some
              )
              .map(r => NgProxyEngineError.NgResultProxyEngineError(r).left)
          case Some((vm, localConfig)) =>
            vm.call(
              WasmFunctionParameters.ExtismFuntionCall(
                config.functionName.orElse(localConfig.functionName).getOrElse("call_backend"),
                input.stringify
              ),
              None
            ).flatMap {
              case Right(output) =>
                val response      =
                  try {
                    Json.parse(output._1)
                  } catch {
                    case e: Exception =>
                      logger.error("error during json parsing", e)
                      Json.obj()
                  }
                AttrsHelper.updateAttrs(ctx.attrs, response)
                val delegatesCall = response.select("delegates_call").asOpt[Boolean].contains(true)
                if (delegatesCall) {
                  delegates()
                } else {
                  val body = BodyHelper.extractBodyFrom(response)
                  inMemoryBodyResponse(
                    status = response.select("status").asOpt[Int].getOrElse(200),
                    headers = response
                      .select("headers")
                      .asOpt[Map[String, String]]
                      .getOrElse(Map("Content-Type" -> "application/json")),
                    body = body
                  ).vfuture
                }
              case Left(value)   =>
                inMemoryBodyResponse(
                  status = 400,
                  headers = Map.empty,
                  body = Json.stringify(value).byteString
                ).vfuture
            }.andThen { case _ =>
              vm.release()
            }
        }
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

    env.wasmIntegration.wasmVmFor(config).flatMap {
      case None                    =>
        Errors
          .craftResponseResult(
            "plugin not found !",
            Results.Status(500),
            ctx.request,
            None,
            None,
            attrs = ctx.attrs,
            maybeRoute = ctx.route.some
          )
          .map(r => NgAccess.NgDenied(r))
      case Some((vm, localConfig)) =>
        vm.call(
          WasmFunctionParameters.ExtismFuntionCall(
            config.functionName.orElse(localConfig.functionName).getOrElse("access"),
            ctx.wasmJson.stringify
          ),
          None
        ).flatMap {
          case Right(res) =>
            val response = Json.parse(res._1)
            AttrsHelper.updateAttrs(ctx.attrs, response)
            val result   = (response \ "result").asOpt[Boolean].getOrElse(false)
            if (result) {
              NgAccess.NgAllowed.vfuture
            } else {
              val error = (response \ "error").asOpt[JsObject].getOrElse(Json.obj())
              Errors
                .craftResponseResult(
                  (error \ "message").asOpt[String].getOrElse("An error occurred"),
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
                (err \ "error").asOpt[String].getOrElse("An error occurred"),
                Results.Status(400),
                ctx.request,
                None,
                None,
                attrs = ctx.attrs,
                maybeRoute = ctx.route.some
              )
              .map(r => NgAccess.NgDenied(r))
        }.andThen { case e =>
          vm.release()
        }
    }
    //} else {
    //  WasmUtils
    //    .execute(config, "access", ctx.wasmJson, ctx.attrs.some, None)
    //    .flatMap {
    //      case Right(res) =>
    //        val response = Json.parse(res)
    //        AttrsHelper.updateAttrs(ctx.attrs, response)
    //        val result = (response \ "result").asOpt[Boolean].getOrElse(false)
    //        if (result) {
    //          NgAccess.NgAllowed.vfuture
    //        } else {
    //          val error = (response \ "error").asOpt[JsObject].getOrElse(Json.obj())
    //          Errors
    //            .craftResponseResult(
    //              (error \ "message").asOpt[String].getOrElse("An error occurred"),
    //              Results.Status((error \ "status").asOpt[Int].getOrElse(403)),
    //              ctx.request,
    //              None,
    //              None,
    //              attrs = ctx.attrs,
    //              maybeRoute = ctx.route.some
    //            )
    //            .map(r => NgAccess.NgDenied(r))
    //        }
    //      case Left(err) =>
    //        Errors
    //          .craftResponseResult(
    //            (err \ "error").asOpt[String].getOrElse("An error occurred"),
    //            Results.Status(400),
    //            ctx.request,
    //            None,
    //            None,
    //            attrs = ctx.attrs,
    //            maybeRoute = ctx.route.some
    //          )
    //          .map(r => NgAccess.NgDenied(r))
    //    }
    //}
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
        env.wasmIntegration.wasmVmFor(config).flatMap {
          case None                    =>
            Errors
              .craftResponseResult(
                "plugin not found !",
                Results.Status(500),
                ctx.request,
                None,
                None,
                attrs = ctx.attrs,
                maybeRoute = ctx.route.some
              )
              .map(r => Left(r))
          case Some((vm, localConfig)) =>
            vm.call(
              WasmFunctionParameters.ExtismFuntionCall(
                config.functionName.orElse(localConfig.functionName).getOrElse("transform_request"),
                input.stringify
              ),
              None
            ).map {
              case Right(res)  =>
                val response = Json.parse(res._1)

                AttrsHelper.updateAttrs(ctx.attrs, response)
                if (response.select("error").asOpt[Boolean].getOrElse(false)) {
                  val status      = response.select("status").asOpt[Int].getOrElse(500)
                  val headers     = (response \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty)
                  val cookies     = WasmUtils.convertJsonPlayCookies(response).getOrElse(Seq.empty)
                  val contentType = headers.getIgnoreCase("Content-Type").getOrElse("application/octet-stream")
                  val body        = BodyHelper.extractBodyFrom(response)
                  Left(
                    Results
                      .Status(status)(body)
                      .withCookies(cookies: _*)
                      .withHeaders(headers.toSeq: _*)
                      .as(contentType)
                  )
                } else {
                  val body = BodyHelper.extractBodyFromOpt(response)
                  Right(
                    ctx.otoroshiRequest.copy(
                      // TODO: handle client cert chain and backend
                      method = (response \ "method").asOpt[String].getOrElse(ctx.otoroshiRequest.method),
                      url = (response \ "url").asOpt[String].getOrElse(ctx.otoroshiRequest.url),
                      headers =
                        (response \ "headers").asOpt[Map[String, String]].getOrElse(ctx.otoroshiRequest.headers),
                      cookies = WasmUtils.convertJsonCookies(response).getOrElse(ctx.otoroshiRequest.cookies),
                      body = body.map(_.chunks(16 * 1024)).getOrElse(ctx.otoroshiRequest.body)
                    )
                  )
                }
              case Left(value) => Left(Results.BadRequest(value))
            }.andThen { case _ =>
              vm.release()
            }
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
        env.wasmIntegration.wasmVmFor(config).flatMap {
          case None                    =>
            Errors
              .craftResponseResult(
                "plugin not found !",
                Results.Status(500),
                ctx.request,
                None,
                None,
                attrs = ctx.attrs,
                maybeRoute = ctx.route.some
              )
              .map(r => Left(r))
          case Some((vm, localConfig)) =>
            vm.call(
              WasmFunctionParameters.ExtismFuntionCall(
                config.functionName.orElse(localConfig.functionName).getOrElse("transform_response"),
                input.stringify
              ),
              None
            ).map {
              case Right(res)  =>
                val response = Json.parse(res._1)
                AttrsHelper.updateAttrs(ctx.attrs, response)
                if (response.select("error").asOpt[Boolean].getOrElse(false)) {
                  val status      = response.select("status").asOpt[Int].getOrElse(500)
                  val headers     = (response \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty)
                  val cookies     = WasmUtils.convertJsonPlayCookies(response).getOrElse(Seq.empty)
                  val contentType = headers.getIgnoreCase("Content-Type").getOrElse("application/octet-stream")
                  val body        = BodyHelper.extractBodyFrom(response)
                  Left(
                    Results
                      .Status(status)(body)
                      .withCookies(cookies: _*)
                      .withHeaders(headers.toSeq: _*)
                      .as(contentType)
                  )
                } else {
                  val body = BodyHelper.extractBodyFromOpt(response)
                  ctx.otoroshiResponse
                    .copy(
                      headers =
                        (response \ "headers").asOpt[Map[String, String]].getOrElse(ctx.otoroshiResponse.headers),
                      status = (response \ "status").asOpt[Int].getOrElse(200),
                      cookies = WasmUtils.convertJsonCookies(response).getOrElse(ctx.otoroshiResponse.cookies),
                      body = body.map(_.chunks(16 * 1024)).getOrElse(ctx.otoroshiResponse.body)
                    )
                    .right
                }
              case Left(value) => Left(Results.BadRequest(value))
            }.andThen { case _ =>
              vm.release()
            }
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
    val fu     = env.wasmIntegration.wasmVmFor(config).flatMap {
      case None                    => false.vfuture
      case Some((vm, localConfig)) =>
        vm.call(WasmFunctionParameters.ExtismFuntionCall("sink_matches", ctx.wasmJson.stringify), None)
          .map {
            case Left(error) => false
            case Right(res)  => {
              val response = Json.parse(res._1)
              AttrsHelper.updateAttrs(ctx.attrs, response)
              (response \ "result").asOpt[Boolean].getOrElse(false)
            }
          }
          .andThen { case _ =>
            vm.release()
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

      env.wasmIntegration.wasmVmFor(config).flatMap {
        case None                    =>
          Errors
            .craftResponseResult(
              "plugin not found !",
              Results.Status(500),
              ctx.request,
              None,
              None,
              maybeRoute = None,
              attrs = ctx.attrs
            )
        case Some((vm, localConfig)) =>
          vm.call(
            WasmFunctionParameters.ExtismFuntionCall(
              config.functionName.orElse(localConfig.functionName).getOrElse("sink_handle"),
              input.stringify
            ),
            None
          ).map {
            case Left(error) => Results.InternalServerError(error)
            case Right(res)  => {
              val response = Json.parse(res._1)
              AttrsHelper.updateAttrs(ctx.attrs, response)
              val status   = response
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

              val body = BodyHelper.extractBodyFrom(response)

              Results
                .Status(status)(body)
                .withHeaders(headers.toSeq: _*)
                .as(contentType)
            }
          }.andThen { case _ =>
            vm.release()
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
        JsonHelpers.requestToJson(request, TypedMap.empty).asObject ++ Json.obj(
          "request_body_bytes" -> rawBody.toArray[Byte]
        )
      }
    } else {
      JsonHelpers.requestToJson(request, TypedMap.empty).vfuture
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
              env.wasmIntegration.wasmVmFor(config).flatMap {
                case None                    =>
                  Errors
                    .craftResponseResult(
                      "plugin not found !",
                      Results.Status(500),
                      request,
                      None,
                      None,
                      maybeRoute = None,
                      attrs = TypedMap.empty
                    )
                case Some((vm, localConfig)) =>
                  vm.call(
                    WasmFunctionParameters.ExtismFuntionCall(
                      config.functionName.orElse(localConfig.functionName).getOrElse("handle_request"),
                      Json.obj("request" -> json).stringify
                    ),
                    None
                  ).flatMap {
                    case Right(ok) => {
                      val response                     = Json.parse(ok._1)
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
                  }.andThen { case _ =>
                    vm.release()
                  }
              }
            }
          }
        }
      }
    }
  }
}

case class FakeWasmContext(config: JsValue, idx: Int = 0) extends NgCachedConfigContext {
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
  override def predicate(ctx: JobContext, env: Env): Option[Boolean]           =
    None // TODO: make it configurable base on global env ???

  override def jobStart(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = Try {
    env.wasmIntegration.wasmVmFor(config.config).flatMap {
      case None          => Future.failed(new RuntimeException("no plugin found"))
      case Some((vm, _)) =>
        vm.call(WasmFunctionParameters.ExtismFuntionCall("job_start", ctx.wasmJson.stringify), None)
          .map {
            case Left(err) => logger.error(s"error while starting wasm job ${config.uniqueId}: ${err.stringify}")
            case Right(_)  => ()
          }
          .andThen { case _ =>
            vm.release()
          }
    }
  } match {
    case Failure(e) =>
      logger.error("error during wasm job start", e)
      funit
    case Success(s) => s
  }
  override def jobStop(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit]  = Try {
    env.wasmIntegration.wasmVmFor(config.config).flatMap {
      case None          => Future.failed(new RuntimeException("no plugin found"))
      case Some((vm, _)) =>
        vm.call(WasmFunctionParameters.ExtismFuntionCall("job_stop", ctx.wasmJson.stringify), None)
          .map {
            case Left(err) => logger.error(s"error while stopping wasm job ${config.uniqueId}: ${err.stringify}")
            case Right(_)  => ()
          }
          .andThen { case _ =>
            vm.release()
          }
    }
  } match {
    case Failure(e) =>
      logger.error("error during wasm job stop", e)
      funit
    case Success(s) => s
  }
  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit]   = Try {
    env.wasmIntegration.wasmVmFor(config.config).flatMap {
      case None                    => Future.failed(new RuntimeException("no plugin found"))
      case Some((vm, localConfig)) =>
        vm.call(
          WasmFunctionParameters.ExtismFuntionCall(
            config.config.functionName.orElse(localConfig.functionName).getOrElse("job_run"),
            ctx.wasmJson.stringify
          ),
          None
        ).map {
          case Left(err) => logger.error(s"error while running wasm job ${config.uniqueId}: ${err.stringify}")
          case Right(_)  => ()
        }.andThen { case _ =>
          vm.release()
        }
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

  private val handledJobs = new UnboundedTrieMap[String, Job]()

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

  private def onError(error: String, ctx: NgAccessContext, status: Option[Int] = Some(400))(implicit
      env: Env,
      ec: ExecutionContext
  ) = Errors
    .craftResponseResult(
      error,
      Results.Status(status.get),
      ctx.request,
      None,
      None,
      attrs = ctx.attrs,
      maybeRoute = ctx.route.some
    )
    .map(r => NgAccess.NgDenied(r))

  private def execute(vm: WasmVm, ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext) = {
    vm.callOpa("execute", ctx.wasmJson.stringify)
      .flatMap {
        case Right((rawResult, _)) =>
          val response  = Json.parse(if (rawResult.isEmpty) "[{\"result\":false}]" else rawResult)
          val result    = response.asOpt[JsArray].getOrElse(Json.arr())
          val canAccess = (result.value.head \ "result").asOpt[Boolean].getOrElse(false)
          if (canAccess)
            NgAccess.NgAllowed.vfuture
          else
            onError("Forbidden access", ctx, 403.some)
        case Left(err)             => onError((err \ "error").asOpt[String].getOrElse("An error occurred"), ctx)
      }
      .andThen { case _ =>
        vm.release()
      }
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx
      .cachedConfig(internalName)(WasmConfig.format)
      .getOrElse(WasmConfig())

    env.wasmIntegration.wasmVmFor(config).flatMap {
      case None                    =>
        Errors
          .craftResponseResult(
            "plugin not found !",
            Results.Status(500),
            ctx.request,
            None,
            None,
            attrs = ctx.attrs,
            maybeRoute = ctx.route.some
          )
          .map(r => NgAccess.NgDenied(r))
      case Some((vm, localConfig)) => execute(vm, ctx)
      // if (!vm.initialized()) {
      //   vm.call(WasmFunctionParameters.OPACall("initialize", in = ctx.wasmJson.stringify), None)
      //     .flatMap {
      //       case Left(error)  => onError(error.stringify, ctx)
      //       case Right(value) =>
      //         vm.initialize {
      //           val pointers = Json.parse(value._1)
      //           vm.opaPointers = OPAWasmVm(
      //             opaDataAddr = (pointers \ "dataAddr").as[Int],
      //             opaBaseHeapPtr = (pointers \ "baseHeapPtr").as[Int]
      //           ).some
      //         }
      //         execute(vm, ctx)
      //     }
      // } else {
      //   execute(vm, ctx)
      // }
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
      env.wasmIntegration.wasmVmFor(config).flatMap {
        case None                    => Left(Json.obj("error" -> "plugin not found")).vfuture
        case Some((vm, localConfig)) =>
          val ret = vm
            .call(
              WasmFunctionParameters.ExtismFuntionCall(
                config.functionName.orElse(localConfig.functionName).getOrElse("find_route"),
                ctx.json.stringify
              ),
              None
            )
            .andThen { case _ =>
              vm.release()
            }
          vm.release()
          ret
      },
      3.seconds
    ) match {
      case Right(res) =>
        val response = Json.parse(res._1)
        AttrsHelper.updateAttrs(ctx.attrs, response)
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
