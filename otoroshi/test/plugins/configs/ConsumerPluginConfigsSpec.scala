package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class ConsumerPluginConfigsSpec extends PluginConfigFormatSpec {

  "MandatoryConsumerPresetConfig" should {

    val format = MandatoryConsumerPresetConfig.format

    "have no auth. module and no mandatory tag by default" in {
      MandatoryConsumerPresetConfig.default mustBe MandatoryConsumerPresetConfig(None, Seq.empty)
      MandatoryConsumerPresetConfig.default mustBe MandatoryConsumerPresetConfig()
    }

    "write the default config with a null ref" in {
      format.writes(MandatoryConsumerPresetConfig.default) mustBe Json.obj(
        "ref"  -> JsNull,
        "tags" -> Json.arr()
      )
    }

    "write the auth. module id under ref and the tags under tags" in {
      format.writes(MandatoryConsumerPresetConfig(Some("auth_mod_1"), Seq("prod", "team-a"))) mustBe Json.obj(
        "ref"  -> "auth_mod_1",
        "tags" -> Json.arr("prod", "team-a")
      )
    }

    "round trip the default config" in {
      roundTrip(format, MandatoryConsumerPresetConfig.default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, MandatoryConsumerPresetConfig(Some("auth_mod_1"), Seq("prod", "team-a")))
    }

    "round trip a ref alone" in {
      roundTrip(format, MandatoryConsumerPresetConfig(ref = Some("auth_mod_1")))
    }

    "round trip tags alone" in {
      roundTrip(format, MandatoryConsumerPresetConfig(tags = Seq("prod")))
    }

    "round trip an empty ref as is" in {
      roundTrip(format, MandatoryConsumerPresetConfig(ref = Some(""))).ref mustBe Some("")
    }

    "keep the order and the duplicates of the tags" in {
      roundTrip(format, MandatoryConsumerPresetConfig(tags = Seq("b", "a", "b"))).tags mustBe Seq("b", "a", "b")
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe MandatoryConsumerPresetConfig.default
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe MandatoryConsumerPresetConfig.default
    }

    "read a json string as the default config" in {
      readOk(format, JsString("auth_mod_1")) mustBe MandatoryConsumerPresetConfig.default
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("prod")) mustBe MandatoryConsumerPresetConfig.default
    }

    "read a null ref as no auth. module" in {
      readOk(format, Json.obj("ref" -> JsNull, "tags" -> Json.arr("prod"))) mustBe MandatoryConsumerPresetConfig(
        tags = Seq("prod")
      )
    }

    "ignore a ref that is not a string" in {
      readOk(format, Json.obj("ref" -> 42)).ref mustBe None
    }

    "ignore tags written as a string" in {
      readOk(format, Json.obj("ref" -> "auth_mod_1", "tags" -> "prod")) mustBe MandatoryConsumerPresetConfig(
        ref = Some("auth_mod_1")
      )
    }

    "ignore tags holding something else than strings" in {
      readOk(format, Json.obj("tags" -> Json.arr("prod", true))).tags mustBe empty
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("ref" -> "auth_mod_1", "foo" -> "bar")) mustBe MandatoryConsumerPresetConfig(
        ref = Some("auth_mod_1")
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, MandatoryConsumerPresetConfig(Some("auth_mod_1"), Seq("prod")))
    }

    "list ref and tags in its config flow and schema" in {
      MandatoryConsumerPresetConfig.configFlow mustBe Seq("ref", "tags")
      MandatoryConsumerPresetConfig.configSchema.get.keys mustBe Set("ref", "tags")
    }

    "be the config flow and schema of MandatoryConsumerPreset" in {
      val plugin = new MandatoryConsumerPreset()
      plugin.configFlow mustBe MandatoryConsumerPresetConfig.configFlow
      plugin.configSchema mustBe MandatoryConsumerPresetConfig.configSchema
    }

    "be the default config of MandatoryConsumerPreset" in {
      defaultConfigReadsBack(new MandatoryConsumerPreset(), format) mustBe MandatoryConsumerPresetConfig.default
    }
  }
}
