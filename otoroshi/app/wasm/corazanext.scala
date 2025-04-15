package otoroshi.wasm.proxywasm

import akka.util.ByteString
import io.otoroshi.wasm4s.scaladsl._
import otoroshi.env.Env
import otoroshi.next.plugins.api.{NgAccess, NgPluginHttpRequest, NgPluginHttpResponse}
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm._
import play.api.libs.json._
import play.api.mvc
import play.api.mvc.{RequestHeader, Results}

import scala.concurrent._
import scala.util._


class CorazaNextPlugin(wasm: WasmConfig, val config: CorazaWafConfig, key: String, env: Env) extends CorazaImplementation {
  private implicit val ec = env.otoroshiExecutionContext

  private lazy val pool: WasmVmPool = WasmVmPool.forConfigurationWithId(key, wasm)(env.wasmIntegration.context)

  def start(attrs: TypedMap): Future[Unit] = {
    pool.getPooledVm(WasmVmInitOptions(importDefaultHostFunctions = false, resetMemory = false, _ => Seq.empty)).flatMap { vm =>
      attrs.put(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaWasmVmKey -> vm)
      vm.finitialize {
        vm.callCorazaNext("initialize", "", None,  Json.stringify(Json.obj(
          "directives" -> config.directives.mkString("\n"),
          "inspect_bodies" -> config.inspectBody
        )).some)
      }
    }
  }

  override def runRequestPath(request: RequestHeader, attrs: TypedMap): Future[NgAccess] = {
    val instance: WasmVm = attrs.get(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaWasmVmKey).get
    val in = Json.obj(
      "request" -> Json.obj(
        "url" -> request.uri,
        "method" -> request.method,
        "headers" -> request.headers.toSimpleMap,
      )
    )

    // if (config.inspectBody)
    instance.callCorazaNext("evaluate", in.stringify).map {
        case Left(_) => NgAccess.NgDenied(Results.Forbidden)
        case Right(value) =>
          if (Json.parse(value._1).selectAsOptBoolean("result").getOrElse(false)) {
            NgAccess.NgAllowed
          } else {
            NgAccess.NgDenied(Results.Forbidden)
          }
      }
  }

  override def runRequestBodyPath(
                                   request: RequestHeader,
                                   req: NgPluginHttpRequest,
                                   body_bytes: Option[ByteString],
                                   attrs: TypedMap): Future[Either[mvc.Result, Unit]] = {
    if (body_bytes.isDefined) {
      val instance = attrs.get(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaWasmVmKey).get
      val in = Json.obj(
        "request" -> Json.obj(
          "url"     -> request.uri,
          "method"  -> request.method,
          "headers" -> request.headers.toSimpleMap,
          "body"    -> body_bytes.get
        )
      )

      instance.callCorazaNext("evaluate", in.stringify).map {
          case Left(errRes) => Results.BadRequest.left
          case Right(value) =>
            if (Json.parse(value._1).selectAsOptBoolean("result").getOrElse(false)) {
              ().right
            } else {
              Results.Unauthorized.left
            }
        }
      } else {
      Right(()).vfuture
    }
  }

  override def runResponsePath(response: NgPluginHttpResponse, body_bytes: Option[ByteString], attrs: TypedMap): Future[Either[mvc.Result, Unit]] = {
    ().rightf
  }
}