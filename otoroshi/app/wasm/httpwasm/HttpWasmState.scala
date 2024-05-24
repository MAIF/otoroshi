package otoroshi.wasm.httpwasm

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.sun.jna.Pointer
import org.extism.sdk.{ExtismCurrentPlugin, HostFunction}
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm.httpwasm.api.{BodyKind, LogLevel, _}
import play.api.Logger

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.DurationInt

class HttpWasmState(env: Env) {

  val logger = Logger("otoroshi-http-wasm")

  val u32Len = 4

  def unimplementedFunction[A](name: String): A = {
    logger.error(s"unimplemented state function: '${name}'")
    throw new NotImplementedError(s"proxy state method '${name}' is not implemented")
  }

  def enableFeatures(vmData: HttpWasmVmData, features: Int)(implicit mat: Materializer, ec: ExecutionContext): Int = {
    vmData.features = vmData.features.withEnabled(features)

    if (vmData.features.isEnabled(Feature.FeatureBufferRequest)) {
      vmData.request.body.runFold(ByteString.empty)(_ ++ _).map { b =>
        vmData.bufferedRequestBody = b.some
      }
    }

    if (vmData.features.isEnabled(Feature.FeatureBufferResponse)) {
      vmData.response.body.runFold(ByteString.empty)(_ ++ _).map { b =>
        vmData.bufferedResponseBody = b.some
      }
    }

    vmData.features.f
  }

  def getConfig(plugin: ExtismCurrentPlugin, vmData: HttpWasmVmData, buf: Int, bufLimit: Int) = {
    writeIfUnderLimit(plugin, buf, bufLimit, ByteString(vmData.config.stringify))
  }

  private def writeIfUnderLimit(plugin: ExtismCurrentPlugin, offset: Int, limit: Int, v: ByteString): Int = {
    val vLen = v.length
    if (vLen > limit || vLen == 0) {
      return vLen
    }

    val memory: Pointer = plugin.customMemoryGet()
    memory.write(offset, v.toArray, 0, vLen)

    vLen
  }

  private def writeNullTerminated(plugin: ExtismCurrentPlugin, buf: Int, bufLimit: Int, input: Seq[String]): BigInt = {
    val count = input.length
    if (count == 0) {
      return 0
    }

    val encodedInput = input.map(i => ByteString(i))
    val byteCount = encodedInput.foldLeft(0) { case (acc, i) => acc + i.length }

    val countLen = (count << 32) | BigInt(byteCount)

    if (byteCount > bufLimit) {
      return countLen
    }

    var offset = 0

    val memory: Pointer = plugin.customMemoryGet()

    encodedInput.foreach(s => {
      val sLen = s.length
      memory.write(buf + offset, s.toArray, 0, sLen)
      offset += sLen
      memory.setInt(buf + offset, 0)
      offset += 1
    })

    countLen
  }

  private def writeStringIfUnderLimit(
                               plugin: ExtismCurrentPlugin,
                               offset: Int,
                               limit: Int,
                               v: String
  ): Int = this.writeIfUnderLimit(plugin, offset, limit, ByteString(v))

  def getHeaderNames(
      plugin: ExtismCurrentPlugin,
      vmData: HttpWasmVmData,
      kind: HeaderKind,
      buf: Int,
      bufLimit: Int,
  ): BigInt = {
    val headers = vmData.headers(kind)

    val headerNames = headers.keys.toSeq
    this.writeNullTerminated (plugin, buf, bufLimit, headerNames)
  }

  def getHeaderValues(
      plugin: ExtismCurrentPlugin,
      vmData: HttpWasmVmData,
      kind: HeaderKind,
      name: Int,
      nameLen: Int,
      buf: Int,
      bufLimit: Int
  ): BigInt = {

    if (nameLen == 0) {
      throw new RuntimeException("HTTP header name cannot be empty")
    }

    val headers = vmData.headers(kind)

    val n = this.mustReadString(plugin, "name", name, nameLen).toLowerCase()
    val value = headers.get(n)
    val values: Seq[String] = value.map(value => value.split("; ").toSeq).getOrElse(Seq.empty)

    this.writeNullTerminated(plugin, buf, bufLimit, values)
  }

  def getMethod(plugin: ExtismCurrentPlugin, vmData: HttpWasmVmData, buf: Int, bufLimit: Int): Int = {
    writeStringIfUnderLimit (plugin, buf, bufLimit, vmData.request.method)
  }

