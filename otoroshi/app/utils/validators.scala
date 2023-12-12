package otoroshi.utils

import io.otoroshi.wasm4s.scaladsl.{BasicWasmConfiguration, WasmSource, WasmSourceKind}
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits.{BetterJsReadable, BetterJsValue}
import play.api.libs.json.{Format, JsArray, JsError, JsResult, JsSuccess, JsValue, Json}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

trait JsonValidator {
  def kind: String
  def json: JsValue
  def error: Option[String]
  def validate(ctx: JsValue)(implicit env: Env): Boolean
}

object JsonValidator {
  val format = new Format[JsonValidator] {
    override def reads(json: JsValue): JsResult[JsonValidator] = try {
      val kind = json.select("kind").asOpt[String].getOrElse("json-path-validator").toLowerCase()
      kind match {
        case "json-path-validator"   => JsonPathValidator.format.reads(json)
        case "wasm-plugin-validator" => WasmPluginValidator.format.reads(json)
        case "opa-plugin-validator"  => OpaPluginValidator.format.reads(json)
      }
    } catch {
      case e: Throwable => JsError(e.getMessage)
    }

    override def writes(o: JsonValidator): JsValue = o.json
  }
}

object WasmPluginValidator {
  val format = new Format[JsonValidator] {
    override def reads(json: JsValue): JsResult[JsonValidator] = try {
      JsSuccess(
        WasmPluginValidator(
          ref = json.select("ref").asString,
          error = json.select("error").asOpt[String]
        )
      )
    } catch {
      case e: Throwable => JsError(e.getMessage)
    }

    override def writes(o: JsonValidator): JsValue = o.json
  }
}

case class WasmPluginValidator(ref: String, error: Option[String] = None) extends JsonValidator {

  private lazy val config = BasicWasmConfiguration(WasmSource(WasmSourceKind.Local, ref), wasi = true)

  override def kind: String = "wasm-plugin-validator"

  override def json: JsValue = Json.obj(
    "kind" -> kind,
    "ref"  -> ref
  )

  override def validate(ctx: JsValue)(implicit env: Env): Boolean = {
    val fu = env.wasmIntegration.withPooledVm(config) { vm =>
      vm.callExtismFunction("validate", ctx.prettify)(env.otoroshiExecutionContext)
        .map {
          case Right(rawResult) => {
            val result = Json.parse(rawResult)
            (result \ "result").asOpt[Boolean].getOrElse(false)
          }
          case Left(_)          => false
        }(env.otoroshiExecutionContext)
    }
    Await.result(fu, 30.seconds)
  }
}

object OpaPluginValidator {
  val format = new Format[JsonValidator] {
    override def reads(json: JsValue): JsResult[JsonValidator] = try {
      JsSuccess(
        OpaPluginValidator(
          ref = json.select("ref").asString,
          error = json.select("error").asOpt[String]
        )
      )
    } catch {
      case e: Throwable => JsError(e.getMessage)
    }

    override def writes(o: JsonValidator): JsValue = o.json
  }
}

case class OpaPluginValidator(ref: String, error: Option[String] = None) extends JsonValidator {

  private lazy val config = BasicWasmConfiguration(WasmSource(WasmSourceKind.Local, ref), opa = true)

  override def kind: String = "opa-plugin-validator"

  override def json: JsValue = Json.obj(
    "kind" -> kind,
    "ref"  -> ref
  )

  override def validate(ctx: JsValue)(implicit env: Env): Boolean = {
    val fu = env.wasmIntegration.withPooledVm(config) { vm =>
      vm.callOpa("execute", ctx.prettify)(env.otoroshiExecutionContext)
        .map {
          case Right((rawResult, _)) => {
            val response = Json.parse(rawResult)
            val result   = response.asOpt[JsArray].getOrElse(Json.arr())
            (result.value.head \ "result").asOpt[Boolean].getOrElse(false)
          }
          case Left(_)               => false
        }(env.otoroshiExecutionContext)
    }
    Await.result(fu, 30.seconds)
  }
}
