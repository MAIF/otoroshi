package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class EurekaPluginConfigsSpec extends PluginConfigFormatSpec {

  "EurekaServerConfig" should {

    val format = EurekaServerConfig.format

    "evict instances after 300 seconds by default" in {
      EurekaServerConfig().evictionTimeout mustBe 300
    }

    "write the default config under the camel case evictionTimeout key" in {
      format.writes(EurekaServerConfig()) mustBe Json.obj("evictionTimeout" -> 300)
    }

    "write a custom timeout" in {
      format.writes(EurekaServerConfig(60)) mustBe Json.obj("evictionTimeout" -> 60)
    }

    "round trip the default config" in {
      roundTrip(format, EurekaServerConfig())
    }

    "round trip a custom timeout" in {
      roundTrip(format, EurekaServerConfig(30)).evictionTimeout mustBe 30
    }

    "round trip a zero timeout" in {
      roundTrip(format, EurekaServerConfig(0))
    }

    "round trip the largest timeout" in {
      roundTrip(format, EurekaServerConfig(Int.MaxValue))
    }

    "round trip a negative timeout as is" in {
      roundTrip(format, EurekaServerConfig(-1)).evictionTimeout mustBe -1
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe EurekaServerConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe EurekaServerConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("60")) mustBe EurekaServerConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(60)) mustBe EurekaServerConfig()
    }

    "ignore a timeout written as a string" in {
      readOk(format, Json.obj("evictionTimeout" -> "60")) mustBe EurekaServerConfig()
    }

    "ignore a timeout that is not a whole number" in {
      readOk(format, Json.obj("evictionTimeout" -> 60.5)) mustBe EurekaServerConfig()
    }

    "ignore a timeout that does not fit an int" in {
      readOk(format, Json.obj("evictionTimeout" -> (Int.MaxValue.toLong + 1L))) mustBe EurekaServerConfig()
    }

    "not read a snake case eviction_timeout key" in {
      readOk(format, Json.obj("eviction_timeout" -> 60)) mustBe EurekaServerConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, EurekaServerConfig(120))
    }

    "be the default config of EurekaServerSink" in {
      defaultConfigReadsBack(new EurekaServerSink(), format) mustBe EurekaServerConfig()
    }
  }

  "EurekaTargetConfig" should {

    val format = EurekaTargetConfig.format

    "target nothing by default" in {
      EurekaTargetConfig().eurekaServer mustBe None
      EurekaTargetConfig().eurekaApp mustBe None
    }

    "write the default config with null values" in {
      format.writes(EurekaTargetConfig()) mustBe Json.obj("eureka_server" -> JsNull, "eureka_app" -> JsNull)
    }

    "write the server and the app" in {
      format.writes(EurekaTargetConfig(Some("route_123"), Some("my-app"))) mustBe Json.obj(
        "eureka_server" -> "route_123",
        "eureka_app"    -> "my-app"
      )
    }

    "round trip the default config" in {
      roundTrip(format, EurekaTargetConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, EurekaTargetConfig(Some("route_123"), Some("my-app")))
    }

    "round trip a server without app" in {
      roundTrip(format, EurekaTargetConfig(eurekaServer = Some("route_123"))).eurekaApp mustBe None
    }

    "round trip an app without server" in {
      roundTrip(format, EurekaTargetConfig(eurekaApp = Some("my-app"))).eurekaServer mustBe None
    }

    "round trip empty strings as defined values" in {
      roundTrip(format, EurekaTargetConfig(Some(""), Some(""))) mustBe EurekaTargetConfig(Some(""), Some(""))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe EurekaTargetConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe EurekaTargetConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("my-app")) mustBe EurekaTargetConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("my-app")) mustBe EurekaTargetConfig()
    }

    "read the app alone" in {
      readOk(format, Json.obj("eureka_app" -> "my-app")) mustBe EurekaTargetConfig(eurekaApp = Some("my-app"))
    }

    "ignore a server that is not a string" in {
      readOk(format, Json.obj("eureka_server" -> 42, "eureka_app" -> "my-app")) mustBe EurekaTargetConfig(
        eurekaApp = Some("my-app")
      )
    }

    "not read the camel case keys" in {
      readOk(format, Json.obj("eurekaServer" -> "route_123", "eurekaApp" -> "my-app")) mustBe EurekaTargetConfig()
    }

    "not read the service_url_default_zone of the external target config" in {
      readOk(format, Json.obj("service_url_default_zone" -> "http://eureka:8761/eureka")) mustBe EurekaTargetConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("eureka_app" -> "my-app", "foo" -> "bar")) mustBe EurekaTargetConfig(
        eurekaApp = Some("my-app")
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, EurekaTargetConfig(Some("route_123"), Some("my-app")))
    }

    "be the default config of EurekaTarget" in {
      defaultConfigReadsBack(new EurekaTarget(), format) mustBe EurekaTargetConfig()
    }
  }

  "ExternalEurekaTargetConfig" should {

    val format = ExternalEurekaTargetConfig.format

    "target nothing by default" in {
      ExternalEurekaTargetConfig().serviceUrlDefaultZone mustBe None
      ExternalEurekaTargetConfig().eurekaApp mustBe None
    }

    "write the default config with null values" in {
      format.writes(ExternalEurekaTargetConfig()) mustBe Json.obj(
        "service_url_default_zone" -> JsNull,
        "eureka_app"               -> JsNull
      )
    }

    "write the zone url and the app" in {
      format.writes(ExternalEurekaTargetConfig(Some("http://eureka:8761/eureka"), Some("my-app"))) mustBe Json.obj(
        "service_url_default_zone" -> "http://eureka:8761/eureka",
        "eureka_app"               -> "my-app"
      )
    }

    "round trip the default config" in {
      roundTrip(format, ExternalEurekaTargetConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, ExternalEurekaTargetConfig(Some("http://eureka:8761/eureka"), Some("my-app")))
    }

    "round trip a zone url without app" in {
      roundTrip(format, ExternalEurekaTargetConfig(serviceUrlDefaultZone = Some("http://eureka:8761/eureka")))
    }

    "round trip an app without zone url" in {
      roundTrip(format, ExternalEurekaTargetConfig(eurekaApp = Some("my-app")))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe ExternalEurekaTargetConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe ExternalEurekaTargetConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("http://eureka:8761/eureka")) mustBe ExternalEurekaTargetConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr()) mustBe ExternalEurekaTargetConfig()
    }

    "read the zone url alone" in {
      readOk(format, Json.obj("service_url_default_zone" -> "http://e:8761")) mustBe ExternalEurekaTargetConfig(
        serviceUrlDefaultZone = Some("http://e:8761")
      )
    }

    "ignore an app that is not a string" in {
      readOk(format, Json.obj("eureka_app" -> Json.arr("my-app"))) mustBe ExternalEurekaTargetConfig()
    }

    "not read the eureka_server of the internal target config" in {
      readOk(format, Json.obj("eureka_server" -> "route_123")) mustBe ExternalEurekaTargetConfig()
    }

    "not read the camel case keys" in {
      readOk(
        format,
        Json.obj("serviceUrlDefaultZone" -> "http://e:8761", "eurekaApp" -> "my-app")
      ) mustBe ExternalEurekaTargetConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, ExternalEurekaTargetConfig(Some("http://e:8761"), Some("my-app")))
    }

    "be the default config of ExternalEurekaTarget" in {
      defaultConfigReadsBack(new ExternalEurekaTarget(), format) mustBe ExternalEurekaTargetConfig()
    }

    "give ExternalEurekaTarget a default config with its own keys" in {
      new ExternalEurekaTarget().defaultConfig mustBe Some(
        Json.obj("service_url_default_zone" -> JsNull, "eureka_app" -> JsNull)
      )
    }
  }
}
