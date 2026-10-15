package plugins.configs

import otoroshi.next.models.NgTlsConfig
import otoroshi.next.plugins.*
import play.api.libs.json.*

import scala.concurrent.duration.*

class OpenFgaPluginConfigsSpec extends PluginConfigFormatSpec {

  "OpenFGAValidatorConfig" should {

    val format = OpenFGAValidatorConfig.format

    val tupleKey = Json.obj(
      "user"     -> "user:${user.email}",
      "relation" -> "viewer",
      "object"   -> "document:${req.pathparams.id}"
    )

    val contextualTuples = Json.arr(
      Json.obj("user" -> "user:anne", "relation" -> "member", "object" -> "group:eng"),
      Json.obj("user" -> "group:eng#member", "relation" -> "viewer", "object" -> "folder:root")
    )

    val full = OpenFGAValidatorConfig(
      url = "https://fga.oto.tools",
      token = Some("fga-token"),
      tlsConfig = NgTlsConfig(certs = Seq("client"), trustedCerts = Seq("ca"), enabled = true, loose = true),
      timeout = 3.seconds,
      storeId = "01HSTORE",
      modelId = "01HMODEL",
      tupleKey = tupleKey,
      contextualTuples = contextualTuples,
      cache = true,
      ttl = 1.minute
    )

    "check against a local server without cache by default" in {
      val config = OpenFGAValidatorConfig.default
      config.url mustBe "http://localhost:8088"
      config.token mustBe None
      config.tlsConfig mustBe NgTlsConfig()
      config.timeout mustBe 10.seconds
      config.storeId mustBe "--"
      config.modelId mustBe "--"
      config.tupleKey mustBe Json.obj()
      config.contextualTuples mustBe Json.arr()
      config.cache mustBe false
      config.ttl mustBe 10.seconds
    }

    "write the default config with durations in milliseconds and a null token" in {
      format.writes(OpenFGAValidatorConfig.default) mustBe Json.obj(
        "url"               -> "http://localhost:8088",
        "token"             -> JsNull,
        "tls_config"        -> Json.obj(
          "certs"         -> Json.arr(),
          "trusted_certs" -> Json.arr(),
          "enabled"       -> false,
          "loose"         -> false,
          "trust_all"     -> false
        ),
        "timeout"           -> 10000,
        "store_id"          -> "--",
        "model_id"          -> "--",
        "tuple_key"         -> Json.obj(),
        "contextual_tuples" -> Json.arr(),
        "cache"             -> false,
        "ttl"               -> 10000
      )
    }

    "write the token as a string" in {
      (format.writes(OpenFGAValidatorConfig(token = Some("t"))) \ "token").get mustBe JsString("t")
    }

    "round trip the default config" in {
      roundTrip(format, OpenFGAValidatorConfig.default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a token alone" in {
      roundTrip(format, OpenFGAValidatorConfig(token = Some("fga-token"))).token mustBe Some("fga-token")
    }

    "round trip an empty token as a defined token" in {
      roundTrip(format, OpenFGAValidatorConfig(token = Some(""))).token mustBe Some("")
    }

    "round trip a tuple key holding expression language and nested values" in {
      val nested = tupleKey ++ Json.obj("condition" -> Json.obj("name" -> "ip", "context" -> Json.obj("n" -> 1)))
      roundTrip(format, OpenFGAValidatorConfig(tupleKey = nested)).tupleKey mustBe nested
    }

    "round trip contextual tuples and keep their order" in {
      roundTrip(format, OpenFGAValidatorConfig(contextualTuples = contextualTuples)).contextualTuples mustBe contextualTuples
    }

    "round trip durations with a millisecond precision" in {
      val back = roundTrip(format, OpenFGAValidatorConfig(timeout = 1500.millis, ttl = 1.millis))
      back.timeout mustBe 1500.millis
      back.ttl mustBe 1.millis
    }

    "fail to read an empty object because the url is mandatory" in {
      readError(format, Json.obj())
    }

    "fail to read a json null" in {
      readError(format, JsNull)
    }

    "fail to read a json string" in {
      readError(format, JsString("http://localhost:8088"))
    }

    "fail to read a json array" in {
      readError(format, Json.arr("http://localhost:8088"))
    }

    "fail to read a url that is not a string" in {
      readError(format, Json.obj("url" -> 8088))
    }

    "read the url alone and default the rest" in {
      readOk(format, Json.obj("url" -> "https://fga.oto.tools")) mustBe OpenFGAValidatorConfig(url = "https://fga.oto.tools")
    }

    "read the cache settings alone" in {
      readOk(format, Json.obj("url" -> "http://fga", "cache" -> true, "ttl" -> 500)) mustBe OpenFGAValidatorConfig(
        url = "http://fga",
        cache = true,
        ttl = 500.millis
      )
    }

    "read a null token as no token" in {
      readOk(format, Json.obj("url" -> "http://fga", "token" -> JsNull)).token mustBe None
    }

    "ignore a token that is not a string" in {
      readOk(format, Json.obj("url" -> "http://fga", "token" -> 42)).token mustBe None
    }

    "read a tls config given as a json string as the default tls config" in {
      readOk(format, Json.obj("url" -> "http://fga", "tls_config" -> "{\"enabled\":true}")).tlsConfig mustBe NgTlsConfig()
    }

    "read a partial tls config and default its other fields" in {
      readOk(
        format,
        Json.obj("url" -> "http://fga", "tls_config" -> Json.obj("trust_all" -> true))
      ).tlsConfig mustBe NgTlsConfig(trustAll = true)
    }

    "ignore a timeout written as a string" in {
      readOk(format, Json.obj("url" -> "http://fga", "timeout" -> "5000")).timeout mustBe 10.seconds
    }

    "ignore a ttl that is not a whole number" in {
      readOk(format, Json.obj("url" -> "http://fga", "ttl" -> 1.5)).ttl mustBe 10.seconds
    }

    "ignore a tuple key that is not an object" in {
      readOk(format, Json.obj("url" -> "http://fga", "tuple_key" -> "{\"user\":\"anne\"}")).tupleKey mustBe Json.obj()
    }

    "ignore contextual tuples that are not an array" in {
      readOk(
        format,
        Json.obj("url" -> "http://fga", "contextual_tuples" -> Json.obj("tuple_keys" -> Json.arr()))
      ).contextualTuples mustBe Json.arr()
    }

    "ignore a cache flag written as a string" in {
      readOk(format, Json.obj("url" -> "http://fga", "cache" -> "true")).cache mustBe false
    }

    "ignore ids that are not strings" in {
      val back = readOk(format, Json.obj("url" -> "http://fga", "store_id" -> 1, "model_id" -> true))
      back.storeId mustBe "--"
      back.modelId mustBe "--"
    }

    "not read the camel case keys" in {
      val back = readOk(format, Json.obj("url" -> "http://fga", "storeId" -> "s", "modelId" -> "m", "tupleKey" -> tupleKey))
      back mustBe OpenFGAValidatorConfig(url = "http://fga")
    }

    "build the same cache key for the same check" in {
      full.asKey mustBe full.copy().asKey
      full.asKey.length mustBe 128
    }

    "build a cache key that does not depend on the token, the timeouts, the cache and the tls settings" in {
      full.asKey mustBe full.copy(token = None, timeout = 1.second, cache = false, ttl = 1.second, tlsConfig = NgTlsConfig()).asKey
    }

    "build another cache key when the checked tuple changes" in {
      full.asKey must not be full.copy(storeId = "other").asKey
      full.asKey must not be full.copy(modelId = "other").asKey
      full.asKey must not be full.copy(tupleKey = Json.obj("user" -> "user:bob")).asKey
      full.asKey must not be full.copy(contextualTuples = Json.arr()).asKey
      full.asKey must not be full.copy(url = "https://other.oto.tools").asKey
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "list in its config flow only keys of its config schema" in {
      OpenFGAValidatorConfig.configFlow.foreach { key =>
        OpenFGAValidatorConfig.configSchema.keys must contain(key)
      }
    }

    "expose its config flow and schema through its plugin" in {
      val plugin = new OpenFGAValidator()
      plugin.configFlow mustBe OpenFGAValidatorConfig.configFlow
      plugin.configSchema mustBe Some(OpenFGAValidatorConfig.configSchema)
    }

    "be the default config of OpenFGAValidator" in {
      defaultConfigReadsBack(new OpenFGAValidator(), format) mustBe OpenFGAValidatorConfig.default
    }
  }
}
