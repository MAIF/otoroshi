package otoroshi.wasm.proxywasm

import akka.util.ByteString
import play.api.Logger

object WasmUtils {

  val logger = Logger("otoroshi-proxy-wasm-utils")

  def traceVmHost(message: String): Unit = {
//    println("[vm->host]: " + message)
    if (logger.isTraceEnabled) logger.trace("[vm->host]: " + message)
  }

  def traceHostVm(message: String) {
//    println("[host->vm]: " + message)
    if (logger.isTraceEnabled) logger.trace("[host->vm]: " + message)
  }

  def DEBUG(functionName: String, str: String) {
    if (logger.isDebugEnabled) logger.debug("[DEBUG](" + functionName + "): " + str)
  }
}

class IoBuffer(var buf: ByteString) {

  def this(buf: String) {
    this(ByteString(buf))
  }

  def length: Int = buf.length

  def write(p: ByteString): Int = {
    buf.concat(p)
    p.length
  }

  // Drain drains a offset length of bytes in buffer.
  // It can be used with Bytes(), after consuming a fixed-length of data
  def drain(offset: Int, maxSize: Int): Unit = {
    if (offset > buf.length)
      return ()
    if (maxSize == -1) {
      this.buf = buf.slice(offset, buf.length)
    } else {
      this.buf = buf.slice(offset, offset + maxSize)
    }
  }
}
