package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class StaticAssetPluginConfigsSpec extends PluginConfigFormatSpec {

  "StaticAssetEndpointConfiguration" should {

    val format = StaticAssetEndpointConfiguration.format

    "have no url by default" in {
      StaticAssetEndpointConfiguration.default.url mustBe None
      StaticAssetEndpointConfiguration.default mustBe StaticAssetEndpointConfiguration()
    }

    "write the default config with a null url" in {
      format.writes(StaticAssetEndpointConfiguration.default) mustBe Json.obj("url" -> JsNull)
    }

    "write the url under url" in {
      format.writes(StaticAssetEndpointConfiguration(Some("https://cdn.oto.tools/logo.png"))) mustBe Json.obj(
        "url" -> "https://cdn.oto.tools/logo.png"
      )
    }

    "round trip the default config" in {
      roundTrip(format, StaticAssetEndpointConfiguration.default)
    }

    "round trip a config with an url" in {
      roundTrip(format, StaticAssetEndpointConfiguration(Some("https://cdn.oto.tools/assets/app.js")))
    }

    "round trip an http url with a port and a query" in {
      roundTrip(format, StaticAssetEndpointConfiguration(Some("http://127.0.0.1:8080/file.css?v=42")))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe StaticAssetEndpointConfiguration.default
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe StaticAssetEndpointConfiguration.default
    }

    "read a json string as the default config" in {
      readOk(format, JsString("https://cdn.oto.tools/logo.png")) mustBe StaticAssetEndpointConfiguration.default
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("https://cdn.oto.tools/logo.png")) mustBe StaticAssetEndpointConfiguration.default
    }

    "read a null url as no url" in {
      readOk(format, Json.obj("url" -> JsNull)) mustBe StaticAssetEndpointConfiguration.default
    }

    "read an empty url as no url" in {
      readOk(format, Json.obj("url" -> "")) mustBe StaticAssetEndpointConfiguration.default
    }

    "keep a blank url" in {
      readOk(format, Json.obj("url" -> " ")) mustBe StaticAssetEndpointConfiguration(Some(" "))
    }

    "ignore an url that is not a string" in {
      readOk(format, Json.obj("url" -> 42)) mustBe StaticAssetEndpointConfiguration.default
    }

    "ignore an url given as an object" in {
      readOk(
        format,
        Json.obj("url" -> Json.obj("href" -> "https://cdn.oto.tools"))
      ) mustBe StaticAssetEndpointConfiguration.default
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("url" -> "https://cdn.oto.tools", "foo" -> "bar")) mustBe StaticAssetEndpointConfiguration(
        Some("https://cdn.oto.tools")
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, StaticAssetEndpointConfiguration(Some("https://cdn.oto.tools/logo.png")))
      jsonMatchesFormat(format, StaticAssetEndpointConfiguration.default)
    }

    "list url in its config flow and schema" in {
      StaticAssetEndpointConfiguration.configFlow mustBe Seq("url")
      StaticAssetEndpointConfiguration.configSchema.get.keys must contain("url")
    }

    "describe url as a string in its config schema" in {
      (StaticAssetEndpointConfiguration.configSchema.get \ "url" \ "type").as[String] mustBe "string"
    }

    "be the default config of StaticAssetEndpoint" in {
      defaultConfigReadsBack(new StaticAssetEndpoint(), format) mustBe StaticAssetEndpointConfiguration.default
    }

    "be described by the config flow and schema of StaticAssetEndpoint" in {
      val plugin = new StaticAssetEndpoint()
      plugin.configFlow mustBe StaticAssetEndpointConfiguration.configFlow
      plugin.configSchema mustBe StaticAssetEndpointConfiguration.configSchema
    }
  }
}
