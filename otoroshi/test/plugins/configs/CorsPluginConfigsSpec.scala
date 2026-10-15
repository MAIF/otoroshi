package plugins.configs

import otoroshi.models.CorsSettings
import otoroshi.next.plugins.*
import play.api.libs.json.*

import scala.concurrent.duration.*

class CorsPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgCorsSettings" should {

    val format = NgCorsSettings.format

    val full = NgCorsSettings(
      allowOrigin = "https://app.oto.tools",
      exposeHeaders = Seq("x-request-id", "x-total-count"),
      allowHeaders = Seq("authorization", "content-type"),
      allowMethods = Seq("GET", "POST", "DELETE"),
      excludedPatterns = Seq("/health", "/static/.*"),
      maxAge = Some(1.hour),
      allowCredentials = false
    )

    "allow any origin with credentials and nothing else by default" in {
      val config = NgCorsSettings()
      config.allowOrigin mustBe "*"
      config.exposeHeaders mustBe empty
      config.allowHeaders mustBe empty
      config.allowMethods mustBe empty
      config.excludedPatterns mustBe empty
      config.maxAge mustBe None
      config.allowCredentials mustBe true
    }

    "write the default config" in {
      format.writes(NgCorsSettings()) mustBe Json.obj(
        "allow_origin"      -> "*",
        "expose_headers"    -> Json.arr(),
        "allow_headers"     -> Json.arr(),
        "allow_methods"     -> Json.arr(),
        "excluded_patterns" -> Json.arr(),
        "max_age"           -> JsNull,
        "allow_credentials" -> true
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "allow_origin"      -> "https://app.oto.tools",
        "expose_headers"    -> Json.arr("x-request-id", "x-total-count"),
        "allow_headers"     -> Json.arr("authorization", "content-type"),
        "allow_methods"     -> Json.arr("GET", "POST", "DELETE"),
        "excluded_patterns" -> Json.arr("/health", "/static/.*"),
        "max_age"           -> 3600,
        "allow_credentials" -> false
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgCorsSettings())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a config without credentials only" in {
      roundTrip(format, NgCorsSettings(allowCredentials = false)).allowCredentials mustBe false
    }

    "round trip a zero max age" in {
      roundTrip(format, NgCorsSettings(maxAge = Some(0.seconds))).maxAge mustBe Some(0.seconds)
    }

    "round trip a max age expressed in minutes" in {
      roundTrip(format, NgCorsSettings(maxAge = Some(2.minutes))).maxAge mustBe Some(120.seconds)
    }

    "write the max age in seconds" in {
      written(format, NgCorsSettings(maxAge = Some(1.day))) \ "max_age" mustBe JsDefined(JsNumber(86400))
    }

    "read the max age as seconds" in {
      readOk(format, Json.obj("max_age" -> 600)).maxAge mustBe Some(10.minutes)
    }

    "truncate a max age that is not a whole number of seconds" in {
      val back = readOk(format, format.writes(NgCorsSettings(maxAge = Some(1500.millis))))
      back.maxAge mustBe Some(1.second)
    }

    "write the exposed headers in lower case and trimmed" in {
      written(format, NgCorsSettings(exposeHeaders = Seq("X-Request-Id", " X-Total "))) \ "expose_headers" mustBe JsDefined(
        Json.arr("x-request-id", "x-total")
      )
    }

    "write the allowed headers in lower case and trimmed" in {
      written(format, NgCorsSettings(allowHeaders = Seq("Authorization", "Content-Type "))) \ "allow_headers" mustBe JsDefined(
        Json.arr("authorization", "content-type")
      )
    }

    "rewrite mixed case headers in lower case on a round trip" in {
      val back = readOk(format, format.writes(NgCorsSettings(exposeHeaders = Seq("X-Foo"), allowHeaders = Seq("X-Bar"))))
      back.exposeHeaders mustBe Seq("x-foo")
      back.allowHeaders mustBe Seq("x-bar")
    }

    "read mixed case headers as they are" in {
      val back = readOk(format, Json.obj("expose_headers" -> Json.arr("X-Foo"), "allow_headers" -> Json.arr("X-Bar")))
      back.exposeHeaders mustBe Seq("X-Foo")
      back.allowHeaders mustBe Seq("X-Bar")
    }

    "keep the case of the allowed methods" in {
      roundTrip(format, NgCorsSettings(allowMethods = Seq("get", "POST", "Patch"))).allowMethods mustBe Seq(
        "get",
        "POST",
        "Patch"
      )
    }

    "keep the excluded patterns as they are" in {
      roundTrip(format, NgCorsSettings(excludedPatterns = Seq("^/API/.*$", " /spaced "))).excludedPatterns mustBe Seq(
        "^/API/.*$",
        " /spaced "
      )
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgCorsSettings()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgCorsSettings()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("*")) mustBe NgCorsSettings()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("*")) mustBe NgCorsSettings()
    }

    "read the allowed origin alone" in {
      readOk(format, Json.obj("allow_origin" -> "https://foo.oto.tools")) mustBe NgCorsSettings(
        allowOrigin = "https://foo.oto.tools"
      )
    }

    "read the allowed methods alone" in {
      readOk(format, Json.obj("allow_methods" -> Json.arr("PUT"))) mustBe NgCorsSettings(allowMethods = Seq("PUT"))
    }

    "read the credentials flag alone" in {
      readOk(format, Json.obj("allow_credentials" -> false)) mustBe NgCorsSettings(allowCredentials = false)
    }

    "read a null max age as no max age" in {
      readOk(format, Json.obj("max_age" -> JsNull)).maxAge mustBe None
    }

    "ignore a max age written as a string" in {
      readOk(format, Json.obj("max_age" -> "60")).maxAge mustBe None
    }

    "ignore a max age that is not a whole number" in {
      readOk(format, Json.obj("max_age" -> 1.5)).maxAge mustBe None
    }

    "ignore an allowed origin that is not a string" in {
      readOk(format, Json.obj("allow_origin" -> Json.arr("https://a.oto.tools"))).allowOrigin mustBe "*"
    }

    "ignore a header list written as a string" in {
      readOk(format, Json.obj("expose_headers" -> "x-foo, x-bar")).exposeHeaders mustBe empty
    }

    "ignore a whole list as soon as one item is not a string" in {
      readOk(format, Json.obj("allow_methods" -> Json.arr("GET", 1))).allowMethods mustBe empty
    }

    "ignore a credentials flag written as a string" in {
      readOk(format, Json.obj("allow_credentials" -> "false")).allowCredentials mustBe true
    }

    "not read the camel case keys" in {
      readOk(
        format,
        Json.obj("allowOrigin" -> "https://foo.oto.tools", "allowCredentials" -> false, "maxAge" -> 10)
      ) mustBe NgCorsSettings()
    }

    "build enabled legacy settings with the same values" in {
      full.legacy mustBe CorsSettings(
        enabled = true,
        allowOrigin = "https://app.oto.tools",
        exposeHeaders = Seq("x-request-id", "x-total-count"),
        allowHeaders = Seq("authorization", "content-type"),
        allowMethods = Seq("GET", "POST", "DELETE"),
        excludedPatterns = Seq("/health", "/static/.*"),
        maxAge = Some(1.hour),
        allowCredentials = false
      )
    }

    "be built from legacy settings, whatever their enabled flag" in {
      NgCorsSettings.fromLegacy(full.legacy.copy(enabled = false)) mustBe full
    }

    "not apply cors on the excluded paths of its legacy settings" in {
      full.legacy.shouldApplyCors("/static/app.js") mustBe false
      full.legacy.shouldApplyCors("/health") mustBe false
      full.legacy.shouldApplyCors("/api/users") mustBe true
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
      jsonMatchesFormat(format, NgCorsSettings(exposeHeaders = Seq("X-Upper")))
    }

    "be the default config of Cors" in {
      defaultConfigReadsBack(new Cors(), format) mustBe NgCorsSettings()
    }
  }
}
