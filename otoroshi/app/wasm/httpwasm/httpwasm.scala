package otoroshi.wasm.httpwasm

import akka.stream.Materializer
import akka.util.ByteString
import io.otoroshi.wasm4s.scaladsl._
import org.extism.sdk.wasmotoroshi._
import org.extism.sdk.{ExtismCurrentPlugin, HostFunction, HostUserData, LibExtism}
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api._
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm._
import otoroshi.wasm.httpwasm.HttpWasmFunctions.parameters
import otoroshi.wasm.httpwasm.api.{BodyKind, HeaderKind}
import play.api._
import play.api.libs.json._
import play.api.libs.typedmap.TypedKey
import play.api.mvc.Results.{Ok, Status}
import play.api.mvc.{RequestHeader, Results}

import java.util.Optional
import java.util.concurrent.atomic._
import scala.concurrent._
import scala.util._


object HttpWasmPluginKeys {
  val HttpWasmVmKey    = TypedKey[WasmVm]("otoroshi.next.plugins.HttpWasmVm")
}

class HttpWasmPlugin(wasm: WasmConfig, key: String, env: Env) {

  private implicit val ev = env
  private implicit val ec = env.otoroshiExecutionContext
  private implicit val ma = env.otoroshiMaterializer

  private lazy val state                   = new HttpWasmState(env)
  private lazy val pool: WasmVmPool        = WasmVmPool.forConfigurationWithId(key, wasm)(env.wasmIntegration.context)

  def createFunctions(ref: AtomicReference[WasmVmData]): Seq[HostFunction[_ <: HostUserData]] = {
    HttpWasmFunctions.build(state, ref)
  }

  def start(attrs: TypedMap): Future[Unit] = {
    println("Create vm with custom functions")
    pool.getPooledVm(WasmVmInitOptions(
      importDefaultHostFunctions = false,
      resetMemory = true,
      addHostFunctions = createFunctions
    )).flatMap { vm =>
      println("VM created")
      attrs.put(otoroshi.wasm.httpwasm.HttpWasmPluginKeys.HttpWasmVmKey -> vm)
      vm.finitialize {
        println("VM initialized")
        Future.successful(())
      }
    }
  }

}

class NgHttpWasm extends NgRequestTransformer {

  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Wasm)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Http WASM"
  override def description: Option[String]                 = "Http WASM plugin".some
  override def defaultConfigObject: Option[NgPluginConfig] = WasmConfig().some

  override def isTransformRequestAsync: Boolean  = true
  override def isTransformResponseAsync: Boolean = true
  override def usesCallbacks: Boolean            = true
  override def transformsRequest: Boolean        = true
  override def transformsResponse: Boolean       = true
  override def transformsError: Boolean          = false

    override def beforeRequest(ctx: NgBeforeRequestContext)
                              (implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    val config = WasmConfig.format.reads(ctx.config).getOrElse(WasmConfig())
    new HttpWasmPlugin(config, "http-wasm", env).start(ctx.attrs)
  }

  private def handleResponse(vm: WasmVm, vmData: HttpWasmVmData, reqCtx: Int, isError: Int)
                            (implicit env: Env, ec: ExecutionContext) = {
    println(s"calling handle_response with reqCtx $reqCtx")
    vm.call(
       WasmFunctionParameters.NoResult("handle_response", new Parameters(2).pushInts(reqCtx, isError)),
        vmData.some
    )
  }

  private def execute(vm: WasmVm, ctx: NgTransformerRequestContext)
                     (implicit env: Env, ec: ExecutionContext): Future[Either[mvc.Result, NgPluginHttpRequest]] = {
    val vmData = HttpWasmVmData
          .withRequest(ctx.otoroshiRequest)
          .some

    vm.callWithParamsAndResult("handle_request",
        new Parameters(0),
        1,
        None,
        vmData
      )
      .flatMap {
        case Left(error) => {
          println("got an error on handle_request")
          Errors.craftResponseResult(
              error.toString(),
              Status(401),
              ctx.request,
              None,
              None,
              attrs = TypedMap.empty
            ).map(r => Left(r))
        }
        case Right(res) =>
          println("ending handle_Request and get result")
          val ctxNext = res.results.getValue(0).v.i64

          println(s"handle request has returned $ctxNext")

          val data = vmData.get
          if ((ctxNext & 0x1) != 0x1) {
              println("returning response as guest asked")
              Left(data.response.asResult).future
          } else {
            data.nextCalled = true

            val reqCtx = ctxNext >> 32
            handleResponse(vm, data, reqCtx.toInt, 0)

            println(s"request has body ${data.request.hasBody}")

            implicit val mat = env.otoroshiMaterializer
            data.request.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
              println(bodyRaw)
            }

            if (data.request.hasBody) {
              Right(ctx.otoroshiRequest.copy(
                headers = data.request.headers,
                url = data.request.url,
                method = data.request.method,
                body = data.request.body
              )).future
            } else {
              Right(ctx.otoroshiRequest.copy(
                headers = data.request.headers,
                url = data.request.url,
                method = data.request.method,
              )).future
            }
          }
      }
  }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer):
  Future[Either[mvc.Result, NgPluginHttpRequest]] = {
    println("Calling transform request")
    ctx.attrs.get(otoroshi.wasm.httpwasm.HttpWasmPluginKeys.HttpWasmVmKey) match {
      case None =>
        println("no vm found in attrs")
        Future.failed(new RuntimeException("no vm found in attrs"))
      case Some(vm) => execute(vm, ctx)
    }
  }

    override def afterRequest(
                             ctx: NgAfterRequestContext
                           )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    ctx.attrs.get(otoroshi.wasm.httpwasm.HttpWasmPluginKeys.HttpWasmVmKey).foreach(_.release())
    ().vfuture
  }
}