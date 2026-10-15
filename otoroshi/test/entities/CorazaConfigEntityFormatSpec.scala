package entities

import otoroshi.models.EntityLocation
import otoroshi.wasm.proxywasm.CorazaWafConfig
import play.api.libs.json.*

class CorazaConfigEntityFormatSpec extends EntityFormatSpec {

  private val defaultDirectives = Seq(
    "Include @recommended-conf",
    "Include @crs-setup-conf",
    "Include @owasp_crs/*.conf",
    "SecRuleEngine On"
  )

  private val customDirectives = Seq(
    "SecRuleEngine DetectionOnly",
    "SecRule REQUEST_URI \"@streq /admin\" \"id:101,phase:1,t:lowercase,deny\""
  )

  private val minimal = CorazaWafConfig(
    location = EntityLocation(),
    id = "coraza-waf-config-1",
    name = "WAF 1",
    description = "",
    tags = Seq.empty,
    metadata = Map.empty,
    inspectInputBody = true,
    inspectOutputBody = true,
    poolCapacity = 2
  )

  private val full = CorazaWafConfig(
    location = customLocation,
    id = "coraza-waf-config-1",
    name = "WAF 1",
    description = "the waf of team 1",
    tags = Seq("waf", "owasp"),
    metadata = Map("owner" -> "secops", "level" -> "paranoid"),
    inspectInputBody = false,
    inspectOutputBody = false,
    includeOwaspCRS = false,
    isBlockingMode = false,
    directives = customDirectives,
    poolCapacity = 8
  )

  /** the json of a config with the given fields, the rest taken from the minimal config */
  private def minimalJson(fields: (String, Json.JsValueWrapper)*): JsObject =
    written(CorazaWafConfig.format, minimal) ++ Json.obj(fields*)

  /** a legacy config holding its directives in config.directives_map.default */
  private def legacyConfig(directives: JsValue): JsObject =
    Json.obj("config" -> Json.obj("directives_map" -> Json.obj("default" -> directives)))

