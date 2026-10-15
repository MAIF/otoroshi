package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class HeadersPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgHeaderNamesConfig" should {

    val format = NgHeaderNamesConfig.format

    "write the names under header_names" in {
      format.writes(NgHeaderNamesConfig(Seq("X-Foo", "X-Bar"))) mustBe Json.obj(
        "header_names" -> Json.arr("X-Foo", "X-Bar")
      )
    }

    "write an empty config as an empty header_names array" in {
      format.writes(NgHeaderNamesConfig()) mustBe Json.obj("header_names" -> Json.arr())
    }

    "round trip the default config" in {
      roundTrip(format, NgHeaderNamesConfig())
    }

    "round trip a single name" in {
      roundTrip(format, NgHeaderNamesConfig(Seq("Authorization")))
    }

    "round trip several names and keep their order" in {
      val back = roundTrip(format, NgHeaderNamesConfig(Seq("X-C", "X-A", "X-B")))
      back.names mustBe Seq("X-C", "X-A", "X-B")
    }

    "keep duplicated names" in {
      roundTrip(format, NgHeaderNamesConfig(Seq("X-Foo", "X-Foo"))).names mustBe Seq("X-Foo", "X-Foo")
    }

    "keep the case of the names" in {
      roundTrip(format, NgHeaderNamesConfig(Seq("x-lower", "X-UPPER", "X-Mixed"))).names mustBe Seq(
        "x-lower",
        "X-UPPER",
        "X-Mixed"
      )
    }

    "read the legacy headers key" in {
      readOk(format, Json.obj("headers" -> Json.arr("X-Legacy"))) mustBe NgHeaderNamesConfig(Seq("X-Legacy"))
    }

    "read the names key" in {
      readOk(format, Json.obj("names" -> Json.arr("X-Names"))) mustBe NgHeaderNamesConfig(Seq("X-Names"))
    }

    "prefer header_names over headers and names" in {
      val json = Json.obj(
        "header_names" -> Json.arr("X-1"),
        "headers"      -> Json.arr("X-2"),
        "names"        -> Json.arr("X-3")
      )
      readOk(format, json) mustBe NgHeaderNamesConfig(Seq("X-1"))
    }

    "prefer headers over names" in {
      val json = Json.obj(
        "headers" -> Json.arr("X-2"),
        "names"   -> Json.arr("X-3")
      )
      readOk(format, json) mustBe NgHeaderNamesConfig(Seq("X-2"))
    }

    "fall back to headers when header_names is empty" in {
      val json = Json.obj(
        "header_names" -> Json.arr(),
        "headers"      -> Json.arr("X-2")
      )
      readOk(format, json) mustBe NgHeaderNamesConfig(Seq("X-2"))
    }

    "fall back to names when header_names and headers are empty" in {
      val json = Json.obj(
        "header_names" -> Json.arr(),
        "headers"      -> Json.arr(),
        "names"        -> Json.arr("X-3")
      )
      readOk(format, json) mustBe NgHeaderNamesConfig(Seq("X-3"))
    }

    "read an empty object as an empty config" in {
      readOk(format, Json.obj()) mustBe NgHeaderNamesConfig()
    }

    "read empty arrays everywhere as an empty config" in {
      val json = Json.obj(
        "header_names" -> Json.arr(),
        "headers"      -> Json.arr(),
        "names"        -> Json.arr()
      )
      readOk(format, json) mustBe NgHeaderNamesConfig()
    }

    "ignore a header_names that is not an array" in {
      readOk(format, Json.obj("header_names" -> "X-Foo")) mustBe NgHeaderNamesConfig()
    }

    "ignore a header_names array holding something else than strings" in {
      readOk(format, Json.obj("header_names" -> Json.arr("X-Foo", 42))) mustBe NgHeaderNamesConfig()
    }

    "fall back to headers when header_names is not an array" in {
      val json = Json.obj(
        "header_names" -> 42,
        "headers"      -> Json.arr("X-2")
      )
      readOk(format, json) mustBe NgHeaderNamesConfig(Seq("X-2"))
    }

    "not mistake the headers map of a values config for a list of names" in {
      readOk(format, Json.obj("headers" -> Json.obj("X-Foo" -> "bar"))) mustBe NgHeaderNamesConfig()
    }

    "read a json null as an empty config" in {
      readOk(format, JsNull) mustBe NgHeaderNamesConfig()
    }

    "read a json string as an empty config" in {
      readOk(format, JsString("X-Foo")) mustBe NgHeaderNamesConfig()
    }

    "read a json array as an empty config" in {
      readOk(format, Json.arr("X-Foo")) mustBe NgHeaderNamesConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("header_names" -> Json.arr("X-Foo"), "foo" -> "bar")) mustBe NgHeaderNamesConfig(
        Seq("X-Foo")
      )
    }

    "rewrite the legacy keys as header_names" in {
      val legacy = readOk(format, Json.obj("headers" -> Json.arr("X-Legacy")))
      format.writes(legacy) mustBe Json.obj("header_names" -> Json.arr("X-Legacy"))
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgHeaderNamesConfig(Seq("X-Foo")))
    }

    "be the default config of RemoveHeadersIn" in {
      defaultConfigReadsBack(new RemoveHeadersIn(), format) mustBe NgHeaderNamesConfig()
    }

    "be the default config of RemoveHeadersOut" in {
      defaultConfigReadsBack(new RemoveHeadersOut(), format) mustBe NgHeaderNamesConfig()
    }
  }

  "NgHeaderValuesConfig" should {

    val format = NgHeaderValuesConfig.format

    "write the values under headers" in {
      format.writes(NgHeaderValuesConfig(Map("X-Foo" -> "foo"))) mustBe Json.obj(
        "headers" -> Json.obj("X-Foo" -> "foo")
      )
    }

    "write an empty config as an empty headers object" in {
      format.writes(NgHeaderValuesConfig()) mustBe Json.obj("headers" -> Json.obj())
    }

    "round trip the default config" in {
      roundTrip(format, NgHeaderValuesConfig())
    }

    "round trip a single header" in {
      roundTrip(format, NgHeaderValuesConfig(Map("X-Foo" -> "foo")))
    }

    "round trip several headers" in {
      roundTrip(format, NgHeaderValuesConfig(Map("X-Foo" -> "foo", "X-Bar" -> "bar", "X-Baz" -> "baz")))
    }

    "round trip an empty value" in {
      roundTrip(format, NgHeaderValuesConfig(Map("X-Empty" -> "")))
    }

    "round trip values holding expression language" in {
      roundTrip(
        format,
        NgHeaderValuesConfig(Map("X-Request-Id" -> "${req.id}", "X-User" -> "${user.email}"))
      )
    }

    "round trip values holding a regex" in {
      roundTrip(format, NgHeaderValuesConfig(Map("X-Version" -> "Regex(^v[0-9]+$)")))
    }

    "round trip values with quotes, unicode and line breaks" in {
      roundTrip(format, NgHeaderValuesConfig(Map("X-Weird" -> "a \"quoted\" été\nvalue")))
    }

    "keep the case of the header names" in {
      roundTrip(format, NgHeaderValuesConfig(Map("x-lower" -> "1", "X-UPPER" -> "2"))).headers.keySet mustBe Set(
        "x-lower",
        "X-UPPER"
      )
    }

    "read an empty object as an empty config" in {
      readOk(format, Json.obj()) mustBe NgHeaderValuesConfig()
    }

    "read a json null as an empty config" in {
      readOk(format, JsNull) mustBe NgHeaderValuesConfig()
    }

    "read a json string as an empty config" in {
      readOk(format, JsString("X-Foo")) mustBe NgHeaderValuesConfig()
    }

    "ignore a headers key that is an array" in {
      readOk(format, Json.obj("headers" -> Json.arr("X-Foo"))) mustBe NgHeaderValuesConfig()
    }

    "ignore a headers key that is a string" in {
      readOk(format, Json.obj("headers" -> "X-Foo: foo")) mustBe NgHeaderValuesConfig()
    }

    "ignore the whole map as soon as a value is not a string" in {
      readOk(format, Json.obj("headers" -> Json.obj("X-Foo" -> "foo", "X-Count" -> 42))) mustBe NgHeaderValuesConfig()
    }

    "ignore a map holding a nested object" in {
      readOk(format, Json.obj("headers" -> Json.obj("X-Foo" -> Json.obj("a" -> "b")))) mustBe NgHeaderValuesConfig()
    }

    "ignore a map holding a null value" in {
      readOk(format, Json.obj("headers" -> Json.obj("X-Foo" -> JsNull))) mustBe NgHeaderValuesConfig()
    }

    "not read the header_names of a names config" in {
      readOk(format, Json.obj("header_names" -> Json.arr("X-Foo"))) mustBe NgHeaderValuesConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("headers" -> Json.obj("X-Foo" -> "foo"), "foo" -> "bar")) mustBe NgHeaderValuesConfig(
        Map("X-Foo" -> "foo")
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgHeaderValuesConfig(Map("X-Foo" -> "foo")))
    }

    "be the default config of AdditionalHeadersIn" in {
      defaultConfigReadsBack(new AdditionalHeadersIn(), format) mustBe NgHeaderValuesConfig()
    }

    "be the default config of AdditionalHeadersOut" in {
      defaultConfigReadsBack(new AdditionalHeadersOut(), format) mustBe NgHeaderValuesConfig()
    }

    "be the default config of MissingHeadersIn" in {
      defaultConfigReadsBack(new MissingHeadersIn(), format) mustBe NgHeaderValuesConfig()
    }

    "be the default config of MissingHeadersOut" in {
      defaultConfigReadsBack(new MissingHeadersOut(), format) mustBe NgHeaderValuesConfig()
    }

    "be the default config of HeadersValidation" in {
      defaultConfigReadsBack(new HeadersValidation(), format) mustBe NgHeaderValuesConfig()
    }
  }

  "OverrideLocationHeaderConfig" should {

    val format = OverrideLocationHeaderConfig.format

    "write the hostnames under matching_hostnames" in {
      format.writes(OverrideLocationHeaderConfig(Seq("foo.oto.tools"))) mustBe Json.obj(
        "matching_hostnames" -> Json.arr("foo.oto.tools")
      )
    }

    "write the default config as an empty matching_hostnames array" in {
      format.writes(OverrideLocationHeaderConfig.default) mustBe Json.obj("matching_hostnames" -> Json.arr())
    }

    "have no matching hostname by default" in {
      OverrideLocationHeaderConfig.default.matchingHostnames mustBe empty
    }

    "round trip the default config" in {
      roundTrip(format, OverrideLocationHeaderConfig.default)
    }

    "round trip several hostnames and keep their order" in {
      roundTrip(
        format,
        OverrideLocationHeaderConfig(Seq("b.oto.tools", "a.oto.tools", "backend.svc.cluster.local"))
      ).matchingHostnames mustBe Seq("b.oto.tools", "a.oto.tools", "backend.svc.cluster.local")
    }

    "round trip hostnames with a port" in {
      roundTrip(format, OverrideLocationHeaderConfig(Seq("localhost:8080", "127.0.0.1:9999")))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe OverrideLocationHeaderConfig.default
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe OverrideLocationHeaderConfig.default
    }

    "ignore a matching_hostnames that is a string" in {
      readOk(format, Json.obj("matching_hostnames" -> "foo.oto.tools")) mustBe OverrideLocationHeaderConfig.default
    }

    "ignore a matching_hostnames array holding something else than strings" in {
      readOk(
        format,
        Json.obj("matching_hostnames" -> Json.arr("foo.oto.tools", true))
      ) mustBe OverrideLocationHeaderConfig.default
    }

    "not read the camel case key" in {
      readOk(
        format,
        Json.obj("matchingHostnames" -> Json.arr("foo.oto.tools"))
      ) mustBe OverrideLocationHeaderConfig.default
    }

    "match a listed hostname" in {
      OverrideLocationHeaderConfig(Seq("foo.oto.tools", "bar.oto.tools")).matches("bar.oto.tools") mustBe true
    }

    "not match a hostname that is not listed" in {
      OverrideLocationHeaderConfig(Seq("foo.oto.tools")).matches("baz.oto.tools") mustBe false
    }

    "not match anything when no hostname is listed" in {
      OverrideLocationHeaderConfig.default.matches("foo.oto.tools") mustBe false
      OverrideLocationHeaderConfig.default.matches("") mustBe false
    }

    "match hostnames exactly, without wildcard" in {
      OverrideLocationHeaderConfig(Seq("*.oto.tools")).matches("foo.oto.tools") mustBe false
      OverrideLocationHeaderConfig(Seq("*.oto.tools")).matches("*.oto.tools") mustBe true
    }

    "match hostnames exactly, without ignoring the case" in {
      OverrideLocationHeaderConfig(Seq("foo.oto.tools")).matches("FOO.oto.tools") mustBe false
    }

    "match hostnames exactly, without ignoring the port" in {
      OverrideLocationHeaderConfig(Seq("foo.oto.tools")).matches("foo.oto.tools:8080") mustBe false
      OverrideLocationHeaderConfig(Seq("foo.oto.tools:8080")).matches("foo.oto.tools:8080") mustBe true
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, OverrideLocationHeaderConfig(Seq("foo.oto.tools")))
    }

    "list matching_hostnames in its config flow and schema" in {
      OverrideLocationHeaderConfig.configFlow mustBe Seq("matching_hostnames")
      OverrideLocationHeaderConfig.configSchema.get.keys must contain("matching_hostnames")
    }

    "be the default config of OverrideLocationHeader" in {
      defaultConfigReadsBack(new OverrideLocationHeader(), format) mustBe OverrideLocationHeaderConfig.default
    }
  }

  "RejectHeaderConfig" should {

    val format = RejectHeaderConfig.format

    "default to 8 kilobytes" in {
      RejectHeaderConfig().value mustBe 8192L
      RejectHeaderConfig.default mustBe RejectHeaderConfig()
    }

    "write the limit under value" in {
      format.writes(RejectHeaderConfig(1024)) mustBe Json.obj("value" -> 1024)
    }

    "round trip the default config" in {
      roundTrip(format, RejectHeaderConfig.default)
    }

    "round trip a small limit" in {
      roundTrip(format, RejectHeaderConfig(1))
    }

    "round trip a zero limit" in {
      roundTrip(format, RejectHeaderConfig(0))
    }

    "round trip a large limit" in {
      roundTrip(format, RejectHeaderConfig(1024L * 1024L * 1024L))
    }

    "round trip the largest limit" in {
      roundTrip(format, RejectHeaderConfig(Long.MaxValue))
    }

    "round trip a negative limit as is" in {
      roundTrip(format, RejectHeaderConfig(-1))
    }

    "read an empty object as the default limit" in {
      readOk(format, Json.obj()) mustBe RejectHeaderConfig.default
    }

    "read a json null as the default limit" in {
      readOk(format, JsNull) mustBe RejectHeaderConfig.default
    }

    "ignore a limit written as a string" in {
      readOk(format, Json.obj("value" -> "1024")) mustBe RejectHeaderConfig.default
    }

    "ignore a limit that is not a whole number" in {
      readOk(format, Json.obj("value" -> 1024.5)) mustBe RejectHeaderConfig.default
    }

    "ignore a limit that does not fit a long" in {
      readOk(format, Json.obj("value" -> JsNumber(BigDecimal("99999999999999999999")))) mustBe RejectHeaderConfig.default
    }

    "read a whole number written with a decimal part" in {
      readOk(format, Json.obj("value" -> JsNumber(BigDecimal("2048.0")))) mustBe RejectHeaderConfig(2048)
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, RejectHeaderConfig(4096))
    }

    "list value in its config flow and schema" in {
      RejectHeaderConfig.configFlow mustBe Seq("value")
      RejectHeaderConfig.configSchema.get.keys must contain("value")
    }

    "be the default config of RejectHeaderInTooLong" in {
      defaultConfigReadsBack(new RejectHeaderInTooLong(), format) mustBe RejectHeaderConfig.default
    }

    "be the default config of RejectHeaderOutTooLong" in {
      defaultConfigReadsBack(new RejectHeaderOutTooLong(), format) mustBe RejectHeaderConfig.default
    }

    "be the default config of LimitHeaderInTooLong" in {
      defaultConfigReadsBack(new LimitHeaderInTooLong(), format) mustBe RejectHeaderConfig.default
    }

    "be the default config of LimitHeaderOutTooLong" in {
      defaultConfigReadsBack(new LimitHeaderOutTooLong(), format) mustBe RejectHeaderConfig.default
    }
  }

  "the header plugins without configuration" should {

    "have no default config for OverrideHost" in {
      hasNoDefaultConfig(new OverrideHost())
    }

    "have no default config for OtoroshiHeadersIn" in {
      hasNoDefaultConfig(new OtoroshiHeadersIn())
    }

    "have no default config for SendOtoroshiHeadersBack" in {
      hasNoDefaultConfig(new SendOtoroshiHeadersBack())
    }

    "have no default config for XForwardedHeaders" in {
      hasNoDefaultConfig(new XForwardedHeaders())
    }

    "have no default config for ForwardedHeader" in {
      hasNoDefaultConfig(new ForwardedHeader())
    }
  }
}
