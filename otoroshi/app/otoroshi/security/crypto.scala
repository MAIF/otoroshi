package otoroshi.security

import com.google.common.base.Charsets

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object ClaimCrypto {}

case class ClaimCrypto(sharedKey: String) {

  private lazy val encoder = Base64.getUrlEncoder
  private lazy val key     = new SecretKeySpec(sharedKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512")

  private lazy val mac = {
    val a = Mac.getInstance("HmacSHA512")
    a.init(key)
    a
  }

  def signString(in: String): String = new String(encoder.encode(sign(in.getBytes(StandardCharsets.UTF_8))), StandardCharsets.UTF_8)

  def sign(in: Array[Byte]): Array[Byte] = mac.synchronized { mac.doFinal(in) }

  def verifyString(expected: String, in: String): Boolean = signString(in).equals(expected)
}
