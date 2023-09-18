package io.otoroshi.common.wasm.scaladsl

import akka.stream.Materializer
import akka.util.ByteString
import io.otoroshi.common.wasm.impl._
import implicits._
import org.extism.sdk.wasmotoroshi._
import play.api.libs.json._

import java.nio.charset.StandardCharsets
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}

trait AwaitCapable {
  def await[T](future: Future[T], atMost: FiniteDuration = 5.seconds): T = {
    Await.result(future, atMost)
  }
}

case class HostFunctionWithAuthorization(
                                          function: WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData],
                                          authorized: WasmConfiguration => Boolean
                                        )

case class EnvUserData(
                        ic: WasmIntegrationContext,
                        executionContext: ExecutionContext,
                        mat: Materializer,
                        config: WasmConfiguration
                      ) extends WasmOtoroshiHostUserData

case class StateUserData(
                          ic: WasmIntegrationContext,
                          executionContext: ExecutionContext,
                          mat: Materializer,
                          cache: TrieMap[String, TrieMap[String, ByteString]]
                        ) extends WasmOtoroshiHostUserData

case class EmptyUserData() extends WasmOtoroshiHostUserData

sealed abstract class WasmFunctionParameters {
  def functionName: String
  def input: Option[String]
  def parameters: Option[WasmOtoroshiParameters]
  def resultSize: Option[Int]
  def call(plugin: WasmOtoroshiInstance): Either[JsValue, (String, ResultsWrapper)]
  def withInput(input: Option[String]): WasmFunctionParameters
  def withFunctionName(functionName: String): WasmFunctionParameters
}

object WasmFunctionParameters {

  def from(
            functionName: String,
            input: Option[String],
            parameters: Option[WasmOtoroshiParameters],
            resultSize: Option[Int]
          ) = {
    (input, parameters, resultSize) match {
      case (_, Some(p), Some(s))  => BothParamsResults(functionName, p, s)
      case (_, Some(p), None)     => NoResult(functionName, p)
      case (_, None, Some(s))     => NoParams(functionName, s)
      case (Some(in), None, None) => ExtismFuntionCall(functionName, in)
      case _                      => UnknownCombination()
    }
  }

  case class UnknownCombination(
                                 functionName: String = "unknown",
                                 input: Option[String] = None,
                                 parameters: Option[WasmOtoroshiParameters] = None,
                                 resultSize: Option[Int] = None
                               ) extends WasmFunctionParameters {
    override def call(plugin: WasmOtoroshiInstance): Either[JsValue, (String, ResultsWrapper)] = {
      Left(Json.obj("error" -> "bad call combination"))
    }
    def withInput(input: Option[String]): WasmFunctionParameters       = this.copy(input = input)
    def withFunctionName(functionName: String): WasmFunctionParameters = this.copy(functionName = functionName)
  }

  case class NoResult(
                       functionName: String,
                       params: WasmOtoroshiParameters,
                       input: Option[String] = None,
                       resultSize: Option[Int] = None
                     ) extends WasmFunctionParameters {
    override def parameters: Option[WasmOtoroshiParameters]                     = Some(params)
    override def call(plugin: WasmOtoroshiInstance): Either[JsValue, (String, ResultsWrapper)] = {
      plugin.callWithoutResults(functionName, parameters.get)
      Right[JsValue, (String, ResultsWrapper)](("", ResultsWrapper(new WasmOtoroshiResults(0), plugin)))
    }
    override def withInput(input: Option[String]): WasmFunctionParameters       = this.copy(input = input)
    override def withFunctionName(functionName: String): WasmFunctionParameters = this.copy(functionName = functionName)
  }

  case class NoParams(
                       functionName: String,
                       result: Int,
                       input: Option[String] = None,
                       parameters: Option[WasmOtoroshiParameters] = None
                     ) extends WasmFunctionParameters {
    override def resultSize: Option[Int]                                        = Some(result)
    override def call(plugin: WasmOtoroshiInstance): Either[JsValue, (String, ResultsWrapper)] = {
      plugin
        .callWithoutParams(functionName, resultSize.get)
        .right
        .map(_ => ("", ResultsWrapper(new WasmOtoroshiResults(0), plugin)))
    }
    override def withInput(input: Option[String]): WasmFunctionParameters       = this.copy(input = input)
    override def withFunctionName(functionName: String): WasmFunctionParameters = this.copy(functionName = functionName)
  }

  case class BothParamsResults(
                                functionName: String,
                                params: WasmOtoroshiParameters,
                                result: Int,
                                input: Option[String] = None
                              ) extends WasmFunctionParameters {
    override def parameters: Option[WasmOtoroshiParameters]                     = Some(params)
    override def resultSize: Option[Int]                                        = Some(result)
    override def call(plugin: WasmOtoroshiInstance): Either[JsValue, (String, ResultsWrapper)] = {
      plugin
        .call(functionName, parameters.get, resultSize.get)
        .right
        .map(res => ("", ResultsWrapper(res, plugin)))
    }
    override def withInput(input: Option[String]): WasmFunctionParameters       = this.copy(input = input)
    override def withFunctionName(functionName: String): WasmFunctionParameters = this.copy(functionName = functionName)
  }

  case class ExtismFuntionCall(
                                functionName: String,
                                in: String,
                                parameters: Option[WasmOtoroshiParameters] = None,
                                resultSize: Option[Int] = None
                              ) extends WasmFunctionParameters {
    override def input: Option[String] = Some(in)
    override def call(plugin: WasmOtoroshiInstance): Either[JsValue, (String, ResultsWrapper)] = {
      plugin
        .extismCall(functionName, input.get.getBytes(StandardCharsets.UTF_8))
        .right
        .map { str =>
          (str, ResultsWrapper(new WasmOtoroshiResults(0), plugin))
        }
    }

    override def withInput(input: Option[String]): WasmFunctionParameters       = this.copy(in = input.get)
    override def withFunctionName(functionName: String): WasmFunctionParameters = this.copy(functionName = functionName)
  }

  case class OPACall(functionName: String, pointers: Option[OPAWasmVm] = None, in: String)
    extends WasmFunctionParameters {
    override def input: Option[String] = Some(in)

    override def call(plugin: WasmOtoroshiInstance): Either[JsValue, (String, ResultsWrapper)] = {
      if (functionName == "initialize")
        OPA.initialize(plugin)
      else
        OPA.evaluate(plugin, pointers.get.opaDataAddr, pointers.get.opaBaseHeapPtr, in)
    }

    override def withInput(input: Option[String]): WasmFunctionParameters = this.copy(in = input.get)

    override def withFunctionName(functionName: String): WasmFunctionParameters = this
    override def parameters: Option[WasmOtoroshiParameters]                     = None
    override def resultSize: Option[Int]                                        = None
  }
}