package otoroshi.utils.crypto

import java.nio.charset.StandardCharsets

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Signatures {

  def hmacSha256SignBytes(what: String, secret: String): Array[Byte] = {
    val sha256_HMAC = Mac.getInstance("HmacSHA256")
    val secret_key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")
    sha256_HMAC.init(secret_key)
    sha256_HMAC.doFinal(what.getBytes(StandardCharsets.UTF_8))
  }

  def hmacSha256Sign(what: String, secret: String): String = {
    org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(hmacSha256SignBytes(what, secret))
  }
}
