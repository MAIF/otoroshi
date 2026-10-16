package entities

import otoroshi.auth.*
import otoroshi.models.*
import otoroshi.next.models.NgTlsConfig
import otoroshi.utils.{JsonPathValidator, JsonValidator, OpaPluginValidator, WasmPluginValidator}
import play.api.libs.json.*

import scala.concurrent.duration.*

/**
 * The nested types shared by every auth. module: the session cookie values, the user validators, the remote user
 * validators, the error reasons and the login forms.
 */
class AuthModuleCommonsEntityFormatSpec extends EntityFormatSpec {

  "SessionCookieValues" should {

    val format = SessionCookieValues.fmt

    "be http only and secure by default" in {
      SessionCookieValues() mustBe SessionCookieValues(httpOnly = true, secure = true)
    }

    "write its two flags" in {
      format.writes(SessionCookieValues(httpOnly = false, secure = true)) mustBe Json.obj(
        "httpOnly" -> false,
        "secure"   -> true
      )
    }

    "expose through asJson what the format writes" in {
      SessionCookieValues(false, false).asJson mustBe format.writes(SessionCookieValues(false, false))
    }

    "round trip the default values" in {
      roundTrip(format, SessionCookieValues())
    }

    "round trip cookies that are neither http only nor secure" in {
      roundTrip(format, SessionCookieValues(httpOnly = false, secure = false))
    }

    "read an empty object as the default values" in {
      readOk(format, Json.obj()) mustBe SessionCookieValues()
    }

    "read a json null as the default values" in {
      readOk(format, JsNull) mustBe SessionCookieValues()
    }

    "fall back to the default for a flag that is not a boolean" in {
      readOk(format, Json.obj("httpOnly" -> "false", "secure" -> false)) mustBe SessionCookieValues(true, false)
    }
  }

  "ErrorReason" should {

    val format = ErrorReason.format

    "write a missing internal reason as null" in {
      format.writes(ErrorReason("denied")) mustBe Json.obj("display" -> "denied", "internal" -> JsNull)
    }

    "expose through json what the format writes" in {
      val reason = ErrorReason("denied", Some(Json.obj("error" -> "blocked")))
      reason.json mustBe format.writes(reason)
    }

    "round trip a reason without internal reason" in {
      roundTrip(format, ErrorReason("denied"))
    }

    "round trip a reason with an internal reason" in {
      roundTrip(format, ErrorReason("denied", Some(Json.obj("error" -> "blocked", "code" -> 42))))
    }

    "not read a reason without display" in {
      readError(format, Json.obj("internal" -> Json.obj()))
    }

    "ignore an internal reason that is not an object" in {
      readOk(format, Json.obj("display" -> "denied", "internal" -> "blocked")).internal mustBe None
    }
  }

