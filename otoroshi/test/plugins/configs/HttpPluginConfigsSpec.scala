package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class HttpPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgAllowedMethodsConfig" should {

    val format = NgAllowedMethodsConfig.format

    "allow and forbid nothing by default" in {
      NgAllowedMethodsConfig() mustBe NgAllowedMethodsConfig(Seq.empty, Seq.empty)
    }

    "write the default config as two empty arrays" in {
      format.writes(NgAllowedMethodsConfig()) mustBe Json.obj(
        "allowed"   -> Json.arr(),
        "forbidden" -> Json.arr()
      )
    }

    "write the allowed and forbidden methods" in {
      format.writes(NgAllowedMethodsConfig(Seq("get", "post"), Seq("delete"))) mustBe Json.obj(
        "allowed"   -> Json.arr("get", "post"),
        "forbidden" -> Json.arr("delete")
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgAllowedMethodsConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, NgAllowedMethodsConfig(Seq("get", "head", "options"), Seq("delete", "patch")))
    }

    "keep the allowed and forbidden methods apart" in {
      val back = roundTrip(format, NgAllowedMethodsConfig(Seq("get"), Seq("put")))
      back.allowed mustBe Seq("get")
      back.forbidden mustBe Seq("put")
    }

    "round trip allowed methods alone" in {
      roundTrip(format, NgAllowedMethodsConfig(allowed = Seq("get")))
    }

    "round trip forbidden methods alone" in {
      roundTrip(format, NgAllowedMethodsConfig(forbidden = Seq("trace")))
    }

    "keep the order of the methods and their duplicates" in {
      roundTrip(format, NgAllowedMethodsConfig(Seq("post", "get", "post"))).allowed mustBe Seq("post", "get", "post")
    }

    "keep unknown methods" in {
      roundTrip(format, NgAllowedMethodsConfig(Seq("propfind", "foo"))).allowed mustBe Seq("propfind", "foo")
    }

    "lower case the allowed methods when reading" in {
      readOk(format, Json.obj("allowed" -> Json.arr("GET", "Post"))).allowed mustBe Seq("get", "post")
    }

    "lower case the forbidden methods when reading" in {
      readOk(format, Json.obj("forbidden" -> Json.arr("DELETE"))).forbidden mustBe Seq("delete")
    }

    "rewrite upper case methods in lower case" in {
      val read = readOk(format, format.writes(NgAllowedMethodsConfig(Seq("GET"), Seq("PUT"))))
      format.writes(read) mustBe Json.obj("allowed" -> Json.arr("get"), "forbidden" -> Json.arr("put"))
    }

    "read the allowed methods alone" in {
      readOk(format, Json.obj("allowed" -> Json.arr("get"))) mustBe NgAllowedMethodsConfig(allowed = Seq("get"))
    }

    "read the forbidden methods alone" in {
      readOk(format, Json.obj("forbidden" -> Json.arr("get"))) mustBe NgAllowedMethodsConfig(forbidden = Seq("get"))
    }

    "ignore methods that are not an array" in {
      readOk(format, Json.obj("allowed" -> "get", "forbidden" -> "post")) mustBe NgAllowedMethodsConfig()
    }

    "ignore an array holding something else than strings" in {
      readOk(format, Json.obj("allowed" -> Json.arr("get", 1))).allowed mustBe empty
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgAllowedMethodsConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgAllowedMethodsConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("get")) mustBe NgAllowedMethodsConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("get", "post")) mustBe NgAllowedMethodsConfig()
    }

    "not read the methods key" in {
      readOk(format, Json.obj("methods" -> Json.arr("get"))) mustBe NgAllowedMethodsConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgAllowedMethodsConfig(Seq("get"), Seq("delete")))
    }

    "be the default config of AllowHttpMethods" in {
      defaultConfigReadsBack(new AllowHttpMethods(), format) mustBe NgAllowedMethodsConfig()
    }
  }

  "the http plugins without configuration" should {

    "have no default config for ReadOnlyCalls" in {
      hasNoDefaultConfig(new ReadOnlyCalls())
    }
  }
}
