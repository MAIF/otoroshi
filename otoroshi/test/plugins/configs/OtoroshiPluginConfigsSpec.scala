package plugins.configs

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.KeyType
import otoroshi.models.{
  ESAlgoSettings,
  ESKPAlgoSettings,
  HSAlgoSettings,
  JWKSAlgoSettings,
  KidAlgoSettings,
  RSAKPAlgoSettings,
  RSAlgoSettings,
  SecComInfoTokenVersion,
  SecComVersion
}
import otoroshi.next.plugins.*
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.infotoken.AddFieldsSettings
import play.api.libs.json.*

import scala.concurrent.duration.*

class OtoroshiPluginConfigsSpec extends PluginConfigFormatSpec {

  private val defaultAlgo     = HSAlgoSettings(512, "secret", false)
  private val defaultAlgoJson = Json.obj("type" -> "HSAlgoSettings", "size" -> 512, "secret" -> "secret", "base64" -> false)

  "NgOtoroshiChallengeConfig" should {

    val format  = NgOtoroshiChallengeConfig.format
    val default = NgOtoroshiChallengeConfig(Json.obj())

    val full = NgOtoroshiChallengeConfig(
      secComVersion = SecComVersion.V1,
      secComTtl = 2.minutes,
      requestHeaderName = Some("X-Challenge"),
      responseHeaderName = Some("X-Challenge-Response"),
      algoOtoToBackend = RSAlgoSettings(256, "public-key", Some("private-key")),
      algoBackendToOto = ESAlgoSettings(384, "public-key", None),
      stateRespLeeway = 42
    )

    "use the V2 protocol, a 30 seconds ttl and HS512 by default" in {
      default mustBe NgOtoroshiChallengeConfig(
        secComVersion = SecComVersion.V2,
        secComTtl = 30.seconds,
        requestHeaderName = None,
        responseHeaderName = None,
        algoOtoToBackend = defaultAlgo,
        algoBackendToOto = defaultAlgo,
        stateRespLeeway = 10
      )
    }

    "write the default config" in {
      format.writes(default) mustBe Json.obj(
        "version"              -> "V2",
        "ttl"                  -> 30,
        "request_header_name"  -> JsNull,
        "response_header_name" -> JsNull,
        "algo_to_backend"      -> defaultAlgoJson,
        "algo_from_backend"    -> defaultAlgoJson,
        "state_resp_leeway"    -> 10
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "version"              -> "V1",
        "ttl"                  -> 120,
        "request_header_name"  -> "X-Challenge",
        "response_header_name" -> "X-Challenge-Response",
        "algo_to_backend"      -> Json.obj(
          "type"       -> "RSAlgoSettings",
          "size"       -> 256,
          "publicKey"  -> "public-key",
          "privateKey" -> "private-key"
        ),
        "algo_from_backend"    -> Json.obj(
          "type"       -> "ESAlgoSettings",
          "size"       -> 384,
          "publicKey"  -> "public-key",
          "privateKey" -> JsNull
        ),
        "state_resp_leeway"    -> 42
      )
    }

    "round trip the default config" in {
      roundTrip(format, default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip the V1 protocol alone" in {
      roundTrip(format, default.copy(secComVersion = SecComVersion.V1)).secComVersion mustBe SecComVersion.V1
    }

    "round trip a request header name without response header name" in {
      roundTrip(format, default.copy(requestHeaderName = Some("X-State")))
    }

    "round trip a response header name without request header name" in {
      roundTrip(format, default.copy(responseHeaderName = Some("X-State-Resp")))
    }

    "round trip a base64 HS secret" in {
      roundTrip(format, default.copy(algoOtoToBackend = HSAlgoSettings(256, "c2VjcmV0", base64 = true)))
    }

    "round trip key pair algorithms" in {
      roundTrip(
        format,
        default.copy(algoOtoToBackend = RSAKPAlgoSettings(512, "cert_1"), algoBackendToOto = ESKPAlgoSettings(256, "cert_2"))
      )
    }

    "round trip a kid algorithm" in {
      roundTrip(format, default.copy(algoBackendToOto = KidAlgoSettings(onlyExposedCerts = true)))
    }

    "round trip a jwks algorithm" in {
      val jwks = JWKSAlgoSettings(
        url = "https://keys.oto.tools/.well-known/jwks.json",
        headers = Map("X-Foo" -> "bar"),
        timeout = 3.seconds,
        ttl = 10.minutes,
        kty = KeyType.EC,
        proxy = None,
        tlsConfig = MtlsConfig(certs = Seq("cert_1"), mtls = true)
      )
      roundTrip(format, default.copy(algoBackendToOto = jwks)).algoBackendToOto mustBe jwks
    }

    "write the ttl in seconds and truncate what is below the second" in {
      val json = format.writes(default.copy(secComTtl = 1500.millis))
      (json \ "ttl").as[Long] mustBe 1L
      readOk(format, json).secComTtl mustBe 1.second
    }

    "round trip a zero state leeway" in {
      roundTrip(format, default.copy(stateRespLeeway = 0)).stateRespLeeway mustBe 0
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe default
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe default
    }

    "read a json string as the default config" in {
      readOk(format, JsString("V1")) mustBe default
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("V1")) mustBe default
    }

    "read what apply reads" in {
      val json = Json.obj("version" -> "V1", "ttl" -> 5)
      NgOtoroshiChallengeConfig(json) mustBe readOk(format, json)
    }

    "read the version written as a number" in {
      readOk(format, Json.obj("version" -> 1)).secComVersion mustBe SecComVersion.V1
      readOk(format, Json.obj("version" -> 2)).secComVersion mustBe SecComVersion.V2
    }

    "read the version written in lower case" in {
      readOk(format, Json.obj("version" -> "v1")).secComVersion mustBe SecComVersion.V1
      readOk(format, Json.obj("version" -> "v2")).secComVersion mustBe SecComVersion.V2
    }

    "fall back to V2 for an unknown version" in {
      readOk(format, Json.obj("version" -> "V3")).secComVersion mustBe SecComVersion.V2
      readOk(format, Json.obj("version" -> 3)).secComVersion mustBe SecComVersion.V2
      readOk(format, Json.obj("version" -> true)).secComVersion mustBe SecComVersion.V2
    }

    "read the ttl alone, in seconds" in {
      readOk(format, Json.obj("ttl" -> 60)) mustBe default.copy(secComTtl = 1.minute)
    }

    "ignore a ttl written as a string" in {
      readOk(format, Json.obj("ttl" -> "60")).secComTtl mustBe 30.seconds
    }

    "read the state leeway alone" in {
      readOk(format, Json.obj("state_resp_leeway" -> 5)) mustBe default.copy(stateRespLeeway = 5)
    }

    "ignore a state leeway that is not a whole number" in {
      readOk(format, Json.obj("state_resp_leeway" -> 5.5)).stateRespLeeway mustBe 10
    }

    "read blank header names as none" in {
      val back = readOk(format, Json.obj("request_header_name" -> "", "response_header_name" -> "   "))
      back.requestHeaderName mustBe None
      back.responseHeaderName mustBe None
    }

    "ignore header names that are not strings" in {
      readOk(format, Json.obj("request_header_name" -> 42)).requestHeaderName mustBe None
    }

    "read the algorithm to the backend alone" in {
      val json = Json.obj("algo_to_backend" -> Json.obj("type" -> "RSAKPAlgoSettings", "size" -> 256, "certId" -> "c1"))
      readOk(format, json) mustBe default.copy(algoOtoToBackend = RSAKPAlgoSettings(256, "c1"))
    }

    "fall back to HS512 for an algorithm without type" in {
      readOk(format, Json.obj("algo_to_backend" -> Json.obj("size" -> 256, "secret" -> "foo"))).algoOtoToBackend mustBe defaultAlgo
    }

    "fall back to HS512 for an unknown algorithm type" in {
      readOk(format, Json.obj("algo_from_backend" -> Json.obj("type" -> "FooAlgoSettings"))).algoBackendToOto mustBe defaultAlgo
    }

    "fall back to HS512 for an algorithm missing a mandatory field" in {
      readOk(format, Json.obj("algo_to_backend" -> Json.obj("type" -> "HSAlgoSettings", "size" -> 256))).algoOtoToBackend mustBe defaultAlgo
    }

    "fall back to HS512 for an algorithm that is not an object" in {
      readOk(format, Json.obj("algo_to_backend" -> "HSAlgoSettings")).algoOtoToBackend mustBe defaultAlgo
    }

    "not read the camel case keys" in {
      val json = Json.obj(
        "requestHeaderName" -> "X-Challenge",
        "stateRespLeeway"   -> 42,
        "algoOtoToBackend"  -> Json.obj("type" -> "KidAlgoSettings")
      )
      readOk(format, json) mustBe default
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("version" -> "V1", "foo" -> "bar")) mustBe default.copy(secComVersion = SecComVersion.V1)
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of OtoroshiChallenge" in {
      defaultConfigReadsBack(new OtoroshiChallenge(), format) mustBe default
    }
  }

  "NgOtoroshiInfoConfig" should {

    val format  = NgOtoroshiInfoConfig.format
    val default = NgOtoroshiInfoConfig(Json.obj())

    val full = NgOtoroshiInfoConfig(
      secComVersion = SecComInfoTokenVersion.Legacy,
      secComTtl = 1.minute,
      headerName = Some("X-Infos"),
      addFields = Some(AddFieldsSettings(Map("path" -> "${req.path}", "env" -> "prod"))),
      projection = Json.obj("apikey" -> Json.obj("clientId" -> true)),
      algo = RSAKPAlgoSettings(256, "cert_1")
    )

    "use the latest token version, a 30 seconds ttl and HS512 by default" in {
      default mustBe NgOtoroshiInfoConfig(
        secComVersion = SecComInfoTokenVersion.Latest,
        secComTtl = 30.seconds,
        headerName = None,
        addFields = None,
        projection = Json.obj(),
        algo = defaultAlgo
      )
    }

    "write the default config" in {
      format.writes(default) mustBe Json.obj(
        "version"     -> "Latest",
        "ttl"         -> 30,
        "header_name" -> JsNull,
        "add_fields"  -> JsNull,
        "projection"  -> Json.obj(),
        "algo"        -> defaultAlgoJson
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "version"     -> "Legacy",
        "ttl"         -> 60,
        "header_name" -> "X-Infos",
        "add_fields"  -> Json.obj("path" -> "${req.path}", "env" -> "prod"),
        "projection"  -> Json.obj("apikey" -> Json.obj("clientId" -> true)),
        "algo"        -> Json.obj("type" -> "RSAKPAlgoSettings", "size" -> 256, "certId" -> "cert_1")
      )
    }

    "round trip the default config" in {
      roundTrip(format, default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip the url token version" in {
      roundTrip(format, default.copy(secComVersion = SecComInfoTokenVersion.Url)).secComVersion mustBe SecComInfoTokenVersion.Url
    }

    "round trip the legacy token version" in {
      roundTrip(format, default.copy(secComVersion = SecComInfoTokenVersion.Legacy)).secComVersion mustBe SecComInfoTokenVersion.Legacy
    }

    "round trip empty additional fields as an empty object" in {
      val back = roundTrip(format, default.copy(addFields = Some(AddFieldsSettings(Map.empty))))
      back.addFields mustBe Some(AddFieldsSettings(Map.empty))
      written(format, back).value("add_fields") mustBe Json.obj()
    }

    "round trip a nested projection" in {
      val projection = Json.obj("user" -> Json.obj("email" -> true, "metadata" -> Json.obj("team" -> true)))
      roundTrip(format, default.copy(projection = projection)).projection mustBe projection
    }

    "round trip a jwks algorithm" in {
      val jwks = JWKSAlgoSettings(
        url = "https://keys.oto.tools/.well-known/jwks.json",
        headers = Map.empty,
        timeout = 2.seconds,
        ttl = 1.hour,
        kty = KeyType.RSA,
        proxy = None,
        tlsConfig = MtlsConfig()
      )
      roundTrip(format, default.copy(algo = jwks)).algo mustBe jwks
    }

    "write the ttl in seconds and truncate what is below the second" in {
      val json = format.writes(default.copy(secComTtl = 2999.millis))
      (json \ "ttl").as[Long] mustBe 2L
      readOk(format, json).secComTtl mustBe 2.seconds
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe default
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe default
    }

    "read a json string as the default config" in {
      readOk(format, JsString("Legacy")) mustBe default
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("Legacy")) mustBe default
    }

    "read what apply reads" in {
      val json = Json.obj("version" -> "Url", "header_name" -> "X-Infos")
      NgOtoroshiInfoConfig(json) mustBe readOk(format, json)
    }

    "read the token version written in lower case" in {
      readOk(format, Json.obj("version" -> "legacy")).secComVersion mustBe SecComInfoTokenVersion.Legacy
      readOk(format, Json.obj("version" -> "latest")).secComVersion mustBe SecComInfoTokenVersion.Latest
      readOk(format, Json.obj("version" -> "url")).secComVersion mustBe SecComInfoTokenVersion.Url
    }

    "fall back to the latest token version for an unknown version" in {
      readOk(format, Json.obj("version" -> "V2")).secComVersion mustBe SecComInfoTokenVersion.Latest
    }

    "not read the token version written as a number" in {
      readOk(format, Json.obj("version" -> 1)).secComVersion mustBe SecComInfoTokenVersion.Latest
    }

    "read the header name alone" in {
      readOk(format, Json.obj("header_name" -> "X-Infos")) mustBe default.copy(headerName = Some("X-Infos"))
    }

    "read a blank header name as none" in {
      readOk(format, Json.obj("header_name" -> "  ")).headerName mustBe None
    }

    "read the additional fields alone" in {
      readOk(format, Json.obj("add_fields" -> Json.obj("foo" -> "bar"))) mustBe default.copy(
        addFields = Some(AddFieldsSettings(Map("foo" -> "bar")))
      )
    }

    "ignore additional fields as soon as a value is not a string" in {
      readOk(format, Json.obj("add_fields" -> Json.obj("foo" -> "bar", "count" -> 1))).addFields mustBe None
    }

    "ignore additional fields written as an array" in {
      readOk(format, Json.obj("add_fields" -> Json.arr("foo"))).addFields mustBe None
    }

    "ignore a projection that is not an object" in {
      readOk(format, Json.obj("projection" -> Json.arr("apikey"))).projection mustBe Json.obj()
    }

    "ignore a ttl that is not a whole number" in {
      readOk(format, Json.obj("ttl" -> 1.5)).secComTtl mustBe 30.seconds
    }

    "read the algorithm alone" in {
      readOk(format, Json.obj("algo" -> Json.obj("type" -> "KidAlgoSettings"))) mustBe default.copy(
        algo = KidAlgoSettings(onlyExposedCerts = false)
      )
    }

    "fall back to HS512 for an algorithm without type" in {
      readOk(format, Json.obj("algo" -> Json.obj("certId" -> "cert_1"))).algo mustBe defaultAlgo
    }

    "not read the challenge algorithm keys" in {
      readOk(format, Json.obj("algo_to_backend" -> Json.obj("type" -> "KidAlgoSettings"))).algo mustBe defaultAlgo
    }

    "not read the camel case keys" in {
      val json = Json.obj(
        "headerName" -> "X-Infos",
        "addFields"  -> Json.obj("foo" -> "bar")
      )
      readOk(format, json) mustBe default
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("header_name" -> "X-Infos", "foo" -> "bar")) mustBe default.copy(headerName = Some("X-Infos"))
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of OtoroshiInfos" in {
      defaultConfigReadsBack(new OtoroshiInfos(), format) mustBe default
    }
  }

  "PossibleCerts" should {

    val format = PossibleCerts.format

    val full = PossibleCerts(
      certIds = Seq("cert_1", "cert_2"),
      includeAlgorithms = true,
      rsaAlgorithms = Seq(JWSAlgorithm.RS256, JWSAlgorithm.PS512),
      esAlgorithms = Seq(JWSAlgorithm.ES256),
      cacheTtl = Some(5.seconds)
    )

    "allow no certificate and no algorithm, with the global cache, by default" in {
      PossibleCerts.default mustBe PossibleCerts(Seq.empty, false, Seq.empty, Seq.empty, None)
    }

    "write the default config without cache_ttl" in {
      format.writes(PossibleCerts.default) mustBe Json.obj(
        "cert_ids"           -> Json.arr(),
        "include_algorithms" -> false,
        "rsa_algorithms"     -> Json.arr(),
        "es_algorithms"      -> Json.arr()
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "cert_ids"           -> Json.arr("cert_1", "cert_2"),
        "include_algorithms" -> true,
        "rsa_algorithms"     -> Json.arr("RS256", "PS512"),
        "es_algorithms"      -> Json.arr("ES256"),
        "cache_ttl"          -> 5000
      )
    }

    "round trip the default config" in {
      roundTrip(format, PossibleCerts.default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a zero cache ttl, that disables the cache" in {
      val back = roundTrip(format, PossibleCerts.default.copy(cacheTtl = Some(Duration.Zero)))
      back.cacheTtl mustBe Some(Duration.Zero)
      written(format, back).value("cache_ttl") mustBe JsNumber(0)
    }

    "round trip certificate ids alone" in {
      roundTrip(format, PossibleCerts.default.copy(certIds = Seq("cert_1")))
    }

    "round trip an algorithm name nimbus does not know" in {
      val back = roundTrip(format, PossibleCerts.default.copy(rsaAlgorithms = Seq(new JWSAlgorithm("FOO512"))))
      back.rsaAlgorithms.map(_.getName) mustBe Seq("FOO512")
    }

    "read the known algorithm names as the nimbus constants" in {
      val back = readOk(format, Json.obj("rsa_algorithms" -> Json.arr("RS384"), "es_algorithms" -> Json.arr("ES512")))
      back.rsaAlgorithms.head must be theSameInstanceAs JWSAlgorithm.RS384
      back.esAlgorithms.head must be theSameInstanceAs JWSAlgorithm.ES512
    }

    "not check the family of the listed algorithms" in {
      readOk(format, Json.obj("rsa_algorithms" -> Json.arr("ES256"))).rsaAlgorithms mustBe Seq(JWSAlgorithm.ES256)
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe PossibleCerts.default
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe PossibleCerts.default
    }

    "read a json string as the default config" in {
      readOk(format, JsString("cert_1")) mustBe PossibleCerts.default
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("cert_1")) mustBe PossibleCerts.default
    }

    "read the cache ttl alone, in milliseconds" in {
      readOk(format, Json.obj("cache_ttl" -> 1500)) mustBe PossibleCerts.default.copy(cacheTtl = Some(1500.millis))
    }

    "read a null cache ttl as the global setting" in {
      readOk(format, Json.obj("cache_ttl" -> JsNull)).cacheTtl mustBe None
    }

    "ignore a cache ttl written as a string" in {
      readOk(format, Json.obj("cache_ttl" -> "1500")).cacheTtl mustBe None
    }

    "ignore include_algorithms written as a string" in {
      readOk(format, Json.obj("include_algorithms" -> "true")).includeAlgorithms mustBe false
    }

    "ignore certificate ids written as a string" in {
      readOk(format, Json.obj("cert_ids" -> "cert_1")).certIds mustBe empty
    }

    "ignore algorithms holding something else than strings" in {
      readOk(format, Json.obj("rsa_algorithms" -> Json.arr("RS256", 256))).rsaAlgorithms mustBe empty
    }

    "not read the camel case keys" in {
      val json = Json.obj(
        "certIds"           -> Json.arr("cert_1"),
        "includeAlgorithms" -> true,
        "cacheTtl"          -> 1000
      )
      readOk(format, json) mustBe PossibleCerts.default
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("include_algorithms" -> true, "foo" -> "bar")) mustBe PossibleCerts.default.copy(
        includeAlgorithms = true
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "list every field in its config flow and schema" in {
      PossibleCerts.configFlow mustBe Seq("cert_ids", "include_algorithms", "rsa_algorithms", "es_algorithms", "cache_ttl")
      PossibleCerts.configSchema.get.keys mustBe PossibleCerts.configFlow.toSet
    }

    "be the config flow and schema of the certificate endpoints" in {
      Seq(new OtoroshiOCSPResponderEndpoint(), new OtoroshiAIAEndpoint(), new OtoroshiJWKSEndpoint()).foreach { plugin =>
        plugin.configFlow mustBe PossibleCerts.configFlow
        plugin.configSchema mustBe PossibleCerts.configSchema
      }
    }

    "be the default config of OtoroshiOCSPResponderEndpoint" in {
      defaultConfigReadsBack(new OtoroshiOCSPResponderEndpoint(), format) mustBe PossibleCerts.default
    }

    "be the default config of OtoroshiAIAEndpoint" in {
      defaultConfigReadsBack(new OtoroshiAIAEndpoint(), format) mustBe PossibleCerts.default
    }

    "be the default config of OtoroshiJWKSEndpoint" in {
      defaultConfigReadsBack(new OtoroshiJWKSEndpoint(), format) mustBe PossibleCerts.default
    }
  }

  "OtoroshiMetricsEndpointConfig" should {

    val format = OtoroshiMetricsEndpointConfig.format

    "not filter the metrics by default" in {
      OtoroshiMetricsEndpointConfig.default mustBe OtoroshiMetricsEndpointConfig(None)
    }

    "write the default config with a null filter" in {
      format.writes(OtoroshiMetricsEndpointConfig.default) mustBe Json.obj("filter" -> JsNull)
    }

    "write the filter under filter" in {
      format.writes(OtoroshiMetricsEndpointConfig(Some("otoroshi_*"))) mustBe Json.obj("filter" -> "otoroshi_*")
    }

    "round trip the default config" in {
      roundTrip(format, OtoroshiMetricsEndpointConfig.default)
    }

    "round trip a filter" in {
      roundTrip(format, OtoroshiMetricsEndpointConfig(Some("jvm.*"))).filter mustBe Some("jvm.*")
    }

    "round trip an empty filter as is" in {
      roundTrip(format, OtoroshiMetricsEndpointConfig(Some(""))).filter mustBe Some("")
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe OtoroshiMetricsEndpointConfig.default
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe OtoroshiMetricsEndpointConfig.default
    }

    "read a json string as the default config" in {
      readOk(format, JsString("jvm.*")) mustBe OtoroshiMetricsEndpointConfig.default
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("jvm.*")) mustBe OtoroshiMetricsEndpointConfig.default
    }

    "ignore a filter that is not a string" in {
      readOk(format, Json.obj("filter" -> Json.arr("jvm.*"))) mustBe OtoroshiMetricsEndpointConfig.default
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("filter" -> "jvm.*", "format" -> "json")) mustBe OtoroshiMetricsEndpointConfig(
        Some("jvm.*")
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, OtoroshiMetricsEndpointConfig(Some("jvm.*")))
    }

    "list filter in its config flow and schema" in {
      OtoroshiMetricsEndpointConfig.configFlow mustBe Seq("filter")
      OtoroshiMetricsEndpointConfig.configSchema.get.keys mustBe Set("filter")
    }

    "be the config flow and schema of OtoroshiMetricsEndpoint" in {
      val plugin = new OtoroshiMetricsEndpoint()
      plugin.configFlow mustBe OtoroshiMetricsEndpointConfig.configFlow
      plugin.configSchema mustBe OtoroshiMetricsEndpointConfig.configSchema
    }

    "be the default config of OtoroshiMetricsEndpoint" in {
      defaultConfigReadsBack(new OtoroshiMetricsEndpoint(), format) mustBe OtoroshiMetricsEndpointConfig.default
    }
  }

  "the otoroshi plugins without configuration" should {

    "have no default config for OtoroshiHealthEndpoint" in {
      hasNoDefaultConfig(new OtoroshiHealthEndpoint())
    }
  }
}
