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

  private def isBlocking = config.directives.contains("SecRuleEngine On")

  def start(attrs: TypedMap): Future[Unit] = {
    pool.getPooledVm(WasmVmInitOptions(importDefaultHostFunctions = false, resetMemory = false, _ => Seq.empty)).flatMap { vm =>
      attrs.put(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaWasmVmKey -> vm)
      vm.finitialize {
        var directives = Seq("Include @recommended-conf", "Include @crs-setup-conf")
        if (config.includeOwaspCRS) {
          directives = directives :+ "Include @owasp_crs/*.conf"
        }

        val defaultDirectives = Seq(
          "SecRuleEngine On",
          "SecRuleEngine DetectionOnly",
          "SecRequestBodyAccess On",
          "SecResponseBodyAccess On",
          "Include @coraza",
          "Include @recommended-conf",
          "Include @crs-setup",
          "Include @owasp_crs/*.conf")

        config
          .directives
          .filter(line => !defaultDirectives.contains(line))
          .foreach(v => directives = directives :+ v)

         if (config.isBlockingMode) {
           directives = directives :+ "SecRuleEngine On"
         } else {
           directives = directives :+ "SecRuleEngine DetectionOnly"
         }

         if (config.inspectBody) {
           directives = directives :+ "SecRequestBodyAccess On"
           directives = directives :+ "SecResponseBodyAccess On"
         }

        println("initialize with", directives)
        vm.callCorazaNext("initialize", "", None,  Json.stringify(Json.obj(
          "directives" -> directives.mkString("\n"),
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

    evaluate(instance, in)
  }

  def evaluate(instance: WasmVm, in: JsObject) = {
    instance.callCorazaNext("evaluate", in.stringify).map {
      case Left(_) => rejectCall()
      case Right(value) =>
        val result = Json.parse(value._1)
        val response = result.select("response")
        val errors = result.select("errors").asOpt[JsArray].getOrElse(Json.arr())

        println("Result of coraza call: ", response, errors)
        if (Json.parse(value._1).selectAsOptBoolean("result").getOrElse(false)) {
          NgAccess.NgAllowed
        } else {
          rejectCall(errors)
        }
    }
  }

  private def rejectCall(errors: JsArray = Json.arr()): NgAccess = {
    if (isBlocking) {
      NgAccess.NgDenied(Results.Forbidden)
    } else {
      // TODO - send event and log it
      NgAccess.NgAllowed
    }
  }

  override def runRequestBodyPath(
                                   request: RequestHeader,
                                   req: NgPluginHttpRequest,
                                   body_bytes: Option[ByteString],
                                   attrs: TypedMap): Future[Either[mvc.Result, Unit]] = {
    if (config.inspectBody && body_bytes.isDefined) {
      val instance = attrs.get(otoroshi.wasm.proxywasm.CorazaPluginKeys.CorazaWasmVmKey).get
      val in = Json.obj(
        "request" -> Json.obj(
          "url"     -> request.uri,
          "method"  -> request.method,
          "headers" -> request.headers.toSimpleMap,
          "body"    -> body_bytes.get
        )
      )

      evaluate(instance, in)
        .map {
          case NgAccess.NgAllowed => ().right
          case NgAccess.NgDenied(result) => Results.Forbidden.left
        }
    } else {
      ().rightf
    }
  }

  override def runResponsePath(response: NgPluginHttpResponse, body_bytes: Option[ByteString], attrs: TypedMap): Future[Either[mvc.Result, Unit]] = {
    ().rightf
  }
}