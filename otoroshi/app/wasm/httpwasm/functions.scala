package otoroshi.wasm.httpwasm

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import io.otoroshi.wasm4s.scaladsl._
import org.extism.sdk.{ExtismCurrentPlugin, HostFunction, HostUserData, LibExtism}
import otoroshi.env.Env
import otoroshi.next.plugins.api.{NgPluginHttpRequest, NgPluginHttpResponse}
import otoroshi.utils.syntax.implicits.BetterSyntax
import otoroshi.wasm.httpwasm.HttpWasmFunctions.parameters
import otoroshi.wasm.httpwasm.api.{BodyKind, Feature, Features, HeaderKind, LogLevel}
import play.api.libs.json.{JsObject, Json}

import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext

case class HttpWasmVmData(
                           config: JsObject = Json.obj(),
                           properties: Map[String, Array[Byte]] = Map.empty,
                           var requestStatusCode: Int = 200,
                           var request: NgPluginHttpRequest,
                           var response: NgPluginHttpResponse,
                           var features: Features = Features(3 | Feature.FeatureBufferRequest.value | Feature.FeatureBufferResponse.value | Feature.FeatureTrailers.value),
                           var nextCalled: Boolean = false,
                           var requestBodyReadIndex: Int = 0,
                           var responseBodyReadIndex: Int = 0,
                           var requestBodyReplaced: Boolean = false,
                           var responseBodyReplaced: Boolean = false
                         ) extends HostUserData
  with WasmVmData {
  def headers(kind: HeaderKind): Map[String, String] = {
    kind match {
      case HeaderKind.HeaderKindRequest => request.headers
      case HeaderKind.HeaderKindResponse => response.headers
      case HeaderKind.HeaderKindRequestTrailers => ???  // TODO
      case HeaderKind.HeaderKindResponseTrailers => ??? // TODO
    }
  }

  def setRequest(newRequest: NgPluginHttpRequest) = {
    request = newRequest
  }

  def setResponse(newResponse: NgPluginHttpResponse) = {
    response = newResponse
  }

  def setHeader(kind: HeaderKind, key: String, value: Seq[String]) = {
    kind match {
      case HeaderKind.HeaderKindRequest => setRequest(request.copy(headers = request.headers ++ Map(key -> value.head)))
      case HeaderKind.HeaderKindResponse => setResponse(response.copy(headers = response.headers ++ Map(key -> value.head)))
      case HeaderKind.HeaderKindRequestTrailers => ???  // TODO
      case HeaderKind.HeaderKindResponseTrailers => ???  // TODO
    }
  }

  def removeHeader(kind: HeaderKind, key: String) = {
    kind match {
      case HeaderKind.HeaderKindRequest => setRequest(request.copy(headers = request.headers - key))
      case HeaderKind.HeaderKindResponse => setResponse(response.copy(headers = response.headers - key))
      case HeaderKind.HeaderKindRequestTrailers => ???  // TODO
      case HeaderKind.HeaderKindResponseTrailers => ???  // TODO
    }
  }
}

object HttpWasmVmData {
  def withRequest(request: NgPluginHttpRequest) = HttpWasmVmData(
    request = request,
    response = NgPluginHttpResponse(
    status = 200,
    headers = Map.empty[String, String],
    body = Source.empty
  ))
}

object AdministrativeFunctions {
  def all(state: HttpWasmState, getCurrentVmData: () => HttpWasmVmData) = {
    Seq(
      new HostFunction[EnvUserData](
        "enable_features",
        parameters(2),
        parameters(1),
        (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          data: Optional[EnvUserData]
        ) => state.enableFeatures(getCurrentVmData(), params(0).v.i32),
        Optional.empty[EnvUserData]()
      ),
      new HostFunction[EnvUserData](
        "get_config",
        parameters(2),
        parameters(1),
        (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          data: Optional[EnvUserData]
        ) => state.getConfig(plugin, getCurrentVmData(), params(0).v.i32, params(0).v.i32),
        Optional.empty[EnvUserData]()
      )
    )
      .map(_.withNamespace("http_handler"))
  }
}

