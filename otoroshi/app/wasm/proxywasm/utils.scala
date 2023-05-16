package otoroshi.wasm.proxywasm

import akka.util.ByteString

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object WasmUtils {

  def traceVmHost(message: String): Unit = {
    System.out.println("[vm->host]: " + message)
  }

  def  traceHostVm(message: String) {
    System.out.println("[host->vm]: " + message)
  }

  def DEBUG(functionName: String, str: String) {
    System.out.println("[DEBUG](" + functionName + "): " + str)
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
  def drain(offset: Int, maxSize: Int) = {
    if (offset > buf.length)
      return
    if (maxSize == -1){
      this.buf = buf.slice(offset, buf.length)
    }
    else {
      this.buf = buf.slice(offset, offset + maxSize)
    }
  }
}