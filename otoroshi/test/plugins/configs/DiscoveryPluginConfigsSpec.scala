package plugins.configs

import otoroshi.next.plugins.*
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import scala.concurrent.duration.*

class DiscoveryPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgDiscoverySelfRegistrationConfig" should {

    val format = NgDiscoverySelfRegistrationConfig.format

    val raw = Json.obj(
      "hosts"           -> Json.arr("discovery.oto.tools", "registry.oto.tools"),
      "targetTemplate"  -> Json.obj("scheme" -> "https", "weight" -> 2),
      "registrationTtl" -> 30000
    )

    val full = NgDiscoverySelfRegistrationConfig(raw)

    "default to an empty raw object" in {
      NgDiscoverySelfRegistrationConfig().raw mustBe Json.obj()
    }

    "write the default config as an empty object" in {
      format.writes(NgDiscoverySelfRegistrationConfig()) mustBe Json.obj()
    }

    "write its raw json as is" in {
      format.writes(full) mustBe raw
    }

    "round trip the default config" in {
      roundTrip(format, NgDiscoverySelfRegistrationConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip the snake case keys of the ui form" in {
      roundTrip(
        format,
        NgDiscoverySelfRegistrationConfig(
          Json.obj("hosts" -> Json.arr("a.oto.tools"), "target_template" -> Json.obj(), "registration_ttl" -> 1000)
        )
      )
    }

    "keep the unknown keys" in {
      roundTrip(format, NgDiscoverySelfRegistrationConfig(raw ++ Json.obj("foo" -> "bar"))).raw mustBe
        (raw ++ Json.obj("foo" -> "bar"))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgDiscoverySelfRegistrationConfig()
    }

    "keep a json null as its raw value" in {
      val config = readOk(format, JsNull)
      config.raw mustBe JsNull
      format.writes(config) mustBe JsNull
    }

    "keep a json string as its raw value" in {
      readOk(format, JsString("discovery")).raw mustBe JsString("discovery")
    }

    "have no host by default" in {
      NgDiscoverySelfRegistrationConfig().hosts mustBe empty
    }

    "read the hosts and keep their order" in {
      full.hosts mustBe Seq("discovery.oto.tools", "registry.oto.tools")
    }

    "ignore hosts written as a single string" in {
      NgDiscoverySelfRegistrationConfig(Json.obj("hosts" -> "discovery.oto.tools")).hosts mustBe empty
    }

    "ignore hosts holding something else than strings" in {
      NgDiscoverySelfRegistrationConfig(Json.obj("hosts" -> Json.arr("a.oto.tools", 1))).hosts mustBe empty
    }

    "have no host when its raw value is not an object" in {
      NgDiscoverySelfRegistrationConfig(JsNull).hosts mustBe empty
    }

    "have an empty target template by default" in {
      NgDiscoverySelfRegistrationConfig().targetTemplate mustBe Json.obj()
    }

    "read the camel case target template" in {
      full.targetTemplate mustBe Json.obj("scheme" -> "https", "weight" -> 2)
    }

    "read the snake case target template" in {
      val config = NgDiscoverySelfRegistrationConfig(Json.obj("target_template" -> Json.obj("scheme" -> "http")))
      config.targetTemplate mustBe Json.obj("scheme" -> "http")
    }

    "prefer the camel case target template over the snake case one" in {
      NgDiscoverySelfRegistrationConfig(
        Json.obj("targetTemplate" -> Json.obj("a" -> 1), "target_template" -> Json.obj("b" -> 2))
      ).targetTemplate mustBe Json.obj("a" -> 1)
    }

    "fall back to the snake case target template when the camel case one is not an object" in {
      NgDiscoverySelfRegistrationConfig(
        Json.obj("targetTemplate" -> "nope", "target_template" -> Json.obj("b" -> 2))
      ).targetTemplate mustBe Json.obj("b" -> 2)
    }

    "ignore a target template that is not an object" in {
      NgDiscoverySelfRegistrationConfig(Json.obj("targetTemplate" -> Json.arr())).targetTemplate mustBe Json.obj()
    }

    "have a one minute registration ttl by default" in {
      NgDiscoverySelfRegistrationConfig().registrationTtl mustBe 60.seconds
    }

    "read the camel case registration ttl in milliseconds" in {
      full.registrationTtl mustBe 30.seconds
    }

    "read the snake case registration ttl in milliseconds" in {
      NgDiscoverySelfRegistrationConfig(Json.obj("registration_ttl" -> 1500)).registrationTtl mustBe 1500.millis
    }

    "prefer the camel case registration ttl over the snake case one" in {
      NgDiscoverySelfRegistrationConfig(
        Json.obj("registrationTtl" -> 1000, "registration_ttl" -> 2000)
      ).registrationTtl mustBe 1.second
    }

    "ignore a registration ttl written as a string" in {
      NgDiscoverySelfRegistrationConfig(Json.obj("registrationTtl" -> "1000")).registrationTtl mustBe 60.seconds
    }

    "hand its raw json to the legacy config" in {
      full.legacy.raw mustBe raw
    }

    "keep the camel case values in the legacy config" in {
      val legacy = full.legacy
      legacy.hosts mustBe full.hosts
      legacy.targetTemplate mustBe full.targetTemplate
      legacy.registrationTtl mustBe full.registrationTtl
    }

    "hand the snake case target template of the ui form to the legacy config" in {
      val config = NgDiscoverySelfRegistrationConfig(Json.obj("target_template" -> Json.obj("scheme" -> "https")))
      config.legacy.targetTemplate mustBe Json.obj("scheme" -> "https")
    }

    "hand the snake case registration ttl of the ui form to the legacy config" in {
      val config = NgDiscoverySelfRegistrationConfig(Json.obj("registration_ttl" -> 5000))
      config.legacy.registrationTtl mustBe 5.seconds
    }

    "hand the hosts of the ui form to the legacy config" in {
      val config = NgDiscoverySelfRegistrationConfig(Json.obj("hosts" -> Json.arr("a.oto.tools", "b.oto.tools")))
      config.legacy.hosts mustBe Seq("a.oto.tools", "b.oto.tools")
    }

    "hand every snake case value of the ui form to the legacy config" in {
      val config = NgDiscoverySelfRegistrationConfig(
        Json.obj(
          "hosts"            -> Json.arr("a.oto.tools"),
          "target_template"  -> Json.obj("weight" -> 3),
          "registration_ttl" -> 1000
        )
      )
      val legacy = config.legacy
      legacy.hosts mustBe config.hosts
      legacy.targetTemplate mustBe config.targetTemplate
      legacy.registrationTtl mustBe config.registrationTtl
    }

    "hand the same values to the legacy config whatever the key case" in {
      val camel = NgDiscoverySelfRegistrationConfig(raw).legacy
      val snake = NgDiscoverySelfRegistrationConfig(
        Json.obj(
          "hosts"            -> Json.arr("discovery.oto.tools", "registry.oto.tools"),
          "target_template"  -> Json.obj("scheme" -> "https", "weight" -> 2),
          "registration_ttl" -> 30000
        )
      ).legacy
      snake.hosts mustBe camel.hosts
      snake.targetTemplate mustBe camel.targetTemplate
      snake.registrationTtl mustBe camel.registrationTtl
    }

    "hand an empty template and a one minute ttl to the legacy config by default" in {
      val legacy = NgDiscoverySelfRegistrationConfig().legacy
      legacy.hosts mustBe empty
      legacy.targetTemplate mustBe Json.obj()
      legacy.registrationTtl mustBe 60.seconds
    }

    "keep the unknown keys in the legacy config" in {
      NgDiscoverySelfRegistrationConfig(Json.obj("foo" -> "bar")).legacy.raw.select("foo").asOpt[String] mustBe Some(
        "bar"
      )
    }

    "hand a legacy config to plugins whose raw value is not an object" in {
      val legacy = NgDiscoverySelfRegistrationConfig(JsString("foo")).legacy
      legacy.hosts mustBe empty
      legacy.registrationTtl mustBe 60.seconds
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of NgDiscoverySelfRegistrationSink" in {
      defaultConfigReadsBack(new NgDiscoverySelfRegistrationSink(), format) mustBe NgDiscoverySelfRegistrationConfig()
    }

    "be the default config of NgDiscoverySelfRegistrationTransformer" in {
      defaultConfigReadsBack(new NgDiscoverySelfRegistrationTransformer(), format) mustBe
        NgDiscoverySelfRegistrationConfig()
    }

    "be the default config of NgDiscoveryTargetsSelector" in {
      defaultConfigReadsBack(new NgDiscoveryTargetsSelector(), format) mustBe NgDiscoverySelfRegistrationConfig()
    }
  }
}
