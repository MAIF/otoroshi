package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class EchoPluginConfigsSpec extends PluginConfigFormatSpec {

  "EchoBackendConfig" should {

    val format = EchoBackendConfig.format

    "limit the request body to 512 kilobytes by default" in {
      EchoBackendConfig().limit mustBe 524288L
      EchoBackendConfig.default mustBe EchoBackendConfig()
    }

    "write the default config" in {
      format.writes(EchoBackendConfig.default) mustBe Json.obj("limit" -> 524288)
    }

    "write the limit under limit" in {
      format.writes(EchoBackendConfig(1024)) mustBe Json.obj("limit" -> 1024)
    }

    "round trip the default config" in {
      roundTrip(format, EchoBackendConfig.default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, EchoBackendConfig(10L * 1024L * 1024L))
    }

    "round trip a zero limit" in {
      roundTrip(format, EchoBackendConfig(0)).limit mustBe 0L
    }

    "round trip the largest limit" in {
      roundTrip(format, EchoBackendConfig(Long.MaxValue))
    }

    "round trip a negative limit as is" in {
      roundTrip(format, EchoBackendConfig(-1)).limit mustBe -1L
    }

    "ignore a limit written as a string" in {
      readOk(format, Json.obj("limit" -> "1024")) mustBe EchoBackendConfig.default
    }

    "ignore a limit that is not a whole number" in {
      readOk(format, Json.obj("limit" -> 1024.5)) mustBe EchoBackendConfig.default
    }

    "ignore a limit that does not fit a long" in {
      readOk(format, Json.obj("limit" -> JsNumber(BigDecimal("99999999999999999999")))) mustBe EchoBackendConfig.default
    }

    "read a whole number written with a decimal part" in {
      readOk(format, Json.obj("limit" -> JsNumber(BigDecimal("2048.0")))) mustBe EchoBackendConfig(2048)
    }

    "read a null limit as the default limit" in {
      readOk(format, Json.obj("limit" -> JsNull)) mustBe EchoBackendConfig.default
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe EchoBackendConfig.default
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe EchoBackendConfig.default
    }

    "read a json number as the default config" in {
      readOk(format, JsNumber(1024)) mustBe EchoBackendConfig.default
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(1024)) mustBe EchoBackendConfig.default
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("limit" -> 10, "max_body_size" -> 20)) mustBe EchoBackendConfig(10)
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, EchoBackendConfig(4096))
    }

    "list limit in its config flow and schema" in {
      EchoBackendConfig.configFlow mustBe Seq("limit")
      EchoBackendConfig.configSchema.get.keys must contain("limit")
    }

    "be the config flow and schema of both echo plugins" in {
      new EchoBackend().configFlow mustBe EchoBackendConfig.configFlow
      new EchoBackend().configSchema mustBe EchoBackendConfig.configSchema
      new RequestBodyEchoBackend().configFlow mustBe EchoBackendConfig.configFlow
      new RequestBodyEchoBackend().configSchema mustBe EchoBackendConfig.configSchema
    }

    "be the default config of EchoBackend" in {
      defaultConfigReadsBack(new EchoBackend(), format) mustBe EchoBackendConfig.default
    }

    "be the default config of RequestBodyEchoBackend" in {
      defaultConfigReadsBack(new RequestBodyEchoBackend(), format) mustBe EchoBackendConfig.default
    }
  }
}
