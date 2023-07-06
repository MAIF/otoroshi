package otoroshi.wasm

import org.extism.sdk.Results
import org.extism.sdk.otoroshi._
import play.api.libs.json._
import otoroshi.utils.syntax.implicits._

import java.nio.charset.StandardCharsets

sealed abstract class WasmFunctionParameters {
  def functionName: String
  def input: Option[String]
  def parameters: Option[OtoroshiParameters]
  def resultSize: Option[Int]
  def call(plugin: OtoroshiInstance): Either[JsValue, (String, ResultsWrapper)]
  def withInput(input: Option[String]): WasmFunctionParameters
  def withFunctionName(functionName: String): WasmFunctionParameters
}

object WasmFunctionParameters {
  def from(functionName: String, input: Option[String], parameters: Option[OtoroshiParameters], resultSize: Option[Int]) = {
    (input, parameters, resultSize) match {
      case (_, Some(p), Some(s))        => BothParamsResults(functionName, p, s)
      case (_, Some(p), None)           => NoResult(functionName, p)
      case (_, None, Some(s))           => NoParams(functionName, s)
      case (Some(in), None, None)       => ExtismFuntionCall(functionName, in)
      case _                            => UnknownCombination()
    }
  }

  case class UnknownCombination(functionName: String = "unknown",
                                input: Option[String] = None,
                                parameters: Option[OtoroshiParameters] = None,
                                resultSize: Option[Int] = None)
    extends WasmFunctionParameters {
    override def call(plugin: OtoroshiInstance): Either[JsValue, (String, ResultsWrapper)] = {
      Left(Json.obj("error" -> "bad call combination"))
    }
    def withInput(input: Option[String]): WasmFunctionParameters = this.copy(input = input)
    def withFunctionName(functionName: String): WasmFunctionParameters = this.copy(functionName = functionName)
  }

  case class NoResult(functionName: String, params: OtoroshiParameters,
                      input: Option[String] = None,
                      resultSize: Option[Int] = None) extends WasmFunctionParameters {
    override def parameters: Option[OtoroshiParameters] = Some(params)
    override def call(plugin: OtoroshiInstance): Either[JsValue, (String, ResultsWrapper)] = {
      plugin.callWithoutResults(functionName, parameters.get)
      Right[JsValue, (String, ResultsWrapper)](("", ResultsWrapper(new OtoroshiResults(0), plugin)))
    }
    override def withInput(input: Option[String]): WasmFunctionParameters = this.copy(input = input)
    override def withFunctionName(functionName: String): WasmFunctionParameters = this.copy(functionName = functionName)
  }

  case class NoParams(functionName: String, result: Int,
                      input: Option[String] = None,
                      parameters: Option[OtoroshiParameters] = None) extends WasmFunctionParameters {
    override def resultSize: Option[Int] = Some(result)
    override def call(plugin: OtoroshiInstance): Either[JsValue, (String, ResultsWrapper)] = {
      plugin.callWithoutParams(functionName, resultSize.get)
        .right
        .map(_ => ("", ResultsWrapper(new OtoroshiResults(0), plugin)))
    }
    override def withInput(input: Option[String]): WasmFunctionParameters = this.copy(input = input)
    override def withFunctionName(functionName: String): WasmFunctionParameters = this.copy(functionName = functionName)
  }

  case class BothParamsResults(functionName: String, params: OtoroshiParameters, result: Int,
                               input: Option[String] = None) extends WasmFunctionParameters {
    override def parameters: Option[OtoroshiParameters] = Some(params)
    override def resultSize: Option[Int] = Some(result)
    override def call(plugin: OtoroshiInstance): Either[JsValue, (String, ResultsWrapper)] = {
      plugin.call(functionName, parameters.get, resultSize.get)
        .right
        .map(res => ("", ResultsWrapper(res, plugin)))
    }
    override def withInput(input: Option[String]): WasmFunctionParameters = this.copy(input = input)
    override def withFunctionName(functionName: String): WasmFunctionParameters = this.copy(functionName = functionName)
  }

  case class ExtismFuntionCall(functionName: String,
                               in: String,
                               parameters: Option[OtoroshiParameters] = None,
                               resultSize: Option[Int] = None) extends WasmFunctionParameters {
    override def input: Option[String] = Some(in)
    override def call(plugin: OtoroshiInstance): Either[JsValue, (String, ResultsWrapper)] = {
      plugin.extismCall(functionName, input.get.getBytes(StandardCharsets.UTF_8))
        .right
        .map { str =>
          (str, ResultsWrapper(new OtoroshiResults(0), plugin))
        }
    }

    override def withInput(input: Option[String]): WasmFunctionParameters = this.copy(in = input.get)
    override def withFunctionName(functionName: String): WasmFunctionParameters = this.copy(functionName = functionName)
  }

  case class OPACall(functionName: String, pointers: Option[OPAWasmVm] = None, in: String) extends WasmFunctionParameters {
    override def input: Option[String] = Some(in)

    override def call(plugin: OtoroshiInstance): Either[JsValue, (String, ResultsWrapper)] = {
      if (functionName == "initialize")
        OPA.initialize(plugin)
      else
        OPA.evaluate(plugin, pointers.get.opaDataAddr, pointers.get.opaBaseHeapPtr, in)
    }

    override def withInput(input: Option[String]): WasmFunctionParameters = this.copy(in = input.get)

    override def withFunctionName(functionName: String): WasmFunctionParameters = this
    override def parameters: Option[OtoroshiParameters] = None
    override def resultSize: Option[Int] = None
  }
}