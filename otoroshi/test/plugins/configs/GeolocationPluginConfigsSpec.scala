package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class GeolocationPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgMaxMindGeolocationInfoExtractorConfig" should {

    val format = NgMaxMindGeolocationInfoExtractorConfig.format

    "use the global geolocation settings without logging by default" in {
      NgMaxMindGeolocationInfoExtractorConfig().path mustBe "global"
      NgMaxMindGeolocationInfoExtractorConfig().log mustBe false
    }

    "write the default config" in {
      format.writes(NgMaxMindGeolocationInfoExtractorConfig()) mustBe Json.obj("path" -> "global", "log" -> false)
    }

    "round trip the default config" in {
      roundTrip(format, NgMaxMindGeolocationInfoExtractorConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, NgMaxMindGeolocationInfoExtractorConfig(path = "/data/GeoLite2-City.mmdb", log = true))
    }

    "round trip a database url as path" in {
      roundTrip(
        format,
        NgMaxMindGeolocationInfoExtractorConfig(path = "https://cdn.oto.tools/GeoLite2-City.mmdb")
      ).path mustBe "https://cdn.oto.tools/GeoLite2-City.mmdb"
    }

    "round trip an empty path" in {
      roundTrip(format, NgMaxMindGeolocationInfoExtractorConfig(path = "")).path mustBe ""
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgMaxMindGeolocationInfoExtractorConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgMaxMindGeolocationInfoExtractorConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("/data/db.mmdb")) mustBe NgMaxMindGeolocationInfoExtractorConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("/data/db.mmdb")) mustBe NgMaxMindGeolocationInfoExtractorConfig()
    }

    "read the path alone and do not log" in {
      readOk(format, Json.obj("path" -> "/data/db.mmdb")) mustBe NgMaxMindGeolocationInfoExtractorConfig(
        path = "/data/db.mmdb"
      )
    }

    "read log alone and use the global settings" in {
      readOk(format, Json.obj("log" -> true)) mustBe NgMaxMindGeolocationInfoExtractorConfig(log = true)
    }

    "ignore a path that is not a string" in {
      readOk(format, Json.obj("path" -> 42)).path mustBe "global"
    }

    "ignore a log flag written as a string" in {
      readOk(format, Json.obj("log" -> "true")).log mustBe false
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("log" -> true, "foo" -> "bar")) mustBe NgMaxMindGeolocationInfoExtractorConfig(log = true)
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgMaxMindGeolocationInfoExtractorConfig(path = "/data/db.mmdb", log = true))
    }

    "be the default config of NgMaxMindGeolocationInfoExtractor" in {
      defaultConfigReadsBack(
        new NgMaxMindGeolocationInfoExtractor(),
        format
      ) mustBe NgMaxMindGeolocationInfoExtractorConfig()
    }
  }

  "NgIpStackGeolocationInfoExtractorConfig" should {

    val format = NgIpStackGeolocationInfoExtractorConfig.format

    "have no apikey, a 2 seconds timeout and no logging by default" in {
      val config = NgIpStackGeolocationInfoExtractorConfig()
      config.apikey mustBe None
      config.timeout mustBe 2000L
      config.log mustBe false
    }

    "write the default config with a null apikey" in {
      format.writes(NgIpStackGeolocationInfoExtractorConfig()) mustBe Json.obj(
        "apikey"  -> JsNull,
        "timeout" -> 2000,
        "log"     -> false
      )
    }

    "write the apikey as a string" in {
      (written(format, NgIpStackGeolocationInfoExtractorConfig(apikey = Some("my-key"))) \ "apikey").get mustBe JsString(
        "my-key"
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgIpStackGeolocationInfoExtractorConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, NgIpStackGeolocationInfoExtractorConfig(apikey = Some("my-key"), timeout = 500, log = true))
    }

    "round trip an apikey alone" in {
      roundTrip(format, NgIpStackGeolocationInfoExtractorConfig(apikey = Some("my-key"))).apikey mustBe Some("my-key")
    }

    "round trip a zero timeout" in {
      roundTrip(format, NgIpStackGeolocationInfoExtractorConfig(timeout = 0)).timeout mustBe 0L
    }

    "trim the apikey when reading it" in {
      readOk(format, Json.obj("apikey" -> "  my-key  ")).apikey mustBe Some("my-key")
    }

    "read an empty apikey as no apikey" in {
      readOk(format, Json.obj("apikey" -> "")).apikey mustBe None
    }

    "read a blank apikey as no apikey" in {
      val config = NgIpStackGeolocationInfoExtractorConfig(apikey = Some("   "))
      readOk(format, format.writes(config)).apikey mustBe None
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgIpStackGeolocationInfoExtractorConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgIpStackGeolocationInfoExtractorConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("my-key")) mustBe NgIpStackGeolocationInfoExtractorConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("my-key")) mustBe NgIpStackGeolocationInfoExtractorConfig()
    }

    "read the timeout alone" in {
      readOk(format, Json.obj("timeout" -> 750)) mustBe NgIpStackGeolocationInfoExtractorConfig(timeout = 750)
    }

    "ignore an apikey that is not a string" in {
      readOk(format, Json.obj("apikey" -> 12345)).apikey mustBe None
    }

    "ignore a timeout written as a string" in {
      readOk(format, Json.obj("timeout" -> "750")).timeout mustBe 2000L
    }

    "ignore a timeout that is not a whole number" in {
      readOk(format, Json.obj("timeout" -> 750.5)).timeout mustBe 2000L
    }

    "ignore a log flag written as a string" in {
      readOk(format, Json.obj("log" -> "true")).log mustBe false
    }

    "not read the camel case apiKey key" in {
      readOk(format, Json.obj("apiKey" -> "my-key")).apikey mustBe None
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgIpStackGeolocationInfoExtractorConfig(apikey = Some("my-key"), log = true))
    }

    "be the default config of NgIpStackGeolocationInfoExtractor" in {
      defaultConfigReadsBack(
        new NgIpStackGeolocationInfoExtractor(),
        format
      ) mustBe NgIpStackGeolocationInfoExtractorConfig()
    }
  }

  "NgGeolocationInfoHeaderConfig" should {

    val format = NgGeolocationInfoHeaderConfig.format

    "send the location in X-User-Agent-Info by default" in {
      NgGeolocationInfoHeaderConfig().headerName mustBe "X-User-Agent-Info"
    }

    "write the default config under header_name" in {
      format.writes(NgGeolocationInfoHeaderConfig()) mustBe Json.obj("header_name" -> "X-User-Agent-Info")
    }

    "round trip the default config" in {
      roundTrip(format, NgGeolocationInfoHeaderConfig())
    }

    "round trip a custom header name" in {
      roundTrip(format, NgGeolocationInfoHeaderConfig("X-Geolocation")).headerName mustBe "X-Geolocation"
    }

    "round trip an empty header name" in {
      roundTrip(format, NgGeolocationInfoHeaderConfig("")).headerName mustBe ""
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgGeolocationInfoHeaderConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgGeolocationInfoHeaderConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("X-Geolocation")) mustBe NgGeolocationInfoHeaderConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("X-Geolocation")) mustBe NgGeolocationInfoHeaderConfig()
    }

    "ignore a header name that is not a string" in {
      readOk(format, Json.obj("header_name" -> 42)) mustBe NgGeolocationInfoHeaderConfig()
    }

    "ignore a null header name" in {
      readOk(format, Json.obj("header_name" -> JsNull)) mustBe NgGeolocationInfoHeaderConfig()
    }

    "not read the camel case key" in {
      readOk(format, Json.obj("headerName" -> "X-Geolocation")) mustBe NgGeolocationInfoHeaderConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("header_name" -> "X-Geo", "foo" -> "bar")) mustBe NgGeolocationInfoHeaderConfig("X-Geo")
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgGeolocationInfoHeaderConfig("X-Geolocation"))
    }

    "be the default config of NgGeolocationInfoHeader" in {
      defaultConfigReadsBack(new NgGeolocationInfoHeader(), format) mustBe NgGeolocationInfoHeaderConfig()
    }
  }

  "the geolocation plugins without configuration" should {

    "have no default config for NgGeolocationInfoEndpoint" in {
      hasNoDefaultConfig(new NgGeolocationInfoEndpoint())
    }
  }
}
