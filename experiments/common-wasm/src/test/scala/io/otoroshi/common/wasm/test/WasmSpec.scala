package io.otoroshi.common.wasm.test

import akka.actor.ActorSystem
import akka.stream.Materializer
import io.otoroshi.common.utils.implicits.{BetterJsValue, BetterSyntax}
import io.otoroshi.common.wasm.{CacheableWasmScript, TlsConfig, WasmConfiguration, WasmFunctionParameters, WasmIntegration, WasmIntegrationContext, WasmManagerSettings, WasmSource, WasmSourceKind, WasmVmKillOptions}
import org.extism.sdk.wasmotoroshi.{WasmOtoroshiHostFunction, WasmOtoroshiHostUserData}
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.WSRequest

import java.util.concurrent.Executors
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

class WasmSpec extends munit.FunSuite {

  val testWasmConfigs: Map[String, WasmConfiguration] = Map(
    "basic" -> new WasmConfiguration {
      override def source: WasmSource = WasmSource(WasmSourceKind.File, "./src/test/resources/basic.wasm")
      override def memoryPages: Int = 4
      override def functionName: Option[String] = None
      override def config: Map[String, String] = Map.empty
      override def allowedHosts: Seq[String] = Seq.empty
      override def allowedPaths: Map[String, String] = Map.empty
      override def wasi: Boolean = true
      override def opa: Boolean = false
      override def instances: Int = 1
      override def killOptions: WasmVmKillOptions = WasmVmKillOptions.default
      override def json: JsValue = Json.obj()
    },
    "opa" -> new WasmConfiguration {
      override def source: WasmSource = WasmSource(WasmSourceKind.File, "./src/test/resources/opa.wasm")
      override def memoryPages: Int = 4
      override def functionName: Option[String] = None
      override def config: Map[String, String] = Map.empty
      override def allowedHosts: Seq[String] = Seq.empty
      override def allowedPaths: Map[String, String] = Map.empty
      override def wasi: Boolean = false
      override def opa: Boolean = true
      override def instances: Int = 1
      override def killOptions: WasmVmKillOptions = WasmVmKillOptions.default
      override def json: JsValue = Json.obj()
    }
  )

  implicit val ctx = new WasmIntegrationContext {

    val system = ActorSystem("test-common-wasm")
    val materializer: Materializer = Materializer(system)
    val executionContext: ExecutionContext = system.dispatcher
    val logger: Logger = Logger("test-common-wasm")
    val wasmCacheTtl: Long = 2000
    val wasmQueueBufferSize: Int = 100
    val wasmManagerSettings: Future[Option[WasmManagerSettings]] = Future.successful(None)
    val wasmScriptCache: TrieMap[String, CacheableWasmScript] = new TrieMap[String, CacheableWasmScript]()
    val wasmExecutor: ExecutionContext = ExecutionContext.fromExecutorService(
      Executors.newWorkStealingPool(Math.max(32, (Runtime.getRuntime.availableProcessors * 4) + 1))
    )

    override def url(path: String): WSRequest = ???
    override def mtlsUrl(path: String, tlsConfig: TlsConfig): WSRequest = ???
    override def wasmConfig(path: String): Option[WasmConfiguration] = testWasmConfigs.get(path)
    override def wasmConfigs(): Seq[WasmConfiguration] = testWasmConfigs.values.toSeq
    override def hostFunctions(config: WasmConfiguration, pluginId: String): Array[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]] = Array.empty
  }

  test("basic setup with manual release should work") {

    implicit val ec = ctx.executionContext

    val integration = WasmIntegration(ctx)

    integration.runVmLoaderJob()

    val fu = integration.wasmVmById("basic").flatMap {
      case Some((vm, _)) => {
        vm.call(
          WasmFunctionParameters.ExtismFuntionCall(
            "execute",
            Json.obj("message" -> "coucou").stringify
          ),
          None
        ).map {
          case Left(error) => println(s"error: ${error.prettify}")
          case Right((out, wrapper)) => {
            assertEquals(out, "{\"input\":{\"message\":\"coucou\"},\"message\":\"yo\"}")
            println(s"output: ${out}")
          }
        }
        .andThen {
          case _ => vm.release()
        }
      }
      case _ =>
        println("vm not found !")
        ().vfuture
    }
    Await.result(fu, 10.seconds)
  }

  test("basic setup with auto release should work") {

    implicit val ec = ctx.executionContext

    val integration = WasmIntegration(ctx)

    integration.runVmLoaderJob()

    val fu = integration.withPooledVm(testWasmConfigs("basic")) { vm =>
      vm.call(
        WasmFunctionParameters.ExtismFuntionCall(
          "execute",
          Json.obj("message" -> "coucou").stringify
        ),
        None
      ).map {
        case Left(error) => println(s"error: ${error.prettify}")
        case Right((out, wrapper)) => {
          assertEquals(out, "{\"input\":{\"message\":\"coucou\"},\"message\":\"yo\"}")
          println(s"output: ${out}")
        }
      }
    }

    Await.result(fu, 10.seconds)
  }

  test("opa manual setup with auto release should work") {

    implicit val ec = ctx.executionContext

    val integration = WasmIntegration(ctx)

    integration.runVmLoaderJob()

    val callCtx = Json.obj("request" -> Json.obj("headers" -> Json.obj("foo" -> "bar"))).stringify
    val fu = integration.withPooledVm(testWasmConfigs("opa")) { vm =>
      vm.ensureOpaInitialized(callCtx.some).call(
        WasmFunctionParameters.OPACall("execute", vm.opaPointers, callCtx), None
      ).map {
        case Left(error) => println(s"error: ${error.prettify}")
        case Right((out, wrapper)) => {
          println(s"opa output: ${out}")
        }
      }
    }

    Await.result(fu, 10.seconds)
  }

  test("opa auto setup with auto release should work") {

    implicit val ec = ctx.executionContext

    val integration = WasmIntegration(ctx)

    integration.runVmLoaderJob()

    val callCtx = Json.obj("request" -> Json.obj("headers" -> Json.obj("foo" -> "bar"))).stringify
    val fu = integration.withPooledVm(testWasmConfigs("opa")) { vm =>
      vm.opaCall("execute", callCtx).map {
        case Left(error) => println(s"error: ${error.prettify}")
        case Right((out, wrapper)) => {
          println(s"opa output: ${out}")
        }
      }
    }

    Await.result(fu, 10.seconds)
  }
}
