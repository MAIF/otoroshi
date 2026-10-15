package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class TailscalePluginConfigsSpec extends PluginConfigFormatSpec {

  "TailscaleSelectTargetByNameConfig" should {

    val format = TailscaleSelectTargetByNameConfig.format

    "write the machine name and the ip address flag" in {
      format.writes(TailscaleSelectTargetByNameConfig("web-1", true)) mustBe Json.obj(
        "machine_name"   -> "web-1",
        "use_ip_address" -> true
      )
    }

    "write the plugin default config" in {
      new TailscaleSelectTargetByName().defaultConfig mustBe Some(
        Json.obj(
          "machine_name"   -> "my-machine",
          "use_ip_address" -> false
        )
      )
    }

    "round trip the plugin default config" in {
      roundTrip(format, TailscaleSelectTargetByNameConfig("my-machine", false))
    }

    "round trip a config where every field is set" in {
      roundTrip(format, TailscaleSelectTargetByNameConfig("backend-eu-1", true))
    }

    "round trip a wildcard machine name" in {
      roundTrip(format, TailscaleSelectTargetByNameConfig("web-*", false)).machineName mustBe "web-*"
    }

    "round trip a regex machine name" in {
      val back = roundTrip(format, TailscaleSelectTargetByNameConfig("Regex(^web-[0-9]+$)", false))
      back.machineName mustBe "Regex(^web-[0-9]+$)"
    }

    "round trip an empty machine name" in {
      roundTrip(format, TailscaleSelectTargetByNameConfig("", true)).machineName mustBe ""
    }

    "read the machine name alone without using the ip address" in {
      readOk(format, Json.obj("machine_name" -> "web-1")) mustBe TailscaleSelectTargetByNameConfig("web-1", false)
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a json string" in {
      readError(format, JsString("web-1"))
    }

    "not read a json array" in {
      readError(format, Json.arr("web-1"))
    }

    "not read the ip address flag alone" in {
      readError(format, Json.obj("use_ip_address" -> true))
    }

    "not read a null machine name" in {
      readError(format, Json.obj("machine_name" -> JsNull, "use_ip_address" -> true))
    }

    "not read a machine name that is not a string" in {
      readError(format, Json.obj("machine_name" -> 42))
    }

    "not read the camel case machineName key" in {
      readError(format, Json.obj("machineName" -> "web-1"))
    }

    "ignore an ip address flag written as a string" in {
      readOk(format, Json.obj("machine_name" -> "web-1", "use_ip_address" -> "true")).useIpAddress mustBe false
    }

    "ignore a null ip address flag" in {
      readOk(format, Json.obj("machine_name" -> "web-1", "use_ip_address" -> JsNull)).useIpAddress mustBe false
    }

    "ignore the camel case useIpAddress key" in {
      readOk(format, Json.obj("machine_name" -> "web-1", "useIpAddress" -> true)).useIpAddress mustBe false
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("machine_name" -> "web-1", "foo" -> "bar")) mustBe TailscaleSelectTargetByNameConfig(
        "web-1",
        false
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, TailscaleSelectTargetByNameConfig("web-1", true))
    }

    "be the default config of TailscaleSelectTargetByName" in {
      defaultConfigReadsBack(new TailscaleSelectTargetByName(), format) mustBe TailscaleSelectTargetByNameConfig(
        "my-machine",
        false
      )
    }
  }

  "the tailscale jobs" should {

    "have no default config for TailscaleTargetsJob" in {
      new TailscaleTargetsJob().defaultConfig mustBe None
    }

    "have no default config for TailscaleCertificatesFetcherJob" in {
      new TailscaleCertificatesFetcherJob().defaultConfig mustBe None
    }
  }
}
