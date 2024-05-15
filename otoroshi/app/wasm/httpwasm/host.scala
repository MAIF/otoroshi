package otoroshi.wasm.httpwasm.api

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.sun.jna.Pointer
import io.otoroshi.wasm4s.scaladsl.{EnvUserData, ResultsWrapper, WasmFunctionParameters, WasmVmData, WasmVmInitOptions, WasmVmPool}
import org.extism.sdk.wasmotoroshi.Parameters
import org.extism.sdk.{ExtismCurrentPlugin, HostFunction, HostUserData}
import otoroshi.env.Env
import otoroshi.next.plugins.api.{NgPluginHttpRequest, NgPluginHttpResponse}
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm.httpwasm.{HttpWasmFunctions, HttpWasmVmData}
import otoroshi.wasm.proxywasm.{VmData, WasmUtils}
import otoroshi.wasm.WasmConfig
import play.api.Logger

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt

sealed trait HeaderKind {
  def value: Int
}

object HeaderKind {
  case object HeaderKindRequest extends HeaderKind {
    def value: Int = 0
  }
  case object HeaderKindResponse extends HeaderKind {
    def value: Int = 1
  }
  case object HeaderKindRequestTrailers extends HeaderKind {
    def value: Int = 2
  }
  case object HeaderKindResponseTrailers extends HeaderKind {
    def value: Int = 3
  }
}

sealed trait BodyKind {
  def value: Int
}

object BodyKind {
  case object BodyKindRequest extends BodyKind {
    def value: Int = 0
  }

  case object BodyKindResponse extends BodyKind {
    def value: Int = 1
  }
}

sealed trait LogLevel {
  def value: Int
}

object LogLevel {
  case object LogLevelDebug extends LogLevel {
    def value: Int = -1
  }

  case object LogLevelInfo extends LogLevel {
    def value: Int = 0
  }

  case object LogLevelWarn extends LogLevel {
    def value: Int = 1
  }

  case object LogLevelError extends LogLevel {
    def value: Int = 2
  }

  case object LogLevelNone extends LogLevel {
    def value: Int = 3
  }

  def fromValue(value: Int): LogLevel = {
    value match {
      case -1 => LogLevelDebug
      case 0 => LogLevelInfo
      case 1 => LogLevelWarn
      case 2 => LogLevelError
      case 3 => LogLevelNone
    }
  }
}


sealed trait Feature {
  def value: Int
}

object Feature {
  case object FeatureBufferRequest extends Feature {
    def value: Int = 1 << 0
  }

  case object FeatureBufferResponse extends Feature {
    def value: Int = 1 << 1
  }

  case object FeatureTrailers extends Feature {
    def value: Int = 1 << 2
  }
}

class Features(features: Int) {
  def has(feature: Feature): Boolean = {
    (features & feature.value) == feature.value
  }
}

class RequestState(
                   var request: NgPluginHttpRequest,
                   var response: NgPluginHttpResponse
                 ) extends HostUserData {
  var nextCalled: Boolean = false
  var features: Option[Features] = None
  var requestBodyReadIndex = 0
  var responseBodyReadIndex = 0
  var requestBodyReplaced = false
  var responseBodyReplaced = false

  def headers(kind: HeaderKind): Map[String, String] = {
    kind match {
      case HeaderKind.HeaderKindRequest => request.headers
      case HeaderKind.HeaderKindResponse => response.headers
      case HeaderKind.HeaderKindRequestTrailers => ???  // TODO
      case HeaderKind.HeaderKindResponseTrailers => ??? // TODO
    }
  }

  def setHeader(kind: HeaderKind, key: String, value: Seq[String]) = {
    kind match {
      case HeaderKind.HeaderKindRequest => request = request.copy(headers = request.headers ++ Map(key -> value.head))
      case HeaderKind.HeaderKindResponse => response = response.copy(headers = response.headers ++ Map(key -> value.head))
      case HeaderKind.HeaderKindRequestTrailers => ???  // TODO
      case HeaderKind.HeaderKindResponseTrailers => ???  // TODO
    }
  }

  def removeHeader(kind: HeaderKind, key: String) = {
    kind match {
      case HeaderKind.HeaderKindRequest => request = request.copy(headers = request.headers.removeAllArgs(key))
      case HeaderKind.HeaderKindResponse => response = response.copy(headers = response.headers.removeAllArgs(key))
      case HeaderKind.HeaderKindRequestTrailers => ???  // TODO
      case HeaderKind.HeaderKindResponseTrailers => ???  // TODO
    }
  }
}