  def getProtocolVersion(plugin: ExtismCurrentPlugin, vmData: HttpWasmVmData, buf: Int, bufLimit: Int): Int = {
    var httpVersion = vmData.request.version
    httpVersion match {
      case "1.0" => httpVersion = "HTTP/1.0"
      case "1.1" => httpVersion = "HTTP/1.1"
      case "2" => httpVersion = "HTTP/2.0"
      case "2.0" => httpVersion = "HTTP/2.0"
      case _ => httpVersion = httpVersion
    }

    this.writeStringIfUnderLimit (plugin, buf, bufLimit, httpVersion)
  }

  def getSourceAddr(plugin: ExtismCurrentPlugin, vmData: HttpWasmVmData, buf: Int, bufLimit: Int): Int = {
    this.writeStringIfUnderLimit (plugin, buf, bufLimit, vmData.remoteAddress.get)
  }

  def getStatusCode(vmData: HttpWasmVmData): Int = vmData.requestStatusCode

  def getUri(plugin: ExtismCurrentPlugin, vmData: HttpWasmVmData, buf: Int, bufLimit: Int): Int = {
    val uri = vmData.request.relativeUri
    this.writeStringIfUnderLimit (plugin, buf, bufLimit, if (uri.isEmpty) "/" else uri)
  }

  def log(plugin: ExtismCurrentPlugin, level: LogLevel, buf: Int, bufLimit: Int) = {
    val s = mustReadString(plugin, "log", buf, bufLimit)

    level match {
      case LogLevel.LogLevelDebug => logger.debug(s)
      case LogLevel.LogLevelInfo => logger.info(s)
      case LogLevel.LogLevelWarn => logger.warn(s)
      case LogLevel.LogLevelError => logger.error(s)
      case _ => throw new Exception("invalid log level")
    }
  }

  def logEnabled(level: LogLevel): Int = {
    if (level != LogLevel.LogLevelDebug) {
      return 1
    }
    0
  }

  private def mustHeaderMutable(vmData: HttpWasmVmData, op: String, kind: HeaderKind) {
    kind match {
      case HeaderKind.HeaderKindRequest => mustBeforeNext(vmData, op, "request header")
      case HeaderKind.HeaderKindResponse => mustBeforeNextOrFeature(vmData, Feature.FeatureBufferResponse, op, "response header")
      case HeaderKind.HeaderKindRequestTrailers => mustBeforeNext(vmData, op, "request trailer")
      case HeaderKind.HeaderKindResponseTrailers => mustBeforeNextOrFeature(vmData, Feature.FeatureBufferResponse, op, "response trailer")
    }
  }

  private def mustBeforeNext(vmData: HttpWasmVmData, op: String, kind: String) {
	if (vmData.afterNext) {
        throw new RuntimeException(s"can't $op $kind after next handler")
	}
  }

  private def mustBeforeNextOrFeature(vmData: HttpWasmVmData, feature: Feature, op: String, kind: String): Unit = {
	if (!vmData.afterNext) {
		// Assume this is serving a response from the guest.
	} else if (vmData.features.isEnabled(feature)) {
		// Assume the guest is overwriting the response from next.
	} else {
		throw new RuntimeException(s"can't $op $kind after next handler unless " +
          s"${Feature.toString(feature)} is enabled")
	}
  }

  private def _readBody(plugin: ExtismCurrentPlugin, vmData: HttpWasmVmData, buf: Int, bufLimit: Int, body: ByteString, kind: BodyKind): BigInt = {
	// buf_limit 0 serves no purpose as implementations won't return EOF on it.
    if (bufLimit == 0) {
      throw new RuntimeException("buf_limit==0 reading body")
    }

    val memory = plugin.customMemoryGet()

	val start = kind match {
      case BodyKind.BodyKindRequest => vmData.requestBodyReadIndex
      case BodyKind.BodyKindResponse => vmData.requestBodyReadIndex
      case _ => throw new Exception("invalid body kind")
    }
    val end = Math.min (start + bufLimit, body.length)
    val slice = body.slice(start, end)

    memory.write(buf, slice.toArray, 0, slice.length)
    kind match {
      case BodyKind.BodyKindRequest =>
            vmData.requestBodyReadIndex = end
      case BodyKind.BodyKindResponse =>
            vmData.responseBodyReadIndex = end
    }

    if (end == body.length) {
      return (1 << 32) | BigInt (slice.length)
    }
    BigInt (slice.length)
}