  "RemoteUserValidatorSettings" should {

    val format = RemoteUserValidatorSettings.format

    val full = RemoteUserValidatorSettings(
      url = "https://validator.oto.tools/validate",
      headers = Map("Authorization" -> "Bearer xxx", "X-Foo" -> "bar"),
      timeout = 2500.millis,
      tlsSettings = NgTlsConfig(
        certs = Seq("cert-1"),
        trustedCerts = Seq("ca-1"),
        enabled = true,
        loose = true,
        trustAll = false
      )
    )

    "write the timeout in milliseconds and the tls settings in snake case" in {
      format.writes(full) mustBe Json.obj(
        "url"          -> "https://validator.oto.tools/validate",
        "headers"      -> Json.obj("Authorization" -> "Bearer xxx", "X-Foo" -> "bar"),
        "timeout"      -> 2500,
        "tls_settings" -> Json.obj(
          "certs"         -> Json.arr("cert-1"),
          "trusted_certs" -> Json.arr("ca-1"),
          "enabled"       -> true,
          "loose"         -> true,
          "trust_all"     -> false
        )
      )
    }

    "expose through json what the format writes" in {
      full.json mustBe format.writes(full)
    }

    "round trip settings where every field is set" in {
      roundTrip(format, full)
    }

    "round trip settings without headers and with the default tls settings" in {
      roundTrip(format, RemoteUserValidatorSettings("http://localhost", Map.empty, 10.seconds, NgTlsConfig()))
    }

    "read a lone url with no header, a 10 seconds timeout and the default tls settings" in {
      readOk(format, Json.obj("url" -> "http://localhost")) mustBe RemoteUserValidatorSettings(
        "http://localhost",
        Map.empty,
        10.seconds,
        NgTlsConfig()
      )
    }

    "not read settings without url" in {
      readError(format, Json.obj("timeout" -> 1000))
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "fall back to the default timeout when it is not a number" in {
      readOk(format, Json.obj("url" -> "http://localhost", "timeout" -> "1000")).timeout mustBe 10.seconds
    }

    "ignore tls settings that are not an object" in {
      readOk(format, Json.obj("url" -> "http://localhost", "tls_settings" -> true)).tlsSettings mustBe NgTlsConfig()
    }

    "ignore headers holding a non string value" in {
      readOk(format, Json.obj("url" -> "http://localhost", "headers" -> Json.obj("a" -> 1))).headers mustBe Map.empty
    }
  }

  "Form" should {

    val format = Form._fmt

    "write its schema and its flow" in {
      format.writes(Form(Json.obj("name" -> Json.obj("type" -> "string")), Seq("name"))) mustBe Json.obj(
        "schema" -> Json.obj("name" -> Json.obj("type" -> "string")),
        "flow"   -> Json.arr("name")
      )
    }

    "round trip a form" in {
      roundTrip(format, Form(Json.obj("a" -> Json.obj("type" -> "bool")), Seq("a")))
    }

    "read an empty object as an empty schema and no flow" in {
      readOk(format, Json.obj()) mustBe Form(Json.obj(), Seq.empty)
    }

    "keep a schema that is not an object" in {
      readOk(format, Json.obj("schema" -> "raw")).schema mustBe JsString("raw")
    }
  }

  "JsonPathValidator" should {

    val format = JsonPathValidator.format

    "write its kind and a missing error as null" in {
      format.writes(JsonPathValidator("$.email", JsString("Regex(.*@oto.tools)"))) mustBe Json.obj(
        "kind"  -> "json-path-validator",
        "path"  -> "$.email",
        "value" -> "Regex(.*@oto.tools)",
        "error" -> JsNull
      )
    }

    "round trip a validator with an error message" in {
      roundTrip(format, JsonPathValidator("$.groups", JsString("Contains(admins)"), Some("not an admin")))
    }

    "round trip a validator without error message" in {
      roundTrip(format, JsonPathValidator("$.verified", JsBoolean(true)))
    }

    "round trip a validator expecting a json object" in {
      roundTrip(format, JsonPathValidator("$.profile", Json.obj("a" -> 1)))
    }

    "read a blank error message as no error" in {
      readOk(format, Json.obj("path" -> "$.a", "value" -> 1, "error" -> "  ")).error mustBe None
    }

    "not read a validator without path" in {
      readError(format, Json.obj("value" -> 1))
    }

    "not read a validator without value" in {
      readError(format, Json.obj("path" -> "$.a"))
    }

    "read a json null value as a json null" in {
      readOk(format, Json.obj("path" -> "$.a", "value" -> JsNull)).value mustBe JsNull
    }
  }

  "JsonValidator" should {

    val format = JsonValidator.format

    "read a validator without kind as a json path validator" in {
      readOk(format, Json.obj("path" -> "$.a", "value" -> "b")) mustBe JsonPathValidator("$.a", JsString("b"))
    }

    "read the kind whatever its case" in {
      readOk(format, Json.obj("kind" -> "WASM-Plugin-Validator", "ref" -> "wasm-1")) mustBe WasmPluginValidator("wasm-1")
    }

    "round trip a json path validator" in {
      roundTrip(format, JsonPathValidator("$.a", JsString("b"), Some("bad a")))
    }

    "round trip a wasm plugin validator without error message" in {
      roundTrip(format, WasmPluginValidator("wasm-1"))
    }

    "round trip an opa plugin validator without error message" in {
      roundTrip(format, OpaPluginValidator("opa-1"))
    }

    "write the kind, the ref and the error of a wasm plugin validator" in {
      format.writes(WasmPluginValidator("wasm-1")) mustBe Json.obj(
        "kind"  -> "wasm-plugin-validator",
        "ref"   -> "wasm-1",
        "error" -> JsNull
      )
    }

    "write the kind, the ref and the error of an opa plugin validator" in {
      format.writes(OpaPluginValidator("opa-1")) mustBe Json.obj(
        "kind"  -> "opa-plugin-validator",
        "ref"   -> "opa-1",
        "error" -> JsNull
      )
    }

    "round trip the custom error of a wasm plugin validator" in {
      roundTrip(format, WasmPluginValidator("wasm-1", Some("not allowed by the wasm policy"))) mustBe WasmPluginValidator(
        "wasm-1",
        Some("not allowed by the wasm policy")
      )
    }

    "round trip the custom error of an opa plugin validator" in {
      roundTrip(format, OpaPluginValidator("opa-1", Some("denied by opa"))) mustBe OpaPluginValidator(
        "opa-1",
        Some("denied by opa")
      )
    }

    "not read an unknown kind" in {
      readError(format, Json.obj("kind" -> "foo", "ref" -> "x"))
    }

    "not read a wasm plugin validator without ref" in {
      readError(format, Json.obj("kind" -> "wasm-plugin-validator"))
    }

    "not read a json null" in {
      readError(format, JsNull)
    }
  }

  "AuthModuleConfigFormat" should {

    // the env is only used to look for the auth. modules contributed by plugins, for unknown types
    val format = AuthModuleConfigFormat(null)

    val session = Json.obj("clientSideSessionEnabled" -> true)

    "not read a config without type" in {
      readError(format, session ++ Json.obj("id" -> "a", "name" -> "b"))
      readError(format, JsNull)
    }

    "read a basic module" in {
      readOk(format, session ++ Json.obj("type" -> "basic", "id" -> "a", "name" -> "b")) mustBe a[BasicAuthModuleConfig]
    }

    "read an oauth2 module" in {
      readOk(format, session ++ Json.obj("type" -> "oauth2", "id" -> "a", "name" -> "b")) mustBe a[GenericOauth2ModuleConfig]
    }

    "read a legacy global oauth2 module as an oauth2 module" in {
      val back = readOk(format, session ++ Json.obj("type" -> "oauth2-global", "id" -> "a", "name" -> "b"))
      back mustBe a[GenericOauth2ModuleConfig]
      (format.writes(back) \ "type").as[String] mustBe "oauth2"
    }

    "read an ldap module" in {
      val json = Json.obj(
        "type"         -> "ldap",
        "id"           -> "a",
        "name"         -> "b",
        "searchBase"   -> "dc=oto",
        "searchFilter" -> "(mail=${username})",
        "nameField"    -> "cn",
        "emailField"   -> "mail"
      )
      readOk(format, json) mustBe a[LdapAuthModuleConfig]
    }

    "read a saml module" in {
      val json = Json.obj(
        "type"               -> "saml",
        "id"                 -> "a",
        "name"               -> "b",
        "singleSignOnUrl"    -> "https://idp/sso",
        "credentials"        -> Json.obj("signingKey" -> Json.obj(), "encryptionKey" -> Json.obj()),
        "issuer"             -> "otoroshi",
        "validateSignature"  -> true,
        "validateAssertions" -> true,
        "signature"          -> Json.obj()
      )
      readOk(format, json) mustBe a[SamlAuthModuleConfig]
    }

    "read an oauth1 module" in {
      val json = Json.obj(
        "type"            -> "oauth1",
        "id"              -> "a",
        "name"            -> "b",
        "consumerKey"     -> "k",
        "consumerSecret"  -> "s",
        "requestTokenURL" -> "https://idp/request",
        "authorizeURL"    -> "https://idp/authorize",
        "accessTokenURL"  -> "https://idp/access"
      )
      readOk(format, json) mustBe a[Oauth1ModuleConfig]
    }

    "read a wasm module" in {
      readOk(format, Json.obj("type" -> "wasm", "id" -> "a", "name" -> "b")) mustBe a[WasmAuthModuleConfig]
    }

    "read a workflow module" in {
      readOk(format, Json.obj("type" -> "workflow", "id" -> "a", "name" -> "b")) mustBe a[WorkflowAuthModuleConfig]
    }

    "give the error of the module format for a known type" in {
      readError(format, Json.obj("type" -> "basic", "name" -> "b"))
    }

    "write what the module writes" in {
      val module = WasmAuthModuleConfig.defaultConfig
      format.writes(module) mustBe module.asJson
      module.json mustBe module.asJson
    }

    "read back what it writes for every module type" in {
      val modules: Seq[AuthModuleConfig] = Seq(
        BasicAuthModule.defaultConfig,
        WasmAuthModuleConfig.defaultConfig,
        WorkflowAuthModuleConfig.defaultConfig,
        GenericOauth2ModuleConfig(
          id = "o",
          name = "o",
          desc = "o",
          clientSideSessionEnabled = true,
          pkce = Some(PKCEConfig()),
          tags = Seq.empty,
          metadata = Map.empty,
          sessionCookieValues = SessionCookieValues()
        )
      )
      modules.map(m => readOk(format, format.writes(m))) mustBe modules
    }
  }

  "ValidableUser.strMatch" should {

    val user = PrivateAppsUser(
      randomId = "session-1",
      name = "John",
      email = "john@oto.tools",
      profile = Json.obj(),
      realm = "realm",
      authConfigId = "auth-1",
      otoroshiData = None,
      tags = Seq.empty,
      metadata = Map.empty,
      location = EntityLocation()
    )

    "match a plain value exactly" in {
      user.strMatch("john@oto.tools", "john@oto.tools") mustBe true
      user.strMatch("john@oto.tools", "jane@oto.tools") mustBe false
    }

    "match a regex" in {
      user.strMatch("john@oto.tools", "Regex(.*@oto\\.tools)") mustBe true
      user.strMatch("john@foo.bar", "Regex(.*@oto\\.tools)") mustBe false
    }

    "match a wildcard" in {
      user.strMatch("john@oto.tools", "Wildcard(*@oto.tools)") mustBe true
      user.strMatch("john@foo.bar", "Wildcard(*@oto.tools)") mustBe false
    }

    "negate a regex and a wildcard" in {
      user.strMatch("john@foo.bar", "RegexNot(.*@oto\\.tools)") mustBe true
      user.strMatch("john@oto.tools", "WildcardNot(*@oto.tools)") mustBe false
    }

    "check what a value contains" in {
      user.strMatch("john@oto.tools", "Contains(@oto)") mustBe true
      user.strMatch("john@oto.tools", "ContainsNot(@oto)") mustBe false
    }

    "negate a plain value" in {
      user.strMatch("john@oto.tools", "Not(jane@oto.tools)") mustBe true
      user.strMatch("john@oto.tools", "Not(john@oto.tools)") mustBe false
    }

    "check a comma separated list of values" in {
      user.strMatch("john@oto.tools", "ContainedIn(jane@oto.tools, john@oto.tools)") mustBe true
      user.strMatch("john@oto.tools", "NotContainedIn(jane@oto.tools, john@oto.tools)") mustBe false
    }

    "not trim a plain expected value" in {
      user.strMatch("john@oto.tools", " john@oto.tools ") mustBe false
    }
  }
}
