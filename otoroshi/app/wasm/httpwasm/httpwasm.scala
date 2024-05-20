package otoroshi.wasm.httpwasm

import akka.stream.Materializer
import akka.util.ByteString
import io.otoroshi.wasm4s.scaladsl._
import org.extism.sdk.HostFunction
import org.extism.sdk.wasmotoroshi._
import org.joda.time.DateTime
import otoroshi.api.{GenericResourceAccessApiWithState, Resource, ResourceVersion}
import otoroshi.env.Env
import otoroshi.events.AnalyticEvent
import otoroshi.gateway.Errors
import otoroshi.models.{EntityLocation, EntityLocationSupport}
import otoroshi.next.extensions._
import otoroshi.next.plugins.api._
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.{ReplaceAllWith, TypedMap}
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm._
import play.api.libs.json._
import play.api._
import play.api.libs.typedmap.TypedKey
import play.api.mvc.{RequestHeader, Results}
import play.api.mvc.Results.Ok

import java.util.concurrent.atomic._
import scala.concurrent.duration._
import scala.concurrent._
import scala.util._


object HttpWasmPluginKeys {
  val HttpWasmVmKey    = TypedKey[WasmVm]("otoroshi.next.plugins.HttpWasmVm")
}

class HttpWasmPlugin(wasm: WasmConfig, key: String, env: Env) {

  private implicit val ev = env
  private implicit val ec = env.otoroshiExecutionContext
  private implicit val ma = env.otoroshiMaterializer

  private lazy val started                 = new AtomicBoolean(false)
  private lazy val logger                  = Logger("otoroshi-plugin-http-wasm")
  private lazy val state                   = new HttpWasmState(env)
  private lazy val pool: WasmVmPool        = WasmVmPool.forConfigurationWithId(key, wasm)(env.wasmIntegration.context)

  def isStarted(): Boolean = started.get()

  def createFunctions(ref: AtomicReference[WasmVmData]): Seq[HostFunction[EnvUserData]] = {
    HttpWasmFunctions.build(state, ref)
  }

  def callPluginWithResults(
      function: String,
      params: Parameters,
      results: Int,
      data: HttpWasmVmData,
      attrs: TypedMap
  ): Future[ResultsWrapper] = {
    attrs.get(otoroshi.wasm.httpwasm.HttpWasmPluginKeys.HttpWasmVmKey) match {
      case None     =>
        println("no vm found in attrs")
        Future.failed(new RuntimeException("no vm found in attrs"))
      case Some(vm) => {
        println(function + s" - vm: ${vm.index}")
        vm.call(
          WasmFunctionParameters.BothParamsResults(function, params, results),
          Some(data.copy(properties = data.properties))
        ).flatMap {
          case Left(err)           =>
            /* TODO - REPLACE WITH logger.error( */
            println(s"error while calling plugin: ${err}")
            Future.failed(new RuntimeException(s"callPluginWithResults: ${err.stringify}"))
          case Right((_, results)) => results.vfuture
        }
      }
    }
  }

  def start(attrs: TypedMap): Future[Unit] = {
    pool.getPooledVm(WasmVmInitOptions(false, true, createFunctions)).flatMap { vm =>
      attrs.put(otoroshi.wasm.httpwasm.HttpWasmPluginKeys.HttpWasmVmKey -> vm)
      vm.finitialize {
        Future.successful(())
      }
    }
  }

  def stop(attrs: TypedMap): Future[Unit] = {
    ().vfuture
  }


  def handleRequest(
      request: RequestHeader,
      req: NgPluginHttpRequest,
      body_bytes: Option[ByteString],
      attrs: TypedMap
  ): Future[Either[mvc.Result, Unit]] = {
    val data = HttpWasmVmData.withRequest(req)

    callPluginWithResults("handle_request", new Parameters(0), 1, data, attrs)
      .map { res =>
        println(res.results.getValues().head)

        Left(Ok(Json.obj()))
      }
  }

  def handleResponse(
      response: NgPluginHttpResponse,
      body_bytes: Option[ByteString],
      attrs: TypedMap
  ): Future[Either[mvc.Result, Unit]] = {
    Left(Ok(Json.obj())).future
  }
}

//case class NgHttpWasmConfig(ref: String) extends NgPluginConfig {
//  override def json: JsValue = NgHttpWasmConfig.format.writes(this)
//}
//
//object NgHttpWasmConfig {
//  val format = new Format[NgHttpWasmConfig] {
//    override def writes(o: NgHttpWasmConfig): JsValue             = Json.obj("ref" -> o.ref)
//    override def reads(json: JsValue): JsResult[NgHttpWasmConfig] = Try {
//      NgHttpWasmConfig(
//        ref = json.select("ref").asString
//      )
//    } match {
//      case Success(e) => JsSuccess(e)
//      case Failure(e) => JsError(e.getMessage)
//    }
//  }

