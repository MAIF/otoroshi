package plugins.configs

import otoroshi.next.plugins.*
import otoroshi.plugins.mirror.MirroringPluginConfig
import play.api.libs.json.*
import play.api.test.FakeRequest

class MirrorPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgTrafficMirroringConfig" should {

    val format = NgTrafficMirroringConfig.format

    // the config wraps a MirroringPluginConfig, a plain class compared by reference, so configs are compared
    // through the json they write instead of with roundTrip
    def jsonRoundTrip(value: NgTrafficMirroringConfig): NgTrafficMirroringConfig = {
      val json = format.writes(value)
      val back = readOk(format, json)
      withClue(s"${Json.stringify(json)}: ") {
        format.writes(back) mustBe json
      }
      back
    }

    def config(json: JsObject): NgTrafficMirroringConfig = NgTrafficMirroringConfig(MirroringPluginConfig(json))

    val fullJson = Json.obj(
      "to"               -> "https://mirror.oto.tools",
      "enabled"          -> false,
      "capture_response" -> true,
      "generate_events"  -> true,
      "headers"          -> Json.obj("X-Mirror" -> "yes"),
      "salt"             -> "pepper",
      "percentage"       -> 12.5
    )

    "write the default config with all its keys" in {
      format.writes(NgTrafficMirroringConfig()) mustBe Json.obj(
        "to"               -> "https://foo.bar.dev",
        "enabled"          -> true,
        "capture_response" -> false,
        "generate_events"  -> false,
        "headers"          -> Json.obj(),
        "salt"             -> "none",
        "percentage"       -> 100.0
      )
    }

    "mirror every request to a placeholder target by default" in {
      val legacy = NgTrafficMirroringConfig().legacy
      legacy.to mustBe "https://foo.bar.dev"
      legacy.enabled mustBe true
      legacy.percentage mustBe 100.0
      legacy.salt mustBe "none"
    }

    "round trip the default config" in {
      jsonRoundTrip(NgTrafficMirroringConfig())
    }

    "round trip a config where every field is set" in {
      jsonRoundTrip(config(fullJson))
    }

    "write a config where every field is set as it was given" in {
      format.writes(config(fullJson)) mustBe fullJson
    }

    "read every field of a config where every field is set" in {
      val legacy = readOk(format, fullJson).legacy
      legacy.to mustBe "https://mirror.oto.tools"
      legacy.enabled mustBe false
      legacy.shouldCaptureResponse mustBe true
      legacy.generateEvents mustBe true
      legacy.headers mustBe Map("X-Mirror" -> "yes")
      legacy.salt mustBe "pepper"
      legacy.percentage mustBe 12.5
    }

    "round trip several headers" in {
      val back = jsonRoundTrip(config(Json.obj("to" -> "https://m", "headers" -> Json.obj("A" -> "1", "B" -> "2"))))
      back.legacy.headers mustBe Map("A" -> "1", "B" -> "2")
    }

    "round trip a zero percentage" in {
      jsonRoundTrip(config(Json.obj("to" -> "https://m", "percentage" -> 0))).legacy.percentage mustBe 0.0
    }

    "read the target alone and default the rest" in {
      format.writes(readOk(format, Json.obj("to" -> "https://m"))) mustBe Json.obj(
        "to"               -> "https://m",
        "enabled"          -> true,
        "capture_response" -> false,
        "generate_events"  -> false,
        "headers"          -> Json.obj(),
        "salt"             -> "none",
        "percentage"       -> 100.0
      )
    }

    "read an integer percentage" in {
      readOk(format, Json.obj("to" -> "https://m", "percentage" -> 25)).legacy.percentage mustBe 25.0
    }

    "ignore a percentage written as a string" in {
      readOk(format, Json.obj("to" -> "https://m", "percentage" -> "25")).legacy.percentage mustBe 100.0
    }

    "ignore an enabled flag written as a string" in {
      readOk(format, Json.obj("to" -> "https://m", "enabled" -> "false")).legacy.enabled mustBe true
    }

    "ignore headers holding a non string value" in {
      readOk(format, Json.obj("to" -> "https://m", "headers" -> Json.obj("A" -> 1))).legacy.headers mustBe Map.empty
    }

    "ignore a salt that is not a string" in {
      readOk(format, Json.obj("to" -> "https://m", "salt" -> 42)).legacy.salt mustBe "none"
    }

    "not read the camel case keys" in {
      val legacy = readOk(
        format,
        Json.obj("to" -> "https://m", "captureResponse" -> true, "generateEvents" -> true)
      ).legacy
      legacy.shouldCaptureResponse mustBe false
      legacy.generateEvents mustBe false
    }

    "read an empty object but fail to write it back because the target is mandatory" in {
      val back = readOk(format, Json.obj())
      a[JsResultException] must be thrownBy format.writes(back)
    }

    "read a json null but fail to write it back" in {
      val back = readOk(format, JsNull)
      a[JsResultException] must be thrownBy format.writes(back)
    }

    "read a json string but fail to write it back" in {
      val back = readOk(format, JsString("https://m"))
      a[JsResultException] must be thrownBy back.legacy.to
    }

    "read a target that is not a string but fail to write it back" in {
      val back = readOk(format, Json.obj("to" -> 42))
      a[JsResultException] must be thrownBy format.writes(back)
    }

    "keep the unknown keys in the wrapped json but not write them" in {
      val back = readOk(format, Json.obj("to" -> "https://m", "foo" -> "bar"))
      (back.legacy.conf \ "foo").as[String] mustBe "bar"
      writtenKeys(format, back) must not contain "foo"
    }

    "not mirror anything when disabled" in {
      config(Json.obj("to" -> "https://m", "enabled" -> false)).legacy.shouldBeMirrored("route", FakeRequest()) mustBe
        false
    }

    "not mirror anything with a zero or negative percentage" in {
      config(Json.obj("to" -> "https://m", "percentage" -> 0)).legacy.shouldBeMirrored("route", FakeRequest()) mustBe
        false
      config(Json.obj("to" -> "https://m", "percentage" -> -5)).legacy.shouldBeMirrored("route", FakeRequest()) mustBe
        false
    }

    "mirror every request with a percentage of 100 or more" in {
      config(Json.obj("to" -> "https://m", "percentage" -> 100)).legacy.shouldBeMirrored("route", FakeRequest()) mustBe
        true
      config(Json.obj("to" -> "https://m", "percentage" -> 150)).legacy.shouldBeMirrored("route", FakeRequest()) mustBe
        true
    }

    "always take the same decision for the same request" in {
      val legacy  = config(Json.obj("to" -> "https://m", "percentage" -> 50, "salt" -> "s")).legacy
      val request = FakeRequest()
      val first   = legacy.shouldBeMirrored("route", request)
      legacy.shouldBeMirrored("route", request) mustBe first
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, config(fullJson))
    }

    "be the default config of NgTrafficMirroring" in {
      val plugin = new NgTrafficMirroring()
      val back   = readOk(format, plugin.defaultConfig.get)
      format.writes(back) mustBe plugin.defaultConfigObject.get.json
    }
  }
}