class HttpHandler(wasm: WasmConfig, config: ByteString, key: String, env: Env) {

  // private lazy val state = RequestState()
  private lazy val pool: WasmVmPool = WasmVmPool.forConfigurationWithId(key, wasm)(env.wasmIntegration.context)

  implicit val ex = env.otoroshiExecutionContext
  implicit val mat = env.otoroshiMaterializer
  implicit val e = env

  val logger = Logger("otoroshi-http-wasm-handler")

  def createFunctions(ref: AtomicReference[WasmVmData]): Seq[HostFunction[EnvUserData]] = {
    HttpWasmFunctions.build(this, ref)
  }

  def start(attrs: TypedMap): Future[Unit] = {
    pool.getPooledVm(WasmVmInitOptions(
      importDefaultHostFunctions = false,
      resetMemory = false, // was true in proxy wasm
      createFunctions)).flatMap { vm =>
      attrs.put(otoroshi.wasm.httpwasm.HttpWasmPluginKeys.HttpWasmVmKey -> vm)
      vm.finitialize {
       Future.successful()
      }
    }
  }

  def callPluginWithResults(
                             function: String,
                             params: Parameters,
                             results: Int,
                             data: VmData,
                             attrs: TypedMap
                           ): Future[ResultsWrapper] = {
    attrs.get(otoroshi.wasm.httpwasm.HttpWasmPluginKeys.HttpWasmVmKey) match {
      case None =>
        println("no vm found in attrs")
        Future.failed(new RuntimeException("no vm found in attrs"))
      case Some(vm) => {
        WasmUtils.traceHostVm(function + s" - vm: ${vm.index}")
        val callId = attrs.get(otoroshi.wasm.httpwasm.HttpWasmPluginKeys.HttpWasmVmKey).getOrElse(0).asInstanceOf[Int]
        vm.call(
          WasmFunctionParameters.BothParamsResults(function, params, results),
          Some(data.copy(properties = data.properties + ("wasm-vm-id" -> callId.bytes)))
        ).flatMap {
          case Left(err) =>
            println(s"error while calling plugin: ${err}")
            Future.failed(new RuntimeException(s"callPluginWithResults: ${err.stringify}"))
          case Right((_, results)) => results.vfuture
        }
      }
    }
  }

  def enableFeatures(vmData: HttpWasmVmData, features: Int): Int = {
    val state = vmData.state
    state.features = new Features(features).some
    features
  }

  def getConfig(plugin: ExtismCurrentPlugin, vmData: HttpWasmVmData, buf: Int, bufLimit: Int) = {
    writeIfUnderLimit(plugin, buf, bufLimit, config)
  }

  def writeIfUnderLimit(plugin: ExtismCurrentPlugin, offset: Int, limit: Int, v: ByteString): Int = {
    val vLen = v.length
    if (vLen > limit || vLen == 0) {
      return vLen
    }

    val memory: Pointer = plugin.customMemoryGet()
    memory.write(offset, v.toArray, 0, vLen)
    vLen
  }

  def writeNullTerminated(plugin: ExtismCurrentPlugin, buf: Int, bufLimit: Int, input: Seq[String]): BigInt = {
    val count = BigInt(input.length)
    if (count == 0) {
      return 0
    }

    val encodedInput = input.map(i => ByteString(i))
    val byteCount = encodedInput.foldLeft(0) { case (acc, i) => acc + i.length + 1 }

    val countLen = (count << 32) | BigInt (byteCount)

    if (byteCount > bufLimit) {
      return countLen
    }

    var offset = 0

    val memory: Pointer = plugin.customMemoryGet()

    encodedInput.foreach(s => {
      val sLen = s.length
      memory.write(buf + offset, s.toArray, 0, s.length)
      offset += sLen
      memory.setInt(buf + offset, 0)
      offset += 1
    })

    countLen
  }

  def writeStringIfUnderLimit(
                               plugin: ExtismCurrentPlugin,
                               offset: Int,
                               limit: Int,
                               v: String
  ): Int = {
    this.writeIfUnderLimit(plugin, offset, limit, ByteString(v))
  }

  def getHeaderNames(
      plugin: ExtismCurrentPlugin,
      vmData: HttpWasmVmData,
      kind: HeaderKind,
      buf: Int,
      bufLimit: Int,
  ): BigInt = {
    val state = vmData.state
    val headers = state.headers(kind)

    val headerNames = headers.keys.toSeq
    this.writeNullTerminated (plugin, buf, bufLimit, headerNames)
  }