//class NgHttpWasm extends NgRequestTransformer {
//
//  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest, NgStep.TransformResponse)
//  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Wasm)
//  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
//  override def multiInstance: Boolean                      = true
//  override def core: Boolean                               = true
//  override def name: String                                = "Http WASM"
//  override def description: Option[String]                 = "Http WASM plugin".some
//  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig().some
//
//  override def isTransformRequestAsync: Boolean  = true
//  override def isTransformResponseAsync: Boolean = true
//  override def usesCallbacks: Boolean            = true
//  override def transformsRequest: Boolean        = true
//  override def transformsResponse: Boolean       = true
//  override def transformsError: Boolean          = false
//
//  private def onError(error: String, ctx: NgAccessContext, status: Option[Int] = Some(400))(implicit
//      env: Env,
//      ec: ExecutionContext
//  ) = Errors
//    .craftResponseResult(
//      error,
//      Results.Status(status.get),
//      ctx.request,
//      None,
//      None,
//      attrs = ctx.attrs,
//      maybeRoute = ctx.route.some
//    )
//    .map(r => NgAccess.NgDenied(r))
//
//    override def beforeRequest(
//                              ctx: NgBeforeRequestContext
//                            )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
//    val config = WasmConfig.format.reads(ctx.config).getOrElse(WasmConfig())
//    new HttpWasmPlugin(config, "http-wasm", env).start(ctx.attrs)
//  }
//
//  override def afterRequest(
//                             ctx: NgAfterRequestContext
//                           )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
//    ctx.attrs.get(otoroshi.wasm.httpwasm.HttpWasmPluginKeys.HttpWasmVmKey).foreach(_.release())
//    ().vfuture
//  }
//
//  private def execute(vm: WasmVm, ctx: NgTransformerRequestContext)
//                     (implicit env: Env, ec: ExecutionContext) = {
//    // input: Option[String] = None, parameters: Option[WasmOtoroshiParameters] = None, context: Option[WasmVmData] = None
//    vm.callWithParamsAndResult("handle_request",
//        new Parameters(0),
//        1,
//        None,
//        HttpWasmVmData.withRequest(ctx.otoroshiRequest).some
//      )
//      .flatMap {
//        case Left(error) => println(error)
//        case Right(value) => println(value)
//      }
//      .andThen { case _ =>
//        vm.release()
//      }
//  }
//
//  override def transformRequest(
//      ctx: NgTransformerRequestContext
//  )(implicit env: Env, ec: ExecutionContext, mat: Materializer):
//  Future[Either[mvc.Result, NgPluginHttpRequest]] = {
//    val config = ctx
//      .cachedConfig(internalName)(WasmConfig.format)
//      .getOrElse(WasmConfig())
//
////    val hasBody                            = ctx.request.theHasBody
////    val bytesf: Future[Option[ByteString]] = if (!hasBody) {
////      None.vfuture
////    } else {
////      ctx.otoroshiRequest.body.runFold(ByteString.empty)(_ ++ _).map(_.some)
////    }
//
//    env.wasmIntegration.wasmVmFor(config).flatMap {
//      case None                    =>
//        Errors
//          .craftResponseResult(
//            "plugin not found !",
//            Results.Status(500),
//            ctx.request,
//            None,
//            None,
//            attrs = ctx.attrs,
//            maybeRoute = ctx.route.some
//          ).map(result => Left(result))
//      case Some((vm, localConfig)) => execute(vm, ctx)
//  }
//
////  override def transformResponse(
////      ctx: NgTransformerResponseContext
////  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[mvc.Result, NgPluginHttpResponse]] = {
////    val config                             = ctx.cachedConfig(internalName)(NgHttpWasmConfig.format).getOrElse(NgHttpWasmConfig("none"))
////    val plugin                             = getPlugin(config.ref, ctx.attrs)
////    val bytesf: Future[Option[ByteString]] = ctx.otoroshiResponse.body.runFold(ByteString.empty)(_ ++ _).map(_.some)
////    bytesf.flatMap { bytes =>
////      val res = ctx.otoroshiResponse.copy(body = bytes.get.chunks(16 * 1024))
////      plugin.handleResponse(
////          res,
////          bytes,
////          ctx.attrs
////        )
////        .map {
////          case Left(result) => Left(result)
////          case Right(_)     => Right(res)
////        }
////    }
////  }
//}