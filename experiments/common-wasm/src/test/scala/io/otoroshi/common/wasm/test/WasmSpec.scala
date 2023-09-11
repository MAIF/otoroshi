package io.otoroshi.common.wasm.test

import io.otoroshi.common.wasm.scaladsl._
import io.otoroshi.common.wasm.scaladsl.implicits._
import play.api.libs.json.Json

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class WasmSpec extends munit.FunSuite {

  val wasmStore = InMemoryWasmConfigurationStore(
    "basic" -> BasicWasmConfiguration.fromWasiSource(WasmSource(WasmSourceKind.File, "./src/test/resources/basic.wasm")),
    "opa" -> BasicWasmConfiguration.fromOpaSource(WasmSource(WasmSourceKind.File, "./src/test/resources/opa.wasm")),
  )
  val wasmIntegration = WasmIntegration(BasicWasmIntegrationContextWithNoHttpClient("test-common-wasm", wasmStore))
  wasmIntegration.runVmLoaderJob()

  test("basic setup with manual release should work") {

    import wasmIntegration.executionContext

    val fu = wasmIntegration.wasmVmById("basic").flatMap {
      case Some((vm, _)) => {
        vm.callExtismFunction(
          "execute",
          Json.obj("message" -> "coucou").stringify
        ).map {
          case Left(error) => println(s"error: ${error.prettify}")
          case Right(out) => {
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

    import wasmIntegration.executionContext

    val fu = wasmIntegration.withPooledVm(wasmStore.wasmConfigurationUnsafe("basic")) { vm =>
      vm.callExtismFunction(
        "execute",
        Json.obj("message" -> "coucou").stringify
      ).map {
        case Left(error) => println(s"error: ${error.prettify}")
        case Right(out) => {
          assertEquals(out, "{\"input\":{\"message\":\"coucou\"},\"message\":\"yo\"}")
          println(s"output: ${out}")
        }
      }
    }

    Await.result(fu, 10.seconds)
  }

  test("opa manual setup with auto release should work") {

    import wasmIntegration.executionContext

    val callCtx = Json.obj("request" -> Json.obj("headers" -> Json.obj("foo" -> "bar"))).stringify
    val fu = wasmIntegration.withPooledVm(wasmStore.wasmConfigurationUnsafe("opa")) { vm =>
      vm.ensureOpaInitialized(callCtx.some).call(
        WasmFunctionParameters.OPACall("execute", vm.getOpaPointers(), callCtx), None
      ).map {
        case Left(error) => println(s"error: ${error.prettify}")
        case Right((out, _)) => {
          println(s"opa output: ${out}")
        }
      }
    }

    Await.result(fu, 10.seconds)
  }

  test("opa auto setup with auto release should work") {

    import wasmIntegration.executionContext

    val callCtx = Json.obj("request" -> Json.obj("headers" -> Json.obj("foo" -> "bar"))).stringify
    val fu = wasmIntegration.withPooledVm(wasmStore.wasmConfigurationUnsafe("opa")) { vm =>
      vm.callOpa("execute", callCtx).map {
        case Left(error) => println(s"error: ${error.prettify}")
        case Right((out, wrapper)) => {
          println(s"opa output: ${out}")
        }
      }
    }

    Await.result(fu, 10.seconds)
  }
}