object LoggingFunctions {
  def all(state: HttpWasmState, getCurrentVmData: () => HttpWasmVmData) = {
    Seq(
      new HostFunction[EnvUserData](
        "log",
        parameters(3),
        parameters(0),
        (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          data: Optional[EnvUserData]
        ) => state.log(plugin, LogLevel.fromValue(params(0).v.i32), params(1).v.i32, params(2).v.i32),
        Optional.empty[EnvUserData]()
      ),
      new HostFunction[EnvUserData](
        "log_enabled",
        parameters(1),
        parameters(1),
        (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          data: Optional[EnvUserData]
        ) => state.logEnabled(LogLevel.fromValue(params(0).v.i32)),
        Optional.empty[EnvUserData]()
      )
    )
      .map(_.withNamespace("http_handler"))
  }
}

object HeaderFunctions {
  def all(state: HttpWasmState, getCurrentVmData: () => HttpWasmVmData) = {
    Seq(
      new HostFunction[EnvUserData](
        "get_header_names",
        parameters(3),
        parameters(1),
        (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          data: Optional[EnvUserData]
        ) => state.getHeaderNames(plugin, getCurrentVmData(), HeaderKind.fromValue(params(0).v.i32), params(1).v.i32, params(2).v.i32),
        Optional.empty[EnvUserData]()
      ),
      new HostFunction[EnvUserData](
        "get_header_values",
        parameters(5),
        parameters(0),
        (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          data: Optional[EnvUserData]
        ) => state.getHeaderValues(plugin, getCurrentVmData(), HeaderKind.fromValue(params(0).v.i32), params(1).v.i32, params(2).v.i32, params(3).v.i32, params(4).v.i32),
        Optional.empty[EnvUserData]()
      ),
      new HostFunction[EnvUserData](
        "set_header_value",
        parameters(5),
        parameters(0),
        (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          data: Optional[EnvUserData]
        ) => state.setHeader(plugin, getCurrentVmData(), HeaderKind.fromValue(params(0).v.i32), params(1).v.i32, params(2).v.i32, params(3).v.i32, params(4).v.i32),
        Optional.empty[EnvUserData]()
      ),
      new HostFunction[EnvUserData](
        "add_header_value",
        parameters(5),
        parameters(1),
        (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          data: Optional[EnvUserData]
        ) => state.addHeader(plugin, getCurrentVmData(), HeaderKind.fromValue(params(0).v.i32), params(1).v.i32, params(2).v.i32, params(3).v.i32, params(4).v.i32),
        Optional.empty[EnvUserData]()
      ),
      new HostFunction[EnvUserData](
        "remove_header",
        parameters(3),
        parameters(1),
        (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          data: Optional[EnvUserData]
        ) => state.removeHeader(plugin, getCurrentVmData(), HeaderKind.fromValue(params(0).v.i32), params(1).v.i32, params(2).v.i32),
        Optional.empty[EnvUserData]()
      )
    )
      .map(_.withNamespace("http_handler"))
  }
}

object BodyFunctions {
  def all(state: HttpWasmState, getCurrentVmData: () => HttpWasmVmData) = {
    Seq(
      new HostFunction[EnvUserData](
        "read_body",
        parameters(3),
        parameters(1),
        (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          data: Optional[EnvUserData]
        ) => state.readBody(plugin, getCurrentVmData(), BodyKind.fromValue(params(0).v.i32), params(1).v.i32, params(2).v.i32),
        Optional.empty[EnvUserData]()
      ),
      new HostFunction[EnvUserData](
        "write_body",
        parameters(3),
        parameters(0),
        (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          data: Optional[EnvUserData]
        ) => state.writeBody(plugin, getCurrentVmData(), BodyKind.fromValue(params(0).v.i32), params(1).v.i32, params(2).v.i32),
        Optional.empty[EnvUserData]()
      )
    )
      .map(_.withNamespace("http_handler"))
  }
}

