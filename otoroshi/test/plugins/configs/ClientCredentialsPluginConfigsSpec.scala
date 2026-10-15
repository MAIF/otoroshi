package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

import scala.concurrent.duration.*

class ClientCredentialsPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgClientCredentialsConfig" should {

    val format = NgClientCredentialsConfig.format

    val biscuit = BiscuitConf(
      privkey = Some("ed25519/0123456789abcdef"),
      checks = Seq("check if time($t), $t < 2030-01-01T00:00:00Z"),
      facts = Seq("fact(\"a\")", "fact(\"b\")"),
      rules = Seq("allowed($a) <- fact($a)")
    )

    val custom = NgClientCredentialsConfig(
      expiration = 10.minutes,
      defaultKeyPair = "my-key-pair",
      domain = "auth.oto.tools",
      secure = false,
      biscuit = Some(biscuit),
      jwksCacheTtl = Some(30.seconds),
      maxBodySize = 1024L
    )

    "have a one hour expiration and a 4 megabytes body limit by default" in {
      val config = NgClientCredentialsConfig()
      config.expiration mustBe 1.hour
      config.defaultKeyPair mustBe "otoroshi-jwt-signing"
      config.domain mustBe "*"
      config.secure mustBe true
      config.biscuit mustBe None
      config.jwksCacheTtl mustBe None
      config.maxBodySize mustBe 4L * 1024L * 1024L
      NgClientCredentialsConfig.defaultMaxBodySize mustBe 4194304L
    }

    "write the default config" in {
      format.writes(NgClientCredentialsConfig()) mustBe Json.obj(
        "expiration"       -> 3600000,
        "default_key_pair" -> "otoroshi-jwt-signing",
        "domain"           -> "*",
        "secure"           -> true,
        "biscuit"          -> JsNull,
        "jwks_cache_ttl"   -> JsNull,
        "max_body_size"    -> 4194304
      )
    }

    "write the durations in milliseconds" in {
      val json = written(format, custom)
      (json \ "expiration").as[Long] mustBe 600000L
      (json \ "jwks_cache_ttl").as[Long] mustBe 30000L
    }

    "write the biscuit config as an object" in {
      (written(format, custom) \ "biscuit").get mustBe Json.obj(
        "privkey" -> "ed25519/0123456789abcdef",
        "checks"  -> Json.arr("check if time($t), $t < 2030-01-01T00:00:00Z"),
        "facts"   -> Json.arr("fact(\"a\")", "fact(\"b\")"),
        "rules"   -> Json.arr("allowed($a) <- fact($a)")
      )
    }

    "round trip a config where every field is set" in {
      roundTrip(format, custom)
    }

    "round trip an empty biscuit config" in {
      roundTrip(format, NgClientCredentialsConfig(biscuit = Some(BiscuitConf()))).biscuit mustBe Some(BiscuitConf())
    }

    "round trip a biscuit config without private key" in {
      roundTrip(format, custom.copy(biscuit = Some(biscuit.copy(privkey = None)))).biscuit.get.privkey mustBe None
    }

    "round trip a jwks cache ttl" in {
      roundTrip(format, custom.copy(jwksCacheTtl = Some(2.hours))).jwksCacheTtl mustBe Some(2.hours)
    }

    "round trip a config without jwks cache ttl" in {
      roundTrip(format, custom.copy(jwksCacheTtl = None)).jwksCacheTtl mustBe None
    }

    "round trip a zero expiration" in {
      roundTrip(format, custom.copy(expiration = Duration.Zero)).expiration mustBe Duration.Zero
    }

    "round trip the largest body limit" in {
      roundTrip(format, custom.copy(maxBodySize = Long.MaxValue))
    }

    "truncate the durations to the millisecond" in {
      val back = readOk(format, format.writes(custom.copy(expiration = 1500.micros, jwksCacheTtl = Some(999.micros))))
      back.expiration mustBe 1.milli
      back.jwksCacheTtl mustBe Some(Duration.Zero)
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgClientCredentialsConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgClientCredentialsConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("*")) mustBe NgClientCredentialsConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(Json.obj("domain" -> "auth.oto.tools"))) mustBe NgClientCredentialsConfig()
    }

    "read a lone domain and default the rest" in {
      readOk(format, Json.obj("domain" -> "auth.oto.tools")) mustBe NgClientCredentialsConfig(domain = "auth.oto.tools")
    }

    "read a lone expiration in milliseconds" in {
      readOk(format, Json.obj("expiration" -> 60000)) mustBe NgClientCredentialsConfig(expiration = 1.minute)
    }

    "read a lone biscuit config" in {
      readOk(format, Json.obj("biscuit" -> Json.obj("facts" -> Json.arr("f")))) mustBe NgClientCredentialsConfig(
        biscuit = Some(BiscuitConf(facts = Seq("f")))
      )
    }

    "ignore durations written as strings" in {
      val back = readOk(format, Json.obj("expiration" -> "60000", "jwks_cache_ttl" -> "1000"))
      back.expiration mustBe 1.hour
      back.jwksCacheTtl mustBe None
    }

    "ignore a secure flag written as a string" in {
      readOk(format, Json.obj("secure" -> "false")).secure mustBe true
    }

    "ignore a body limit that is not a whole number" in {
      readOk(format, Json.obj("max_body_size" -> 10.5)).maxBodySize mustBe NgClientCredentialsConfig.defaultMaxBodySize
    }

    "not read the camel case keys" in {
      readOk(
        format,
        Json.obj("defaultKeyPair" -> "kp", "maxBodySize" -> 1, "jwksCacheTtl" -> 1000)
      ) mustBe NgClientCredentialsConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, custom)
    }
  }

  "BiscuitConf" should {

    val format = BiscuitConf.format

    "write an empty config with a null private key" in {
      format.writes(BiscuitConf()) mustBe Json.obj(
        "privkey" -> JsNull,
        "checks"  -> Json.arr(),
        "facts"   -> Json.arr(),
        "rules"   -> Json.arr()
      )
    }

    "round trip a config where every field is set" in {
      roundTrip(format, BiscuitConf(Some("key"), Seq("c1", "c2"), Seq("f1"), Seq("r1", "r2", "r3")))
    }

    "read an empty object as an empty config" in {
      readOk(format, Json.obj()) mustBe BiscuitConf()
    }

    "ignore lists of the wrong type" in {
      readOk(format, Json.obj("checks" -> "c", "facts" -> Json.arr(1), "rules" -> Json.obj())) mustBe BiscuitConf()
    }

    "ignore a private key that is not a string" in {
      readOk(format, Json.obj("privkey" -> 42)).privkey mustBe None
    }
  }

  "NgClientCredentialTokenEndpointConfig" should {

    val format = NgClientCredentialTokenEndpointConfig.format

    val custom = NgClientCredentialTokenEndpointConfig(
      expiration = 5.minutes,
      defaultKeyPair = "my-key-pair",
      allowedApikeys = Seq("apikey-1", "apikey-2"),
      allowedGroups = Seq("group-1"),
      maxBodySize = 2048L
    )

    "have a one hour expiration, the default key pair and nothing allowed by default" in {
      NgClientCredentialTokenEndpointConfig.default mustBe NgClientCredentialTokenEndpointConfig(
        1.hour,
        "otoroshi-jwt-signing",
        Seq.empty,
        Seq.empty,
        4194304L
      )
    }

    "share its default body limit with the client credentials service" in {
      NgClientCredentialTokenEndpointConfig.default.maxBodySize mustBe NgClientCredentialsConfig.defaultMaxBodySize
    }

    "write the default config" in {
      format.writes(NgClientCredentialTokenEndpointConfig.default) mustBe Json.obj(
        "expiration"       -> 3600000,
        "default_key_pair" -> "otoroshi-jwt-signing",
        "allowed_apikeys"  -> Json.arr(),
        "allowed_groups"   -> Json.arr(),
        "max_body_size"    -> 4194304
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgClientCredentialTokenEndpointConfig.default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, custom)
    }

    "keep the order of the allowed apikeys and groups" in {
      val back = roundTrip(format, custom.copy(allowedApikeys = Seq("c", "a", "b"), allowedGroups = Seq("z", "y")))
      back.allowedApikeys mustBe Seq("c", "a", "b")
      back.allowedGroups mustBe Seq("z", "y")
    }

    "round trip a zero body limit" in {
      roundTrip(format, custom.copy(maxBodySize = 0L)).maxBodySize mustBe 0L
    }

    "write the expiration in milliseconds" in {
      (written(format, custom) \ "expiration").as[Long] mustBe 300000L
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgClientCredentialTokenEndpointConfig.default
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgClientCredentialTokenEndpointConfig.default
    }

    "read a json string as the default config" in {
      readOk(format, JsString("apikey-1")) mustBe NgClientCredentialTokenEndpointConfig.default
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("apikey-1")) mustBe NgClientCredentialTokenEndpointConfig.default
    }

    "read lone allowed apikeys and default the rest" in {
      readOk(format, Json.obj("allowed_apikeys" -> Json.arr("apikey-1"))) mustBe NgClientCredentialTokenEndpointConfig.default
        .copy(allowedApikeys = Seq("apikey-1"))
    }

    "read a blank key pair as the default key pair" in {
      readOk(format, Json.obj("default_key_pair" -> "  ")).defaultKeyPair mustBe "otoroshi-jwt-signing"
      readOk(format, Json.obj("default_key_pair" -> "")).defaultKeyPair mustBe "otoroshi-jwt-signing"
    }

    "ignore an expiration written as a string" in {
      readOk(format, Json.obj("expiration" -> "1000")).expiration mustBe 1.hour
    }

    "ignore allowed lists of the wrong type" in {
      readOk(
        format,
        Json.obj("allowed_apikeys" -> "apikey-1", "allowed_groups" -> Json.arr("group-1", 2))
      ) mustBe NgClientCredentialTokenEndpointConfig.default
    }

    "ignore a body limit written as a string" in {
      readOk(format, Json.obj("max_body_size" -> "1024")).maxBodySize mustBe NgClientCredentialsConfig.defaultMaxBodySize
    }

    "not read the camel case keys" in {
      readOk(
        format,
        Json.obj("allowedApikeys" -> Json.arr("a"), "allowedGroups" -> Json.arr("g"), "defaultKeyPair" -> "kp")
      ) mustBe NgClientCredentialTokenEndpointConfig.default
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, custom)
    }

    "be the default config of NgClientCredentialTokenEndpoint" in {
      defaultConfigReadsBack(new NgClientCredentialTokenEndpoint(), format) mustBe NgClientCredentialTokenEndpointConfig.default
    }
  }
}
