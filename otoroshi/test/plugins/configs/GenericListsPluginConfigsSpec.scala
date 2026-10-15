package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class GenericListsPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgGenericListConfig" should {

    val format = NgGenericListConfig.format

    "check the client ip address against no value by default" in {
      NgGenericListConfig().expression mustBe Some("${req.ip_address}")
      NgGenericListConfig().values mustBe empty
    }

    "write the default config" in {
      format.writes(NgGenericListConfig()) mustBe Json.obj(
        "expression" -> "${req.ip_address}",
        "values"     -> Json.arr()
      )
    }

    "write the expression and the values under expression and values" in {
      format.writes(NgGenericListConfig(Some("${req.headers.x-tenant}"), Seq("foo", "bar"))) mustBe Json.obj(
        "expression" -> "${req.headers.x-tenant}",
        "values"     -> Json.arr("foo", "bar")
      )
    }

    "write a missing expression as null" in {
      format.writes(NgGenericListConfig(expression = None)) mustBe Json.obj(
        "expression" -> JsNull,
        "values"     -> Json.arr()
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgGenericListConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, NgGenericListConfig(Some("${apikey.metadata.tier}"), Seq("gold", "silver")))
    }

    "round trip a config without expression" in {
      roundTrip(format, NgGenericListConfig(expression = None, values = Seq("1.2.3.4"))).expression mustBe None
    }

    "round trip a constant expression" in {
      roundTrip(format, NgGenericListConfig(Some("constant"), Seq("constant")))
    }

    "round trip several values and keep their order" in {
      roundTrip(format, NgGenericListConfig(values = Seq("10.0.0.3", "10.0.0.1", "10.0.0.2"))).values mustBe Seq(
        "10.0.0.3",
        "10.0.0.1",
        "10.0.0.2"
      )
    }

    "keep duplicated values" in {
      roundTrip(format, NgGenericListConfig(values = Seq("a", "a"))).values mustBe Seq("a", "a")
    }

    "round trip an empty string value" in {
      roundTrip(format, NgGenericListConfig(values = Seq(""))).values mustBe Seq("")
    }

    "read the expression alone and keep no value" in {
      readOk(format, Json.obj("expression" -> "${req.path}")) mustBe NgGenericListConfig(Some("${req.path}"), Seq.empty)
    }

    "read a null expression as no expression" in {
      readOk(format, Json.obj("expression" -> JsNull, "values" -> Json.arr("a"))) mustBe NgGenericListConfig(
        None,
        Seq("a")
      )
    }

    "read an empty expression as no expression" in {
      readOk(format, Json.obj("expression" -> "", "values" -> Json.arr("a"))).expression mustBe None
    }

    "read a blank expression as no expression" in {
      readOk(format, Json.obj("expression" -> " \t ", "values" -> Json.arr("a"))).expression mustBe None
    }

    "keep the spaces around a non blank expression" in {
      readOk(format, Json.obj("expression" -> " ${req.ip_address} ")).expression mustBe Some(" ${req.ip_address} ")
    }

    "read an expression that is not a string as no expression" in {
      readOk(format, Json.obj("expression" -> 42, "values" -> Json.arr("a"))).expression mustBe None
    }

    "ignore values that are not an array" in {
      readOk(format, Json.obj("expression" -> "${req.ip_address}", "values" -> "1.2.3.4")) mustBe NgGenericListConfig()
    }

    "ignore the whole values array as soon as one value is not a string" in {
      readOk(
        format,
        Json.obj("expression" -> "${req.ip_address}", "values" -> Json.arr("1.2.3.4", 42))
      ) mustBe NgGenericListConfig()
    }

    "ignore values given as an object" in {
      readOk(
        format,
        Json.obj("expression" -> "${req.ip_address}", "values" -> Json.obj("1.2.3.4" -> true))
      ) mustBe NgGenericListConfig()
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgGenericListConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgGenericListConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("1.2.3.4")) mustBe NgGenericListConfig()
    }

    "read a missing expression as the client ip address expression" in {
      readOk(format, Json.obj("values" -> Json.arr("1.2.3.4"))) mustBe NgGenericListConfig(
        Some("${req.ip_address}"),
        Seq("1.2.3.4")
      )
    }

    "not turn values without expression into a list that checks nothing" in {
      readOk(format, Json.obj("values" -> Json.arr("1.2.3.4", "5.6.7.8"))).expression mustBe defined
    }

    "tell a missing expression from a null one" in {
      readOk(format, Json.obj("values" -> Json.arr("a"))).expression mustBe Some("${req.ip_address}")
      readOk(format, Json.obj("expression" -> JsNull, "values" -> Json.arr("a"))).expression mustBe None
    }

    "ignore unknown keys" in {
      readOk(
        format,
        Json.obj("expression" -> "${req.ip_address}", "values" -> Json.arr("a"), "foo" -> "bar")
      ) mustBe NgGenericListConfig(values = Seq("a"))
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgGenericListConfig(Some("${req.path}"), Seq("/a", "/b")))
      jsonMatchesFormat(format, NgGenericListConfig(None, Seq.empty))
    }

    "be the default config of NgGenericAllowedList" in {
      defaultConfigReadsBack(new NgGenericAllowedList(), format) mustBe NgGenericListConfig()
    }

    "be the default config of NgGenericBlockList" in {
      defaultConfigReadsBack(new NgGenericBlockList(), format) mustBe NgGenericListConfig()
    }
  }
}
