package plugins.configs

import otoroshi.next.plugins.*
import otoroshi.utils.JsonPathValidator
import play.api.libs.json.*

class ConditionalPluginConfigsSpec extends PluginConfigFormatSpec {

  "ConditionalEvaluationMode" should {

    "name each mode in snake case" in {
      ConditionalEvaluationMode.all.map(_.name) mustBe Seq("per_phase", "once", "latch")
    }

    "write a mode as its name" in {
      ConditionalEvaluationMode.Latch.json mustBe JsString("latch")
    }

    "parse each mode from its name" in {
      ConditionalEvaluationMode.all.map(m => ConditionalEvaluationMode(m.name)) mustBe ConditionalEvaluationMode.all
        .map(Some(_))
    }

    "parse the dashed per-phase alias" in {
      ConditionalEvaluationMode("per-phase") mustBe Some(ConditionalEvaluationMode.PerPhase)
    }

    "parse a mode whatever its case and surrounding spaces" in {
      ConditionalEvaluationMode("  ONCE ") mustBe Some(ConditionalEvaluationMode.Once)
      ConditionalEvaluationMode("Per_Phase") mustBe Some(ConditionalEvaluationMode.PerPhase)
    }

    "not parse an unknown mode" in {
      ConditionalEvaluationMode("always") mustBe None
      ConditionalEvaluationMode("") mustBe None
    }
  }

  "ConditionalPluginConfig" should {

    val format = ConditionalPluginConfig.format

    val predicates = Seq(
      JsonPathValidator("$.apikey.metadata.foo", JsString("Contains(bar)")),
      JsonPathValidator("$.request.method", JsString("GET"), Some("not a GET"))
    )

    val full = ConditionalPluginConfig(
      predicates = predicates,
      invert = true,
      evaluationMode = ConditionalEvaluationMode.Once,
      plugin = Some("cp:otoroshi.next.plugins.AdditionalHeadersIn"),
      pluginConfig = Json.obj("headers" -> Json.obj("X-Foo" -> "bar"))
    )

    "default to an always matching wrapper of no plugin" in {
      val config = ConditionalPluginConfig()
      config.predicates mustBe empty
      config.invert mustBe false
      config.evaluationMode mustBe ConditionalEvaluationMode.PerPhase
      config.plugin mustBe None
      config.pluginConfig mustBe Json.obj()
    }

    "write the default config with a null plugin_id" in {
      format.writes(ConditionalPluginConfig()) mustBe Json.obj(
        "predicates"      -> Json.arr(),
        "invert"          -> false,
        "evaluation_mode" -> "per_phase",
        "plugin_id"       -> JsNull,
        "plugin_config"   -> Json.obj()
      )
    }

    "write every field" in {
      format.writes(full) mustBe Json.obj(
        "predicates"      -> JsArray(predicates.map(JsonPathValidator.format.writes)),
        "invert"          -> true,
        "evaluation_mode" -> "once",
        "plugin_id"       -> "cp:otoroshi.next.plugins.AdditionalHeadersIn",
        "plugin_config"   -> Json.obj("headers" -> Json.obj("X-Foo" -> "bar"))
      )
    }

    "write the predicates as json path validators" in {
      (format.writes(full) \ "predicates" \ 0).as[JsObject] mustBe Json.obj(
        "kind"  -> "json-path-validator",
        "path"  -> "$.apikey.metadata.foo",
        "value" -> "Contains(bar)",
        "error" -> JsNull
      )
    }

    "round trip the default config" in {
      roundTrip(format, ConditionalPluginConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip the per phase mode" in {
      roundTrip(format, full.copy(evaluationMode = ConditionalEvaluationMode.PerPhase))
    }

    "round trip the latch mode" in {
      roundTrip(format, full.copy(evaluationMode = ConditionalEvaluationMode.Latch)).evaluationMode mustBe
        ConditionalEvaluationMode.Latch
    }

    "round trip predicates on non string values" in {
      roundTrip(
        format,
        ConditionalPluginConfig(
          predicates = Seq(
            JsonPathValidator("$.count", JsNumber(3)),
            JsonPathValidator("$.enabled", JsBoolean(true)),
            JsonPathValidator("$.user", JsNull),
            JsonPathValidator("$.tags", Json.arr("a", "b"))
          )
        )
      )
    }

    "keep the order of the predicates" in {
      roundTrip(format, full.copy(predicates = predicates.reverse)).predicates.map(_.path) mustBe Seq(
        "$.request.method",
        "$.apikey.metadata.foo"
      )
    }

    "round trip a nested conditional plugin config" in {
      val inner = ConditionalPluginConfig(
        predicates = predicates.take(1),
        plugin = Some("cp:otoroshi.next.plugins.OverrideHost")
      )
      roundTrip(
        format,
        ConditionalPluginConfig(
          plugin = Some("cp:otoroshi.next.plugins.ConditionalPlugin"),
          pluginConfig = written(format, inner)
        )
      )
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe ConditionalPluginConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe ConditionalPluginConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("cp:foo")) mustBe ConditionalPluginConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr()) mustBe ConditionalPluginConfig()
    }