  def readBody(plugin: ExtismCurrentPlugin, vmData: HttpWasmVmData, kind: BodyKind, buf: Int, bufLimit: Int): BigInt = {
    val body = kind match {
      case BodyKind.BodyKindRequest =>
        mustBeforeNextOrFeature(vmData, Feature.FeatureBufferRequest, "read", BodyKind.toString(BodyKind.BodyKindRequest))

        if (vmData.bufferedRequestBody.isEmpty) {
          vmData.bufferedRequestBody = Some(Await.result(vmData.request.body.runFold(ByteString.empty)(_ ++ _)
          (env.otoroshiMaterializer), 10.seconds))
        }

        vmData.bufferedRequestBody.get

      case BodyKind.BodyKindResponse =>
        mustBeforeNextOrFeature(vmData, Feature.FeatureBufferResponse, "read", BodyKind.toString(BodyKind.BodyKindResponse))


        if (vmData.bufferedResponseBody.isEmpty) {
          vmData.bufferedResponseBody = Some(Await.result(vmData.response.body.runFold(ByteString.empty)(_ ++ _)
          (env.otoroshiMaterializer), 10.seconds))
        }

        vmData.bufferedResponseBody.get
    }

    _readBody(plugin, vmData, buf, bufLimit, body, kind)
  }

  def setMethod(plugin: ExtismCurrentPlugin, vmData: HttpWasmVmData, method: Int, methodLen: Int) {
    mustBeforeNext(vmData, "set", "method")

    if (methodLen == 0) {
		throw new RuntimeException("HTTP method cannot be empty")
	}

    val readMethod = this.mustReadString(plugin, "method", method, methodLen)

    vmData.setMethod(readMethod)
  }

  def writeBody(plugin: ExtismCurrentPlugin, vmData: HttpWasmVmData, kind: BodyKind, body: Int, bodyLen: Int) = {
    var b: ByteString = ByteString.empty

    if (bodyLen == 0) {
      b = ByteString.empty
    } else {
      b = this.mustRead(plugin, "body", body, bodyLen)
    }

    vmData.setBody(Source.single(b), kind)
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
    if (nameLen == 0) {
      throw new RuntimeException("HTTP header name cannot be empty")
    }

	mustHeaderMutable(vmData, "add", kind)

    val n = this.mustReadString (plugin, "name", name, nameLen)
    val v = this.mustReadString (plugin, "value", value, valueLen)

    val headers = vmData.headers(kind)
    val existing = headers.get(n)
    val newValue = existing.map(existing => Seq(existing, v)).getOrElse(Seq(v))

    vmData.setHeader(kind, n, newValue)
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
    if (nameLen == 0) {
      throw new RuntimeException("HTTP header name cannot be empty")
    }

    mustHeaderMutable(vmData, "set", kind)

    val n = this.mustReadString (plugin, "name", name, nameLen)
    val v = this.mustReadString (plugin, "value", value, valueLen)

    vmData.setHeader (kind, n, Seq(v))
  }

  def removeHeader(
                    plugin: ExtismCurrentPlugin,
                    vmData: HttpWasmVmData,
                    kind: HeaderKind,
                    name: Int,
                    nameLen: Int): Unit = {
    if (nameLen == 0) {
      throw new RuntimeException ("HTTP header name cannot be empty")
    }

    mustHeaderMutable(vmData, "remove", kind)

    val n = this.mustReadString (plugin, "name", name, nameLen)
    vmData.removeHeader (kind, n)
  }

  def setStatusCode(vmData: HttpWasmVmData, statusCode: Int): Unit = {
    vmData.setResponse(vmData.response.copy(status = statusCode))
  }

  def setUri(plugin: ExtismCurrentPlugin, vmData: HttpWasmVmData, uri: Int, uriLen: Int): Unit = {

    mustBeforeNext(vmData, "set", "uri")

    val u = if (uriLen > 0) {
      this.mustReadString(plugin, "uri", uri, uriLen)
    } else {
      ""
    }

    vmData.setUri(u)
  }

  private def mustReadString(
      plugin: ExtismCurrentPlugin,
      fieldName: String,
      offset: Int,
      byteCount: Int,
  ): String = {
    if (byteCount == 0) {
      return ""
    }

    this.mustRead(plugin, fieldName, offset, byteCount).utf8String
  }

  private def mustRead(
      plugin: ExtismCurrentPlugin,
      fieldName: String,
      offset: Int,
      byteCount: Int
  ): ByteString = {
    if (byteCount == 0) {
      return ByteString.empty
    }

    val memory: Pointer = plugin.customMemoryGet()
    ByteString(memory.share(offset).getByteArray(0, byteCount))
  }
}
