package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class OAuthProtectedResourcesPluginConfigsSpec extends PluginConfigFormatSpec {

  "OAuthProtectedResourceMetadataConfig" should {

    val format = OAuthProtectedResourceMetadataConfig.format

    val default = OAuthProtectedResourceMetadataConfig()

    val full = OAuthProtectedResourceMetadataConfig(
      ref = Some("auth-module-1"),
      resource = Some("https://api.oto.tools"),
      authorizationServersOverride = Seq("https://as1.oto.tools", "https://as2.oto.tools"),
      jwksUri = Some("https://api.oto.tools/.well-known/jwks.json"),
      scopesSupported = Seq("read", "write"),
      bearerMethodsSupported = Seq("header", "body", "query"),
      resourceSigningAlgValuesSupported = Seq("RS256", "ES256"),
      resourceName = Some("My API"),
      resourceDocumentation = Some("https://docs.oto.tools"),
      resourcePolicyUri = Some("https://oto.tools/policy"),
      resourceTosUri = Some("https://oto.tools/tos"),
      tlsClientCertificateBoundAccessTokens = true,
      dpopSigningAlgValuesSupported = Seq("ES256"),
      dpopBoundAccessTokensRequired = true,
      authorizationDetailsTypesSupported = Seq("payment_initiation"),
      extraMetadata = Json.obj("x_custom" -> "value", "x_nested" -> Json.obj("a" -> 1)),
      signedMetadata = true,
      signingCertRef = Some("cert-1"),
      signingAlg = "ES256",
      signedMetadataKid = Some("kid-1")
    )

    "write the default config with all its keys" in {
      format.writes(default) mustBe Json.obj(
        "ref"                                        -> JsNull,
        "resource"                                   -> JsNull,
        "authorization_servers_override"             -> Json.arr(),
        "jwks_uri"                                   -> JsNull,
        "scopes_supported"                           -> Json.arr(),
        "bearer_methods_supported"                   -> Json.arr("header"),
        "resource_signing_alg_values_supported"      -> Json.arr(),
        "resource_name"                              -> JsNull,
        "resource_documentation"                     -> JsNull,
        "resource_policy_uri"                        -> JsNull,
        "resource_tos_uri"                           -> JsNull,
        "tls_client_certificate_bound_access_tokens" -> false,
        "dpop_signing_alg_values_supported"          -> Json.arr(),
        "dpop_bound_access_tokens_required"          -> false,
        "authorization_details_types_supported"      -> Json.arr(),
        "extra_metadata"                             -> Json.obj(),
        "signed_metadata"                            -> false,
        "signing_cert_ref"                           -> JsNull,
        "signing_alg"                                -> "RS256",
        "signed_metadata_kid"                        -> JsNull
      )
    }

    "round trip the default config" in {
      roundTrip(format, default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "write the optional strings as strings when set" in {
      val json = written(format, full)
      json.value("ref") mustBe JsString("auth-module-1")
      json.value("resource") mustBe JsString("https://api.oto.tools")
      json.value("jwks_uri") mustBe JsString("https://api.oto.tools/.well-known/jwks.json")
      json.value("resource_name") mustBe JsString("My API")
      json.value("resource_documentation") mustBe JsString("https://docs.oto.tools")
      json.value("resource_policy_uri") mustBe JsString("https://oto.tools/policy")
      json.value("resource_tos_uri") mustBe JsString("https://oto.tools/tos")
      json.value("signing_cert_ref") mustBe JsString("cert-1")
      json.value("signed_metadata_kid") mustBe JsString("kid-1")
    }

    "write the lists as arrays keeping their order" in {
      val json = written(format, full)
      json.value("authorization_servers_override") mustBe Json.arr("https://as1.oto.tools", "https://as2.oto.tools")
      json.value("bearer_methods_supported") mustBe Json.arr("header", "body", "query")
      json.value("resource_signing_alg_values_supported") mustBe Json.arr("RS256", "ES256")
    }

    "write the extra metadata as is" in {
      written(format, full).value("extra_metadata") mustBe Json.obj("x_custom" -> "value", "x_nested" -> Json.obj("a" -> 1))
    }

    "round trip a config with only a resource" in {
      roundTrip(format, OAuthProtectedResourceMetadataConfig(resource = Some("https://api.oto.tools")))
    }

    "round trip a signed config without kid" in {
      roundTrip(
        format,
        OAuthProtectedResourceMetadataConfig(signedMetadata = true, signingCertRef = Some("cert-1"), signingAlg = "RS512")
      )
    }

    "round trip an empty bearer methods list" in {
      roundTrip(format, OAuthProtectedResourceMetadataConfig(bearerMethodsSupported = Seq.empty)).bearerMethodsSupported mustBe empty
    }

    "round trip several authorization servers and keep their order" in {
      roundTrip(
        format,
        OAuthProtectedResourceMetadataConfig(authorizationServersOverride = Seq("https://b", "https://a"))
      ).authorizationServersOverride mustBe Seq("https://b", "https://a")
    }

    "round trip the dpop settings alone" in {
      roundTrip(
        format,
        OAuthProtectedResourceMetadataConfig(dpopSigningAlgValuesSupported = Seq("ES256", "PS256"), dpopBoundAccessTokensRequired = true)
      )
    }

    "round trip a signing alg it does not list in its schema" in {
      roundTrip(format, OAuthProtectedResourceMetadataConfig(signingAlg = "HS256")).signingAlg mustBe "HS256"
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe default
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe default
    }

    "read a json string as the default config" in {
      readOk(format, JsString("https://api.oto.tools")) mustBe default
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr()) mustBe default
    }

    "read the resource alone and default the rest" in {
      readOk(format, Json.obj("resource" -> "https://api.oto.tools")) mustBe default.copy(
        resource = Some("https://api.oto.tools")
      )
    }

    "read the scopes alone and default the rest" in {
      readOk(format, Json.obj("scopes_supported" -> Json.arr("openid"))) mustBe default.copy(
        scopesSupported = Seq("openid")
      )
    }

    "read empty optional strings as absent" in {
      val json = Json.obj(
        "ref"                    -> "",
        "resource"               -> "",
        "jwks_uri"               -> "",
        "resource_name"          -> "",
        "resource_documentation" -> "",
        "resource_policy_uri"    -> "",
        "resource_tos_uri"       -> "",
        "signing_cert_ref"       -> "",
        "signed_metadata_kid"    -> ""
      )
      readOk(format, json) mustBe default
    }

    "turn an empty optional string into a null on a round trip" in {
      val json = format.writes(readOk(format, format.writes(default.copy(resourceName = Some("")))))
      (json \ "resource_name").get mustBe JsNull
    }

    "keep an empty signing alg" in {
      readOk(format, Json.obj("signing_alg" -> "")).signingAlg mustBe ""
    }

    "read null optional strings as absent" in {
      readOk(format, Json.obj("ref" -> JsNull, "jwks_uri" -> JsNull)) mustBe default
    }

    "fall back to the header bearer method when the list is not an array" in {
      readOk(format, Json.obj("bearer_methods_supported" -> "query")).bearerMethodsSupported mustBe Seq("header")
    }

    "ignore a list holding something else than strings" in {
      readOk(format, Json.obj("scopes_supported" -> Json.arr("read", 1))).scopesSupported mustBe empty
    }

    "ignore extra metadata that is not an object" in {
      readOk(format, Json.obj("extra_metadata" -> Json.arr("x"))).extraMetadata mustBe Json.obj()
    }

    "ignore a signed_metadata flag written as a string" in {
      readOk(format, Json.obj("signed_metadata" -> "true")).signedMetadata mustBe false
    }

    "ignore a dpop_bound_access_tokens_required flag written as a number" in {
      readOk(format, Json.obj("dpop_bound_access_tokens_required" -> 1)).dpopBoundAccessTokensRequired mustBe false
    }

    "ignore a signing alg that is not a string" in {
      readOk(format, Json.obj("signing_alg" -> 256)).signingAlg mustBe "RS256"
    }

    "ignore a ref that is not a string" in {
      readOk(format, Json.obj("ref" -> Json.obj("id" -> "x"))).ref mustBe None
    }

    "not read the camel case keys" in {
      readOk(
        format,
        Json.obj("jwksUri" -> "https://x", "scopesSupported" -> Json.arr("read"), "signedMetadata" -> true)
      ) mustBe default
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "write exactly the keys of its config flow" in {
      writtenKeys(format, full) mustBe OAuthProtectedResourceMetadataConfig.configFlow.toSet
    }

    "describe every config flow field in its config schema" in {
      val schemaKeys = OAuthProtectedResourceMetadataConfig.configSchema.get.keys
      OAuthProtectedResourceMetadataConfig.configFlow.foreach(field => schemaKeys must contain(field))
    }

    "offer the default signing alg in its schema" in {
      val options =
        (OAuthProtectedResourceMetadataConfig.configSchema.get \ "signing_alg" \ "props" \ "options").as[Seq[String]]
      options mustBe Seq("RS256", "RS384", "RS512", "ES256", "ES384", "ES512")
      options must contain(default.signingAlg)
    }

    "be the default config of OAuthProtectedResourceMetadata" in {
      defaultConfigReadsBack(new OAuthProtectedResourceMetadata(), format) mustBe default
    }
  }
}
