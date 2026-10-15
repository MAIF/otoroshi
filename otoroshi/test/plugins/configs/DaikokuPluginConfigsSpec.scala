package plugins.configs

import com.nimbusds.jose.jwk.KeyType
import otoroshi.models.*
import otoroshi.next.plugins.*
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.infotoken.AddFieldsSettings
import play.api.libs.json.*

import scala.concurrent.duration.*

class DaikokuPluginConfigsSpec extends PluginConfigFormatSpec {

  "ExposeDaikokuPresetConfig" should {

    val format = ExposeDaikokuPresetConfig.format

    val defaultExclude = Seq("/admin-api/.*", "/cms-api/.*", "/health", "/integration-api/.*", "/robots.txt")

    val full = ExposeDaikokuPresetConfig(
      authenticationRef = Some("auth_mod_daikoku"),
      exclude = Seq("/api/.*", "/status"),
      headerName = Some("X-Daikoku-Claim"),
      addFields = Some(AddFieldsSettings(Map("tenant" -> "acme", "user" -> "${user.email}"))),
      algo = RSAlgoSettings(256, "-----BEGIN PUBLIC KEY-----", Some("-----BEGIN PRIVATE KEY-----"))
    )

    def withAlgo(algo: AlgoSettings): ExposeDaikokuPresetConfig = ExposeDaikokuPresetConfig(algo = algo)

    def readAlgo(json: JsValue): AlgoSettings = readOk(format, Json.obj("algo" -> json)).algo

    "exclude the daikoku apis and sign with a hmac 512 by default" in {
      val config = ExposeDaikokuPresetConfig.default
      config.authenticationRef mustBe None
      config.exclude mustBe defaultExclude
      config.headerName mustBe Some("Otoroshi-claim")
      config.addFields mustBe None
      config.algo mustBe HSAlgoSettings(512, "secret")
    }

    "write the default config with null optional values" in {
      format.writes(ExposeDaikokuPresetConfig.default) mustBe Json.obj(
        "authentication_ref" -> JsNull,
        "exclude"            -> defaultExclude,
        "header_name"        -> "Otoroshi-claim",
        "add_fields"         -> JsNull,
        "algo"               -> Json.obj("type" -> "HSAlgoSettings", "size" -> 512, "secret" -> "secret", "base64" -> false)
      )
    }

    "write the added fields as a string map" in {
      (format.writes(full) \ "add_fields").get mustBe Json.obj("tenant" -> "acme", "user" -> "${user.email}")
    }

    "round trip the default config" in {
      roundTrip(format, ExposeDaikokuPresetConfig.default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip an authentication module alone" in {
      roundTrip(format, ExposeDaikokuPresetConfig(authenticationRef = Some("auth_mod_1"))).authenticationRef mustBe Some(
        "auth_mod_1"
      )
    }

    "round trip an empty exclude list" in {
      roundTrip(format, ExposeDaikokuPresetConfig(exclude = Seq.empty)).exclude mustBe empty
    }

    "round trip empty added fields as defined" in {
      roundTrip(format, ExposeDaikokuPresetConfig(addFields = Some(AddFieldsSettings(Map.empty)))).addFields mustBe Some(
        AddFieldsSettings(Map.empty)
      )
    }

    "round trip a base64 hmac algo" in {
      roundTrip(format, withAlgo(HSAlgoSettings(256, "c2VjcmV0", base64 = true)))
    }

    "round trip a rsa algo without private key" in {
      roundTrip(format, withAlgo(RSAlgoSettings(512, "pub", None))).algo mustBe RSAlgoSettings(512, "pub", None)
    }

    "round trip an ecdsa algo" in {
      roundTrip(format, withAlgo(ESAlgoSettings(384, "pub", Some("priv"))))
    }

    "round trip key pair algos" in {
      roundTrip(format, withAlgo(RSAKPAlgoSettings(512, "cert_rsa")))
      roundTrip(format, withAlgo(ESKPAlgoSettings(256, "cert_ec")))
    }

    "round trip a kid algo" in {
      roundTrip(format, withAlgo(KidAlgoSettings(onlyExposedCerts = true)))
    }

    "round trip a jwks algo" in {
      roundTrip(
        format,
        withAlgo(
          JWKSAlgoSettings(
            url = "https://daikoku.oto.tools/.well-known/jwks.json",
            headers = Map("Accept" -> "application/json"),
            timeout = 3.seconds,
            ttl = 10.minutes,
            kty = KeyType.EC,
            tlsConfig = MtlsConfig()
          )
        )
      )
    }

    "read an empty object with an empty exclude list, unlike the default config" in {
      readOk(format, Json.obj()) mustBe ExposeDaikokuPresetConfig.default.copy(exclude = Seq.empty)
    }

    "read a json null like an empty object" in {
      readOk(format, JsNull) mustBe ExposeDaikokuPresetConfig.default.copy(exclude = Seq.empty)
    }

    "read a json string like an empty object" in {
      readOk(format, JsString("daikoku")) mustBe ExposeDaikokuPresetConfig.default.copy(exclude = Seq.empty)
    }

    "read a json array like an empty object" in {
      readOk(format, Json.arr()) mustBe ExposeDaikokuPresetConfig.default.copy(exclude = Seq.empty)
    }

    "read the authentication module alone" in {
      readOk(format, Json.obj("authentication_ref" -> "auth_mod_1")).authenticationRef mustBe Some("auth_mod_1")
    }

    "read a missing header name as Otoroshi-claim" in {
      readOk(format, Json.obj("exclude" -> defaultExclude)).headerName mustBe Some("Otoroshi-claim")
    }

    "ignore an authentication module that is not a string" in {
      readOk(format, Json.obj("authentication_ref" -> 42)).authenticationRef mustBe None
    }

    "ignore an exclude list that is not an array" in {
      readOk(format, Json.obj("exclude" -> "/health")).exclude mustBe empty
    }

    "ignore added fields holding a non string value" in {
      readOk(format, Json.obj("add_fields" -> Json.obj("a" -> "b", "n" -> 1))).addFields mustBe None
    }

    "read an algo without type as the default hmac" in {
      readAlgo(Json.obj("size" -> 256, "secret" -> "other")) mustBe HSAlgoSettings(512, "secret")
    }

    "read an algo of unknown type as the default hmac" in {
      readAlgo(Json.obj("type" -> "NoneAlgoSettings")) mustBe HSAlgoSettings(512, "secret")
    }

    "read an incomplete algo as the default hmac" in {
      readAlgo(Json.obj("type" -> "RSAlgoSettings", "size" -> 256)) mustBe HSAlgoSettings(512, "secret")
    }

    "read an algo that is not an object as the default hmac" in {
      readAlgo(JsString("HSAlgoSettings")) mustBe HSAlgoSettings(512, "secret")
    }

    "not read the camel case keys" in {
      val json = Json.obj("authenticationRef" -> "auth_mod_1", "headerName" -> "X-Claim", "addFields" -> Json.obj("a" -> "b"))
      readOk(format, json) mustBe ExposeDaikokuPresetConfig.default.copy(exclude = Seq.empty)
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "have no config flow and no config schema" in {
      ExposeDaikokuPresetConfig.configFlow mustBe empty
      ExposeDaikokuPresetConfig.configSchema mustBe None
    }

    "be the default config of DaikokuProxyPlugin" in {
      defaultConfigReadsBack(new DaikokuProxyPlugin(), format) mustBe ExposeDaikokuPresetConfig.default
    }
  }
}
