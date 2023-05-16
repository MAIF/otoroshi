package otoroshi.wasm.proxywasm

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

class IoBuffer(var buf: Array[Byte]) {

  def this(buf: String) {
    this(buf.getBytes(StandardCharsets.UTF_8))
  }

  // Len returns the number of bytes of the unread portion of the buffer;
  // b.Len() == len(b.Bytes()).
  def length: Int = buf.length

  // Bytes returns all bytes from buffer, without draining any buffered data.
  // It can be used to get fixed-length content, such as headers, body.
  // Note: do not change content in return bytes, use write instead
  def bytes: Array[Byte] = buf

  // Write appends the contents of p to the buffer, growing the buffer as
  // needed. The return value n is the length of p; err is always nil. If the
  // buffer becomes too large, Write will panic with ErrTooLarge.
  def write(p: Array[Byte]): Int = { // (n int, err error)
    val outputStream = new ByteArrayOutputStream()
    outputStream.write(buf)
    outputStream.write(p)
    buf = outputStream.toByteArray
    p.length
  }

  // Drain drains a offset length of bytes in buffer.
  // It can be used with Bytes(), after consuming a fixed-length of data
  def drain(offset: Int, maxSize: Int): Unit = {
    if (offset > buf.length) return
    if (maxSize == -1) buf = java.util.Arrays.copyOfRange(buf, offset, buf.length)
    else buf = java.util.Arrays.copyOfRange(buf, offset, offset + maxSize)
  }
}