package plugins.configs

import otoroshi.next.plugins.*
import otoroshi.plugins.hmac.HMACUtils
import play.api.libs.json.*

class HmacPluginConfigsSpec extends PluginConfigFormatSpec {

  "HMACValidatorConfig" should {

    val format = HMACValidatorConfig.format
    val full   = HMACValidatorConfig(
      secret = Some("s3cr3t"),
      authorizationHeader = Some("X-Signature"),
      maxDateSkewSeconds = Some(30L),
      signRequestTarget = true
    )

    "have no secret, no header, no date skew and not sign the request target by default" in {
      HMACValidatorConfig() mustBe HMACValidatorConfig(None, None, None, signRequestTarget = false)
    }

    "write the default config with camel case keys and nulls" in {
      format.writes(HMACValidatorConfig()) mustBe Json.obj(
        "secret"              -> JsNull,
        "authorizationHeader" -> JsNull,
        "maxDateSkewSeconds"  -> JsNull,
        "signRequestTarget"   -> false
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "secret"              -> "s3cr3t",
        "authorizationHeader" -> "X-Signature",
        "maxDateSkewSeconds"  -> 30,
        "signRequestTarget"   -> true
      )
    }

    "round trip the default config" in {
      roundTrip(format, HMACValidatorConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a secret alone" in {
      roundTrip(format, HMACValidatorConfig(secret = Some("s3cr3t"))).secret mustBe Some("s3cr3t")
    }

    "round trip an authorization header alone" in {
      roundTrip(format, HMACValidatorConfig(authorizationHeader = Some("Proxy-Authorization")))
    }

    "round trip a date skew alone" in {
      roundTrip(format, HMACValidatorConfig(maxDateSkewSeconds = Some(300L))).maxDateSkewSeconds mustBe Some(300L)
    }

    "round trip a zero date skew" in {
      roundTrip(format, HMACValidatorConfig(maxDateSkewSeconds = Some(0L))).maxDateSkewSeconds mustBe Some(0L)
    }

    "round trip the request target signing alone" in {
      roundTrip(format, HMACValidatorConfig(signRequestTarget = true)).signRequestTarget mustBe true
    }

    "round trip an empty secret as an empty string" in {
      roundTrip(format, HMACValidatorConfig(secret = Some(""))).secret mustBe Some("")
    }

    "read the secret alone" in {
      readOk(format, Json.obj("secret" -> "abc")) mustBe HMACValidatorConfig(secret = Some("abc"))
    }

    "read the request target signing alone" in {
      readOk(format, Json.obj("signRequestTarget" -> true)) mustBe HMACValidatorConfig(signRequestTarget = true)
    }

    "read null values as missing" in {
      readOk(
        format,
        Json.obj("secret" -> JsNull, "authorizationHeader" -> JsNull, "maxDateSkewSeconds" -> JsNull)
      ) mustBe HMACValidatorConfig()
    }

    "ignore a date skew written as a string" in {
      readOk(format, Json.obj("maxDateSkewSeconds" -> "30")).maxDateSkewSeconds mustBe None
    }

    "ignore a date skew that is not a whole number" in {
      readOk(format, Json.obj("maxDateSkewSeconds" -> 1.5)).maxDateSkewSeconds mustBe None
    }

    "ignore a request target signing written as a string" in {
      readOk(format, Json.obj("signRequestTarget" -> "true")).signRequestTarget mustBe false
    }

    "ignore a secret that is not a string" in {
      readOk(format, Json.obj("secret" -> 1234)).secret mustBe None
    }

    "ignore an authorization header that is not a string" in {
      readOk(format, Json.obj("authorizationHeader" -> Json.arr("X-Sig"))).authorizationHeader mustBe None
    }

    "not read snake case keys" in {
      readOk(
        format,
        Json.obj(
          "authorization_header"  -> "X-Signature",
          "max_date_skew_seconds" -> 30,
          "sign_request_target"   -> true
        )
      ) mustBe HMACValidatorConfig()
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe HMACValidatorConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe HMACValidatorConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("s3cr3t")) mustBe HMACValidatorConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("s3cr3t")) mustBe HMACValidatorConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("secret" -> "abc", "algo" -> "HMAC-SHA1")) mustBe HMACValidatorConfig(secret = Some("abc"))
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of HMACValidator" in {
      defaultConfigReadsBack(new HMACValidator(), format) mustBe HMACValidatorConfig()
    }
  }

  "HMACCallerConfig" should {

    val format = HMACCallerConfig.format
    val full   = HMACCallerConfig(
      secret = Some("s3cr3t"),
      algo = "HMAC-SHA256",
      authorizationHeader = Some("X-Signature"),
      signRequestTarget = true
    )

    "sign with HMAC-SHA512, without secret nor header by default" in {
      HMACCallerConfig() mustBe HMACCallerConfig(None, "HMAC-SHA512", None, signRequestTarget = false)
    }

    "default to an algorithm known by the hmac utils" in {
      HMACUtils.Algo.keySet must contain(HMACCallerConfig().algo)
    }

    "write the default config with camel case keys and nulls" in {
      format.writes(HMACCallerConfig()) mustBe Json.obj(
        "secret"              -> JsNull,
        "algo"                -> "HMAC-SHA512",
        "authorizationHeader" -> JsNull,
        "signRequestTarget"   -> false
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "secret"              -> "s3cr3t",
        "algo"                -> "HMAC-SHA256",
        "authorizationHeader" -> "X-Signature",
        "signRequestTarget"   -> true
      )
    }

    "round trip the default config" in {
      roundTrip(format, HMACCallerConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip every algorithm known by the hmac utils" in {
      HMACUtils.Algo.keys.foreach { algo =>
        roundTrip(format, HMACCallerConfig(algo = algo)).algo mustBe algo
      }
    }

    "round trip an unknown algorithm as is" in {
      roundTrip(format, HMACCallerConfig(algo = "HMAC-MD5")).algo mustBe "HMAC-MD5"
    }

    "keep the case of the algorithm" in {
      roundTrip(format, HMACCallerConfig(algo = "hmac-sha256")).algo mustBe "hmac-sha256"
    }

    "round trip a secret alone" in {
      roundTrip(format, HMACCallerConfig(secret = Some("s3cr3t"))).secret mustBe Some("s3cr3t")
    }

    "round trip an authorization header alone" in {
      roundTrip(format, HMACCallerConfig(authorizationHeader = Some("X-Signature")))
    }

    "round trip the request target signing alone" in {
      roundTrip(format, HMACCallerConfig(signRequestTarget = true)).signRequestTarget mustBe true
    }

    "read the algorithm alone" in {
      readOk(format, Json.obj("algo" -> "HMAC-SHA1")) mustBe HMACCallerConfig(algo = "HMAC-SHA1")
    }

    "read the secret alone" in {
      readOk(format, Json.obj("secret" -> "abc")) mustBe HMACCallerConfig(secret = Some("abc"))
    }

    "read a null algorithm as HMAC-SHA512" in {
      readOk(format, Json.obj("algo" -> JsNull)).algo mustBe "HMAC-SHA512"
    }

    "ignore an algorithm that is not a string" in {
      readOk(format, Json.obj("algo" -> 256)).algo mustBe "HMAC-SHA512"
    }

    "ignore a request target signing written as a string" in {
      readOk(format, Json.obj("signRequestTarget" -> "true")).signRequestTarget mustBe false
    }

    "ignore a secret that is not a string" in {
      readOk(format, Json.obj("secret" -> Json.obj("value" -> "abc"))).secret mustBe None
    }

    "not read snake case keys" in {
      readOk(
        format,
        Json.obj("authorization_header" -> "X-Signature", "sign_request_target" -> true)
      ) mustBe HMACCallerConfig()
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe HMACCallerConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe HMACCallerConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("HMAC-SHA1")) mustBe HMACCallerConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("HMAC-SHA1")) mustBe HMACCallerConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("algo" -> "HMAC-SHA1", "maxDateSkewSeconds" -> 30)) mustBe HMACCallerConfig(
        algo = "HMAC-SHA1"
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of HMACCaller" in {
      defaultConfigReadsBack(new HMACCaller(), format) mustBe HMACCallerConfig()
    }
  }
}
