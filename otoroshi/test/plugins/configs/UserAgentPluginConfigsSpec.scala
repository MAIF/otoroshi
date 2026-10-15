package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class UserAgentPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgUserAgentExtractorConfig" should {

    val format = NgUserAgentExtractorConfig.format

    "not log by default" in {
      NgUserAgentExtractorConfig().log mustBe false
    }

    "write the default config" in {
      format.writes(NgUserAgentExtractorConfig()) mustBe Json.obj("log" -> false)
    }

    "write the log flag" in {
      format.writes(NgUserAgentExtractorConfig(log = true)) mustBe Json.obj("log" -> true)
    }

    "round trip the default config" in {
      roundTrip(format, NgUserAgentExtractorConfig())
    }

    "round trip a logging config" in {
      roundTrip(format, NgUserAgentExtractorConfig(log = true)).log mustBe true
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgUserAgentExtractorConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgUserAgentExtractorConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("log")) mustBe NgUserAgentExtractorConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(Json.obj("log" -> true))) mustBe NgUserAgentExtractorConfig()
    }

    "ignore a log flag written as a string" in {
      readOk(format, Json.obj("log" -> "true")) mustBe NgUserAgentExtractorConfig()
    }

    "ignore a log flag written as a number" in {
      readOk(format, Json.obj("log" -> 1)) mustBe NgUserAgentExtractorConfig()
    }

    "ignore a null log flag" in {
      readOk(format, Json.obj("log" -> JsNull)) mustBe NgUserAgentExtractorConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("log" -> true, "header_name" -> "X-UA")) mustBe NgUserAgentExtractorConfig(log = true)
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgUserAgentExtractorConfig(log = true))
    }

    "be the default config of NgUserAgentExtractor" in {
      defaultConfigReadsBack(new NgUserAgentExtractor(), format) mustBe NgUserAgentExtractorConfig()
    }
  }

  "NgUserAgentInfoHeaderConfig" should {

    val format = NgUserAgentInfoHeaderConfig.format

    "use the X-User-Agent-Info header by default" in {
      NgUserAgentInfoHeaderConfig().headerName mustBe "X-User-Agent-Info"
    }

    "write the default config" in {
      format.writes(NgUserAgentInfoHeaderConfig()) mustBe Json.obj("header_name" -> "X-User-Agent-Info")
    }

    "write the header name under header_name" in {
      format.writes(NgUserAgentInfoHeaderConfig("X-UA")) mustBe Json.obj("header_name" -> "X-UA")
    }

    "round trip the default config" in {
      roundTrip(format, NgUserAgentInfoHeaderConfig())
    }

    "round trip a custom header name and keep its case" in {
      roundTrip(format, NgUserAgentInfoHeaderConfig("x-Ua-INFO")).headerName mustBe "x-Ua-INFO"
    }

    "round trip an empty header name as is" in {
      roundTrip(format, NgUserAgentInfoHeaderConfig("")).headerName mustBe ""
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgUserAgentInfoHeaderConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgUserAgentInfoHeaderConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("X-UA")) mustBe NgUserAgentInfoHeaderConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("X-UA")) mustBe NgUserAgentInfoHeaderConfig()
    }

    "ignore a header name that is not a string" in {
      readOk(format, Json.obj("header_name" -> 42)) mustBe NgUserAgentInfoHeaderConfig()
      readOk(format, Json.obj("header_name" -> Json.arr("X-UA"))) mustBe NgUserAgentInfoHeaderConfig()
    }

    "not read the camel case key" in {
      readOk(format, Json.obj("headerName" -> "X-UA")) mustBe NgUserAgentInfoHeaderConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgUserAgentInfoHeaderConfig("X-UA"))
    }

    "be the default config of NgUserAgentInfoHeader" in {
      defaultConfigReadsBack(new NgUserAgentInfoHeader(), format) mustBe NgUserAgentInfoHeaderConfig()
    }
  }

  "the user agent plugins without configuration" should {

    "have no default config for NgUserAgentInfoEndpoint" in {
      hasNoDefaultConfig(new NgUserAgentInfoEndpoint())
    }
  }
}
