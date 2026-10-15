package plugins.configs

import otoroshi.next.plugins.*
import otoroshi.plugins.authcallers.OAuth2Kind
import otoroshi.utils.http.MtlsConfig
import play.api.libs.json.*

import scala.concurrent.duration.*

class OAuthPluginConfigsSpec extends PluginConfigFormatSpec {

  "OAuth1CallerConfig" should {

    val format = OAuth1CallerConfig.format

    val full = OAuth1CallerConfig(
      consumerKey = Some("consumer-key"),
      consumerSecret = Some("consumer-secret"),
      token = Some("oauth-token"),
      tokenSecret = Some("oauth-token-secret"),
      algo = Some("HMAC-SHA256")
    )

    "have no credential nor algorithm by default" in {
      OAuth1CallerConfig() mustBe OAuth1CallerConfig(None, None, None, None, None)
    }

    "write the default config with null values" in {
      format.writes(OAuth1CallerConfig()) mustBe Json.obj(
        "consumerKey"    -> JsNull,
        "consumerSecret" -> JsNull,
        "token"          -> JsNull,
        "tokenSecret"    -> JsNull,
        "algo"           -> JsNull
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "consumerKey"    -> "consumer-key",
        "consumerSecret" -> "consumer-secret",
        "token"          -> "oauth-token",
        "tokenSecret"    -> "oauth-token-secret",
        "algo"           -> "HMAC-SHA256"
      )
    }

    "round trip the default config" in {
      roundTrip(format, OAuth1CallerConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a config with the consumer credentials only" in {
      roundTrip(format, OAuth1CallerConfig(consumerKey = Some("ck"), consumerSecret = Some("cs")))
    }

    "round trip every supported signature algorithm" in {
      OAuth1Caller.Algo.keys.toSeq.sorted.foreach { algo =>
        roundTrip(format, OAuth1CallerConfig(algo = Some(algo))).algo mustBe Some(algo)
      }
    }

    "round trip empty strings as they are" in {
      roundTrip(format, OAuth1CallerConfig(consumerKey = Some(""), token = Some(""))).consumerKey mustBe Some("")
    }

    "round trip values holding expression language" in {
      roundTrip(format, OAuth1CallerConfig(token = Some("${apikey.metadata.oauth_token}")))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe OAuth1CallerConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe OAuth1CallerConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("consumer-key")) mustBe OAuth1CallerConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("consumer-key")) mustBe OAuth1CallerConfig()
    }

    "read the algorithm alone" in {
      readOk(format, Json.obj("algo" -> "TEXTPLAIN")) mustBe OAuth1CallerConfig(algo = Some("TEXTPLAIN"))
    }

    "read the token secret alone" in {
      readOk(format, Json.obj("tokenSecret" -> "ts")) mustBe OAuth1CallerConfig(tokenSecret = Some("ts"))
    }

    "keep an unknown algorithm" in {
      readOk(format, Json.obj("algo" -> "RSA-SHA1")).algo mustBe Some("RSA-SHA1")
    }

    "ignore values that are not strings" in {
      readOk(
        format,
        Json.obj("consumerKey" -> 42, "consumerSecret" -> true, "token" -> Json.arr("t"), "algo" -> Json.obj())
      ) mustBe OAuth1CallerConfig()
    }

    "not read the snake case keys" in {
      readOk(
        format,
        Json.obj("consumer_key" -> "ck", "consumer_secret" -> "cs", "token_secret" -> "ts")
      ) mustBe OAuth1CallerConfig()
    }

    "not read the oauth parameter names" in {
      readOk(
        format,
        Json.obj(OAuth1Caller.Keys.consumerKey -> "ck", OAuth1Caller.Keys.tokenSecret -> "ts")
      ) mustBe OAuth1CallerConfig()
    }

    "map the supported signature methods to the jvm mac algorithms" in {
      OAuth1Caller.Algo mustBe Map(
        "HMAC-SHA1"   -> "HmacSHA1",
        "HMAC-SHA256" -> "HmacSHA256",
        "HMAC-SHA384" -> "HmacSHA384",
        "HMAC-SHA512" -> "HmacSHA512",
        "TEXTPLAIN"   -> "TEXTPLAIN"
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
      jsonMatchesFormat(format, OAuth1CallerConfig())
    }

    "be the default config of OAuth1Caller" in {
      defaultConfigReadsBack(new OAuth1Caller(), format) mustBe OAuth1CallerConfig()
    }
  }

