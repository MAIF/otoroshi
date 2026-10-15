package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

import scala.concurrent.duration.*

class ExternalPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgExternalValidatorConfig" should {

    val format = NgExternalValidatorConfig.format

    val full = NgExternalValidatorConfig(
      cacheExpression = Some("${req.headers.authorization}"),
      ttl = 5.minutes,
      timeout = 2500.millis,
      url = Some("https://validator.oto.tools/check"),
      headers = Map("Authorization" -> "Bearer ${config.token}", "X-Foo" -> "bar"),
      errorMessage = "you shall not pass",
      errorStatus = 401
    )

    "have no url, no cache and a forbidden error by default" in {
      val default = NgExternalValidatorConfig()
      default.cacheExpression mustBe None
      default.url mustBe None
      default.ttl mustBe 60.seconds
      default.timeout mustBe 30.seconds
      default.headers mustBe empty
      default.errorMessage mustBe "forbidden"
      default.errorStatus mustBe 403
    }

    "write the default config" in {
      format.writes(NgExternalValidatorConfig()) mustBe Json.obj(
        "cache_expression" -> JsNull,
        "url"              -> JsNull,
        "ttl"              -> 60000,
        "timeout"          -> 30000,
        "headers"          -> Json.obj(),
        "error_message"    -> "forbidden",
        "error_status"     -> 403
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "cache_expression" -> "${req.headers.authorization}",
        "url"              -> "https://validator.oto.tools/check",
        "ttl"              -> 300000,
        "timeout"          -> 2500,
        "headers"          -> Json.obj("Authorization" -> "Bearer ${config.token}", "X-Foo" -> "bar"),
        "error_message"    -> "you shall not pass",
        "error_status"     -> 401
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgExternalValidatorConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a url without cache expression" in {
      roundTrip(format, NgExternalValidatorConfig(url = Some("http://localhost:9000/validate")))
    }

    "round trip a cache expression without url" in {
      roundTrip(format, NgExternalValidatorConfig(cacheExpression = Some("${req.ip}")))
    }

    "round trip durations below the second in milliseconds" in {
      val back = roundTrip(format, NgExternalValidatorConfig(ttl = 1500.millis, timeout = 1.milli))
      back.ttl.toMillis mustBe 1500L
      back.timeout.toMillis mustBe 1L
    }

    "round trip zero durations" in {
      roundTrip(format, NgExternalValidatorConfig(ttl = Duration.Zero, timeout = Duration.Zero))
    }

    "truncate durations below the millisecond" in {
      val json = format.writes(NgExternalValidatorConfig(ttl = 1500.micros))
      (json \ "ttl").as[Long] mustBe 1L
      readOk(format, json).ttl mustBe 1.milli
    }

    "round trip a non http error status as is" in {
      roundTrip(format, NgExternalValidatorConfig(errorStatus = 999)).errorStatus mustBe 999
    }

    "round trip an empty error message" in {
      roundTrip(format, NgExternalValidatorConfig(errorMessage = "")).errorMessage mustBe ""
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgExternalValidatorConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgExternalValidatorConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("https://validator.oto.tools")) mustBe NgExternalValidatorConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("https://validator.oto.tools")) mustBe NgExternalValidatorConfig()
    }

    "read a url alone" in {
      readOk(format, Json.obj("url" -> "https://validator.oto.tools")) mustBe NgExternalValidatorConfig(
        url = Some("https://validator.oto.tools")
      )
    }

    "read a ttl alone, in milliseconds" in {
      readOk(format, Json.obj("ttl" -> 10000)) mustBe NgExternalValidatorConfig(ttl = 10.seconds)
    }

    "read a timeout alone, in milliseconds" in {
      readOk(format, Json.obj("timeout" -> 500)) mustBe NgExternalValidatorConfig(timeout = 500.millis)
    }

    "read an error status and message alone" in {
      readOk(format, Json.obj("error_status" -> 451, "error_message" -> "nope")) mustBe NgExternalValidatorConfig(
        errorStatus = 451,
        errorMessage = "nope"
      )
    }

    "read the headers alone" in {
      readOk(format, Json.obj("headers" -> Json.obj("X-Foo" -> "bar"))) mustBe NgExternalValidatorConfig(
        headers = Map("X-Foo" -> "bar")
      )
    }

    "read a blank url as no url" in {
      readOk(format, Json.obj("url" -> "   ")).url mustBe None
      readOk(format, Json.obj("url" -> "")).url mustBe None
    }

    "read a blank cache expression as no cache expression" in {
      readOk(format, Json.obj("cache_expression" -> " ")).cacheExpression mustBe None
    }

    "read null url and cache expression as none" in {
      val back = readOk(format, Json.obj("url" -> JsNull, "cache_expression" -> JsNull))
      back.url mustBe None
      back.cacheExpression mustBe None
    }

    "ignore a url that is not a string" in {
      readOk(format, Json.obj("url" -> 42)).url mustBe None
    }

    "ignore a ttl written as a string" in {
      readOk(format, Json.obj("ttl" -> "10000")).ttl mustBe 60.seconds
    }

    "ignore a timeout that is not a whole number" in {
      readOk(format, Json.obj("timeout" -> 10.5)).timeout mustBe 30.seconds
    }

    "ignore an error status written as a string" in {
      readOk(format, Json.obj("error_status" -> "401")).errorStatus mustBe 403
    }

    "ignore the headers as soon as a value is not a string" in {
      readOk(format, Json.obj("headers" -> Json.obj("X-Foo" -> "bar", "X-Count" -> 1))).headers mustBe empty
    }

    "not read the camel case keys" in {
      val json = Json.obj(
        "cacheExpression" -> "${req.ip}",
        "errorMessage"    -> "nope",
        "errorStatus"     -> 401
      )
      readOk(format, json) mustBe NgExternalValidatorConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("error_status" -> 401, "foo" -> "bar")) mustBe NgExternalValidatorConfig(
        errorStatus = 401
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of NgExternalValidator" in {
      defaultConfigReadsBack(new NgExternalValidator(), format) mustBe NgExternalValidatorConfig()
    }
  }
}
