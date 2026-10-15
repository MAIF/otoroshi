package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class HttpsPluginConfigsSpec extends PluginConfigFormatSpec {

  "BlockHttpTrafficConfig" should {

    val format  = BlockHttpTrafficConfig.format
    val default = BlockHttpTrafficConfig.default

    "revoke apikeys but not user sessions by default, with the built-in message" in {
      default mustBe BlockHttpTrafficConfig(revokeApikeys = true, revokeUserSession = false, message = None)
    }

    "write the default config with a null message" in {
      format.writes(default) mustBe Json.obj(
        "revoke_apikeys"      -> true,
        "revoke_user_session" -> false,
        "message"             -> JsNull
      )
    }

    "write every field under its snake case key" in {
      format.writes(BlockHttpTrafficConfig(false, true, Some("use https"))) mustBe Json.obj(
        "revoke_apikeys"      -> false,
        "revoke_user_session" -> true,
        "message"             -> "use https"
      )
    }

    "round trip the default config" in {
      roundTrip(format, default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, BlockHttpTrafficConfig(revokeApikeys = false, revokeUserSession = true, Some("HTTPS only")))
    }

    "round trip a config revoking nothing" in {
      roundTrip(format, BlockHttpTrafficConfig(revokeApikeys = false, revokeUserSession = false, None))
    }

    "round trip a config revoking everything" in {
      roundTrip(format, BlockHttpTrafficConfig(revokeApikeys = true, revokeUserSession = true, None))
    }

    "round trip a custom message" in {
      roundTrip(format, default.copy(message = Some("All connections MUST use \"HTTPS\" - été")))
    }

    "round trip an empty message as an empty string, not as no message" in {
      roundTrip(format, default.copy(message = Some(""))).message mustBe Some("")
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe default
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe default
    }

    "read a json string as the default config" in {
      readOk(format, JsString("use https")) mustBe default
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(true, false)) mustBe default
    }

    "read revoke_apikeys alone" in {
      readOk(format, Json.obj("revoke_apikeys" -> false)) mustBe default.copy(revokeApikeys = false)
    }

    "read revoke_user_session alone" in {
      readOk(format, Json.obj("revoke_user_session" -> true)) mustBe default.copy(revokeUserSession = true)
    }

    "read a message alone" in {
      readOk(format, Json.obj("message" -> "nope")) mustBe default.copy(message = Some("nope"))
    }

    "ignore a revoke_apikeys written as a string" in {
      readOk(format, Json.obj("revoke_apikeys" -> "false")) mustBe default
    }

    "ignore a revoke_apikeys written as a number" in {
      readOk(format, Json.obj("revoke_apikeys" -> 0)) mustBe default
    }

    "ignore a revoke_user_session written as a string" in {
      readOk(format, Json.obj("revoke_user_session" -> "true")) mustBe default
    }

    "read a null message as no message" in {
      readOk(format, Json.obj("message" -> JsNull)) mustBe default
    }

    "ignore a message that is not a string" in {
      readOk(format, Json.obj("message" -> Json.obj("text" -> "nope"))) mustBe default
    }

    "not read the camel case keys" in {
      readOk(
        format,
        Json.obj("revokeApikeys" -> false, "revokeUserSession" -> true)
      ) mustBe default
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("message" -> "nope", "foo" -> "bar")) mustBe default.copy(message = Some("nope"))
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, BlockHttpTrafficConfig(false, true, Some("use https")))
    }

    "list every written key in its config flow and schema" in {
      BlockHttpTrafficConfig.configFlow mustBe Seq("revoke_apikeys", "revoke_user_session", "message")
      BlockHttpTrafficConfig.configFlow.toSet mustBe writtenKeys(format, default)
      BlockHttpTrafficConfig.configSchema.get.keys mustBe BlockHttpTrafficConfig.configFlow.toSet
    }

    "describe the revocation flags as booleans and the message as a string in its schema" in {
      val schema = BlockHttpTrafficConfig.configSchema.get
      (schema \ "revoke_apikeys" \ "type").as[String] mustBe "bool"
      (schema \ "revoke_user_session" \ "type").as[String] mustBe "bool"
      (schema \ "message" \ "type").as[String] mustBe "string"
    }

    "be the default config of BlockHttpTraffic" in {
      defaultConfigReadsBack(new BlockHttpTraffic(), format) mustBe default
    }

    "be the config flow and schema of BlockHttpTraffic" in {
      val plugin = new BlockHttpTraffic()
      plugin.configFlow mustBe BlockHttpTrafficConfig.configFlow
      plugin.configSchema mustBe BlockHttpTrafficConfig.configSchema
    }
  }

  "the https plugins without configuration" should {

    "have no default config for ForceHttpsTraffic" in {
      hasNoDefaultConfig(new ForceHttpsTraffic())
    }
  }
}