  def getHeaderValues(
      vmData: HttpWasmVmData,
      plugin: ExtismCurrentPlugin,
      kind: HeaderKind,
      name: Int,
      nameLen: Int,
      buf: Int,
      bufLimit: Int
  ): BigInt = {

    if (nameLen == 0) {
      throw new Error("HTTP header name cannot be empty")
    }

    val state = vmData.state
    val headers = state.headers(kind)

    val n = this.mustReadString(plugin, "name", name, nameLen).toLowerCase()
    val value = headers.get(n)
    var values: Seq[String] = Seq.empty

    value.foreach(value => {
      n match {
        // TODO(anuraaga): date is not mentioned as a header where duplicates are discarded.
        // However, since it has a comma inside, it seems it must be handled as a single
        // string. Double-check this.
        case "date" |  "age" |  "authorization" |  "content - length" |  "content - type" |  "etag" |  "expires" |  "from" |  "host" |  "if - modified - since" |  "if - unmodified - since" |  "last - modified" |  "location" |  "max - forwards" |  "proxy - authorization" |  "referer" |  "retry - after" |  "server" |  "user - agent" =>
          values = Seq(value)
        case "cookie" => values = value.split("; ")
        case _        => values = value.split(", ")
      }
    })

    this.writeNullTerminated(plugin, buf, bufLimit, values)
  }

  def getMethod(vmData: HttpWasmVmData, plugin: ExtismCurrentPlugin, buf: Int, bufLimit: Int): Int = {
    val state = vmData.state
    writeStringIfUnderLimit (plugin, buf, bufLimit, state.request.method)
  }

  def getProtocolVersion(vmData: HttpWasmVmData, plugin: ExtismCurrentPlugin, buf: Int, bufLimit: Int): Int = {
    val state = vmData.state
    var httpVersion = state.request.version
    httpVersion match {
      case "1.0" => httpVersion = "HTTP/1.0"
      case "1.1" => httpVersion = "HTTP/1.1"
      case "2" => httpVersion = "HTTP/2.0"
      case "2.0" => httpVersion = "HTTP/2.0"
    }
    this.writeStringIfUnderLimit (plugin, buf, bufLimit, httpVersion)
  }

  def getStatusCode(request: RequestState): Int = {
    request.response.status
  }

  def getUri(vmData: HttpWasmVmData, plugin: ExtismCurrentPlugin, buf: Int, bufLimit: Int): Int = {
    val state = vmData.state
    this.writeStringIfUnderLimit (plugin, buf, bufLimit, state.request.uri.toString());
  }

  def log(plugin: ExtismCurrentPlugin, level: LogLevel, buf: Int, bufLimit: Int) = {
    val s = mustReadString(plugin, "log", buf, bufLimit)

    level match {
      case LogLevel.LogLevelDebug => logger.debug(s)
      case LogLevel.LogLevelInfo => logger.info(s)
      case LogLevel.LogLevelWarn => logger.warn(s)
      case LogLevel.LogLevelError => logger.error(s)
    }
  }

  def logEnabled(level: LogLevel): Int = {
    if (level != LogLevel.LogLevelDebug) {
      return 1
    }
    0
  }

  def readBody(vmData: HttpWasmVmData, plugin: ExtismCurrentPlugin, kind: BodyKind, buf: Int, bufLimit: Int): BigInt = {
    val state = vmData.state
    val memory = plugin.customMemoryGet()

    if (kind == BodyKind.BodyKindRequest) {
      val body = Await.result(state.request.body.runFold(ByteString.empty)(_ ++ _)(env.otoroshiMaterializer), 10.seconds)
      val start = state.requestBodyReadIndex
      val end = Math.min (start + bufLimit, body.length)
      val slice = body.slice(start, end)

      memory.write(buf, slice.toArray, 0, slice.length)
      state.requestBodyReadIndex = end
      if (end == body.length) {
        return (1 << 32) | BigInt (slice.length)
      }
      return BigInt (slice.length);
    }

    if (kind != BodyKind.BodyKindResponse) {
      throw new Error(s"Unknown body kind $kind")
    }

    val body = Await.result(state.response.body.runFold(ByteString.empty)(_ ++ _)(env.otoroshiMaterializer), 10.seconds)

//    if (buffer.isEmpty) {
//      throw new Error(s"Response body not buffered")
//    }

    val start = state.responseBodyReadIndex
    val end = Math.min(start + bufLimit, body.length)
    val slice = body.slice(start, end)
    memory.write(buf, slice.toArray, 0, slice.length)

    state.responseBodyReadIndex = end
    if (end == body.length) {
      return (1 << 32) | BigInt (slice.length)
    }
    BigInt (slice.length)
  }

