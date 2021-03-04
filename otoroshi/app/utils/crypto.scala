package otoroshi.utils.crypto

import java.nio.charset.StandardCharsets

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import otoroshi.utils.syntax.implicits._

object Signatures {

  def hmac(algo: String, what: String, secret: String): Array[Byte] = {
    val sha256_HMAC = Mac.getInstance(algo)
    val secret_key  = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algo)
    sha256_HMAC.init(secret_key)
    sha256_HMAC.doFinal(what.getBytes(StandardCharsets.UTF_8))
  }

  def hmacSha256SignBytes(what: String, secret: String): Array[Byte] = {
    hmac("HmacSHA256", what, secret)
  }

  def hmacSha384SignBytes(what: String, secret: String): Array[Byte] = {
    hmac("HmacSHA384", what, secret)
  }

  def hmacSha512SignBytes(what: String, secret: String): Array[Byte] = {
    hmac("HmacSHA512", what, secret)
  }

  def hmacSha256Sign(what: String, secret: String): String = {
    org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(hmacSha256SignBytes(what, secret))
  }

  def hmacSha384Sign(what: String, secret: String): String = {
    org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(hmacSha384SignBytes(what, secret))
  }

  def hmacSha512Sign(what: String, secret: String): String = {
    org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(hmacSha512SignBytes(what, secret))
  }
}
