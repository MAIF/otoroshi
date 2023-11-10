package io.otoroshi.common.wasm.scaladsl

import com.auth0.jwt.algorithms.Algorithm
import play.api.libs.json.{JsObject, Json}

import java.nio.charset.StandardCharsets

object ApikeyHelper {

  def generate(settings: WasmManagerSettings): String = {
      val header = Json.obj(
        "typ" -> "JWT",
        "alg" -> "HS512"
      )
      val payload = Json.obj(
        "iss" -> "otoroshi"
      )
      sign(header, payload, settings.tokenSecret.getOrElse(""))
    }

  private def sign(headerJson: JsObject, payloadJson: JsObject, tokenSecret: String): String = {
    val header: String = org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(Json.toBytes(headerJson))
    val payload: String = org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(Json.toBytes(payloadJson))
    val signatureBytes: Array[Byte] =
      Algorithm.HMAC512(tokenSecret).sign(header.getBytes(StandardCharsets.UTF_8), payload.getBytes(StandardCharsets.UTF_8))

    val signature: String = org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(signatureBytes)
    String.format("%s.%s.%s", header, payload, signature)
  }
}