  def setMethod(vmData: HttpWasmVmData, plugin: ExtismCurrentPlugin, name: Int, nameLen: Int) {
    val state = vmData.state
    val req = state.request
    val method = this.mustReadString(plugin, "method", name, nameLen)
    state.request.copy(method = method)
  }

  def writeBody(vmData: HttpWasmVmData, plugin: ExtismCurrentPlugin, kind: BodyKind, body: Int, bodyLen: Int) = {
    val state = vmData.state
    var b: ByteString = ByteString.empty

    if (bodyLen == 0) {
      b = ByteString.empty
    } else {
      b = this.mustRead(plugin, "body", body, bodyLen)
    }

    kind match {
      case BodyKind.BodyKindRequest =>
        if (state.requestBodyReplaced) {
          state.request.copy(body = state.request.body.concat(Source.single(b)))
        } else {
          state.request.copy(body = Source.single(b))
        }
      case BodyKind.BodyKindResponse =>
        if (!state.nextCalled) {
          state.response.copy(body = Source.single(b))
        } else if (state.responseBodyReplaced) {
          state.response.copy(body = state.response.body.concat(Source.single(b)))
        } else {
          state.response.copy(body = Source.single(b))
        }
    }
  }

  def addHeader(
      plugin: ExtismCurrentPlugin,
      vmData: HttpWasmVmData,
      kind: HeaderKind,
      name: Int,
      nameLen: Int,
      value: Int,
      valueLen: Int
  ) = {
    val state = vmData.state

    if (nameLen == 0) {
      throw new Error ("HTTP header name cannot be empty")
    }

    val n = this.mustReadString (plugin, "name", name, nameLen)
    val v = this.mustReadString (plugin, "value", value, valueLen)

    val headers = state.headers(kind)
    val existing = headers.get(n)
    val newValue = existing.map(existing => Seq(existing, v)).getOrElse(Seq(v))

    state.setHeader(kind, n, newValue)
  }

  def setHeader(
                 plugin: ExtismCurrentPlugin,
                 vmData: HttpWasmVmData,
                 kind: HeaderKind,
                 name: Int,
                 nameLen: Int,
                 value: Int,
                 valueLen: Int
  ) = {
    val state = vmData.state

    if (nameLen == 0) {
      throw new Error ("HTTP header name cannot be empty")
    }

    val n = this.mustReadString (plugin, "name", name, nameLen)
    val v = this.mustReadString (plugin, "value", value, valueLen)

    state.setHeader (kind, n, Seq(v))
  }

  def removeHeader(vmData: HttpWasmVmData,
                   plugin: ExtismCurrentPlugin,
                   kind: HeaderKind,
                   name: Int,
                   nameLen: Int): Unit = {
    val state = vmData.state

    if (nameLen == 0) {
      throw new Error ("HTTP header name cannot be empty")
    }

    val n = this.mustReadString (plugin, "name", name, nameLen)
    state.removeHeader (kind, n)
  }

  def setStatusCode(vmData: HttpWasmVmData, statusCode: Int): Unit = {
    val state = vmData.state

    state.response.copy(status = statusCode)
  }

  def setUri(vmData: HttpWasmVmData, plugin: ExtismCurrentPlugin, uri: Int, uriLen: Int) = {
    val state = vmData.state

    val u = if (uriLen > 0) {
      this.mustReadString(plugin, "uri", uri, uriLen)
    } else {
      ""
    }

    state.request.copy(url = u)
  }

  def mustReadString(
      plugin: ExtismCurrentPlugin,
      fieldName: String,
      offset: Int,
      byteCount: Int,
  ): String = {
    if (byteCount == 0) {
      return ""
    }

    this.mustRead(plugin, fieldName, offset, byteCount).toString()
  }

  def mustRead(
      plugin: ExtismCurrentPlugin,
      fieldName: String,
      offset: Int,
      byteCount: Int
  ): ByteString = {
    if (byteCount == 0) {
      return ByteString.empty
    }

    val memory: Pointer = plugin.customMemoryGet()

    // TODO - get memory size from RUST
    //    if (
    //    offset >= memory.length ||
    //    offset + byteCount >= this.memoryBuffer.length
    //    ) {
    //    throw new Error (
    //    `out of memory reading ${fieldName}, offset: ${offset}, byteCount: ${byteCount}`,
    //    );
    //  }

    ByteString(memory.share(offset).getByteArray(0, byteCount))
  }
}
