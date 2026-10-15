package plugins.configs

import otoroshi.next.tunnel.*
import play.api.libs.json.*

class TunnelPluginConfigsSpec extends PluginConfigFormatSpec {

  "TunnelPluginConfig" should {

    val format = TunnelPluginConfig.format

    "use the default tunnel by default" in {
      TunnelPluginConfig.default mustBe TunnelPluginConfig("default")
    }

    "write the default config as the default tunnel_id" in {
      format.writes(TunnelPluginConfig.default) mustBe Json.obj("tunnel_id" -> "default")
    }

    "write the tunnel id under tunnel_id" in {
      format.writes(TunnelPluginConfig("my-tunnel")) mustBe Json.obj("tunnel_id" -> "my-tunnel")
    }

    "round trip the default config" in {
      roundTrip(format, TunnelPluginConfig.default)
    }

    "round trip another tunnel id" in {
      roundTrip(format, TunnelPluginConfig("tunnel_1234567890")).tunnelId mustBe "tunnel_1234567890"
    }

    "round trip an empty tunnel id" in {
      roundTrip(format, TunnelPluginConfig("")).tunnelId mustBe ""
    }

    "round trip a tunnel id with spaces, unicode and expression language" in {
      roundTrip(format, TunnelPluginConfig("tunnel été ${req.headers.x-tunnel}"))
    }

    "keep the case of the tunnel id" in {
      roundTrip(format, TunnelPluginConfig("Tunnel-ABC")).tunnelId mustBe "Tunnel-ABC"
    }

    "not read an empty object, the tunnel id being mandatory" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a json string" in {
      readError(format, JsString("default"))
    }

    "not read a json array" in {
      readError(format, Json.arr("default"))
    }

    "not read a tunnel_id that is a number" in {
      readError(format, Json.obj("tunnel_id" -> 42))
    }

    "not read a tunnel_id that is null" in {
      readError(format, Json.obj("tunnel_id" -> JsNull))
    }

    "not read the camel case key" in {
      readError(format, Json.obj("tunnelId" -> "default"))
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("tunnel_id" -> "t1", "foo" -> "bar")) mustBe TunnelPluginConfig("t1")
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, TunnelPluginConfig("t1"))
    }

    "be the default config of TunnelPlugin" in {
      defaultConfigReadsBack(new TunnelPlugin(), format) mustBe TunnelPluginConfig.default
    }
  }
}
