package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class JsonSchemaPluginConfigsSpec extends PluginConfigFormatSpec {

  "JsonSchemaValidatorConfig" should {

    val format = JsonSchemaValidatorConfig.format

    val draft202012 = "https://json-schema.org/draft/2020-12/schema"
    val draft07     = "http://json-schema.org/draft-07/schema#"

    val defaultSchema = "{ \"type\": \"object\", \"required\": [\"name\"] }"

    "have no schema, the 2020-12 draft and fail on errors in the case class default" in {
      JsonSchemaValidatorConfig() mustBe JsonSchemaValidatorConfig(None, draft202012, failOnValidationError = true)
    }

    "require a name in the default config of the plugins" in {
      JsonSchemaValidatorConfig.default mustBe JsonSchemaValidatorConfig(schema = Some(defaultSchema))
    }

    "write the default config" in {
      format.writes(JsonSchemaValidatorConfig.default) mustBe Json.obj(
        "schema"                   -> defaultSchema,
        "specification"            -> draft202012,
        "fail_on_validation_error" -> true
      )
    }

    "write a missing schema as null" in {
      format.writes(JsonSchemaValidatorConfig()) mustBe Json.obj(
        "schema"                   -> JsNull,
        "specification"            -> draft202012,
        "fail_on_validation_error" -> true
      )
    }

    "round trip the default config" in {
      roundTrip(format, JsonSchemaValidatorConfig.default)
    }

    "round trip a config without schema" in {
      roundTrip(format, JsonSchemaValidatorConfig()).schema mustBe None
    }

    "round trip a config where every field is set" in {
      roundTrip(
        format,
        JsonSchemaValidatorConfig(
          schema = Some("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}"),
          specification = draft07,
          failOnValidationError = false
        )
      )
    }

    "round trip every specification offered by the schema" in {
      val offered = (JsonSchemaValidatorConfig.configSchema.get \ "specification" \ "props" \ "options")
        .as[Seq[JsObject]]
        .map(o => (o \ "value").as[String])
      offered must have size 5
      offered.foreach { spec =>
        roundTrip(format, JsonSchemaValidatorConfig.default.copy(specification = spec)).specification mustBe spec
      }
    }

    "round trip a schema with line breaks and unicode" in {
      roundTrip(format, JsonSchemaValidatorConfig(schema = Some("{\n  \"title\": \"été\"\n}")))
    }

    "round trip an empty schema as is" in {
      roundTrip(format, JsonSchemaValidatorConfig(schema = Some(""))).schema mustBe Some("")
    }

    "read an empty object as the case class default" in {
      readOk(format, Json.obj()) mustBe JsonSchemaValidatorConfig()
    }

    "read a json null as the case class default" in {
      readOk(format, JsNull) mustBe JsonSchemaValidatorConfig()
    }

    "read a json string as the case class default" in {
      readOk(format, JsString(defaultSchema)) mustBe JsonSchemaValidatorConfig()
    }

    "read a json array as the case class default" in {
      readOk(format, Json.arr(Json.obj("schema" -> defaultSchema))) mustBe JsonSchemaValidatorConfig()
    }

    "read a lone schema and default the rest" in {
      readOk(format, Json.obj("schema" -> defaultSchema)) mustBe JsonSchemaValidatorConfig.default
    }

    "read a lone fail flag and default the rest" in {
      readOk(format, Json.obj("fail_on_validation_error" -> false)) mustBe JsonSchemaValidatorConfig(
        failOnValidationError = false
      )
    }

    "ignore a schema given as a json object" in {
      readOk(format, Json.obj("schema" -> Json.obj("type" -> "object"))).schema mustBe None
    }

    "ignore a specification that is not a string" in {
      readOk(format, Json.obj("specification" -> 7)).specification mustBe draft202012
    }

    "ignore a fail flag written as a string" in {
      readOk(format, Json.obj("fail_on_validation_error" -> "false")).failOnValidationError mustBe true
    }

    "keep an unknown specification as is" in {
      roundTrip(format, JsonSchemaValidatorConfig(specification = "draft-42")).specification mustBe "draft-42"
    }

    "not read the camel case fail flag" in {
      readOk(format, Json.obj("failOnValidationError" -> false)).failOnValidationError mustBe true
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, JsonSchemaValidatorConfig(Some(defaultSchema), draft07, failOnValidationError = false))
    }

    "list its config flow fields in its config schema" in {
      JsonSchemaValidatorConfig.configFlow mustBe Seq("schema", "specification", "fail_on_validation_error")
      JsonSchemaValidatorConfig.configFlow.foreach { field =>
        JsonSchemaValidatorConfig.configSchema.get.keys must contain(field)
      }
    }

    "use the flow keys the format writes" in {
      JsonSchemaValidatorConfig.configFlow.toSet mustBe writtenKeys(format, JsonSchemaValidatorConfig.default)
    }

    "give its config flow and schema to both validators" in {
      new JsonSchemaRequestValidator().configFlow mustBe JsonSchemaValidatorConfig.configFlow
      new JsonSchemaRequestValidator().configSchema mustBe JsonSchemaValidatorConfig.configSchema
      new JsonSchemaResponseValidator().configFlow mustBe JsonSchemaValidatorConfig.configFlow
      new JsonSchemaResponseValidator().configSchema mustBe JsonSchemaValidatorConfig.configSchema
    }

    "be the default config of JsonSchemaRequestValidator" in {
      defaultConfigReadsBack(new JsonSchemaRequestValidator(), format) mustBe JsonSchemaValidatorConfig.default
    }

    "be the default config of JsonSchemaResponseValidator" in {
      defaultConfigReadsBack(new JsonSchemaResponseValidator(), format) mustBe JsonSchemaValidatorConfig.default
    }

    "skip the validation without schema" in {
      JsonSchemaValidator.validate("not even json", JsonSchemaValidatorConfig()) mustBe Right(())
    }

    "skip the validation with a blank schema" in {
      JsonSchemaValidator.validate("not even json", JsonSchemaValidatorConfig(schema = Some("  \n"))) mustBe Right(())
    }

    "accept a body matching the default schema" in {
      JsonSchemaValidator.validate("{\"name\":\"otoroshi\"}", JsonSchemaValidatorConfig.default) mustBe Right(())
    }

    "reject a body missing a required field of the default schema" in {
      val errors = JsonSchemaValidator.validate("{\"other\":1}", JsonSchemaValidatorConfig.default).left.toOption.get
      errors must have size 1
      errors.head must include("name")
    }

    "prefix the errors with the location of the failing field" in {
      val config = JsonSchemaValidatorConfig(schema = Some("{\"properties\":{\"age\":{\"type\":\"integer\"}}}"))
      val errors = JsonSchemaValidator.validate("{\"age\":\"old\"}", config).left.toOption.get
      errors.head must startWith("/age: ")
    }

    "report a schema that cannot be parsed as a validation error" in {
      val errors = JsonSchemaValidator.validate("{}", JsonSchemaValidatorConfig(schema = Some("{ not json"))).left.toOption.get
      errors.head must startWith("validation error: ")
    }

    "fall back to the 2020-12 draft for an unknown specification" in {
      val config = JsonSchemaValidatorConfig.default.copy(specification = "draft-42")
      JsonSchemaValidator.validate("{\"name\":\"otoroshi\"}", config) mustBe Right(())
      JsonSchemaValidator.validate("{}", config).isLeft mustBe true
    }

    "tell json content types apart" in {
      JsonSchemaValidator.isJsonContentType(Some("application/json")) mustBe true
      JsonSchemaValidator.isJsonContentType(Some("application/vnd.api+json; charset=utf-8")) mustBe true
      JsonSchemaValidator.isJsonContentType(Some("APPLICATION/JSON")) mustBe true
      JsonSchemaValidator.isJsonContentType(Some("text/plain")) mustBe false
      JsonSchemaValidator.isJsonContentType(None) mustBe false
    }
  }
}