  "OAuth2CallerConfig" should {

    val format = OAuth2CallerConfig.format

    val full = OAuth2CallerConfig(
      kind = OAuth2Kind.Password,
      authModRef = Some("auth_mod_keycloak"),
      url = "https://auth.oto.tools/realms/foo/protocol/openid-connect/token",
      method = "PUT",
      headerName = "X-Access-Token",
      headerValueFormat = "Token %s",
      jsonPayload = true,
      clientId = "client-1",
      clientSecret = "secret-1",
      scope = Some("openid profile"),
      audience = Some("api://default"),
      resource = Some("https://api.oto.tools"),
      user = Some("alice"),
      password = Some("p4ssw0rd"),
      cacheTokenSeconds = 30.seconds,
      tlsConfig = MtlsConfig(Seq("cert-1"), Seq("ca-1"), mtls = true, loose = true, trustAll = true)
    )

    "fetch a token with the client credentials flow every ten minutes by default" in {
      val config = OAuth2CallerConfig()
      config.kind mustBe OAuth2Kind.ClientCredentials
      config.authModRef mustBe None
      config.url mustBe "https://127.0.0.1:8080/oauth/token"
      config.method mustBe "POST"
      config.headerName mustBe "Authorization"
      config.headerValueFormat mustBe "Bearer %s"
      config.jsonPayload mustBe false
      config.clientId mustBe "the client_id"
      config.clientSecret mustBe "the client_secret"
      config.cacheTokenSeconds mustBe 10.minutes
      config.tlsConfig mustBe MtlsConfig()
    }

    "write the default config" in {
      format.writes(OAuth2CallerConfig()) mustBe Json.obj(
        "kind"              -> "client_credentials",
        "url"               -> "https://127.0.0.1:8080/oauth/token",
        "method"            -> "POST",
        "headerName"        -> "Authorization",
        "headerValueFormat" -> "Bearer %s",
        "jsonPayload"       -> false,
        "clientId"          -> "the client_id",
        "clientSecret"      -> "the client_secret",
        "scope"             -> JsNull,
        "audience"          -> JsNull,
        "resource"          -> JsNull,
        "user"              -> JsNull,
        "password"          -> JsNull,
        "cacheTokenSeconds" -> 600000,
        "tlsConfig"         -> Json.obj(
          "certs"        -> Json.arr(),
          "trustedCerts" -> Json.arr(),
          "mtls"         -> false,
          "loose"        -> false,
          "trustAll"     -> false
        ),
        "authModRef"        -> JsNull
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "kind"              -> "password",
        "url"               -> "https://auth.oto.tools/realms/foo/protocol/openid-connect/token",
        "method"            -> "PUT",
        "headerName"        -> "X-Access-Token",
        "headerValueFormat" -> "Token %s",
        "jsonPayload"       -> true,
        "clientId"          -> "client-1",
        "clientSecret"      -> "secret-1",
        "scope"             -> "openid profile",
        "audience"          -> "api://default",
        "resource"          -> "https://api.oto.tools",
        "user"              -> "alice",
        "password"          -> "p4ssw0rd",
        "cacheTokenSeconds" -> 30000,
        "tlsConfig"         -> Json.obj(
          "certs"        -> Json.arr("cert-1"),
          "trustedCerts" -> Json.arr("ca-1"),
          "mtls"         -> true,
          "loose"        -> true,
          "trustAll"     -> true
        ),
        "authModRef"        -> "auth_mod_keycloak"
      )
    }

    "round trip the default config" in {
      roundTrip(format, OAuth2CallerConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip the client credentials kind" in {
      roundTrip(format, full.copy(kind = OAuth2Kind.ClientCredentials)).kind mustBe OAuth2Kind.ClientCredentials
    }

    "round trip the password kind" in {
      roundTrip(format, OAuth2CallerConfig(kind = OAuth2Kind.Password)).kind mustBe OAuth2Kind.Password
    }

    "round trip the password with basic auth kind" in {
      roundTrip(format, OAuth2CallerConfig(kind = OAuth2Kind.PasswordWithBasicAuth)).kind mustBe OAuth2Kind.PasswordWithBasicAuth
    }

    "round trip the auth module kind" in {
      roundTrip(format, OAuth2CallerConfig(kind = OAuth2Kind.AuthModule)).kind mustBe OAuth2Kind.AuthModule
    }

    "round trip a config without any optional value" in {
      val back = roundTrip(
        format,
        full.copy(authModRef = None, scope = None, audience = None, resource = None, user = None, password = None)
      )
      back.scope mustBe None
      back.password mustBe None
    }

    "write the token cache duration in milliseconds" in {
      written(format, OAuth2CallerConfig(cacheTokenSeconds = 2.minutes)) \ "cacheTokenSeconds" mustBe JsDefined(
        JsNumber(120000)
      )
    }

    "read the token cache duration as milliseconds" in {
      readOk(format, Json.obj("cacheTokenSeconds" -> 1500)).cacheTokenSeconds mustBe 1500.millis
    }

    "round trip a zero token cache duration" in {
      roundTrip(format, OAuth2CallerConfig(cacheTokenSeconds = 0.seconds)).cacheTokenSeconds mustBe Duration.Zero
    }

    "read an empty object as the default config, but with -- as client credentials" in {
      readOk(format, Json.obj()) mustBe OAuth2CallerConfig(clientId = "--", clientSecret = "--")
    }

    "read a json null like an empty object" in {
      readOk(format, JsNull) mustBe readOk(format, Json.obj())
    }

    "read a json string like an empty object" in {
      readOk(format, JsString("client_credentials")) mustBe readOk(format, Json.obj())
    }

    "read a json array like an empty object" in {
      readOk(format, Json.arr("client_credentials")) mustBe readOk(format, Json.obj())
    }

    "read the client credentials alone and default the other fields" in {
      readOk(format, Json.obj("clientId" -> "id", "clientSecret" -> "secret")) mustBe OAuth2CallerConfig(
        clientId = "id",
        clientSecret = "secret"
      )
    }

    "read the token url alone" in {
      readOk(format, Json.obj("url" -> "https://auth.oto.tools/token")).url mustBe "https://auth.oto.tools/token"
    }

    "read any unknown kind as the password kind" in {
      readOk(format, Json.obj("kind" -> "implicit")).kind mustBe OAuth2Kind.Password
      readOk(format, Json.obj("kind" -> "")).kind mustBe OAuth2Kind.Password
    }

    "read the kind in a case sensitive way" in {
      readOk(format, Json.obj("kind" -> "CLIENT_CREDENTIALS")).kind mustBe OAuth2Kind.Password
    }

    "read a kind that is not a string as the client credentials kind" in {
      readOk(format, Json.obj("kind" -> 1)).kind mustBe OAuth2Kind.ClientCredentials
    }

    "read empty optional strings as missing" in {
      val back = readOk(
        format,
        Json.obj("scope" -> "", "audience" -> "", "resource" -> "", "user" -> "", "password" -> "")
      )
      back.scope mustBe None
      back.audience mustBe None
      back.resource mustBe None
      back.user mustBe None
      back.password mustBe None
    }

    "drop empty optional strings on a round trip" in {
      readOk(format, format.writes(OAuth2CallerConfig(scope = Some(""), user = Some("")))) mustBe OAuth2CallerConfig()
    }

    "keep an empty auth module ref" in {
      roundTrip(format, OAuth2CallerConfig(authModRef = Some(""))).authModRef mustBe Some("")
    }

    "ignore a token cache duration written as a string" in {
      readOk(format, Json.obj("cacheTokenSeconds" -> "60000")).cacheTokenSeconds mustBe 10.minutes
    }

    "ignore a token cache duration that is not a whole number" in {
      readOk(format, Json.obj("cacheTokenSeconds" -> 1.5)).cacheTokenSeconds mustBe 10.minutes
    }

    "ignore a json payload flag written as a string" in {
      readOk(format, Json.obj("jsonPayload" -> "true")).jsonPayload mustBe false
    }

    "fall back to -- for client credentials that are not strings" in {
      val back = readOk(format, Json.obj("clientId" -> 42, "clientSecret" -> JsNull))
      back.clientId mustBe "--"
      back.clientSecret mustBe "--"
    }

    "read the legacy certId and tls keys of the tls config" in {
      readOk(format, Json.obj("tlsConfig" -> Json.obj("certId" -> "cert-1", "tls" -> true))).tlsConfig mustBe MtlsConfig(
        certs = Seq("cert-1"),
        mtls = true
      )
    }

    "drop blank certificate ids of the tls config" in {
      readOk(
        format,
        Json.obj("tlsConfig" -> Json.obj("certs" -> Json.arr("cert-1", " "), "trustedCerts" -> Json.arr("")))
      ).tlsConfig mustBe MtlsConfig(certs = Seq("cert-1"))
    }

    "ignore a tls config that is not an object" in {
      readOk(format, Json.obj("tlsConfig" -> "mtls")).tlsConfig mustBe MtlsConfig()
    }

    "not read the snake case keys" in {
      readOk(
        format,
        Json.obj("client_id" -> "id", "header_name" -> "X-Foo", "auth_mod_ref" -> "ref", "json_payload" -> true)
      ) mustBe readOk(format, Json.obj())
    }

    "name the kinds as written in the json" in {
      OAuth2Kind.ClientCredentials.name mustBe "client_credentials"
      OAuth2Kind.Password.name mustBe "password"
      OAuth2Kind.PasswordWithBasicAuth.name mustBe "password_with_basic_auth"
      OAuth2Kind.AuthModule.name mustBe "auth_module"
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
      jsonMatchesFormat(format, OAuth2CallerConfig())
    }

    "be the default config of OAuth2Caller" in {
      defaultConfigReadsBack(new OAuth2Caller(), format) mustBe OAuth2CallerConfig()
    }
  }

  "OAuth2AuthModuleCallerConfig" should {

    val format = OAuth2AuthModuleCallerConfig.format

    val full = OAuth2AuthModuleCallerConfig(
      ref = Some("auth_mod_keycloak"),
      headerName = "X-Access-Token",
      headerValueFormat = "Token %s",
      jsonPayload = true,
      cacheTokenSeconds = 45.seconds
    )

    "use a bearer authorization header cached for ten minutes by default" in {
      OAuth2AuthModuleCallerConfig() mustBe OAuth2AuthModuleCallerConfig(
        ref = None,
        headerName = "Authorization",
        headerValueFormat = "Bearer %s",
        jsonPayload = false,
        cacheTokenSeconds = 10.minutes
      )
    }

    "write the default config" in {
      format.writes(OAuth2AuthModuleCallerConfig()) mustBe Json.obj(
        "ref"               -> JsNull,
        "headerName"        -> "Authorization",
        "headerValueFormat" -> "Bearer %s",
        "jsonPayload"       -> false,
        "cacheTokenSeconds" -> 600000
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "ref"               -> "auth_mod_keycloak",
        "headerName"        -> "X-Access-Token",
        "headerValueFormat" -> "Token %s",
        "jsonPayload"       -> true,
        "cacheTokenSeconds" -> 45000
      )
    }

    "round trip the default config" in {
      roundTrip(format, OAuth2AuthModuleCallerConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a config without auth module ref" in {
      roundTrip(format, full.copy(ref = None)).ref mustBe None
    }

    "round trip a token cache duration that is not a whole number of seconds" in {
      roundTrip(format, OAuth2AuthModuleCallerConfig(cacheTokenSeconds = 1500.millis)).cacheTokenSeconds mustBe 1500.millis
    }

    "read the token cache duration as milliseconds" in {
      readOk(format, Json.obj("cacheTokenSeconds" -> 600)).cacheTokenSeconds mustBe 600.millis
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe OAuth2AuthModuleCallerConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe OAuth2AuthModuleCallerConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("auth_mod_keycloak")) mustBe OAuth2AuthModuleCallerConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("auth_mod_keycloak")) mustBe OAuth2AuthModuleCallerConfig()
    }

    "read the auth module ref alone" in {
      readOk(format, Json.obj("ref" -> "auth_mod_1")) mustBe OAuth2AuthModuleCallerConfig(ref = Some("auth_mod_1"))
    }

    "read the json payload flag alone" in {
      readOk(format, Json.obj("jsonPayload" -> true)) mustBe OAuth2AuthModuleCallerConfig(jsonPayload = true)
    }

    "read an empty auth module ref as no ref" in {
      readOk(format, Json.obj("ref" -> "")).ref mustBe None
    }

    "read a null auth module ref as no ref" in {
      readOk(format, Json.obj("ref" -> JsNull)).ref mustBe None
    }

    "ignore an auth module ref that is not a string" in {
      readOk(format, Json.obj("ref" -> Json.obj("id" -> "auth_mod_1"))).ref mustBe None
    }

    "ignore a header name that is not a string" in {
      readOk(format, Json.obj("headerName" -> 42)).headerName mustBe "Authorization"
    }

    "ignore a token cache duration written as a string" in {
      readOk(format, Json.obj("cacheTokenSeconds" -> "600")).cacheTokenSeconds mustBe 10.minutes
    }

    "ignore a json payload flag written as a string" in {
      readOk(format, Json.obj("jsonPayload" -> "true")).jsonPayload mustBe false
    }

    "not read the snake case keys" in {
      readOk(
        format,
        Json.obj("header_name" -> "X-Foo", "header_value_format" -> "%s", "cache_token_seconds" -> 1)
      ) mustBe OAuth2AuthModuleCallerConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
      jsonMatchesFormat(format, OAuth2AuthModuleCallerConfig())
    }

    "list every written field in its config flow and schema" in {
      OAuth2AuthModuleCallerConfig.configFlow.toSet mustBe writtenKeys(format, OAuth2AuthModuleCallerConfig())
      OAuth2AuthModuleCallerConfig.configFlow.foreach { field =>
        OAuth2AuthModuleCallerConfig.configSchema.get.keys must contain(field)
      }
    }

    "be the default config of OAuth2AuthModuleCaller" in {
      defaultConfigReadsBack(new OAuth2AuthModuleCaller(), format) mustBe OAuth2AuthModuleCallerConfig()
    }

    "be described by the config flow and schema of OAuth2AuthModuleCaller" in {
      val plugin = new OAuth2AuthModuleCaller()
      plugin.configFlow mustBe OAuth2AuthModuleCallerConfig.configFlow
      plugin.configSchema mustBe OAuth2AuthModuleCallerConfig.configSchema
    }
  }
}
