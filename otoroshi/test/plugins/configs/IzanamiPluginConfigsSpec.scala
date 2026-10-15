package plugins.configs

import otoroshi.next.models.NgTlsConfig
import otoroshi.next.plugins.*
import play.api.libs.json.*

import scala.concurrent.duration.*

class IzanamiPluginConfigsSpec extends PluginConfigFormatSpec {

  private val defaultTlsJson = Json.obj(
    "certs"         -> Json.arr(),
    "trusted_certs" -> Json.arr(),
    "enabled"       -> false,
    "loose"         -> false,
    "trust_all"     -> false
  )

  private val customTls = NgTlsConfig(
    certs = Seq("cert-1"),
    trustedCerts = Seq("ca-1"),
    enabled = true,
    loose = true,
    trustAll = true
  )

  "NgIzanamiV1ProxyConfig" should {

    val format = NgIzanamiV1ProxyConfig.format

    val full = NgIzanamiV1ProxyConfig(
      path = "/izanami",
      featurePattern = "project:*",
      configPattern = "project:config:*",
      autoContext = true,
      featuresEnabled = false,
      featuresWithContextEnabled = false,
      configurationEnabled = true,
      tls = customTls,
      izanamiUrl = "https://izanami.oto.tools",
      clientId = "my-client",
      clientSecret = "my-secret",
      timeout = 2.seconds
    )

    "write the default config with all its keys" in {
      format.writes(NgIzanamiV1ProxyConfig()) mustBe Json.obj(
        "path"                          -> "/api/izanami",
        "feature_pattern"               -> "*",
        "config_pattern"                -> "*",
        "auto_context"                  -> false,
        "features_enabled"              -> true,
        "features_with_context_enabled" -> true,
        "configuration_enabled"         -> false,
        "tls"                           -> defaultTlsJson,
        "izanami_url"                   -> "https://izanami.foo.bar",
        "client_id"                     -> "client",
        "client_secret"                 -> "secret",
        "timeout"                       -> 500
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgIzanamiV1ProxyConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "path"                          -> "/izanami",
        "feature_pattern"               -> "project:*",
        "config_pattern"                -> "project:config:*",
        "auto_context"                  -> true,
        "features_enabled"              -> false,
        "features_with_context_enabled" -> false,
        "configuration_enabled"         -> true,
        "tls"                           -> Json.obj(
          "certs"         -> Json.arr("cert-1"),
          "trusted_certs" -> Json.arr("ca-1"),
          "enabled"       -> true,
          "loose"         -> true,
          "trust_all"     -> true
        ),
        "izanami_url"                   -> "https://izanami.oto.tools",
        "client_id"                     -> "my-client",
        "client_secret"                 -> "my-secret",
        "timeout"                       -> 2000
      )
    }

    "write the timeout in milliseconds" in {
      written(format, NgIzanamiV1ProxyConfig(timeout = 1.minute)).value("timeout") mustBe JsNumber(60000)
    }

    "round trip a tls config with only certificates" in {
      roundTrip(format, NgIzanamiV1ProxyConfig(tls = NgTlsConfig(certs = Seq("a", "b")))).tls.certs mustBe Seq("a", "b")
    }

    "round trip every feature flag turned off" in {
      roundTrip(
        format,
        NgIzanamiV1ProxyConfig(featuresEnabled = false, featuresWithContextEnabled = false)
      )
    }

    "round trip a zero timeout" in {
      roundTrip(format, NgIzanamiV1ProxyConfig(timeout = 0.millis)).timeout mustBe 0.millis
    }

    "read an empty object with a 5 seconds timeout instead of the 500 millis of the default config" in {
      readOk(format, Json.obj()) mustBe NgIzanamiV1ProxyConfig(timeout = 5000.millis)
    }

    "read a json null like an empty object" in {
      readOk(format, JsNull) mustBe NgIzanamiV1ProxyConfig(timeout = 5000.millis)
    }

    "read a json string like an empty object" in {
      readOk(format, JsString("/api/izanami")) mustBe NgIzanamiV1ProxyConfig(timeout = 5000.millis)
    }

    "read a json array like an empty object" in {
      readOk(format, Json.arr()) mustBe NgIzanamiV1ProxyConfig(timeout = 5000.millis)
    }

    "read the path alone and default the rest" in {
      readOk(format, Json.obj("path" -> "/features")) mustBe NgIzanamiV1ProxyConfig(
        path = "/features",
        timeout = 5000.millis
      )
    }

    "read the timeout alone" in {
      readOk(format, Json.obj("timeout" -> 1500)).timeout mustBe 1500.millis
    }

    "read the tls config through the backend tls format, with its legacy certId key" in {
      readOk(format, Json.obj("tls" -> Json.obj("certId" -> "cert-1", "enabled" -> true))).tls mustBe NgTlsConfig(
        certs = Seq("cert-1"),
        enabled = true
      )
    }

    "read a tls config that is not an object as the default tls config" in {
      readOk(format, Json.obj("tls" -> "enabled")).tls mustBe NgTlsConfig.default
    }

    "ignore an auto_context written as a string" in {
      readOk(format, Json.obj("auto_context" -> "true")).autoContext mustBe false
    }

    "ignore a features_enabled written as a number" in {
      readOk(format, Json.obj("features_enabled" -> 0)).featuresEnabled mustBe true
    }

    "ignore a timeout written as a string" in {
      readOk(format, Json.obj("timeout" -> "1000")).timeout mustBe 5000.millis
    }

    "ignore a timeout that is not a whole number" in {
      readOk(format, Json.obj("timeout" -> 1000.5)).timeout mustBe 5000.millis
    }

    "ignore an izanami_url that is not a string" in {
      readOk(format, Json.obj("izanami_url" -> Json.obj())).izanamiUrl mustBe "https://izanami.foo.bar"
    }

    "not read the camel case keys" in {
      val back = readOk(
        format,
        Json.obj("featurePattern" -> "p:*", "izanamiUrl" -> "https://other", "clientId" -> "c", "timeout" -> 500)
      )
      back mustBe NgIzanamiV1ProxyConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of NgIzanamiV1Proxy" in {
      defaultConfigReadsBack(new NgIzanamiV1Proxy(), format) mustBe NgIzanamiV1ProxyConfig()
    }
  }

