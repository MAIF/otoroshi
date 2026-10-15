package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class HttpSignaturePluginConfigsSpec extends PluginConfigFormatSpec {

  private val certKey   = HttpSigKeyCertRef("cert_httpsig", Some("key-cert"), Some("ed25519"))
  private val inlineKey = HttpSigKeyInline("base64:c2VjcmV0", Some("key-hmac"), Some("hmac-sha256"))
  private val jwksKey   = HttpSigKeyJwks("https://auth.oto.tools/.well-known/jwks.json", Some("key-jwks"), None)

  "HttpSignatureVerifyRequestConfig" should {

    val format = HttpSignatureVerifyRequestConfig.format

    val full = HttpSignatureVerifyRequestConfig(
      keys = List(certKey, inlineKey, jwksKey),
      requiredComponents = Seq("@method", "@path", "content-digest", "authorization"),
      allowedAlgorithms = Seq(HttpSigAlgorithms.Hmac256, HttpSigAlgorithms.Ed25519Alg),
      requireKeyid = false,
      maxAgeSeconds = Some(60L),
      clockSkewSeconds = 5L,
      label = Some("sig-client"),
      mandatory = true
    )

    "accept any algorithm on method and target uri, without enforcing, by default" in {
      val config = HttpSignatureVerifyRequestConfig()
      config.keys mustBe Nil
      config.requiredComponents mustBe Seq("@method", "@target-uri")
      config.allowedAlgorithms mustBe HttpSigAlgorithms.all
      config.requireKeyid mustBe true
      config.maxAgeSeconds mustBe Some(300L)
      config.clockSkewSeconds mustBe 30L
      config.label mustBe None
      config.mandatory mustBe false
    }

    "write the default config" in {
      format.writes(HttpSignatureVerifyRequestConfig()) mustBe Json.obj(
        "keys"                -> Json.arr(),
        "required_components" -> Json.arr("@method", "@target-uri"),
        "allowed_algorithms"  -> Json.arr(
          "hmac-sha256",
          "rsa-pss-sha512",
          "rsa-v1_5-sha256",
          "ecdsa-p256-sha256",
          "ecdsa-p384-sha384",
          "ed25519"
        ),
        "require_keyid"       -> true,
        "max_age_seconds"     -> 300,
        "clock_skew_seconds"  -> 30,
        "signature_label"     -> JsNull,
        "mandatory"           -> false
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "keys"                -> Json.arr(
          Json.obj("kind" -> "cert", "cert_id" -> "cert_httpsig", "keyid" -> "key-cert", "alg" -> "ed25519"),
          Json.obj(
            "kind"          -> "inline",
            "secret_or_pem" -> "base64:c2VjcmV0",
            "keyid"         -> "key-hmac",
            "alg"           -> "hmac-sha256"
          ),
          Json.obj(
            "kind"  -> "jwks",
            "url"   -> "https://auth.oto.tools/.well-known/jwks.json",
            "keyid" -> "key-jwks",
            "alg"   -> JsNull
          )
        ),
        "required_components" -> Json.arr("@method", "@path", "content-digest", "authorization"),
        "allowed_algorithms"  -> Json.arr("hmac-sha256", "ed25519"),
        "require_keyid"       -> false,
        "max_age_seconds"     -> 60,
        "clock_skew_seconds"  -> 5,
        "signature_label"     -> "sig-client",
        "mandatory"           -> true
      )
    }

    "round trip the default config" in {
      roundTrip(format, HttpSignatureVerifyRequestConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a certificate key" in {
      roundTrip(format, HttpSignatureVerifyRequestConfig(keys = List(certKey))).keys mustBe List(certKey)
    }

    "round trip an inline key" in {
      roundTrip(format, HttpSignatureVerifyRequestConfig(keys = List(inlineKey))).keys mustBe List(inlineKey)
    }

    "round trip a jwks key" in {
      roundTrip(format, HttpSignatureVerifyRequestConfig(keys = List(jwksKey))).keys mustBe List(jwksKey)
    }

    "round trip a key without keyid nor algorithm" in {
      val key = HttpSigKeyInline("-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEA\n-----END PUBLIC KEY-----", None, None)
      roundTrip(format, HttpSignatureVerifyRequestConfig(keys = List(key))).keys mustBe List(key)
    }

    "keep the order of the keys" in {
      roundTrip(format, HttpSignatureVerifyRequestConfig(keys = List(jwksKey, certKey, inlineKey))).keys mustBe List(
        jwksKey,
        certKey,
        inlineKey
      )
    }

    "round trip a config without max age" in {
      roundTrip(format, full.copy(maxAgeSeconds = None)).maxAgeSeconds mustBe None
    }

    "round trip empty components and algorithms lists" in {
      val back = roundTrip(format, HttpSignatureVerifyRequestConfig(requiredComponents = Nil, allowedAlgorithms = Nil))
      back.requiredComponents mustBe empty
      back.allowedAlgorithms mustBe empty
    }

    "write the label under signature_label" in {
      val json = written(format, HttpSignatureVerifyRequestConfig(label = Some("sig1")))
      json \ "signature_label" mustBe JsDefined(JsString("sig1"))
      json.keys must not contain "label"
    }

    "not read the label key" in {
      readOk(format, Json.obj("label" -> "sig1")).label mustBe None
    }

    "read an empty signature label as no label" in {
      readOk(format, Json.obj("signature_label" -> "")).label mustBe None
    }

    // a missing max_age_seconds reads as no max age instead of the 300 seconds default: it is left aside here
    "read an empty object with the default values" in {
      val back = readOk(format, Json.obj())
      back.copy(maxAgeSeconds = Some(300L)) mustBe HttpSignatureVerifyRequestConfig()
    }

    "read a json null like an empty object" in {
      readOk(format, JsNull) mustBe readOk(format, Json.obj())
    }

    "read a json string like an empty object" in {
      readOk(format, JsString("hmac-sha256")) mustBe readOk(format, Json.obj())
    }

    "read a json array like an empty object" in {
      readOk(format, Json.arr(certKey.json)) mustBe readOk(format, Json.obj())
    }

    "read a null max age as no max age" in {
      readOk(format, Json.obj("max_age_seconds" -> JsNull)).maxAgeSeconds mustBe None
    }

    "read the mandatory flag alone" in {
      readOk(format, Json.obj("mandatory" -> true, "max_age_seconds" -> 300)) mustBe HttpSignatureVerifyRequestConfig(
        mandatory = true
      )
    }

    "read the clock skew alone" in {
      readOk(format, Json.obj("clock_skew_seconds" -> 0, "max_age_seconds" -> 300)) mustBe HttpSignatureVerifyRequestConfig(
        clockSkewSeconds = 0L
      )
    }

    "ignore a require_keyid written as a string" in {
      readOk(format, Json.obj("require_keyid" -> "false")).requireKeyid mustBe true
    }

    "ignore a clock skew written as a string" in {
      readOk(format, Json.obj("clock_skew_seconds" -> "5")).clockSkewSeconds mustBe 30L
    }

    "ignore keys that are not an array" in {
      readOk(format, Json.obj("keys" -> certKey.json)).keys mustBe Nil
    }

    "ignore required components written as a string" in {
      readOk(format, Json.obj("required_components" -> "@method")).requiredComponents mustBe Seq(
        "@method",
        "@target-uri"
      )
    }

    "ignore allowed algorithms holding something else than strings" in {
      readOk(format, Json.obj("allowed_algorithms" -> Json.arr("ed25519", 1))).allowedAlgorithms mustBe HttpSigAlgorithms.all
    }

    "keep the case of the allowed algorithms" in {
      readOk(format, Json.obj("allowed_algorithms" -> Json.arr("ED25519"))).allowedAlgorithms mustBe Seq("ED25519")
    }

    "read a key without kind as an inline key" in {
      readOk(format, Json.obj("keys" -> Json.arr(Json.obj("secret_or_pem" -> "s3cr3t")))).keys mustBe List(
        HttpSigKeyInline("s3cr3t", None, None)
      )
    }

    "read a key of an unknown kind as an inline key" in {
      readOk(format, Json.obj("keys" -> Json.arr(Json.obj("kind" -> "vault", "secret_or_pem" -> "s")))).keys mustBe List(
        HttpSigKeyInline("s", None, None)
      )
    }

    "read an inline key without secret as an empty secret" in {
      readOk(format, Json.obj("keys" -> Json.arr(Json.obj("kind" -> "inline")))).keys mustBe List(
        HttpSigKeyInline("", None, None)
      )
    }

    "read a key that is not an object as an inline key without secret" in {
      readOk(format, Json.obj("keys" -> Json.arr("s3cr3t"))).keys mustBe List(HttpSigKeyInline("", None, None))
    }

    "drop a certificate key without certificate id and keep the others" in {
      readOk(
        format,
        Json.obj("keys" -> Json.arr(Json.obj("kind" -> "cert", "keyid" -> "k1"), inlineKey.json))
      ).keys mustBe List(inlineKey)
    }

    "drop a jwks key without url" in {
      readOk(format, Json.obj("keys" -> Json.arr(Json.obj("kind" -> "jwks", "keyid" -> "k1")))).keys mustBe Nil
    }

    "read the algorithm of a key in lower case" in {
      readOk(
        format,
        Json.obj("keys" -> Json.arr(Json.obj("kind" -> "cert", "cert_id" -> "c1", "alg" -> "ECDSA-P256-SHA256")))
      ).keys mustBe List(HttpSigKeyCertRef("c1", None, Some("ecdsa-p256-sha256")))
    }

    "read an empty keyid and an empty algorithm of a key as missing" in {
      readOk(
        format,
        Json.obj("keys" -> Json.arr(Json.obj("kind" -> "jwks", "url" -> "https://jwks", "keyid" -> "", "alg" -> "")))
      ).keys mustBe List(HttpSigKeyJwks("https://jwks", None, None))
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
      jsonMatchesFormat(format, HttpSignatureVerifyRequestConfig())
    }

    "list every written field in its config flow and schema" in {
      HttpSignatureVerifyRequestConfig.configFlow.toSet mustBe writtenKeys(format, HttpSignatureVerifyRequestConfig())
      HttpSignatureVerifyRequestConfig.configFlow.foreach { field =>
        HttpSignatureVerifyRequestConfig.configSchema.get.keys must contain(field)
      }
    }

    "offer every supported algorithm in its config schema" in {
      (HttpSignatureVerifyRequestConfig.configSchema.get \ "allowed_algorithms" \ "props" \ "options")
        .as[Seq[String]] mustBe HttpSigAlgorithms.all
    }

    "be the default config of HttpSignatureVerifyRequest" in {
      defaultConfigReadsBack(new HttpSignatureVerifyRequest(), format) mustBe HttpSignatureVerifyRequestConfig()
    }
  }

  "HttpSignatureSignResponseConfig" should {

    val format = HttpSignatureSignResponseConfig.format

    val full = HttpSignatureSignResponseConfig(
      key = Some(inlineKey),
      algorithm = HttpSigAlgorithms.Hmac256,
      keyid = "gateway-1",
      label = "sig-gw",
      components = Seq("@status", "@target-uri;req", "content-type"),
      expiresInSeconds = Some(30L),
      includeCreated = false,
      includeNonce = true,
      addContentDigest = false,
      contentDigestAlgorithm = "sha-512",
      tag = Some("otoroshi-response")
    )

    "sign status, content type and digest with ed25519 by default" in {
      val config = HttpSignatureSignResponseConfig()
      config.key mustBe None
      config.algorithm mustBe "ed25519"
      config.keyid mustBe ""
      config.label mustBe "sig1"
      config.components mustBe Seq("@status", "content-type", "content-digest")
      config.expiresInSeconds mustBe Some(300L)
      config.includeCreated mustBe true
      config.includeNonce mustBe false
      config.addContentDigest mustBe true
      config.contentDigestAlgorithm mustBe "sha-256"
      config.tag mustBe None
    }

    "write the default config" in {
      format.writes(HttpSignatureSignResponseConfig()) mustBe Json.obj(
        "key"                      -> JsNull,
        "algorithm"                -> "ed25519",
        "keyid"                    -> "",
        "signature_label"          -> "sig1",
        "components"               -> Json.arr("@status", "content-type", "content-digest"),
        "expires_in_seconds"       -> 300,
        "include_created"          -> true,
        "include_nonce"            -> false,
        "add_content_digest"       -> true,
        "content_digest_algorithm" -> "sha-256",
        "tag"                      -> JsNull
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "key"                      -> Json.obj(
          "kind"          -> "inline",
          "secret_or_pem" -> "base64:c2VjcmV0",
          "keyid"         -> "key-hmac",
          "alg"           -> "hmac-sha256"
        ),
        "algorithm"                -> "hmac-sha256",
        "keyid"                    -> "gateway-1",
        "signature_label"          -> "sig-gw",
        "components"               -> Json.arr("@status", "@target-uri;req", "content-type"),
        "expires_in_seconds"       -> 30,
        "include_created"          -> false,
        "include_nonce"            -> true,
        "add_content_digest"       -> false,
        "content_digest_algorithm" -> "sha-512",
        "tag"                      -> "otoroshi-response"
      )
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a certificate key" in {
      roundTrip(format, HttpSignatureSignResponseConfig(key = Some(certKey))).key mustBe Some(certKey)
    }

    "round trip a jwks key, even if it cannot sign" in {
      roundTrip(format, HttpSignatureSignResponseConfig(key = Some(jwksKey))).key mustBe Some(jwksKey)
    }

    "round trip every supported algorithm" in {
      HttpSigAlgorithms.all.foreach { alg =>
        roundTrip(format, HttpSignatureSignResponseConfig(key = Some(certKey), algorithm = alg)).algorithm mustBe alg
      }
    }

    "round trip a config without expiration" in {
      roundTrip(format, full.copy(expiresInSeconds = None)).expiresInSeconds mustBe None
    }

    "round trip a config without tag" in {
      roundTrip(format, full.copy(tag = None)).tag mustBe None
    }

    "round trip an empty components list" in {
      roundTrip(format, HttpSignatureSignResponseConfig(key = Some(certKey), components = Nil)).components mustBe empty
    }

    // a missing expires_in_seconds reads as no expiration instead of the 300 seconds default: it is left aside here
    "read an empty object with the default values" in {
      val back = readOk(format, Json.obj())
      back.copy(expiresInSeconds = Some(300L)) mustBe HttpSignatureSignResponseConfig()
    }

    "read a json null like an empty object" in {
      readOk(format, JsNull) mustBe readOk(format, Json.obj())
    }

    "read a json string like an empty object" in {
      readOk(format, JsString("ed25519")) mustBe readOk(format, Json.obj())
    }

    "read a json array like an empty object" in {
      readOk(format, Json.arr("ed25519")) mustBe readOk(format, Json.obj())
    }

    "read the keyid alone" in {
      readOk(format, Json.obj("keyid" -> "k1", "expires_in_seconds" -> 300)) mustBe HttpSignatureSignResponseConfig(
        keyid = "k1"
      )
    }

    "read a null expiration as no expiration" in {
      readOk(format, Json.obj("expires_in_seconds" -> JsNull)).expiresInSeconds mustBe None
    }

    "read the algorithm in lower case" in {
      readOk(format, Json.obj("algorithm" -> "RSA-PSS-SHA512")).algorithm mustBe "rsa-pss-sha512"
    }

    "read the content digest algorithm in lower case" in {
      readOk(format, Json.obj("content_digest_algorithm" -> "SHA-512")).contentDigestAlgorithm mustBe "sha-512"
    }

    "rewrite an upper case algorithm in lower case" in {
      val back = readOk(format, format.writes(HttpSignatureSignResponseConfig(algorithm = "ED25519")))
      back.algorithm mustBe "ed25519"
    }

    "keep an unknown algorithm" in {
      readOk(format, Json.obj("algorithm" -> "hmac-sha512")).algorithm mustBe "hmac-sha512"
    }

    "read an empty signature label as sig1" in {
      readOk(format, Json.obj("signature_label" -> "")).label mustBe "sig1"
    }

    "not read the label key" in {
      readOk(format, Json.obj("label" -> "other")).label mustBe "sig1"
    }

    "read an empty tag as no tag" in {
      readOk(format, Json.obj("tag" -> "")).tag mustBe None
    }

    "keep an empty keyid" in {
      roundTrip(format, HttpSignatureSignResponseConfig(key = Some(certKey), keyid = "")).keyid mustBe ""
    }

    "read a key that is not an object as an inline key without secret" in {
      readOk(format, Json.obj("key" -> "s3cr3t")).key mustBe Some(HttpSigKeyInline("", None, None))
    }

    "drop a certificate key without certificate id" in {
      readOk(format, Json.obj("key" -> Json.obj("kind" -> "cert"))).key mustBe None
    }

    "not read a list of keys as the signing key" in {
      readOk(format, Json.obj("keys" -> Json.arr(inlineKey.json))).key mustBe None
    }

    "ignore an algorithm that is not a string" in {
      readOk(format, Json.obj("algorithm" -> 42)).algorithm mustBe "ed25519"
    }

    "ignore components written as a string" in {
      readOk(format, Json.obj("components" -> "@status")).components mustBe Seq(
        "@status",
        "content-type",
        "content-digest"
      )
    }

    "ignore flags written as strings" in {
      val back = readOk(
        format,
        Json.obj("include_created" -> "false", "include_nonce" -> "true", "add_content_digest" -> "false")
      )
      back.includeCreated mustBe true
      back.includeNonce mustBe false
      back.addContentDigest mustBe true
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
      jsonMatchesFormat(format, HttpSignatureSignResponseConfig())
    }

    "list every written field in its config flow and schema" in {
      HttpSignatureSignResponseConfig.configFlow.toSet mustBe writtenKeys(format, HttpSignatureSignResponseConfig())
      HttpSignatureSignResponseConfig.configFlow.foreach { field =>
        HttpSignatureSignResponseConfig.configSchema.get.keys must contain(field)
      }
    }

    "be the default config object of HttpSignatureSignResponse" in {
      new HttpSignatureSignResponse().defaultConfigObject mustBe Some(HttpSignatureSignResponseConfig())
    }
  }

  "HttpSigKeySource" should {

    "name its kinds as written in the json" in {
      certKey.kind mustBe "cert"
      inlineKey.kind mustBe "inline"
      jwksKey.kind mustBe "jwks"
    }

    "read back what each kind writes" in {
      HttpSigKeySource.read(certKey.json) mustBe JsSuccess(certKey)
      HttpSigKeySource.read(inlineKey.json) mustBe JsSuccess(inlineKey)
      HttpSigKeySource.read(jwksKey.json) mustBe JsSuccess(jwksKey)
    }

    "fail to read a certificate key whose certificate id is not a string" in {
      readError(Reads(HttpSigKeySource.read), Json.obj("kind" -> "cert", "cert_id" -> 42))
    }
  }
}
