package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class RegexPluginConfigsSpec extends PluginConfigFormatSpec {

  "RegexBodyRewriterConfig" should {

    val format = RegexBodyRewriterConfig.format
    val rule   = RegexReplacementRule("href=([\"'])/v1/(.+?)\\1", "href=$1/v2/$2$1", Some("i"))
    val full   = RegexBodyRewriterConfig(
      contentTypes = Seq("text/html", "text/plain"),
      rules = Seq(rule, RegexReplacementRule("(?i)https?://example\\.com", "https://public.example.com")),
      autoHrefPrefix = Some("/proxy"),
      maxBodySize = Some(1048576L),
      charsetFallback = Some("ISO-8859-1")
    )

    "target html in utf-8 without rule by default" in {
      RegexBodyRewriterConfig() mustBe RegexBodyRewriterConfig(
        contentTypes = Seq("text/html"),
        rules = Seq.empty,
        autoHrefPrefix = None,
        maxBodySize = None,
        charsetFallback = Some("UTF-8")
      )
    }

    "write the default config" in {
      format.writes(RegexBodyRewriterConfig()) mustBe Json.obj(
        "content_types"    -> Json.arr("text/html"),
        "rules"            -> Json.arr(),
        "auto_href_prefix" -> JsNull,
        "max_body_size"    -> JsNull,
        "charset_fallback" -> "UTF-8"
      )
    }

    "write a rule with its pattern, replacement and flags" in {
      (written(format, RegexBodyRewriterConfig(rules = Seq(rule))) \ "rules").as[JsArray] mustBe Json.arr(
        Json.obj("pattern" -> "href=([\"'])/v1/(.+?)\\1", "replacement" -> "href=$1/v2/$2$1", "flags" -> "i")
      )
    }

    "write a rule without flags with null flags" in {
      RegexReplacementRule("a", "b").json mustBe Json.obj("pattern" -> "a", "replacement" -> "b", "flags" -> JsNull)
    }

    "round trip the default config" in {
      roundTrip(format, RegexBodyRewriterConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip several rules and keep their order" in {
      val rules = Seq(
        RegexReplacementRule("c", "3"),
        RegexReplacementRule("a", "1", Some("ms")),
        RegexReplacementRule("b", "2")
      )
      roundTrip(format, RegexBodyRewriterConfig(rules = rules)).rules mustBe rules
    }

    "round trip a rule with an empty replacement" in {
      roundTrip(format, RegexBodyRewriterConfig(rules = Seq(RegexReplacementRule("<script.*?</script>", ""))))
    }

    "round trip an empty list of content types" in {
      roundTrip(format, RegexBodyRewriterConfig(contentTypes = Seq.empty)).contentTypes mustBe empty
    }

    "round trip an auto href prefix alone" in {
      roundTrip(format, RegexBodyRewriterConfig(autoHrefPrefix = Some("/proxy/"))).autoHrefPrefix mustBe Some("/proxy/")
    }

    "round trip an empty auto href prefix" in {
      roundTrip(format, RegexBodyRewriterConfig(autoHrefPrefix = Some(""))).autoHrefPrefix mustBe Some("")
    }

    "round trip a zero max body size" in {
      roundTrip(format, RegexBodyRewriterConfig(maxBodySize = Some(0L))).maxBodySize mustBe Some(0L)
    }

    "round trip the largest max body size" in {
      roundTrip(format, RegexBodyRewriterConfig(maxBodySize = Some(Long.MaxValue)))
    }

    "write no charset fallback as null and read it back as utf-8" in {
      val json = written(format, RegexBodyRewriterConfig(charsetFallback = None))
      json.value("charset_fallback") mustBe JsNull
      readOk(format, json).charsetFallback mustBe Some("UTF-8")
    }

    "read the content types alone" in {
      readOk(format, Json.obj("content_types" -> Json.arr("application/json"))) mustBe RegexBodyRewriterConfig(
        contentTypes = Seq("application/json")
      )
    }

    "read the rules alone" in {
      readOk(
        format,
        Json.obj("rules" -> Json.arr(Json.obj("pattern" -> "a", "replacement" -> "b")))
      ) mustBe RegexBodyRewriterConfig(rules = Seq(RegexReplacementRule("a", "b")))
    }

    "read the max body size alone" in {
      readOk(format, Json.obj("max_body_size" -> 1024)) mustBe RegexBodyRewriterConfig(maxBodySize = Some(1024L))
    }

    "read the charset fallback alone" in {
      readOk(format, Json.obj("charset_fallback" -> "UTF-16")) mustBe RegexBodyRewriterConfig(
        charsetFallback = Some("UTF-16")
      )
    }

    "read content types that are not an array as text/html" in {
      readOk(format, Json.obj("content_types" -> "text/plain")).contentTypes mustBe Seq("text/html")
    }

    "read content types holding something else than strings as text/html" in {
      readOk(format, Json.obj("content_types" -> Json.arr("text/plain", 1))).contentTypes mustBe Seq("text/html")
    }

    "ignore rules that are not an array" in {
      readOk(format, Json.obj("rules" -> Json.obj("pattern" -> "a", "replacement" -> "b"))).rules mustBe empty
    }

    "drop every rule when one of them is not an object" in {
      readOk(
        format,
        Json.obj("rules" -> Json.arr(Json.obj("pattern" -> "a", "replacement" -> "b"), "c"))
      ).rules mustBe empty
    }

    "drop a rule without pattern and keep the others" in {
      readOk(
        format,
        Json.obj(
          "rules" -> Json.arr(
            Json.obj("replacement" -> "b"),
            Json.obj("pattern" -> "c", "replacement" -> "d")
          )
        )
      ).rules mustBe Seq(RegexReplacementRule("c", "d"))
    }

    "drop a rule without replacement" in {
      readOk(format, Json.obj("rules" -> Json.arr(Json.obj("pattern" -> "a")))).rules mustBe empty
    }

    "drop a rule whose pattern is not a string" in {
      readOk(format, Json.obj("rules" -> Json.arr(Json.obj("pattern" -> 1, "replacement" -> "b")))).rules mustBe empty
    }

    "keep a rule whose flags are not a string, without flags" in {
      readOk(
        format,
        Json.obj("rules" -> Json.arr(Json.obj("pattern" -> "a", "replacement" -> "b", "flags" -> true)))
      ).rules mustBe Seq(RegexReplacementRule("a", "b", None))
    }

    "ignore a max body size written as a string" in {
      readOk(format, Json.obj("max_body_size" -> "1024")).maxBodySize mustBe None
    }

    "ignore an auto href prefix that is not a string" in {
      readOk(format, Json.obj("auto_href_prefix" -> true)).autoHrefPrefix mustBe None
    }

    "read a charset fallback that is not a string as utf-8" in {
      readOk(format, Json.obj("charset_fallback" -> 8)).charsetFallback mustBe Some("UTF-8")
    }

    "not read the camel case keys" in {
      readOk(
        format,
        Json.obj("contentTypes" -> Json.arr("text/plain"), "autoHrefPrefix" -> "/p", "maxBodySize" -> 1)
      ) mustBe RegexBodyRewriterConfig()
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe RegexBodyRewriterConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe RegexBodyRewriterConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("text/plain")) mustBe RegexBodyRewriterConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(Json.obj("pattern" -> "a", "replacement" -> "b"))) mustBe RegexBodyRewriterConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "list every written key in its config flow and schema" in {
      RegexBodyRewriterConfig.configFlow.toSet mustBe writtenKeys(format, full)
      RegexBodyRewriterConfig.configSchema.get.keys must contain allElementsOf RegexBodyRewriterConfig.configFlow
    }

    "describe the keys written for a rule in the rules form" in {
      val ruleSchema = (RegexBodyRewriterConfig.configSchema.get \ "rules").as[JsObject]
      (ruleSchema \ "flow").as[Seq[String]].toSet mustBe rule.json.as[JsObject].keys.toSet
      (ruleSchema \ "schema").as[JsObject].keys mustBe rule.json.as[JsObject].keys.toSet
    }

    "be the config flow of RegexResponseBodyRewriter" in {
      new RegexResponseBodyRewriter().configFlow mustBe RegexBodyRewriterConfig.configFlow
    }

    "be the config flow of RegexRequestBodyRewriter, without the auto href prefix" in {
      new RegexRequestBodyRewriter().configFlow mustBe RegexBodyRewriterConfig.configFlow.filterNot(
        _ == "auto_href_prefix"
      )
    }

    "be the default config of RegexResponseBodyRewriter" in {
      defaultConfigReadsBack(new RegexResponseBodyRewriter(), format) mustBe RegexBodyRewriterConfig()
    }

    "be the default config of RegexRequestBodyRewriter" in {
      defaultConfigReadsBack(new RegexRequestBodyRewriter(), format) mustBe RegexBodyRewriterConfig()
    }
  }

  "RegexHeadersRewriterConfig" should {

    val format = RegexHeadersRewriterConfig.format
    val rule   = RegexHeaderReplacementRule("Location", "^http://backend(:\\d+)?/", "https://${req.host}/", Some("i"))

    "have no rule by default" in {
      RegexHeadersRewriterConfig().rules mustBe empty
    }

    "write the default config" in {
      format.writes(RegexHeadersRewriterConfig()) mustBe Json.obj("rules" -> Json.arr())
    }

    "write a rule with its name, pattern, replacement and flags" in {
      format.writes(RegexHeadersRewriterConfig(Seq(rule))) mustBe Json.obj(
        "rules" -> Json.arr(
          Json.obj(
            "name"        -> "Location",
            "pattern"     -> "^http://backend(:\\d+)?/",
            "replacement" -> "https://${req.host}/",
            "flags"       -> "i"
          )
        )
      )
    }

    "write a rule without flags with null flags" in {
      RegexHeaderReplacementRule("X-Foo", "a", "b").json mustBe Json.obj(
        "name"        -> "X-Foo",
        "pattern"     -> "a",
        "replacement" -> "b",
        "flags"       -> JsNull
      )
    }

    "round trip the default config" in {
      roundTrip(format, RegexHeadersRewriterConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, RegexHeadersRewriterConfig(Seq(rule)))
    }

    "round trip a rule without flags" in {
      roundTrip(format, RegexHeadersRewriterConfig(Seq(RegexHeaderReplacementRule("X-Foo", "a", "b")))).rules.head.flags mustBe None
    }

    "round trip several rules and keep their order" in {
      val rules = Seq(
        RegexHeaderReplacementRule("X-B", "b", "2"),
        RegexHeaderReplacementRule("X-A", "a", "1", Some("ims")),
        RegexHeaderReplacementRule("X-B", "c", "3")
      )
      roundTrip(format, RegexHeadersRewriterConfig(rules)).rules mustBe rules
    }

    "round trip a rule with empty strings" in {
      roundTrip(format, RegexHeadersRewriterConfig(Seq(RegexHeaderReplacementRule("", "", "", Some("")))))
    }

    "drop a rule without name and keep the others" in {
      readOk(
        format,
        Json.obj(
          "rules" -> Json.arr(
            Json.obj("pattern" -> "a", "replacement" -> "b"),
            Json.obj("name" -> "X-Foo", "pattern" -> "c", "replacement" -> "d")
          )
        )
      ).rules mustBe Seq(RegexHeaderReplacementRule("X-Foo", "c", "d"))
    }

    "drop a rule without pattern" in {
      readOk(format, Json.obj("rules" -> Json.arr(Json.obj("name" -> "X-Foo", "replacement" -> "b")))).rules mustBe empty
    }

    "drop a rule without replacement" in {
      readOk(format, Json.obj("rules" -> Json.arr(Json.obj("name" -> "X-Foo", "pattern" -> "a")))).rules mustBe empty
    }

    "keep a rule whose flags are not a string, without flags" in {
      readOk(
        format,
        Json.obj("rules" -> Json.arr(Json.obj("name" -> "X-Foo", "pattern" -> "a", "replacement" -> "b", "flags" -> 1)))
      ).rules mustBe Seq(RegexHeaderReplacementRule("X-Foo", "a", "b", None))
    }

    "ignore rules that are not an array" in {
      readOk(
        format,
        Json.obj("rules" -> Json.obj("name" -> "X-Foo", "pattern" -> "a", "replacement" -> "b"))
      ) mustBe RegexHeadersRewriterConfig()
    }

    "drop every rule when one of them is not an object" in {
      readOk(
        format,
        Json.obj("rules" -> Json.arr(Json.obj("name" -> "X-Foo", "pattern" -> "a", "replacement" -> "b"), JsNull))
      ) mustBe RegexHeadersRewriterConfig()
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe RegexHeadersRewriterConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe RegexHeadersRewriterConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("rules")) mustBe RegexHeadersRewriterConfig()
    }

    "read a json array of rules as the default config" in {
      readOk(
        format,
        Json.arr(Json.obj("name" -> "X-Foo", "pattern" -> "a", "replacement" -> "b"))
      ) mustBe RegexHeadersRewriterConfig()
    }

    "ignore unknown keys" in {
      readOk(
        format,
        Json.obj("rules" -> Json.arr(Json.obj("name" -> "X-Foo", "pattern" -> "a", "replacement" -> "b")), "foo" -> 1)
      ) mustBe RegexHeadersRewriterConfig(Seq(RegexHeaderReplacementRule("X-Foo", "a", "b")))
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, RegexHeadersRewriterConfig(Seq(rule)))
    }

    "list rules in its config flow and schema" in {
      RegexHeadersRewriterConfig.configFlow mustBe Seq("rules")
      RegexHeadersRewriterConfig.configSchema.get.keys must contain("rules")
    }

    "describe the keys written for a rule in the rules form" in {
      val ruleSchema = (RegexHeadersRewriterConfig.configSchema.get \ "rules").as[JsObject]
      (ruleSchema \ "flow").as[Seq[String]].toSet mustBe rule.json.as[JsObject].keys.toSet
      (ruleSchema \ "schema").as[JsObject].keys mustBe rule.json.as[JsObject].keys.toSet
    }

    "be the config flow and schema of both header rewriters" in {
      new RegexRequestHeadersRewriter().configFlow mustBe RegexHeadersRewriterConfig.configFlow
      new RegexResponseHeadersRewriter().configSchema mustBe RegexHeadersRewriterConfig.configSchema
    }

    "be the default config of RegexRequestHeadersRewriter" in {
      defaultConfigReadsBack(new RegexRequestHeadersRewriter(), format) mustBe RegexHeadersRewriterConfig()
    }

    "be the default config of RegexResponseHeadersRewriter" in {
      defaultConfigReadsBack(new RegexResponseHeadersRewriter(), format) mustBe RegexHeadersRewriterConfig()
    }
  }
}