  "NgIzanamiV1CanaryConfig" should {

    val format = NgIzanamiV1CanaryConfig.format

    val routeConfig = Json.obj("routes" -> Json.arr())

    "write the default config with all its keys" in {
      format.writes(NgIzanamiV1CanaryConfig()) mustBe Json.obj(
        "experiment_id" -> "foo:bar:qix",
        "config_id"     -> "foo:bar:qix:config",
        "izanami_url"   -> "https://izanami.foo.bar",
        "tls"           -> defaultTlsJson,
        "client_id"     -> "client",
        "client_secret" -> "secret",
        "timeout"       -> 5000,
        "route_config"  -> JsNull
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgIzanamiV1CanaryConfig())
    }

    "round trip the tls config and the timeout" in {
      roundTrip(format, NgIzanamiV1CanaryConfig(tls = customTls, timeout = 750.millis))
    }

    "write a route config as an object under route_config" in {
      written(format, NgIzanamiV1CanaryConfig(routeConfig = Some(routeConfig))).value("route_config") mustBe routeConfig
    }

    "write the non default identifiers under snake case keys" in {
      val json = written(
        format,
        NgIzanamiV1CanaryConfig(
          experimentId = "p:exp",
          configId = "p:exp:config",
          izanamiUrl = "https://izanami.oto.tools",
          clientId = "my-client",
          clientSecret = "my-secret"
        )
      )
      json.value("experiment_id") mustBe JsString("p:exp")
      json.value("config_id") mustBe JsString("p:exp:config")
      json.value("izanami_url") mustBe JsString("https://izanami.oto.tools")
      json.value("client_id") mustBe JsString("my-client")
      json.value("client_secret") mustBe JsString("my-secret")
    }

    "round trip a config where every field is set" in {
      roundTrip(
        format,
        NgIzanamiV1CanaryConfig(
          experimentId = "p:exp",
          configId = "p:exp:config",
          izanamiUrl = "https://izanami.oto.tools",
          tls = customTls,
          clientId = "my-client",
          clientSecret = "my-secret",
          timeout = 750.millis,
          routeConfig = Some(routeConfig)
        )
      )
    }

    "round trip an experiment id alone" in {
      roundTrip(format, NgIzanamiV1CanaryConfig(experimentId = "p:exp")).experimentId mustBe "p:exp"
    }

    "round trip a config id alone" in {
      roundTrip(format, NgIzanamiV1CanaryConfig(configId = "p:exp:config")).configId mustBe "p:exp:config"
    }

    "round trip an izanami url alone" in {
      roundTrip(format, NgIzanamiV1CanaryConfig(izanamiUrl = "https://izanami.oto.tools")).izanamiUrl mustBe "https://izanami.oto.tools"
    }

    "round trip client credentials alone" in {
      val back = roundTrip(format, NgIzanamiV1CanaryConfig(clientId = "my-client", clientSecret = "my-secret"))
      back.clientId mustBe "my-client"
      back.clientSecret mustBe "my-secret"
    }

    "round trip a route config alone" in {
      roundTrip(format, NgIzanamiV1CanaryConfig(routeConfig = Some(routeConfig))).routeConfig mustBe Some(routeConfig)
    }

    "read every field from the snake case keys of the ui form" in {
      val json = Json.obj(
        "experiment_id" -> "p:exp",
        "config_id"     -> "p:exp:config",
        "izanami_url"   -> "https://izanami.oto.tools",
        "tls"           -> NgTlsConfig.format.writes(customTls),
        "client_id"     -> "my-client",
        "client_secret" -> "my-secret",
        "timeout"       -> 750,
        "route_config"  -> routeConfig
      )
      readOk(format, json) mustBe NgIzanamiV1CanaryConfig(
        experimentId = "p:exp",
        configId = "p:exp:config",
        izanamiUrl = "https://izanami.oto.tools",
        tls = customTls,
        clientId = "my-client",
        clientSecret = "my-secret",
        timeout = 750.millis,
        routeConfig = Some(routeConfig)
      )
    }

    "prefer the snake case keys over the camel case ones" in {
      val back = readOk(
        format,
        Json.obj(
          "experiment_id" -> "snake",
          "experimentId"  -> "camel",
          "client_id"     -> "snake-client",
          "clientId"      -> "camel-client",
          "route_config"  -> routeConfig,
          "routeConfig"   -> Json.obj("routes" -> Json.arr("camel"))
        )
      )
      back.experimentId mustBe "snake"
      back.clientId mustBe "snake-client"
      back.routeConfig mustBe Some(routeConfig)
    }

    "fall back to the camel case key when the snake case one is not a string" in {
      readOk(format, Json.obj("experiment_id" -> 42, "experimentId" -> "camel")).experimentId mustBe "camel"
    }

    "still read every field from its camel case key" in {
      val json = Json.obj(
        "experimentId" -> "p:exp",
        "configId"     -> "p:exp:config",
        "izanamiUrl"   -> "https://izanami.oto.tools",
        "tls"          -> NgTlsConfig.format.writes(customTls),
        "clientId"     -> "my-client",
        "clientSecret" -> "my-secret",
        "timeout"      -> 750,
        "routeConfig"  -> routeConfig
      )
      readOk(format, json) mustBe NgIzanamiV1CanaryConfig(
        experimentId = "p:exp",
        configId = "p:exp:config",
        izanamiUrl = "https://izanami.oto.tools",
        tls = customTls,
        clientId = "my-client",
        clientSecret = "my-secret",
        timeout = 750.millis,
        routeConfig = Some(routeConfig)
      )
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgIzanamiV1CanaryConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgIzanamiV1CanaryConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("foo:bar:qix")) mustBe NgIzanamiV1CanaryConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr()) mustBe NgIzanamiV1CanaryConfig()
    }

    "read the timeout alone and default the rest" in {
      readOk(format, Json.obj("timeout" -> 100)) mustBe NgIzanamiV1CanaryConfig(timeout = 100.millis)
    }

    "read a null route config as no route config" in {
      readOk(format, Json.obj("routeConfig" -> JsNull)).routeConfig mustBe None
    }

    "ignore a route config that is not an object" in {
      readOk(format, Json.obj("routeConfig" -> Json.arr())).routeConfig mustBe None
    }

    "ignore a timeout written as a string" in {
      readOk(format, Json.obj("timeout" -> "100")).timeout mustBe 5000.millis
    }

    "ignore an experiment id that is not a string" in {
      readOk(format, Json.obj("experimentId" -> 42)).experimentId mustBe "foo:bar:qix"
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgIzanamiV1CanaryConfig(routeConfig = Some(routeConfig)))
    }

    "be the default config of NgIzanamiV1Canary" in {
      defaultConfigReadsBack(new NgIzanamiV1Canary(), format) mustBe NgIzanamiV1CanaryConfig()
    }
  }

  "NgIzanamiV1CanaryRoutingConfigRoute" should {

    val format = NgIzanamiV1CanaryRoutingConfigRoute.format

    val route = NgIzanamiV1CanaryRoutingConfigRoute(
      route = "/api",
      variants = Map("A" -> "/api/a", "B" -> "/api/b"),
      default = "/api/default",
      wildcard = true,
      exact = true,
      regex = true
    )

    "write all its keys" in {
      format.writes(route) mustBe Json.obj(
        "route"    -> "/api",
        "variants" -> Json.obj("A" -> "/api/a", "B" -> "/api/b"),
        "default"  -> "/api/default",
        "wildcard" -> true,
        "exact"    -> true,
        "regex"    -> true
      )
    }

    "round trip a route where every field is set" in {
      roundTrip(format, route)
    }

    "round trip a route without variant nor flag" in {
      roundTrip(format, NgIzanamiV1CanaryRoutingConfigRoute("/", Map.empty, "/", false, false, false))
    }

    "read a route with only its route and default" in {
      readOk(format, Json.obj("route" -> "/api", "default" -> "/d")) mustBe NgIzanamiV1CanaryRoutingConfigRoute(
        "/api",
        Map.empty,
        "/d",
        false,
        false,
        false
      )
    }

    "refuse a route without route" in {
      readError(format, Json.obj("default" -> "/d"))
    }

    "refuse a route without default" in {
      readError(format, Json.obj("route" -> "/api"))
    }
  }

  "NgIzanamiV1CanaryRoutingConfig" should {

    val format = NgIzanamiV1CanaryRoutingConfig.format

    val route = NgIzanamiV1CanaryRoutingConfigRoute("/api", Map("A" -> "/a"), "/d", false, true, false)

    "write the routes under routes" in {
      format.writes(NgIzanamiV1CanaryRoutingConfig(Seq(route))) mustBe Json.obj(
        "routes" -> Json.arr(NgIzanamiV1CanaryRoutingConfigRoute.format.writes(route))
      )
    }

    "round trip several routes and keep their order" in {
      val other = route.copy(route = "/other")
      roundTrip(format, NgIzanamiV1CanaryRoutingConfig(Seq(other, route))).routes mustBe Seq(other, route)
    }

    "round trip no route" in {
      roundTrip(format, NgIzanamiV1CanaryRoutingConfig(Seq.empty))
    }

    "drop the routes it cannot read and keep the others" in {
      val json = Json.obj(
        "routes" -> Json.arr(Json.obj("route" -> "/broken"), NgIzanamiV1CanaryRoutingConfigRoute.format.writes(route))
      )
      readOk(format, json).routes mustBe Seq(route)
    }

    "read an empty object as no route" in {
      readOk(format, Json.obj()).routes mustBe empty
    }

    "read routes that are not an array as no route" in {
      readOk(format, Json.obj("routes" -> NgIzanamiV1CanaryRoutingConfigRoute.format.writes(route))).routes mustBe empty
    }
  }

  "IzanamiV2ProxyConfig" should {

    val format = IzanamiV2ProxyConfig.format

    val full = IzanamiV2ProxyConfig(
      tls = customTls,
      url = "https://izanami.oto.tools",
      clientId = "my-client",
      clientSecret = "my-secret",
      timeout = 2.seconds,
      context = Some("prod/eu")
    )

    val minimal = Json.obj("url" -> "", "clientId" -> "", "clientSecret" -> "", "timeout" -> 5000)

    "write the default config with all its keys" in {
      format.writes(IzanamiV2ProxyConfig()) mustBe Json.obj(
        "tls"          -> defaultTlsJson,
        "url"          -> "",
        "clientId"     -> "",
        "clientSecret" -> "",
        "context"      -> JsNull,
        "timeout"      -> 5000
      )
    }

    "round trip the default config" in {
      roundTrip(format, IzanamiV2ProxyConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "tls"          -> NgTlsConfig.format.writes(customTls),
        "url"          -> "https://izanami.oto.tools",
        "clientId"     -> "my-client",
        "clientSecret" -> "my-secret",
        "context"      -> "prod/eu",
        "timeout"      -> 2000
      )
    }

    "round trip a context" in {
      roundTrip(format, IzanamiV2ProxyConfig(context = Some("dev"))).context mustBe Some("dev")
    }

    "round trip no context" in {
      roundTrip(format, full.copy(context = None)).context mustBe None
    }

    "read the mandatory keys alone and default the tls config and the context" in {
      readOk(format, minimal) mustBe IzanamiV2ProxyConfig()
    }

    "read a null context as no context" in {
      readOk(format, minimal ++ Json.obj("context" -> JsNull)).context mustBe None
    }

    "ignore a context that is not a string" in {
      readOk(format, minimal ++ Json.obj("context" -> 1)).context mustBe None
    }

    "read a tls config that is not an object as the default tls config" in {
      readOk(format, minimal ++ Json.obj("tls" -> true)).tls mustBe NgTlsConfig.default
    }

    "refuse an empty object" in {
      readError(format, Json.obj())
    }

    "refuse a json null" in {
      readError(format, JsNull)
    }

    "refuse a json string" in {
      readError(format, JsString("https://izanami.oto.tools"))
    }

    "refuse a json array" in {
      readError(format, Json.arr())
    }

    "refuse a config without url" in {
      readError(format, minimal - "url")
    }

    "refuse a config without client id" in {
      readError(format, minimal - "clientId")
    }

    "refuse a config without client secret" in {
      readError(format, minimal - "clientSecret")
    }

    "refuse a config without timeout" in {
      readError(format, minimal - "timeout")
    }

    "refuse a timeout written as a string" in {
      readError(format, minimal ++ Json.obj("timeout" -> "5000"))
    }

    "refuse an url that is not a string" in {
      readError(format, minimal ++ Json.obj("url" -> 42))
    }

    "refuse the snake case keys of the v1 configs" in {
      readError(
        format,
        Json.obj("url" -> "", "client_id" -> "c", "client_secret" -> "s", "timeout" -> 5000)
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of IzanamiV2Proxy" in {
      defaultConfigReadsBack(new IzanamiV2Proxy(), format) mustBe IzanamiV2ProxyConfig()
    }
  }
}