object RequestFunctions {
  def all(state: HttpWasmState, getCurrentVmData: () => HttpWasmVmData) = {
    Seq(
      new HostFunction[EnvUserData](
        "get_method",
        parameters(2),
        parameters(1),
        (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          data: Optional[EnvUserData]
        ) => state.getMethod(plugin, getCurrentVmData(), params(0).v.i32, params(1).v.i32),
        Optional.empty[EnvUserData]()
      ),
      new HostFunction[EnvUserData](
        "set_method",
        parameters(2),
        parameters(0),
        (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          data: Optional[EnvUserData]
        ) => state.setMethod(plugin, getCurrentVmData(), params(0).v.i32, params(1).v.i32),
        Optional.empty[EnvUserData]()
      ),
      new HostFunction[EnvUserData](
        "get_uri",
        parameters(2),
        parameters(1),
        (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          data: Optional[EnvUserData]
        ) => {
          returns(0).v.i32 = state.getUri(plugin, getCurrentVmData(), params(0).v.i32, params(1).v.i32)

          println("get_uri ended")
        },
        Optional.empty[EnvUserData]()
      ),
      new HostFunction[EnvUserData](
        "set_uri",
        parameters(2),
        parameters(0),
        (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          data: Optional[EnvUserData]
        ) => state.setUri(plugin, getCurrentVmData(), params(0).v.i32, params(1).v.i32),
        Optional.empty[EnvUserData]()
      ),
      new HostFunction[EnvUserData](
        "get_protocol_version",
        parameters(2),
        parameters(1),
        (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          data: Optional[EnvUserData]
        ) => state.getProtocolVersion(plugin, getCurrentVmData(), params(0).v.i32, params(1).v.i32),
        Optional.empty[EnvUserData]()
      ),
      new HostFunction[EnvUserData](
        "get_source_addr",
        parameters(2),
        parameters(1),
        (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          data: Optional[EnvUserData]
        ) => {
          println("get_source_addr TODO - not defined")
        },
        Optional.empty[EnvUserData]()
      )
    )
      .map(_.withNamespace("http_handler"))
  }
}

object ResponseFunctions {
  def all(state: HttpWasmState, getCurrentVmData: () => HttpWasmVmData) = {
    Seq(
      new HostFunction[EnvUserData](
        "get_status_code",
        parameters(0),
        parameters(1),
        (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          data: Optional[EnvUserData]
        ) => state.getStatusCode(getCurrentVmData()),
        Optional.empty[EnvUserData]()
      ),
      new HostFunction[EnvUserData](
        "set_status_code",
        parameters(1),
        parameters(0),
        (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          data: Optional[EnvUserData]
        ) => state.setStatusCode(getCurrentVmData(), params(0).v.i32),
        Optional.empty[EnvUserData]()
      )
    )
      .map(_.withNamespace("http_handler"))
  }
}

object HttpWasmFunctions {
  def parameters(n: Int): Array[LibExtism.ExtismValType] = {
    (0 until n).map(_ => LibExtism.ExtismValType.I32).toArray
  }

  def build(
             state: HttpWasmState,
             vmDataRef: AtomicReference[WasmVmData]
           )(implicit ec: ExecutionContext, env: Env, mat: Materializer): Seq[HostFunction[EnvUserData]] = {
    def getCurrentVmData(): HttpWasmVmData = {
      Option(vmDataRef.get()) match {
        case Some(data: HttpWasmVmData) => data
        case _                  =>
          println("missing vm data")
          //          new RuntimeException("missing vm data").printStackTrace()
          throw new RuntimeException("missing vm data")
      }
    }

    AdministrativeFunctions.all(state, getCurrentVmData) ++
      LoggingFunctions.all(state, getCurrentVmData) ++
      HeaderFunctions.all(state, getCurrentVmData) ++
      BodyFunctions.all(state, getCurrentVmData) ++
      RequestFunctions.all(state, getCurrentVmData) ++
      ResponseFunctions.all(state, getCurrentVmData)
  }
}