    "read a plugin_id alone and default the other fields" in {
      readOk(format, Json.obj("plugin_id" -> "cp:foo")) mustBe ConditionalPluginConfig(plugin = Some("cp:foo"))
    }

    "read invert alone and default the other fields" in {
      readOk(format, Json.obj("invert" -> true)) mustBe ConditionalPluginConfig(invert = true)
    }

    "read the dashed per-phase alias and write it back with an underscore" in {
      val config = readOk(format, Json.obj("evaluation_mode" -> "per-phase"))
      config.evaluationMode mustBe ConditionalEvaluationMode.PerPhase
      (format.writes(config) \ "evaluation_mode").as[String] mustBe "per_phase"
    }

    "read an upper case evaluation mode" in {
      readOk(format, Json.obj("evaluation_mode" -> "LATCH")).evaluationMode mustBe ConditionalEvaluationMode.Latch
    }

    "fall back to per phase on an unknown evaluation mode" in {
      readOk(format, Json.obj("evaluation_mode" -> "sometimes")).evaluationMode mustBe
        ConditionalEvaluationMode.PerPhase
    }

    "fall back to per phase on an evaluation mode that is not a string" in {
      readOk(format, Json.obj("evaluation_mode" -> 1)).evaluationMode mustBe ConditionalEvaluationMode.PerPhase
    }

    "not read the camel case evaluationMode key" in {
      readOk(format, Json.obj("evaluationMode" -> "once")).evaluationMode mustBe ConditionalEvaluationMode.PerPhase
    }

    "read a blank plugin_id as no plugin" in {
      readOk(format, Json.obj("plugin_id" -> "   ")).plugin mustBe None
    }

    "read an empty plugin_id as no plugin" in {
      readOk(format, Json.obj("plugin_id" -> "")).plugin mustBe None
    }

    "ignore a plugin_id that is not a string" in {
      readOk(format, Json.obj("plugin_id" -> 42)).plugin mustBe None
    }

    "not read the plugin key the case class is named after" in {
      readOk(format, Json.obj("plugin" -> "cp:foo")).plugin mustBe None
    }

    "ignore a plugin_config that is not an object" in {
      readOk(format, Json.obj("plugin_config" -> Json.arr(1, 2))).pluginConfig mustBe Json.obj()
    }

    "ignore an invert flag written as a string" in {
      readOk(format, Json.obj("invert" -> "true")).invert mustBe false
    }

    "ignore predicates that are not an array" in {
      readOk(format, Json.obj("predicates" -> Json.obj("path" -> "$.foo", "value" -> "bar"))).predicates mustBe empty
    }

    "drop the predicates that cannot be read and keep the others" in {
      val json = Json.obj(
        "predicates" -> Json.arr(
          Json.obj("path" -> "$.foo", "value" -> "bar"),
          Json.obj("value" -> "no path"),
          Json.obj("path" -> "$.no.value"),
          "not an object"
        )
      )
      readOk(format, json).predicates mustBe Seq(JsonPathValidator("$.foo", JsString("bar")))
    }

    "read a predicate without kind" in {
      val json = Json.obj("predicates" -> Json.arr(Json.obj("path" -> "$.foo", "value" -> 1)))
      readOk(format, json).predicates mustBe Seq(JsonPathValidator("$.foo", JsNumber(1)))
    }

    "read a blank predicate error as no error" in {
      val json = Json.obj("predicates" -> Json.arr(Json.obj("path" -> "$.foo", "value" -> "bar", "error" -> "  ")))
      readOk(format, json).predicates.head.error mustBe None
    }

    "derive its state key from its json" in {
      full.stateKey mustBe Json.stringify(format.writes(full))
    }

    "give the same state key to equal configs" in {
      full.copy().stateKey mustBe full.stateKey
    }

    "give different state keys to configs that differ" in {
      full.copy(invert = false).stateKey must not be full.stateKey
      full.copy(evaluationMode = ConditionalEvaluationMode.Latch).stateKey must not be full.stateKey
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "list in its config schema every field of its config flow" in {
      ConditionalPluginConfig.configSchema.get.keys must contain allElementsOf ConditionalPluginConfig.configFlow
    }

    "list the json keys of the format in its config flow" in {
      ConditionalPluginConfig.configFlow.toSet mustBe writtenKeys(format, full)
    }

    "offer every evaluation mode in its config schema" in {
      val options =
        (ConditionalPluginConfig.configSchema.get \ "evaluation_mode" \ "props" \ "options").as[Seq[JsObject]]
      options.map(o => (o \ "value").as[String]) mustBe ConditionalEvaluationMode.all.map(_.name)
    }

    "give its config flow and schema to ConditionalPlugin" in {
      val plugin = new ConditionalPlugin()
      plugin.configFlow mustBe ConditionalPluginConfig.configFlow
      plugin.configSchema mustBe ConditionalPluginConfig.configSchema
    }

    "be the default config of ConditionalPlugin" in {
      defaultConfigReadsBack(new ConditionalPlugin(), format) mustBe ConditionalPluginConfig()
    }
  }
}
