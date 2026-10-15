package plugins.configs

import org.apache.pekko.util.ByteString
import otoroshi.next.plugins.*
import otoroshi.next.plugins.api.NgTransformerRequestContext
import otoroshi.utils.TypedMap
import play.api.libs.json.*

class RequestPluginConfigsSpec extends PluginConfigFormatSpec {

  // NgDefaultRequestBodyConfig has no format: the plugin reads it from the context with `from`, which only
  // looks at the config of the context, so everything else is left empty
  private def contextOf(config: JsValue): NgTransformerRequestContext = NgTransformerRequestContext(
    rawRequest = null,
    otoroshiRequest = null,
    snowflake = "",
    route = null,
    apikey = None,
    user = None,
    request = null,
    config = config,
    attrs = TypedMap.empty,
    report = null,
    sequence = null,
    markPluginItem = null
  )

  private def read(config: JsValue): NgDefaultRequestBodyConfig = NgDefaultRequestBodyConfig.from(contextOf(config))

  private val reads: Reads[NgDefaultRequestBodyConfig] = Reads(json => JsSuccess(read(json)))

  private def roundTripJson(config: NgDefaultRequestBodyConfig): NgDefaultRequestBodyConfig = {
    val back = read(config.json)
    withClue(s"${Json.stringify(config.json)}: ") {
      back mustBe config
      back.json mustBe config.json
    }
    back
  }

  "NgDefaultRequestBodyConfig" should {

    val custom = NgDefaultRequestBodyConfig(ByteString("{\"name\":\"otoroshi\"}"), "application/json", Some("identity"))

    "have an empty text body without encoding by default" in {
      NgDefaultRequestBodyConfig.default mustBe NgDefaultRequestBodyConfig(ByteString.empty, "text/plain", None)
    }

    "write the default config" in {
      NgDefaultRequestBodyConfig.default.json mustBe Json.obj(
        "bodyBinary"      -> "",
        "contentType"     -> "text/plain",
        "contentEncoding" -> JsNull
      )
    }

    "write the body in base64 under bodyBinary" in {
      NgDefaultRequestBodyConfig(ByteString("hello"), "text/plain", Some("gzip")).json mustBe Json.obj(
        "bodyBinary"      -> "aGVsbG8=",
        "contentType"     -> "text/plain",
        "contentEncoding" -> "gzip"
      )
    }

    "round trip the default config" in {
      roundTripJson(NgDefaultRequestBodyConfig.default)
    }

    "round trip a config where every field is set" in {
      roundTripJson(custom)
    }

    "round trip a binary body" in {
      val bytes = ByteString(Array[Byte](0, -1, 31, -117, 8, 0, 127, -128))
      roundTripJson(NgDefaultRequestBodyConfig(bytes, "application/octet-stream", Some("gzip"))).body mustBe bytes
    }

    "round trip a body with unicode" in {
      roundTripJson(NgDefaultRequestBodyConfig(ByteString("été 😀"), "text/plain; charset=utf-8", None))
    }

    "round trip a config without content encoding" in {
      roundTripJson(custom.copy(contentEncoding = None)).contentEncoding mustBe None
    }

    "read an empty object as the default config" in {
      read(Json.obj()) mustBe NgDefaultRequestBodyConfig.default
    }

    "read a json null as the default config" in {
      read(JsNull) mustBe NgDefaultRequestBodyConfig.default
    }

    "read a json string as the default config" in {
      read(JsString("hello")) mustBe NgDefaultRequestBodyConfig.default
    }

    "read a json array as the default config" in {
      read(Json.arr(Json.obj("bodyStr" -> "hello"))) mustBe NgDefaultRequestBodyConfig.default
    }

    "read a plain text body from bodyStr" in {
      read(Json.obj("bodyStr" -> "hello")) mustBe NgDefaultRequestBodyConfig(ByteString("hello"), "text/plain", None)
    }

    "prefer bodyStr over bodyBinary" in {
      read(Json.obj("bodyStr" -> "plain", "bodyBinary" -> "YmluYXJ5")).body mustBe ByteString("plain")
    }

    "fall back to bodyBinary when bodyStr is empty" in {
      read(Json.obj("bodyStr" -> "", "bodyBinary" -> "YmluYXJ5")).body mustBe ByteString("binary")
    }

    "rewrite a bodyStr as bodyBinary" in {
      val json = read(Json.obj("bodyStr" -> "hello")).json.as[JsObject]
      json.keys must not contain "bodyStr"
      (json \ "bodyBinary").as[String] mustBe "aGVsbG8="
    }

    "read a lone content type and default the rest" in {
      read(Json.obj("contentType" -> "application/xml")) mustBe NgDefaultRequestBodyConfig(ByteString.empty, "application/xml", None)
    }

    "read a lone content encoding" in {
      read(Json.obj("contentEncoding" -> "br")).contentEncoding mustBe Some("br")
    }

    "ignore a body that is not a string" in {
      read(Json.obj("bodyStr" -> 42, "bodyBinary" -> Json.arr(1, 2))).body mustBe ByteString.empty
    }

    "ignore a content type and encoding that are not strings" in {
      read(Json.obj("contentType" -> 1, "contentEncoding" -> true)) mustBe NgDefaultRequestBodyConfig.default
    }

    "fail on a bodyBinary that is not base64" in {
      an[IllegalArgumentException] must be thrownBy read(Json.obj("bodyBinary" -> "not base64 !"))
    }

    "not read the snake case keys" in {
      read(
        Json.obj("body_str" -> "hello", "content_type" -> "application/json", "content_encoding" -> "gzip")
      ) mustBe NgDefaultRequestBodyConfig.default
    }

    "be the default config of NgDefaultRequestBody" in {
      defaultConfigReadsBack(new NgDefaultRequestBody(), reads) mustBe NgDefaultRequestBodyConfig.default
    }
  }
}
