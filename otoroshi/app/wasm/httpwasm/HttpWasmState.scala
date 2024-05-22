package otoroshi.wasm.httpwasm

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.sun.jna.Pointer
import org.extism.sdk.ExtismCurrentPlugin
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm.httpwasm.api.{LogLevel, _}
import play.api.Logger

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class HttpWasmState(env: Env) {

  val logger = Logger("otoroshi-proxy-wasm")

  val u32Len = 4

  def unimplementedFunction[A](name: String): A = {
    logger.error(s"unimplemented state function: '${name}'")
    throw new NotImplementedError(s"proxy state method '${name}' is not implemented")
  }

  def enableFeatures(vmData: HttpWasmVmData, features: Int): Int = {
    vmData.features = Features(features)
    features
  }

  def getConfig(plugin: ExtismCurrentPlugin, vmData: HttpWasmVmData, buf: Int, bufLimit: Int) = {
    writeIfUnderLimit(plugin, buf, bufLimit, ByteString(vmData.config.stringify))
  }

  def writeIfUnderLimit(plugin: ExtismCurrentPlugin, offset: Int, limit: Int, v: ByteString): Int = {
    val vLen = v.length
    if (vLen > limit || vLen == 0) {
      return vLen
    }


    println("vLen", vLen)
    val memory: Pointer = plugin.customMemoryGet()
    println("memory", memory)
    memory.write(offset, v.toArray, 0, vLen)

    println("return vLen", vLen)
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
    }
    this.writeStringIfUnderLimit (plugin, buf, bufLimit, httpVersion)
  }

  def getStatusCode(vmData: HttpWasmVmData): Int = vmData.requestStatusCode

  def getUri(plugin: ExtismCurrentPlugin, vmData: HttpWasmVmData, buf: Int, bufLimit: Int): Int = {
    println("get_uri")
    val uri = vmData.request.uri.toString()
    this.writeStringIfUnderLimit (plugin, buf, bufLimit, if (uri.isEmpty) "/" else uri)
  }

  def log(plugin: ExtismCurrentPlugin, level: LogLevel, buf: Int, bufLimit: Int) = {
    println("calling log function", level, buf, bufLimit)
    val s = mustReadString(plugin, "log", buf, bufLimit)

    level match {
      case LogLevel.LogLevelDebug => logger.debug(s)
      case LogLevel.LogLevelInfo => logger.info(s)
      case LogLevel.LogLevelWarn => logger.warn(s)
      case LogLevel.LogLevelError => logger.error(s)
    }
  }

  def logEnabled(level: LogLevel): Int = {
    println("calling log_enabled", level)
    if (level != LogLevel.LogLevelDebug) {
      return 1
    }
    0
  }

  def readBody(plugin: ExtismCurrentPlugin, vmData: HttpWasmVmData, kind: BodyKind, buf: Int, bufLimit: Int): BigInt = {
    val memory = plugin.customMemoryGet()

    if (kind == BodyKind.BodyKindRequest) {
      val body: ByteString = Await.result(vmData.request.body.runFold(ByteString.empty)(_ ++ _)(env.otoroshiMaterializer), 10.seconds)
      val start = vmData.requestBodyReadIndex
      val end = Math.min (start + bufLimit, body.length)
      val slice = body.slice(start, end)

      memory.write(buf, slice.toArray, 0, slice.length)
      vmData.requestBodyReadIndex = end
      if (end == body.length) {
        return (1 << 32) | BigInt (slice.length)
      }
      return BigInt (slice.length);
    }

    if (kind != BodyKind.BodyKindResponse) {
      throw new RuntimeException(s"Unknown body kind $kind")
    }

    val body = Await.result(vmData.response.body.runFold(ByteString.empty)(_ ++ _)(env.otoroshiMaterializer), 10.seconds)

//    if (buffer.isEmpty) {
//      throw new Error(s"Response body not buffered")
//    }

    val start = vmData.responseBodyReadIndex
    val end = Math.min(start + bufLimit, body.length)
    val slice = body.slice(start, end)
    memory.write(buf, slice.toArray, 0, slice.length)

    vmData.responseBodyReadIndex = end
    if (end == body.length) {
      return (1 << 32) | BigInt (slice.length)
    }
    BigInt (slice.length)
  }

  def setMethod(plugin: ExtismCurrentPlugin, vmData: HttpWasmVmData, name: Int, nameLen: Int) {
    val method = this.mustReadString(plugin, "method", name, nameLen)
    vmData.request.copy(method = method)
  }

  def writeBody(plugin: ExtismCurrentPlugin, vmData: HttpWasmVmData, kind: BodyKind, body: Int, bodyLen: Int) = {
    var b: ByteString = ByteString.empty

    if (bodyLen == 0) {
      b = ByteString.empty
    } else {
      b = this.mustRead(plugin, "body", body, bodyLen)
    }

    kind match {
      case BodyKind.BodyKindRequest =>
        if (vmData.requestBodyReplaced) {
          vmData.setRequest(vmData.request.copy(body = vmData.request.body.concat(Source.single(b))))
        } else {
          vmData.setRequest(vmData.request.copy(body = Source.single(b)))
        }
      case BodyKind.BodyKindResponse =>
        if (!vmData.nextCalled) {
          vmData.setResponse(vmData.response.copy(body = Source.single(b)))
        } else if (vmData.responseBodyReplaced) {
          vmData.setResponse(vmData.response.copy(body = vmData.response.body.concat(Source.single(b))))
        } else {
          vmData.setResponse(vmData.response.copy(body = Source.single(b)))
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
    if (nameLen == 0) {
      throw new RuntimeException("HTTP header name cannot be empty")
    }

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

    val n = this.mustReadString (plugin, "name", name, nameLen)
    vmData.removeHeader (kind, n)
  }

  def setStatusCode(vmData: HttpWasmVmData, statusCode: Int): Unit = {
    vmData.setResponse(vmData.response.copy(status = statusCode))
  }

  def setUri(plugin: ExtismCurrentPlugin, vmData: HttpWasmVmData, uri: Int, uriLen: Int) = {
    println("set_uri")
    val u = if (uriLen > 0) {
      this.mustReadString(plugin, "uri", uri, uriLen)
    } else {
      ""
    }
    
    vmData.setRequest(vmData.request.copy(url = u))
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
