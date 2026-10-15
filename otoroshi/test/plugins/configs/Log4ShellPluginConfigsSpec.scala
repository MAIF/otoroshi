package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class Log4ShellPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgLog4ShellFilterConfig" should {

    val format = NgLog4ShellFilterConfig.format

    val full = NgLog4ShellFilterConfig(
      status = 403,
      body = """{"error":"blocked"}""",
      parseBody = true,
      maxBodySize = 1024L
    )

    "write the default config with all its keys" in {
      format.writes(NgLog4ShellFilterConfig()) mustBe Json.obj(
        "status"        -> 200,
        "body"          -> "",
        "parse_body"    -> false,
        "max_body_size" -> 10485760
      )
    }

    "hold at most 10 megabytes of parsed body by default" in {
      NgLog4ShellFilterConfig.defaultMaxBodySize mustBe 10L * 1024L * 1024L
      NgLog4ShellFilterConfig().maxBodySize mustBe NgLog4ShellFilterConfig.defaultMaxBodySize
    }

    "round trip the default config" in {
      roundTrip(format, NgLog4ShellFilterConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "status"        -> 403,
        "body"          -> """{"error":"blocked"}""",
        "parse_body"    -> true,
        "max_body_size" -> 1024
      )
    }

    "round trip body parsing alone" in {
      roundTrip(format, NgLog4ShellFilterConfig(parseBody = true)).parseBody mustBe true
    }

    "round trip a body with quotes, unicode and line breaks" in {
      roundTrip(format, NgLog4ShellFilterConfig(body = "<h1>\"bloqué\"</h1>\n"))
    }

    "round trip a zero max body size" in {
      roundTrip(format, NgLog4ShellFilterConfig(maxBodySize = 0L))
    }

    "round trip the largest max body size" in {
      roundTrip(format, NgLog4ShellFilterConfig(maxBodySize = Long.MaxValue))
    }

    "round trip a status out of the http range as is" in {
      roundTrip(format, NgLog4ShellFilterConfig(status = 999))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgLog4ShellFilterConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgLog4ShellFilterConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("403")) mustBe NgLog4ShellFilterConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(403)) mustBe NgLog4ShellFilterConfig()
    }

    "read the status alone and default the rest" in {
      readOk(format, Json.obj("status" -> 400)) mustBe NgLog4ShellFilterConfig(status = 400)
    }

    "read the max body size alone and default the rest" in {
      readOk(format, Json.obj("max_body_size" -> 2048)) mustBe NgLog4ShellFilterConfig(maxBodySize = 2048L)
    }

    "ignore a status written as a string" in {
      readOk(format, Json.obj("status" -> "403")).status mustBe 200
    }

    "ignore a status that is not a whole number" in {
      readOk(format, Json.obj("status" -> 403.5)).status mustBe 200
    }

    "ignore a body that is not a string" in {
      readOk(format, Json.obj("body" -> Json.obj("error" -> "blocked"))).body mustBe ""
    }

    "ignore a parse_body flag written as a string" in {
      readOk(format, Json.obj("parse_body" -> "true")).parseBody mustBe false
    }

    "ignore a max body size written as a string" in {
      readOk(format, Json.obj("max_body_size" -> "2048")).maxBodySize mustBe NgLog4ShellFilterConfig.defaultMaxBodySize
    }

    "ignore a max body size that does not fit a long" in {
      readOk(format, Json.obj("max_body_size" -> JsNumber(BigDecimal("99999999999999999999")))).maxBodySize mustBe
        NgLog4ShellFilterConfig.defaultMaxBodySize
    }

    "not read the camel case keys" in {
      readOk(format, Json.obj("parseBody" -> true, "maxBodySize" -> 2048)) mustBe NgLog4ShellFilterConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of NgLog4ShellFilter" in {
      defaultConfigReadsBack(new NgLog4ShellFilter(), format) mustBe NgLog4ShellFilterConfig()
    }
  }
}