  "CorazaWafConfig" should {

    val format = CorazaWafConfig.format

    "include the owasp crs, block and use the recommended directives by default" in {
      minimal.includeOwaspCRS mustBe true
      minimal.isBlockingMode mustBe true
      minimal.directives mustBe defaultDirectives
    }

    "write a minimal config" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"              -> defaultLocationJson,
        "id"                -> "coraza-waf-config-1",
        "name"              -> "WAF 1",
        "description"       -> "",
        "metadata"          -> Json.obj(),
        "tags"              -> Json.arr(),
        "inspect_in_body"   -> true,
        "inspect_out_body"  -> true,
        "is_blocking_mode"  -> true,
        "include_owasp_crs" -> true,
        "directives"        -> Json.toJson(defaultDirectives),
        "pool_capacity"     -> 2
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "_loc"              -> customLocationJson,
        "id"                -> "coraza-waf-config-1",
        "name"              -> "WAF 1",
        "description"       -> "the waf of team 1",
        "metadata"          -> Json.obj("owner" -> "secops", "level" -> "paranoid"),
        "tags"              -> Json.arr("waf", "owasp"),
        "inspect_in_body"   -> false,
        "inspect_out_body"  -> false,
        "is_blocking_mode"  -> false,
        "include_owasp_crs" -> false,
        "directives"        -> Json.toJson(customDirectives),
        "pool_capacity"     -> 8
      )
    }

    "round trip a minimal config" in {
      roundTrip(format, minimal)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a config without directive" in {
      roundTrip(format, minimal.copy(directives = Seq.empty)).directives mustBe empty
    }

    "keep the order of the directives" in {
      roundTrip(format, minimal.copy(directives = defaultDirectives.reverse)).directives mustBe defaultDirectives.reverse
    }

    "round trip a config in detection mode only" in {
      val back = roundTrip(format, minimal.copy(isBlockingMode = false))
      back.isBlockingMode mustBe false
      back.includeOwaspCRS mustBe true
    }

    "round trip a config inspecting the input body only" in {
      val back = roundTrip(format, minimal.copy(inspectOutputBody = false))
      back.inspectInputBody mustBe true
      back.inspectOutputBody mustBe false
    }

    "round trip a config inspecting the output body only" in {
      val back = roundTrip(format, minimal.copy(inspectInputBody = false))
      back.inspectInputBody mustBe false
      back.inspectOutputBody mustBe true
    }

    "round trip a config without the owasp crs" in {
      roundTrip(format, minimal.copy(includeOwaspCRS = false)).includeOwaspCRS mustBe false
    }

    "round trip a directive with quotes, new lines and unicode" in {
      val directive = "SecRule ARGS \"@rx (?i)<script>\" \\\n  \"id:1,deny,msg:'xss é'\""
      roundTrip(format, minimal.copy(directives = Seq(directive))).directives mustBe Seq(directive)
    }

    "round trip a custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read a config without _loc in the default location" in {
      readOk(format, minimalJson() - "_loc").location mustBe EntityLocation()
    }

    "read a lone id, name and description with the defaults for the rest" in {
      readOk(format, Json.obj("id" -> "c", "name" -> "C", "description" -> "")) mustBe CorazaWafConfig(
        location = EntityLocation(),
        id = "c",
        name = "C",
        description = "",
        tags = Seq.empty,
        metadata = Map.empty,
        inspectInputBody = true,
        inspectOutputBody = true,
        includeOwaspCRS = true,
        isBlockingMode = true,
        directives = Seq.empty,
        poolCapacity = 2
      )
    }

    "read missing directives as no directive, unlike the default of the config" in {
      readOk(format, minimalJson() - "directives").directives mustBe empty
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a config without id" in {
      readError(format, minimalJson() - "id")
    }

    "not read a config without name" in {
      readError(format, minimalJson() - "name")
    }

    "not read a config without description" in {
      readError(format, minimalJson() - "description")
    }

    "not read an id that is not a string" in {
      readError(format, minimalJson("id" -> 1))
    }

    "not read a config holding a directive that is not a string" in {
      readError(format, minimalJson("directives" -> Json.arr("SecRuleEngine On", 42)))
    }

    "read directives given as a single string as no directive" in {
      readOk(format, minimalJson("directives" -> "SecRuleEngine On")).directives mustBe empty
    }

    "read the inspection flags as enabled when missing" in {
      val back = readOk(format, written(format, full) - "inspect_in_body" - "inspect_out_body")
      back.inspectInputBody mustBe true
      back.inspectOutputBody mustBe true
    }

    "read a missing blocking mode as blocking" in {
      readOk(format, written(format, full) - "is_blocking_mode").isBlockingMode mustBe true
    }

    "read a missing owasp crs flag as including the owasp crs" in {
      readOk(format, written(format, full) - "include_owasp_crs").includeOwaspCRS mustBe true
    }

    "read a missing pool capacity as 2" in {
      readOk(format, written(format, full) - "pool_capacity").poolCapacity mustBe 2
    }

    "read flags written as strings as their defaults" in {
      val back = readOk(format, written(format, full) ++ Json.obj("inspect_in_body" -> "false", "is_blocking_mode" -> "false"))
      back.inspectInputBody mustBe true
      back.isBlockingMode mustBe true
    }

    "read a pool capacity written as a string as 2" in {
      readOk(format, minimalJson("pool_capacity" -> "8")).poolCapacity mustBe 2
    }

    "read a decimal pool capacity as 2" in {
      readOk(format, minimalJson("pool_capacity" -> 4.5)).poolCapacity mustBe 2
    }

    "not read the camel case keys of the fields" in {
      val back = readOk(
        format,
        Json.obj(
          "id"                -> "c",
          "name"              -> "C",
          "description"       -> "",
          "inspectInputBody"  -> false,
          "isBlockingMode"    -> false,
          "includeOwaspCRS"   -> false,
          "poolCapacity"      -> 8
        )
      )
      back.inspectInputBody mustBe true
      back.isBlockingMode mustBe true
      back.includeOwaspCRS mustBe true
      back.poolCapacity mustBe 2
    }

    "read the directives of a legacy config" in {
      readOk(format, (minimalJson() - "directives") ++ legacyConfig(Json.arr("SecRuleEngine On"))).directives mustBe Seq(
        "SecRuleEngine On"
      )
    }

    "read the directives before the ones of a legacy config" in {
      readOk(format, minimalJson("directives" -> Json.toJson(customDirectives)) ++ legacyConfig(Json.arr("SecRuleEngine On"))).directives mustBe
        customDirectives
    }

    "read an empty directives array before the ones of a legacy config" in {
      readOk(format, minimalJson("directives" -> Json.arr()) ++ legacyConfig(Json.arr("SecRuleEngine On"))).directives mustBe empty
    }

    "read the legacy directives when the directives are not an array" in {
      readOk(format, minimalJson("directives" -> "SecRuleEngine Off") ++ legacyConfig(Json.arr("SecRuleEngine On"))).directives mustBe Seq(
        "SecRuleEngine On"
      )
    }

    "read legacy directives that are not an array as no directive" in {
      readOk(format, (minimalJson() - "directives") ++ legacyConfig(JsString("SecRuleEngine On"))).directives mustBe empty
    }

    "read a legacy config without default directives as no directive" in {
      readOk(
        format,
        (minimalJson() - "directives") ++ Json.obj("config" -> Json.obj("directives_map" -> Json.obj("other" -> Json.arr("SecRuleEngine On"))))
      ).directives mustBe empty
    }

    "not read a legacy config holding a directive that is not a string" in {
      readError(format, (minimalJson() - "directives") ++ legacyConfig(Json.arr(true)))
    }

    "not write back the legacy config" in {
      val back = readOk(format, (minimalJson() - "directives") ++ legacyConfig(Json.arr("SecRuleEngine On")))
      writtenKeys(format, back) must not contain "config"
      (written(format, back) \ "directives").get mustBe Json.arr("SecRuleEngine On")
    }

    "read a config without tags nor metadata with empty ones" in {
      val back = readOk(format, written(format, full) - "tags" - "metadata")
      back.tags mustBe empty
      back.metadata mustBe empty
    }

    "ignore metadata holding a non string value" in {
      readOk(format, minimalJson("metadata" -> Json.obj("a" -> "b", "n" -> 1))).metadata mustBe Map.empty
    }

    "ignore tags given as a single string" in {
      readOk(format, minimalJson("tags" -> "waf")).tags mustBe empty
    }

    "ignore unknown keys" in {
      readOk(format, minimalJson("foo" -> "bar")) mustBe minimal
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "coraza-waf-config-1", "WAF 1", "the waf of team 1", Seq("waf", "owasp"), Map("owner" -> "secops", "level" -> "paranoid"))
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "build a template inspecting the bodies with the owasp crs and no directive" in {
      val template = CorazaWafConfig.template()
      template.id must startWith("coraza-waf-config_")
      template.location mustBe EntityLocation.default
      template.inspectInputBody mustBe true
      template.inspectOutputBody mustBe true
      template.includeOwaspCRS mustBe true
      template.isBlockingMode mustBe true
      template.directives mustBe empty
      template.poolCapacity mustBe 2
    }

    "build templates with distinct ids" in {
      CorazaWafConfig.template().id must not be CorazaWafConfig.template().id
    }

    "round trip its template" in {
      roundTrip(format, CorazaWafConfig.template())
    }
  }
}
