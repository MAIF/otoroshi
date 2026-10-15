package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class Http3PluginConfigsSpec extends PluginConfigFormatSpec {

  "Http3SwitchConfig" should {

    val format = Http3SwitchConfig.format

    "default to a one hour max age, no domain and no protocol" in {
      val config = Http3SwitchConfig()
      config.ma mustBe 3600
      config.domain mustBe None
      config.protocols mustBe empty
    }

    "write the default config with a null domain and an empty protocols array" in {
      format.writes(Http3SwitchConfig()) mustBe Json.obj(
        "ma"        -> 3600,
        "domain"    -> JsNull,
        "protocols" -> Json.arr()
      )
    }

    "write every field under its snake case key" in {
      format.writes(Http3SwitchConfig(60, Some("api.oto.tools"), Seq("h3", "h3-29"))) mustBe Json.obj(
        "ma"        -> 60,
        "domain"    -> "api.oto.tools",
        "protocols" -> Json.arr("h3", "h3-29")
      )
    }

    "round trip the default config" in {
      roundTrip(format, Http3SwitchConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, Http3SwitchConfig(ma = 86400, domain = Some("api.oto.tools"), protocols = Seq("h3", "h3-29")))
    }

    "round trip a domain without protocol" in {
      roundTrip(format, Http3SwitchConfig(domain = Some("foo.oto.tools")))
    }

    "round trip an empty domain as an empty string, not as no domain" in {
      roundTrip(format, Http3SwitchConfig(domain = Some(""))).domain mustBe Some("")
    }

    "round trip a single protocol" in {
      roundTrip(format, Http3SwitchConfig(protocols = Seq("h3")))
    }

    "round trip several protocols and keep their order" in {
      roundTrip(format, Http3SwitchConfig(protocols = Seq("h3-29", "h3", "h3-32"))).protocols mustBe Seq(
        "h3-29",
        "h3",
        "h3-32"
      )
    }

    "round trip a zero max age" in {
      roundTrip(format, Http3SwitchConfig(ma = 0))
    }

    "round trip a negative max age as is" in {
      roundTrip(format, Http3SwitchConfig(ma = -1))
    }

    "round trip the largest max age" in {
      roundTrip(format, Http3SwitchConfig(ma = Int.MaxValue))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe Http3SwitchConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe Http3SwitchConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("h3")) mustBe Http3SwitchConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("h3")) mustBe Http3SwitchConfig()
    }

    "read a max age alone" in {
      readOk(format, Json.obj("ma" -> 120)) mustBe Http3SwitchConfig(ma = 120)
    }

    "read a domain alone" in {
      readOk(format, Json.obj("domain" -> "foo.oto.tools")) mustBe Http3SwitchConfig(domain = Some("foo.oto.tools"))
    }

    "read protocols alone" in {
      readOk(format, Json.obj("protocols" -> Json.arr("h3"))) mustBe Http3SwitchConfig(protocols = Seq("h3"))
    }

    "ignore a max age written as a string" in {
      readOk(format, Json.obj("ma" -> "120")) mustBe Http3SwitchConfig()
    }

    "ignore a max age that is not a whole number" in {
      readOk(format, Json.obj("ma" -> 120.5)) mustBe Http3SwitchConfig()
    }

    "ignore a max age that does not fit an int" in {
      readOk(format, Json.obj("ma" -> JsNumber(BigDecimal("3000000000")))) mustBe Http3SwitchConfig()
    }

    "read a null domain as no domain" in {
      readOk(format, Json.obj("domain" -> JsNull, "ma" -> 10)) mustBe Http3SwitchConfig(ma = 10)
    }

    "ignore a domain that is not a string" in {
      readOk(format, Json.obj("domain" -> 42)) mustBe Http3SwitchConfig()
    }

    "ignore protocols written as a single string" in {
      readOk(format, Json.obj("protocols" -> "h3")) mustBe Http3SwitchConfig()
    }

    "ignore a protocols array holding something else than strings" in {
      readOk(format, Json.obj("protocols" -> Json.arr("h3", 29))) mustBe Http3SwitchConfig()
    }

    "not read a max_age key instead of ma" in {
      readOk(format, Json.obj("max_age" -> 120)) mustBe Http3SwitchConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("ma" -> 10, "foo" -> "bar")) mustBe Http3SwitchConfig(ma = 10)
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, Http3SwitchConfig(60, Some("api.oto.tools"), Seq("h3")))
    }

    "be the default config of Http3Switch" in {
      defaultConfigReadsBack(new Http3Switch(), format) mustBe Http3SwitchConfig()
    }
  }
}
