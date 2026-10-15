package plugins.configs

import com.nimbusds.jose.{EncryptionMethod, JWEAlgorithm}
import otoroshi.models.{InCookie, InHeader, InQueryParam}
import otoroshi.next.plugins.*
import play.api.http.HttpEntity
import play.api.libs.json.*
import play.api.mvc.Result

class JwtPluginConfigsSpec extends PluginConfigFormatSpec {

  private val defaultBody = """{"error":"unauthorized"}"""

  private def bodyOf(result: Result): String = result.body match {
    case HttpEntity.Strict(data, _) => data.utf8String
    case other                      => fail(s"expected a strict body, got $other")
  }

  "NgJwtVerificationConfig" should {

    val format = NgJwtVerificationConfig.format

    val full = NgJwtVerificationConfig(
      verifiers = Seq("verifier-1", "verifier-2"),
      customResponse = true,
      customResponseStatus = 403,
      customResponseHeaders = Map("X-Error" -> "denied"),
      customResponseBody = "access denied"
    )

    "write the default config with all its keys" in {
      format.writes(NgJwtVerificationConfig()) mustBe Json.obj(
        "verifiers"               -> Json.arr(),
        "custom_response"         -> false,
        "custom_response_status"  -> 401,
        "custom_response_headers" -> Json.obj(),
        "custom_response_body"    -> defaultBody
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgJwtVerificationConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "verifiers"               -> Json.arr("verifier-1", "verifier-2"),
        "custom_response"         -> true,
        "custom_response_status"  -> 403,
        "custom_response_headers" -> Json.obj("X-Error" -> "denied"),
        "custom_response_body"    -> "access denied"
      )
    }

    "round trip several verifiers and keep their order" in {
      roundTrip(format, NgJwtVerificationConfig(verifiers = Seq("c", "a", "b"))).verifiers mustBe Seq("c", "a", "b")
    }

    "round trip several custom response headers" in {
      roundTrip(
        format,
        NgJwtVerificationConfig(customResponseHeaders = Map("Content-Type" -> "text/plain", "X-A" -> "a", "X-B" -> "b"))
      )
    }

    "round trip an empty custom response body" in {
      roundTrip(format, NgJwtVerificationConfig(customResponseBody = ""))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgJwtVerificationConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgJwtVerificationConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("verifier-1")) mustBe NgJwtVerificationConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("verifier-1")) mustBe NgJwtVerificationConfig()
    }

    "read verifiers alone and default the rest" in {
      readOk(format, Json.obj("verifiers" -> Json.arr("v1"))) mustBe NgJwtVerificationConfig(verifiers = Seq("v1"))
    }

    "read custom_response_status alone and default the rest" in {
      readOk(format, Json.obj("custom_response_status" -> 418)) mustBe NgJwtVerificationConfig(
        customResponseStatus = 418
      )
    }

    "ignore verifiers written as a single string" in {
      readOk(format, Json.obj("verifiers" -> "v1")) mustBe NgJwtVerificationConfig()
    }

    "ignore a verifiers array holding something else than strings" in {
      readOk(format, Json.obj("verifiers" -> Json.arr("v1", 2))) mustBe NgJwtVerificationConfig()
    }

    "ignore a custom_response written as a string" in {
      readOk(format, Json.obj("custom_response" -> "true")) mustBe NgJwtVerificationConfig()
    }

    "ignore a custom_response_status written as a string" in {
      readOk(format, Json.obj("custom_response_status" -> "403")) mustBe NgJwtVerificationConfig()
    }

    "ignore a custom_response_status that is not a whole number" in {
      readOk(format, Json.obj("custom_response_status" -> 403.5)) mustBe NgJwtVerificationConfig()
    }

    "ignore custom_response_headers holding a non string value" in {
      readOk(format, Json.obj("custom_response_headers" -> Json.obj("X-A" -> 1))) mustBe NgJwtVerificationConfig()
    }

    "ignore a custom_response_body written as an object" in {
      readOk(format, Json.obj("custom_response_body" -> Json.obj("error" -> "nope"))) mustBe NgJwtVerificationConfig()
    }

    "not read the camel case keys" in {
      readOk(
        format,
        Json.obj("customResponse" -> true, "customResponseStatus" -> 403)
      ) mustBe NgJwtVerificationConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "build no custom result when custom_response is off" in {
      full.copy(customResponse = false).asResult mustBe None
    }

    "build the custom result with its status, body and headers" in {
      val result = full.asResult.get
      result.header.status mustBe 403
      bodyOf(result) mustBe "access denied"
      result.header.headers.get("X-Error") mustBe Some("denied")
    }

    "build the custom result as json when no content type is set" in {
      NgJwtVerificationConfig(customResponse = true).asResult.get.body.contentType mustBe Some("application/json")
    }

    "take the content type of the custom result from the headers" in {
      val result = NgJwtVerificationConfig(
        customResponse = true,
        customResponseHeaders = Map("Content-Type" -> "text/plain", "X-A" -> "a")
      ).asResult.get
      result.body.contentType mustBe Some("text/plain")
      result.header.headers.keySet.map(_.toLowerCase) must not contain "content-type"
      result.header.headers.get("X-A") mustBe Some("a")
    }

    "be the default config of JwtVerification" in {
      defaultConfigReadsBack(new JwtVerification(), format) mustBe NgJwtVerificationConfig()
    }
  }

  "NgJwtVerificationOnlyConfig" should {

    val format = NgJwtVerificationOnlyConfig.format

    val full = NgJwtVerificationOnlyConfig(
      verifier = Some("verifier-1"),
      failIfAbsent = false,
      customResponse = true,
      customResponseStatus = 403,
      customResponseHeaders = Map("Content-Type" -> "text/plain"),
      customResponseBody = "no token"
    )

    "write the default config with all its keys" in {
      format.writes(NgJwtVerificationOnlyConfig()) mustBe Json.obj(
        "verifier"                -> JsNull,
        "fail_if_absent"          -> true,
        "custom_response"         -> false,
        "custom_response_status"  -> 401,
        "custom_response_headers" -> Json.obj(),
        "custom_response_body"    -> defaultBody
      )
    }

    "fail when the token is absent by default" in {
      NgJwtVerificationOnlyConfig().failIfAbsent mustBe true
    }

    "round trip the default config" in {
      roundTrip(format, NgJwtVerificationOnlyConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "write the verifier as a string when set" in {
      written(format, full).value("verifier") mustBe JsString("verifier-1")
    }

    "round trip a verifier alone" in {
      roundTrip(format, NgJwtVerificationOnlyConfig(verifier = Some("verifier-1")))
    }

    "round trip fail_if_absent set to false" in {
      roundTrip(format, NgJwtVerificationOnlyConfig(failIfAbsent = false)).failIfAbsent mustBe false
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgJwtVerificationOnlyConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgJwtVerificationOnlyConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("verifier-1")) mustBe NgJwtVerificationOnlyConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr()) mustBe NgJwtVerificationOnlyConfig()
    }

    "read a null verifier as no verifier" in {
      readOk(format, Json.obj("verifier" -> JsNull)) mustBe NgJwtVerificationOnlyConfig()
    }

    "read fail_if_absent alone and default the rest" in {
      readOk(format, Json.obj("fail_if_absent" -> false)) mustBe NgJwtVerificationOnlyConfig(failIfAbsent = false)
    }

    "ignore a verifier that is not a string" in {
      readOk(format, Json.obj("verifier" -> Json.arr("verifier-1"))) mustBe NgJwtVerificationOnlyConfig()
    }

    "ignore a fail_if_absent written as a string" in {
      readOk(format, Json.obj("fail_if_absent" -> "false")) mustBe NgJwtVerificationOnlyConfig()
    }

    "ignore a custom_response_status written as a string" in {
      readOk(format, Json.obj("custom_response_status" -> "500")) mustBe NgJwtVerificationOnlyConfig()
    }

    "ignore custom_response_headers written as an array" in {
      readOk(format, Json.obj("custom_response_headers" -> Json.arr("X-A"))) mustBe NgJwtVerificationOnlyConfig()
    }

    "not read the verifiers list of the multi verifier config" in {
      readOk(format, Json.obj("verifiers" -> Json.arr("verifier-1"))) mustBe NgJwtVerificationOnlyConfig()
    }

    "not read the camel case keys" in {
      readOk(format, Json.obj("failIfAbsent" -> false)) mustBe NgJwtVerificationOnlyConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "build no custom result when custom_response is off" in {
      NgJwtVerificationOnlyConfig().asResult mustBe None
    }

    "build the custom result from the custom response fields" in {
      val result = full.asResult.get
      result.header.status mustBe 403
      bodyOf(result) mustBe "no token"
      result.body.contentType mustBe Some("text/plain")
    }

    "take a lower case content-type header as the content type of the custom result" in {
      NgJwtVerificationOnlyConfig(
        customResponse = true,
        customResponseHeaders = Map("content-type" -> "text/html")
      ).asResult.get.body.contentType mustBe Some("text/html")
    }

    "be the default config of JwtVerificationOnly" in {
      defaultConfigReadsBack(new JwtVerificationOnly(), format) mustBe NgJwtVerificationOnlyConfig()
    }
  }

  "NgJwtSignerConfig" should {

    val format = NgJwtSignerConfig.format

    val full = NgJwtSignerConfig(verifier = Some("verifier-1"), replaceIfPresent = false, failIfPresent = true)

    "write the default config with all its keys" in {
      format.writes(NgJwtSignerConfig()) mustBe Json.obj(
        "verifier"           -> JsNull,
        "replace_if_present" -> true,
        "fail_if_present"    -> false
      )
    }

    "replace a present token and not fail by default" in {
      NgJwtSignerConfig().replaceIfPresent mustBe true
      NgJwtSignerConfig().failIfPresent mustBe false
    }

    "round trip the default config" in {
      roundTrip(format, NgJwtSignerConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "verifier"           -> "verifier-1",
        "replace_if_present" -> false,
        "fail_if_present"    -> true
      )
    }

    "round trip a config with only a verifier" in {
      roundTrip(format, NgJwtSignerConfig(verifier = Some("verifier-1")))
    }

    "round trip both flags set to true" in {
      roundTrip(format, NgJwtSignerConfig(replaceIfPresent = true, failIfPresent = true))
    }

    "round trip both flags set to false" in {
      roundTrip(format, NgJwtSignerConfig(replaceIfPresent = false, failIfPresent = false))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgJwtSignerConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgJwtSignerConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("verifier-1")) mustBe NgJwtSignerConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(true)) mustBe NgJwtSignerConfig()
    }

    "read replace_if_present alone and default the rest" in {
      readOk(format, Json.obj("replace_if_present" -> false)) mustBe NgJwtSignerConfig(replaceIfPresent = false)
    }

    "read fail_if_present alone and default the rest" in {
      readOk(format, Json.obj("fail_if_present" -> true)) mustBe NgJwtSignerConfig(failIfPresent = true)
    }

    "ignore a verifier that is a number" in {
      readOk(format, Json.obj("verifier" -> 42)) mustBe NgJwtSignerConfig()
    }

    "ignore flags written as strings" in {
      readOk(format, Json.obj("replace_if_present" -> "false", "fail_if_present" -> "true")) mustBe NgJwtSignerConfig()
    }

    "not read the camel case keys" in {
      readOk(format, Json.obj("replaceIfPresent" -> false, "failIfPresent" -> true)) mustBe NgJwtSignerConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of JwtSigner" in {
      defaultConfigReadsBack(new JwtSigner(), format) mustBe NgJwtSignerConfig()
    }
  }

  "NgJweSignerConfig" should {

    val format = NgJweSignerConfig.format

    val defaultLocation = Json.obj("type" -> "InHeader", "name" -> "X-JWT-Token", "remove" -> "")

    // the two token locations, the only mandatory fields
    val minimal = Json.obj("source" -> defaultLocation, "forward_location" -> defaultLocation)

    "write the default config with all its keys" in {
      format.writes(NgJweSignerConfig()) mustBe Json.obj(
        "key_management_algorithm"     -> "RSA_OAEP_256",
        "content_encryption_algorithm" -> "A128CBC_HS256",
        "certId"                       -> JsNull,
        "source"                       -> defaultLocation,
        "forward_location"             -> defaultLocation,
        "strict"                       -> false,
        "payload"                      -> Json.obj()
      )
    }

    "use rsa oaep 256 and a128cbc hs256 with the X-JWT-Token header by default" in {
      val config = NgJweSignerConfig()
      config.keyManagementAlgorithm mustBe JWEAlgorithm.RSA_OAEP_256
      config.contentEncryptionAlgorithm mustBe EncryptionMethod.A128CBC_HS256
      config.source mustBe InHeader("X-JWT-Token")
      config.forwardLocation mustBe InHeader("X-JWT-Token")
    }

    "round trip the default config" in {
      roundTrip(format, NgJweSignerConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(
        format,
        NgJweSignerConfig(
          keyManagementAlgorithm = JWEAlgorithm.RSA_OAEP_512,
          contentEncryptionAlgorithm = EncryptionMethod.A256GCM,
          certId = Some("cert-1"),
          source = InCookie("jwe"),
          forwardLocation = InQueryParam("token"),
          strict = true,
          payload = Map("sub" -> "${user.email}", "iss" -> "otoroshi")
        )
      )
    }

    "round trip every supported key management algorithm" in {
      Seq(JWEAlgorithm.RSA_OAEP_256, JWEAlgorithm.RSA_OAEP_384, JWEAlgorithm.RSA_OAEP_512).foreach { algo =>
        roundTrip(format, NgJweSignerConfig(keyManagementAlgorithm = algo)).keyManagementAlgorithm mustBe algo
      }
    }

    "round trip every supported content encryption algorithm" in {
      Seq(
        EncryptionMethod.A128CBC_HS256,
        EncryptionMethod.A192CBC_HS384,
        EncryptionMethod.A256CBC_HS512,
        EncryptionMethod.A128GCM,
        EncryptionMethod.A192GCM,
        EncryptionMethod.A256GCM
      ).foreach { enc =>
        roundTrip(format, NgJweSignerConfig(contentEncryptionAlgorithm = enc)).contentEncryptionAlgorithm mustBe enc
      }
    }

    "round trip a payload" in {
      roundTrip(format, NgJweSignerConfig(payload = Map("sub" -> "foo"))).payload mustBe Map("sub" -> "foo")
    }

    "write the algorithms and the payload under the keys of the ui form" in {
      writtenKeys(format, NgJweSignerConfig()) must contain allOf (
        "key_management_algorithm",
        "content_encryption_algorithm",
        "payload"
      )
      writtenKeys(format, NgJweSignerConfig()) must contain noneOf (
        "keyManagementAlgorithm",
        "contentEncryptionAlgorithm",
        "metadata"
      )
    }

    "still read the camel case algorithm keys written by older versions" in {
      val back = readOk(
        format,
        minimal ++ Json.obj("keyManagementAlgorithm" -> "RSA_OAEP_384", "contentEncryptionAlgorithm" -> "A192GCM")
      )
      back.keyManagementAlgorithm mustBe JWEAlgorithm.RSA_OAEP_384
      back.contentEncryptionAlgorithm mustBe EncryptionMethod.A192GCM
    }

    "still read the metadata key written by older versions" in {
      readOk(format, minimal ++ Json.obj("metadata" -> Json.obj("sub" -> "foo"))).payload mustBe Map("sub" -> "foo")
    }

    "prefer the snake case keys over the ones written by older versions" in {
      val back = readOk(
        format,
        minimal ++ Json.obj(
          "key_management_algorithm"     -> "RSA_OAEP_512",
          "keyManagementAlgorithm"       -> "RSA_OAEP_384",
          "content_encryption_algorithm" -> "A256GCM",
          "contentEncryptionAlgorithm"   -> "A192GCM",
          "payload"                      -> Json.obj("sub" -> "new"),
          "metadata"                     -> Json.obj("sub" -> "old")
        )
      )
      back.keyManagementAlgorithm mustBe JWEAlgorithm.RSA_OAEP_512
      back.contentEncryptionAlgorithm mustBe EncryptionMethod.A256GCM
      back.payload mustBe Map("sub" -> "new")
    }

    "round trip a cert id" in {
      roundTrip(format, NgJweSignerConfig(certId = Some("cert-1"))).certId mustBe Some("cert-1")
    }

    "round trip a source in a query param" in {
      roundTrip(format, NgJweSignerConfig(source = InQueryParam("jwe"))).source mustBe InQueryParam("jwe")
    }

    "round trip a source in a cookie" in {
      roundTrip(format, NgJweSignerConfig(source = InCookie("jwe"))).source mustBe InCookie("jwe")
    }

    "round trip a source in a header with a prefix to remove" in {
      roundTrip(format, NgJweSignerConfig(source = InHeader("Authorization", "Bearer "))).source mustBe InHeader(
        "Authorization",
        "Bearer "
      )
    }

    "round trip a forward location in a cookie" in {
      roundTrip(format, NgJweSignerConfig(forwardLocation = InCookie("payload"))).forwardLocation mustBe InCookie(
        "payload"
      )
    }

    "round trip a forward location in a query param" in {
      roundTrip(format, NgJweSignerConfig(forwardLocation = InQueryParam("payload")))
    }

    "round trip strict mode" in {
      roundTrip(format, NgJweSignerConfig(strict = true)).strict mustBe true
    }

    "write the token locations with their type" in {
      val json = written(format, NgJweSignerConfig(source = InQueryParam("q"), forwardLocation = InCookie("c")))
      json.value("source") mustBe Json.obj("type" -> "InQueryParam", "name" -> "q")
      json.value("forward_location") mustBe Json.obj("type" -> "InCookie", "name" -> "c")
    }

    "write every supported key management algorithm" in {
      def algo(a: JWEAlgorithm) = written(format, NgJweSignerConfig(keyManagementAlgorithm = a)).value(
        "key_management_algorithm"
      )
      algo(JWEAlgorithm.RSA_OAEP_256) mustBe JsString("RSA_OAEP_256")
      algo(JWEAlgorithm.RSA_OAEP_384) mustBe JsString("RSA_OAEP_384")
      algo(JWEAlgorithm.RSA_OAEP_512) mustBe JsString("RSA_OAEP_512")
    }

    "write every supported content encryption algorithm" in {
      def enc(e: EncryptionMethod) = written(format, NgJweSignerConfig(contentEncryptionAlgorithm = e)).value(
        "content_encryption_algorithm"
      )
      enc(EncryptionMethod.A128CBC_HS256) mustBe JsString("A128CBC_HS256")
      enc(EncryptionMethod.A192CBC_HS384) mustBe JsString("A192CBC_HS384")
      enc(EncryptionMethod.A256CBC_HS512) mustBe JsString("A256CBC_HS512")
      enc(EncryptionMethod.A128GCM) mustBe JsString("A128GCM")
      enc(EncryptionMethod.A192GCM) mustBe JsString("A192GCM")
      enc(EncryptionMethod.A256GCM) mustBe JsString("A256GCM")
    }

    "write the payload under payload" in {
      written(format, NgJweSignerConfig(payload = Map("sub" -> "${user.email}"))).value("payload") mustBe Json.obj(
        "sub" -> "${user.email}"
      )
    }

    "throw when writing a key management algorithm it does not support" in {
      a[MatchError] must be thrownBy format.writes(NgJweSignerConfig(keyManagementAlgorithm = JWEAlgorithm.ECDH_ES))
    }

    "throw when writing a content encryption algorithm it does not support" in {
      a[MatchError] must be thrownBy format.writes(
        NgJweSignerConfig(contentEncryptionAlgorithm = new EncryptionMethod("A512GCM"))
      )
    }

    "read every supported key management algorithm from key_management_algorithm" in {
      def algo(name: String) =
        readOk(format, minimal ++ Json.obj("key_management_algorithm" -> name)).keyManagementAlgorithm
      algo("RSA_OAEP_256") mustBe JWEAlgorithm.RSA_OAEP_256
      algo("RSA_OAEP_384") mustBe JWEAlgorithm.RSA_OAEP_384
      algo("RSA_OAEP_512") mustBe JWEAlgorithm.RSA_OAEP_512
    }

    "read every supported content encryption algorithm from content_encryption_algorithm" in {
      def enc(name: String) =
        readOk(format, minimal ++ Json.obj("content_encryption_algorithm" -> name)).contentEncryptionAlgorithm
      enc("A128CBC_HS256") mustBe EncryptionMethod.A128CBC_HS256
      enc("A192CBC_HS384") mustBe EncryptionMethod.A192CBC_HS384
      enc("A256CBC_HS512") mustBe EncryptionMethod.A256CBC_HS512
      enc("A128GCM") mustBe EncryptionMethod.A128GCM
      enc("A192GCM") mustBe EncryptionMethod.A192GCM
      enc("A256GCM") mustBe EncryptionMethod.A256GCM
    }

    "refuse an unknown key management algorithm" in {
      readError(format, minimal ++ Json.obj("key_management_algorithm" -> "RSA1_5"))
    }

    "refuse an unknown content encryption algorithm" in {
      readError(format, minimal ++ Json.obj("content_encryption_algorithm" -> "A512GCM"))
    }

    "read the payload from payload" in {
      readOk(format, minimal ++ Json.obj("payload" -> Json.obj("sub" -> "foo"))).payload mustBe Map("sub" -> "foo")
    }

    "read both token locations alone and default the rest" in {
      readOk(format, minimal) mustBe NgJweSignerConfig()
    }

    "read strict alone with the token locations" in {
      readOk(format, minimal ++ Json.obj("strict" -> true)) mustBe NgJweSignerConfig(strict = true)
    }

    "refuse an empty object because the source is mandatory" in {
      readError(format, Json.obj())
    }

    "refuse a config without forward_location" in {
      readError(format, Json.obj("source" -> defaultLocation))
    }

    "refuse a config without source" in {
      readError(format, Json.obj("forward_location" -> defaultLocation))
    }

    "refuse a json null" in {
      readError(format, JsNull)
    }

    "refuse a json string" in {
      readError(format, JsString("RSA_OAEP_256"))
    }

    "refuse a json array" in {
      readError(format, Json.arr(defaultLocation))
    }

    "read a null source as the default header" in {
      readOk(format, Json.obj("source" -> JsNull, "forward_location" -> JsNull)) mustBe NgJweSignerConfig()
    }

    "read a source of unknown type as the default header" in {
      readOk(
        format,
        minimal ++ Json.obj("source" -> Json.obj("type" -> "InBody", "name" -> "token"))
      ).source mustBe InHeader("X-JWT-Token")
    }

    "read a source without name as the default header" in {
      readOk(format, minimal ++ Json.obj("source" -> Json.obj("type" -> "InCookie"))).source mustBe InHeader(
        "X-JWT-Token"
      )
    }

    "read a header source without remove as removing nothing" in {
      readOk(
        format,
        minimal ++ Json.obj("source" -> Json.obj("type" -> "InHeader", "name" -> "X-Token"))
      ).source mustBe InHeader("X-Token", "")
    }

    "ignore a cert id that is not a string" in {
      readOk(format, minimal ++ Json.obj("certId" -> 42)).certId mustBe None
    }

    "not read the snake case cert_id key" in {
      readOk(format, minimal ++ Json.obj("cert_id" -> "cert-1")).certId mustBe None
    }

    "ignore a strict flag written as a string" in {
      readOk(format, minimal ++ Json.obj("strict" -> "true")).strict mustBe false
    }

    "ignore a payload holding a non string value" in {
      readOk(format, minimal ++ Json.obj("payload" -> Json.obj("exp" -> 3600))).payload mustBe Map.empty
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgJweSignerConfig(certId = Some("cert-1"), strict = true))
    }

    "be the default config of JweSigner" in {
      defaultConfigReadsBack(new JweSigner(), format) mustBe NgJweSignerConfig()
    }

    "be the default config of JweExtractor" in {
      defaultConfigReadsBack(new JweExtractor(), format) mustBe NgJweSignerConfig()
    }
  }

  "OIDCJwtVerifierConfig" should {

    val format = OIDCJwtVerifierConfig.format

    val full = OIDCJwtVerifierConfig(
      mandatory = false,
      ref = Some("auth-module-1"),
      source = Some(InHeader("Authorization", "Bearer ")),
      user = true,
      customResponse = true,
      customResponseStatus = 403,
      customResponseHeaders = Map("X-Error" -> "1"),
      customResponseBody = "forbidden"
    )

    "write the default config with all its keys" in {
      format.writes(OIDCJwtVerifierConfig()) mustBe Json.obj(
        "mandatory"               -> true,
        "user"                    -> false,
        "ref"                     -> JsNull,
        "source"                  -> JsNull,
        "custom_response"         -> false,
        "custom_response_status"  -> 401,
        "custom_response_headers" -> Json.obj(),
        "custom_response_body"    -> defaultBody
      )
    }

    "round trip the default config" in {
      roundTrip(format, OIDCJwtVerifierConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "mandatory"               -> false,
        "user"                    -> true,
        "ref"                     -> "auth-module-1",
        "source"                  -> Json.obj("type" -> "InHeader", "name" -> "Authorization", "remove" -> "Bearer "),
        "custom_response"         -> true,
        "custom_response_status"  -> 403,
        "custom_response_headers" -> Json.obj("X-Error" -> "1"),
        "custom_response_body"    -> "forbidden"
      )
    }

    "round trip a ref alone" in {
      roundTrip(format, OIDCJwtVerifierConfig(ref = Some("auth-module-1")))
    }

    "round trip a source in a query param" in {
      roundTrip(format, OIDCJwtVerifierConfig(source = Some(InQueryParam("access_token")))).source mustBe Some(
        InQueryParam("access_token")
      )
    }

    "round trip a source in a cookie" in {
      roundTrip(format, OIDCJwtVerifierConfig(source = Some(InCookie("session")))).source mustBe Some(
        InCookie("session")
      )
    }

    "round trip a non mandatory config" in {
      roundTrip(format, OIDCJwtVerifierConfig(mandatory = false)).mandatory mustBe false
    }

    "round trip the user flag" in {
      roundTrip(format, OIDCJwtVerifierConfig(user = true)).user mustBe true
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe OIDCJwtVerifierConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe OIDCJwtVerifierConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("auth-module-1")) mustBe OIDCJwtVerifierConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr()) mustBe OIDCJwtVerifierConfig()
    }

    "read mandatory alone and default the rest" in {
      readOk(format, Json.obj("mandatory" -> false)) mustBe OIDCJwtVerifierConfig(mandatory = false)
    }

    "read a source alone and default the rest" in {
      readOk(format, Json.obj("source" -> Json.obj("type" -> "InCookie", "name" -> "tok"))) mustBe OIDCJwtVerifierConfig(
        source = Some(InCookie("tok"))
      )
    }

    "ignore a source that is not an object" in {
      readOk(format, Json.obj("source" -> "Authorization")).source mustBe None
    }

    "ignore a source of unknown type" in {
      readOk(format, Json.obj("source" -> Json.obj("type" -> "InBody", "name" -> "tok"))).source mustBe None
    }

    "ignore a source without name" in {
      readOk(format, Json.obj("source" -> Json.obj("type" -> "InQueryParam"))).source mustBe None
    }

    "ignore a ref that is not a string" in {
      readOk(format, Json.obj("ref" -> Json.obj("id" -> "auth-module-1"))).ref mustBe None
    }

    "ignore a mandatory flag written as a string" in {
      readOk(format, Json.obj("mandatory" -> "false")).mandatory mustBe true
    }

    "ignore a user flag written as a number" in {
      readOk(format, Json.obj("user" -> 1)).user mustBe false
    }

    "ignore a custom_response_status written as a string" in {
      readOk(format, Json.obj("custom_response_status" -> "403")).customResponseStatus mustBe 401
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "build no custom result when custom_response is off" in {
      OIDCJwtVerifierConfig().asResult mustBe None
    }

    "build the custom result from the custom response fields" in {
      val result = full.asResult.get
      result.header.status mustBe 403
      bodyOf(result) mustBe "forbidden"
      result.header.headers.get("X-Error") mustBe Some("1")
      result.body.contentType mustBe Some("application/json")
    }

    "write exactly the keys of its config flow" in {
      writtenKeys(format, full) mustBe OIDCJwtVerifierConfig.configFlow.toSet
    }

    "describe every config flow field in its config schema" in {
      val schemaKeys = OIDCJwtVerifierConfig.configSchema.get.keys
      OIDCJwtVerifierConfig.configFlow.foreach(field => schemaKeys must contain(field))
    }

    "be the default config of OIDCJwtVerifier" in {
      defaultConfigReadsBack(new OIDCJwtVerifier(), format) mustBe OIDCJwtVerifierConfig()
    }
  }

  "OAuth2TokenExchangeParams" should {

    val format = OAuth2TokenExchangeParams.format

    val accessTokenType = "urn:ietf:params:oauth:token-type:access_token"

    "write the default params with all their keys" in {
      format.writes(OAuth2TokenExchangeParams()) mustBe Json.obj(
        "audience"             -> JsNull,
        "resource"             -> JsNull,
        "scope"                -> JsNull,
        "requested_token_type" -> accessTokenType,
        "actor_token"          -> JsNull,
        "actor_token_type"     -> accessTokenType
      )
    }

    "round trip the default params" in {
      roundTrip(format, OAuth2TokenExchangeParams())
    }

    "round trip params where every field is set" in {
      roundTrip(
        format,
        OAuth2TokenExchangeParams(
          audience = Some("api://backend"),
          resource = Some("https://backend.oto.tools"),
          scope = Some("read write"),
          requestedTokenType = "urn:ietf:params:oauth:token-type:jwt",
          actorToken = Some("actor"),
          actorTokenType = "urn:ietf:params:oauth:token-type:id_token"
        )
      )
    }

    "read an empty object as the default params" in {
      readOk(format, Json.obj()) mustBe OAuth2TokenExchangeParams()
    }

    "read a json null as the default params" in {
      readOk(format, JsNull) mustBe OAuth2TokenExchangeParams()
    }

    "read the audience alone and default the rest" in {
      readOk(format, Json.obj("audience" -> "api://backend")) mustBe OAuth2TokenExchangeParams(
        audience = Some("api://backend")
      )
    }

    "ignore a requested_token_type that is not a string" in {
      readOk(format, Json.obj("requested_token_type" -> 1)).requestedTokenType mustBe accessTokenType
    }

    "expose through json what the format writes" in {
      val params = OAuth2TokenExchangeParams(scope = Some("openid"))
      params.json mustBe format.writes(params)
    }
  }

  "OAuth2ClientCredentialsOverride" should {

    val format = OAuth2ClientCredentialsOverride.format

    "write the credentials under client_id and client_secret" in {
      format.writes(OAuth2ClientCredentialsOverride("client", "secret")) mustBe Json.obj(
        "client_id"     -> "client",
        "client_secret" -> "secret"
      )
    }

    "round trip credentials" in {
      roundTrip(format, OAuth2ClientCredentialsOverride("client", "secret"))
    }

    "refuse credentials without a secret" in {
      readError(format, Json.obj("client_id" -> "client"))
    }

    "refuse credentials without a client id" in {
      readError(format, Json.obj("client_secret" -> "secret"))
    }

    "refuse an empty object" in {
      readError(format, Json.obj())
    }

    "refuse the camel case keys" in {
      readError(format, Json.obj("clientId" -> "client", "clientSecret" -> "secret"))
    }

    "expose through json what the format writes" in {
      val credentials = OAuth2ClientCredentialsOverride("client", "secret")
      credentials.json mustBe format.writes(credentials)
    }
  }

  "OAuth2TokenExchangeConfig" should {

    val format = OAuth2TokenExchangeConfig.format

    val accessTokenType = "urn:ietf:params:oauth:token-type:access_token"

    val full = OAuth2TokenExchangeConfig(
      ref = Some("auth-module-1"),
      source = Some(InCookie("token")),
      exchange = OAuth2TokenExchangeParams(
        audience = Some("api://backend"),
        resource = Some("https://backend.oto.tools"),
        scope = Some("read write"),
        requestedTokenType = "urn:ietf:params:oauth:token-type:jwt",
        actorToken = Some("actor"),
        actorTokenType = "urn:ietf:params:oauth:token-type:id_token"
      ),
      clientCredentialsOverride = Some(OAuth2ClientCredentialsOverride("client", "secret")),
      cacheTtlMs = 60000L,
      callTimeoutMs = 3000L,
      customResponse = true,
      customResponseStatus = 403,
      customResponseHeaders = Map("X-Error" -> "1"),
      customResponseBody = "exchange failed"
    )

    "write the default config with all its keys" in {
      format.writes(OAuth2TokenExchangeConfig()) mustBe Json.obj(
        "ref"                         -> JsNull,
        "source"                      -> JsNull,
        "exchange"                    -> Json.obj(
          "audience"             -> JsNull,
          "resource"             -> JsNull,
          "scope"                -> JsNull,
          "requested_token_type" -> accessTokenType,
          "actor_token"          -> JsNull,
          "actor_token_type"     -> accessTokenType
        ),
        "client_credentials_override" -> JsNull,
        "cache_ttl_ms"                -> 0,
        "call_timeout_ms"             -> 10000,
        "custom_response"             -> false,
        "custom_response_status"      -> 401,
        "custom_response_headers"     -> Json.obj(),
        "custom_response_body"        -> defaultBody
      )
    }

    "round trip the default config" in {
      roundTrip(format, OAuth2TokenExchangeConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "write the client credentials override as a nested object" in {
      written(format, full).value("client_credentials_override") mustBe Json.obj(
        "client_id"     -> "client",
        "client_secret" -> "secret"
      )
    }

    "round trip a source in a header" in {
      roundTrip(format, OAuth2TokenExchangeConfig(source = Some(InHeader("Authorization", "Bearer "))))
    }

    "round trip a source in a query param" in {
      roundTrip(format, OAuth2TokenExchangeConfig(source = Some(InQueryParam("access_token"))))
    }

    "round trip exchange params with only an audience" in {
      roundTrip(format, OAuth2TokenExchangeConfig(exchange = OAuth2TokenExchangeParams(audience = Some("api://b"))))
    }

    "round trip a client credentials override alone" in {
      roundTrip(
        format,
        OAuth2TokenExchangeConfig(clientCredentialsOverride = Some(OAuth2ClientCredentialsOverride("c", "s")))
      )
    }

    "round trip a disabled call timeout and cache" in {
      roundTrip(format, OAuth2TokenExchangeConfig(cacheTtlMs = 0L, callTimeoutMs = 0L))
    }

    "round trip the largest durations" in {
      roundTrip(format, OAuth2TokenExchangeConfig(cacheTtlMs = Long.MaxValue, callTimeoutMs = Long.MaxValue))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe OAuth2TokenExchangeConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe OAuth2TokenExchangeConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("auth-module-1")) mustBe OAuth2TokenExchangeConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr()) mustBe OAuth2TokenExchangeConfig()
    }

    "read the cache ttl alone and default the rest" in {
      readOk(format, Json.obj("cache_ttl_ms" -> 5000)) mustBe OAuth2TokenExchangeConfig(cacheTtlMs = 5000L)
    }

    "read a partial exchange object and default its other params" in {
      readOk(format, Json.obj("exchange" -> Json.obj("scope" -> "openid"))).exchange mustBe OAuth2TokenExchangeParams(
        scope = Some("openid")
      )
    }

    "read an exchange that is not an object as the default params" in {
      readOk(format, Json.obj("exchange" -> "api://backend")).exchange mustBe OAuth2TokenExchangeParams()
    }

    "read a null exchange as the default params" in {
      readOk(format, Json.obj("exchange" -> JsNull)).exchange mustBe OAuth2TokenExchangeParams()
    }

    "ignore a client credentials override without secret" in {
      readOk(
        format,
        Json.obj("client_credentials_override" -> Json.obj("client_id" -> "client"))
      ).clientCredentialsOverride mustBe None
    }

    "ignore a client credentials override that is not an object" in {
      readOk(format, Json.obj("client_credentials_override" -> "client:secret")).clientCredentialsOverride mustBe None
    }

    "ignore a source of unknown type" in {
      readOk(format, Json.obj("source" -> Json.obj("type" -> "InBody", "name" -> "tok"))).source mustBe None
    }

    "ignore a cache ttl written as a string" in {
      readOk(format, Json.obj("cache_ttl_ms" -> "5000")).cacheTtlMs mustBe 0L
    }

    "ignore a call timeout that is not a whole number" in {
      readOk(format, Json.obj("call_timeout_ms" -> 1500.5)).callTimeoutMs mustBe 10000L
    }

    "ignore a ref that is not a string" in {
      readOk(format, Json.obj("ref" -> 1)).ref mustBe None
    }

    "not read the camel case keys" in {
      readOk(format, Json.obj("cacheTtlMs" -> 5000, "callTimeoutMs" -> 1000)) mustBe OAuth2TokenExchangeConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "build no custom result when custom_response is off" in {
      OAuth2TokenExchangeConfig().asResult mustBe None
    }

    "build the custom result from the custom response fields" in {
      val result = full.asResult.get
      result.header.status mustBe 403
      bodyOf(result) mustBe "exchange failed"
      result.header.headers.get("X-Error") mustBe Some("1")
    }

    "write exactly the keys of its config flow" in {
      writtenKeys(format, full) mustBe OAuth2TokenExchangeConfig.configFlow.toSet
    }

    "describe every config flow field in its config schema" in {
      val schemaKeys = OAuth2TokenExchangeConfig.configSchema.get.keys
      OAuth2TokenExchangeConfig.configFlow.foreach(field => schemaKeys must contain(field))
    }

    "describe the nested exchange and credentials forms with the keys their formats write" in {
      val schema   = OAuth2TokenExchangeConfig.configSchema.get
      val exchange = (schema \ "exchange").as[JsObject]
      (exchange \ "flow").as[Seq[String]].toSet mustBe writtenKeys(OAuth2TokenExchangeParams.format, full.exchange)
      (exchange \ "schema").as[JsObject].keys mustBe writtenKeys(OAuth2TokenExchangeParams.format, full.exchange)
      val credentials = (schema \ "client_credentials_override").as[JsObject]
      (credentials \ "flow").as[Seq[String]].toSet mustBe Set("client_id", "client_secret")
      (credentials \ "schema").as[JsObject].keys mustBe Set("client_id", "client_secret")
    }

    "be the default config of OAuth2TokenExchange" in {
      defaultConfigReadsBack(new OAuth2TokenExchange(), format) mustBe OAuth2TokenExchangeConfig()
    }
  }
}
