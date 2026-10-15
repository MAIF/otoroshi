package plugins.configs

import otoroshi.wasm.proxywasm.*
import play.api.libs.json.*

class CorazaPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgCorazaWAFConfig" should {

    val format = NgCorazaWAFConfig.format

    "write the reference under ref" in {
      format.writes(NgCorazaWAFConfig("coraza-waf-config_1")) mustBe Json.obj("ref" -> "coraza-waf-config_1")
    }

    "write the default config of the plugins with the none reference" in {
      format.writes(NgCorazaWAFConfig("none")) mustBe Json.obj("ref" -> "none")
    }

    "round trip the none reference" in {
      roundTrip(format, NgCorazaWAFConfig("none"))
    }

    "round trip a real reference" in {
      roundTrip(format, NgCorazaWAFConfig("coraza-waf-config_d0c1a4f5-0d2b-4b8e-9b4c-3f5e1a2b3c4d"))
    }

    "round trip an empty reference as is" in {
      roundTrip(format, NgCorazaWAFConfig("")).ref mustBe ""
    }

    "read a reference alone" in {
      readOk(format, Json.obj("ref" -> "my-waf")) mustBe NgCorazaWAFConfig("my-waf")
    }

    "not read an empty object, the reference is mandatory" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a json string, even holding a reference" in {
      readError(format, JsString("my-waf"))
    }

    "not read a json array" in {
      readError(format, Json.arr("my-waf"))
    }

    "not read a reference that is not a string" in {
      readError(format, Json.obj("ref" -> 42))
    }

    "not read a null reference" in {
      readError(format, Json.obj("ref" -> JsNull))
    }

    "not read the reference under another key" in {
      readError(format, Json.obj("config" -> "my-waf"))
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("ref" -> "my-waf", "foo" -> "bar")) mustBe NgCorazaWAFConfig("my-waf")
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgCorazaWAFConfig("my-waf"))
    }

    "be the default config of NgCorazaWAF" in {
      defaultConfigReadsBack(new NgCorazaWAF(), format) mustBe NgCorazaWAFConfig("none")
    }

    "be the default config of NgIncomingRequestValidatorCorazaWAF" in {
      defaultConfigReadsBack(new NgIncomingRequestValidatorCorazaWAF(), format) mustBe NgCorazaWAFConfig("none")
    }
  }
}
