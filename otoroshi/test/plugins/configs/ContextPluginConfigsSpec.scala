package plugins.configs

import otoroshi.next.plugins.*
import otoroshi.utils.JsonPathValidator
import play.api.libs.json.*

class ContextPluginConfigsSpec extends PluginConfigFormatSpec {

  "ContextValidationConfig" should {

    val format = ContextValidationConfig.format

    val clientId = JsonPathValidator("$.apikey.clientId", JsString("my-client"), Some("bad client"))
    val method   = JsonPathValidator("$.request.method", JsString("GET"))

    "write the default config as an empty validators array" in {
      format.writes(ContextValidationConfig()) mustBe Json.obj("validators" -> Json.arr())
    }

    "write each validator with its kind, path, value and error" in {
      format.writes(ContextValidationConfig(Seq(clientId, method))) mustBe Json.obj(
        "validators" -> Json.arr(
          Json.obj(
            "kind"  -> "json-path-validator",
            "path"  -> "$.apikey.clientId",
            "value" -> "my-client",
            "error" -> "bad client"
          ),
          Json.obj(
            "kind"  -> "json-path-validator",
            "path"  -> "$.request.method",
            "value" -> "GET",
            "error" -> JsNull
          )
        )
      )
    }

    "round trip the default config" in {
      roundTrip(format, ContextValidationConfig())
    }

    "round trip a single validator" in {
      roundTrip(format, ContextValidationConfig(Seq(method)))
    }

    "round trip several validators and keep their order" in {
      roundTrip(format, ContextValidationConfig(Seq(method, clientId))).validators mustBe Seq(method, clientId)
    }

    "round trip a validator with an error message" in {
      roundTrip(format, ContextValidationConfig(Seq(clientId))).validators.head.error mustBe Some("bad client")
    }

    "round trip validators expecting a number, a boolean, an object and an array" in {
      roundTrip(
        format,
        ContextValidationConfig(
          Seq(
            JsonPathValidator("$.request.id", JsNumber(42)),
            JsonPathValidator("$.apikey.enabled", JsBoolean(true)),
            JsonPathValidator("$.apikey.metadata", Json.obj("foo" -> "bar")),
            JsonPathValidator("$.apikey.tags", Json.arr("a", "b"))
          )
        )
      )
    }

    "round trip a validator expecting a null value" in {
      roundTrip(format, ContextValidationConfig(Seq(JsonPathValidator("$.user", JsNull)))).validators.head.value mustBe
        JsNull
    }

    "round trip validators holding operators" in {
      roundTrip(
        format,
        ContextValidationConfig(
          Seq(
            JsonPathValidator("$.apikey.tags", JsString("Contains(admin)")),
            JsonPathValidator("$.user", JsString("IsDefined()")),
            JsonPathValidator("$.request.path", JsString("Regex(^/api/.*)"))
          )
        )
      )
    }

    "read an empty object as no validator" in {
      readOk(format, Json.obj()) mustBe ContextValidationConfig()
    }

    "read a json null as no validator" in {
      readOk(format, JsNull) mustBe ContextValidationConfig()
    }

    "read a json string as no validator" in {
      readOk(format, JsString("$.apikey")) mustBe ContextValidationConfig()
    }

    "read a json array as no validator" in {
      readOk(format, Json.arr(JsonPathValidator.format.writes(method))) mustBe ContextValidationConfig()
    }

    "read validators that are not an array as no validator" in {
      readOk(format, Json.obj("validators" -> JsonPathValidator.format.writes(method))) mustBe ContextValidationConfig()
    }

    "read a validator without kind" in {
      readOk(
        format,
        Json.obj("validators" -> Json.arr(Json.obj("path" -> "$.request.method", "value" -> "GET")))
      ) mustBe ContextValidationConfig(Seq(method))
    }

    "read a validator whatever its kind" in {
      readOk(
        format,
        Json.obj("validators" -> Json.arr(Json.obj("kind" -> "other", "path" -> "$.request.method", "value" -> "GET")))
      ) mustBe ContextValidationConfig(Seq(method))
    }

    "drop a validator without path and keep the others" in {
      readOk(
        format,
        Json.obj("validators" -> Json.arr(Json.obj("value" -> "GET"), JsonPathValidator.format.writes(clientId)))
      ) mustBe ContextValidationConfig(Seq(clientId))
    }

    "drop a validator without value" in {
      readOk(format, Json.obj("validators" -> Json.arr(Json.obj("path" -> "$.request.method")))) mustBe
        ContextValidationConfig()
    }

    "drop a validator whose path is not a string" in {
      readOk(format, Json.obj("validators" -> Json.arr(Json.obj("path" -> 1, "value" -> "GET")))) mustBe
        ContextValidationConfig()
    }

    "drop an item of the validators array that is not an object" in {
      readOk(format, Json.obj("validators" -> Json.arr("$.request.method", JsonPathValidator.format.writes(method)))) mustBe
        ContextValidationConfig(Seq(method))
    }

    "read a blank error as no error" in {
      readOk(
        format,
        Json.obj("validators" -> Json.arr(Json.obj("path" -> "$.user", "value" -> "IsDefined()", "error" -> "  ")))
      ).validators.head.error mustBe None
    }

    "ignore an error that is not a string" in {
      readOk(
        format,
        Json.obj("validators" -> Json.arr(Json.obj("path" -> "$.user", "value" -> "IsDefined()", "error" -> 403)))
      ).validators.head.error mustBe None
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("validators" -> Json.arr(), "foo" -> "bar")) mustBe ContextValidationConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, ContextValidationConfig(Seq(clientId, method)))
    }

    "be the default config of ContextValidation" in {
      defaultConfigReadsBack(new ContextValidation(), format) mustBe ContextValidationConfig()
    }
  }
}
