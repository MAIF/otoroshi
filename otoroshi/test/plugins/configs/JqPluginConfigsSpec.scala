package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class JqPluginConfigsSpec extends PluginConfigFormatSpec {

  "JQConfig" should {

    val format = JQConfig.format

    val full = JQConfig(request = "{name: .user.name}", response = ".items | map(.id)")

    "default to the identity request filter and an empty response filter" in {
      JQConfig() mustBe JQConfig(".", "")
    }

    "write the default config" in {
      format.writes(JQConfig()) mustBe Json.obj("request" -> ".", "response" -> "")
    }

    "write both filters" in {
      format.writes(full) mustBe Json.obj("request" -> "{name: .user.name}", "response" -> ".items | map(.id)")
    }

    "round trip the default config" in {
      roundTrip(format, JQConfig())
    }

    "keep the empty response filter of the default config" in {
      roundTrip(format, JQConfig()).response mustBe ""
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip filters with quotes, pipes and line breaks" in {
      roundTrip(format, JQConfig(request = ".a | select(.b == \"c\")\n| .d", response = "$context.route.id"))
    }

    "round trip two empty filters" in {
      roundTrip(format, JQConfig("", ""))
    }

    "read an empty object with the identity filter on both sides" in {
      readOk(format, Json.obj()) mustBe JQConfig(".", ".")
    }

    "not read an empty object as the default config" in {
      readOk(format, Json.obj()) must not be JQConfig()
    }

    "read a json null with the identity filter on both sides" in {
      readOk(format, JsNull) mustBe JQConfig(".", ".")
    }

    "read a json string with the identity filter on both sides" in {
      readOk(format, JsString(".foo")) mustBe JQConfig(".", ".")
    }

    "read a json array with the identity filter on both sides" in {
      readOk(format, Json.arr(".foo")) mustBe JQConfig(".", ".")
    }

    "read the request filter alone and use the identity response filter" in {
      readOk(format, Json.obj("request" -> ".foo")) mustBe JQConfig(".foo", ".")
    }

    "read the response filter alone and use the identity request filter" in {
      readOk(format, Json.obj("response" -> ".bar")) mustBe JQConfig(".", ".bar")
    }

    "ignore a request filter that is not a string" in {
      readOk(format, Json.obj("request" -> Json.obj("filter" -> ".foo"), "response" -> ".bar")) mustBe JQConfig(
        ".",
        ".bar"
      )
    }

    "ignore a response filter that is not a string" in {
      readOk(format, Json.obj("request" -> ".foo", "response" -> 42)) mustBe JQConfig(".foo", ".")
    }

    "not read the filter key of the single side configs" in {
      readOk(format, Json.obj("filter" -> ".foo")) mustBe JQConfig(".", ".")
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of JQ" in {
      defaultConfigReadsBack(new JQ(), format) mustBe JQConfig()
    }
  }

  "JQRequestConfig" should {

    val format = JQRequestConfig.format

    "default to the identity filter" in {
      JQRequestConfig().filter mustBe "."
    }

    "write the default config" in {
      format.writes(JQRequestConfig()) mustBe Json.obj("filter" -> ".")
    }

    "round trip the default config" in {
      roundTrip(format, JQRequestConfig())
    }

    "round trip a filter" in {
      roundTrip(format, JQRequestConfig("{id: .id, ctx: $context.request.path}"))
    }

    "round trip an empty filter" in {
      roundTrip(format, JQRequestConfig("")).filter mustBe ""
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe JQRequestConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe JQRequestConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString(".foo")) mustBe JQRequestConfig()
    }

    "ignore a filter that is not a string" in {
      readOk(format, Json.obj("filter" -> Json.arr(".foo"))) mustBe JQRequestConfig()
    }

    "not read the request key of the two sided config" in {
      readOk(format, Json.obj("request" -> ".foo")) mustBe JQRequestConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, JQRequestConfig(".foo"))
    }

    "be the default config of JQRequest" in {
      defaultConfigReadsBack(new JQRequest(), format) mustBe JQRequestConfig()
    }
  }

  "JQResponseConfig" should {

    val format = JQResponseConfig.format

    "default to the identity filter" in {
      JQResponseConfig().filter mustBe "."
    }

    "write the default config" in {
      format.writes(JQResponseConfig()) mustBe Json.obj("filter" -> ".")
    }

    "round trip the default config" in {
      roundTrip(format, JQResponseConfig())
    }

    "round trip a filter" in {
      roundTrip(format, JQResponseConfig(".items[] | select(.enabled)"))
    }

    "round trip an empty filter" in {
      roundTrip(format, JQResponseConfig("")).filter mustBe ""
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe JQResponseConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe JQResponseConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(".foo")) mustBe JQResponseConfig()
    }

    "ignore a filter that is not a string" in {
      readOk(format, Json.obj("filter" -> true)) mustBe JQResponseConfig()
    }

    "not read the response key of the two sided config" in {
      readOk(format, Json.obj("response" -> ".foo")) mustBe JQResponseConfig()
    }

    "read the json of a request config" in {
      readOk(format, JQRequestConfig.format.writes(JQRequestConfig(".foo"))) mustBe JQResponseConfig(".foo")
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, JQResponseConfig(".foo"))
    }

    "be the default config of JQResponse" in {
      defaultConfigReadsBack(new JQResponse(), format) mustBe JQResponseConfig()
    }
  }
}
