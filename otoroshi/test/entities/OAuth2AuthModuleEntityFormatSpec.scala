package entities

import otoroshi.auth.*
import otoroshi.models.*
import otoroshi.next.models.NgTlsConfig
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.{JsonPathValidator, JsonValidator, OpaPluginValidator}
import play.api.libs.json.*
import play.api.libs.ws.DefaultWSProxyServer

import scala.concurrent.duration.*

class OAuth2AuthModuleEntityFormatSpec extends EntityFormatSpec {

  "PKCEConfig" should {

    val format = PKCEConfig._fmt

    "be disabled with the S256 algorithm by default" in {
      PKCEConfig() mustBe PKCEConfig(enabled = false, algorithm = "S256")
    }

    "write its flag and its algorithm" in {
      format.writes(PKCEConfig(enabled = true, algorithm = "plain")) mustBe Json.obj("enabled" -> true, "algorithm" -> "plain")
    }

    "round trip an enabled plain config" in {
      roundTrip(format, PKCEConfig(enabled = true, algorithm = "plain"))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe PKCEConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe PKCEConfig()
    }

    "fall back to the defaults for values of the wrong type" in {
      readOk(format, Json.obj("enabled" -> "true", "algorithm" -> 256)) mustBe PKCEConfig()
    }
  }

  "GenericOauth2ModuleConfig" should {

    val format = GenericOauth2ModuleConfig._fmt

    val rights = UserRights.varargs(UserRight(TenantAccess("tenant-1"), Seq(TeamAccess("team-1:r"))))

    val minimal = GenericOauth2ModuleConfig(
      id = "auth_mod_oauth2",
      name = "Keycloak",
      desc = "an oidc module",
      clientSideSessionEnabled = true,
      tags = Seq.empty,
      metadata = Map.empty,
      sessionCookieValues = SessionCookieValues()
    )

    val full = GenericOauth2ModuleConfig(
      id = "auth_mod_oauth2",
      name = "Keycloak",
      desc = "an oidc module",
      clientSideSessionEnabled = false,
      sessionMaxAge = 600,
      userValidators = Seq(JsonPathValidator("$.email_verified", JsBoolean(true), Some("email not verified"))),
      remoteValidators = Seq(RemoteUserValidatorSettings("https://validator.oto.tools", Map.empty, 1.second, NgTlsConfig())),
      clientId = "my-client",
      clientSecret = "my-secret",
      tokenUrl = "https://idp.oto.tools/token",
      authorizeUrl = "https://idp.oto.tools/authorize",
      userInfoUrl = "https://idp.oto.tools/userinfo",
      introspectionUrl = "https://idp.oto.tools/introspect",
      loginUrl = "https://idp.oto.tools/login",
      logoutUrl = "https://idp.oto.tools/logout?redirect=${redirect}",
      scope = "openid email",
      claims = "email",
      useCookie = true,
      useJson = true,
      pkce = Some(PKCEConfig(enabled = true, algorithm = "plain")),
      noWildcardRedirectURI = true,
      readProfileFromToken = true,
      jwtVerifier = Some(HSAlgoSettings(512, "a-secret", base64 = true)),
      accessTokenField = "id_token",
      nameField = "preferred_username",
      emailField = "mail",
      apiKeyMetaField = "meta",
      apiKeyTagsField = "tags",
      otoroshiDataField = "otoroshi",
      callbackUrl = "https://privateapps.oto.tools/privateapps/generic/callback",
      oidConfig = Some("https://idp.oto.tools/.well-known/openid-configuration"),
      proxy = Some(
        DefaultWSProxyServer(
          host = "proxy.oto.tools",
          port = 3128,
          protocol = Some("https"),
          principal = Some("user"),
          password = Some("pass"),
          ntlmDomain = Some("domain"),
          encoding = Some("UTF-8"),
          nonProxyHosts = Some(Seq("localhost"))
        )
      ),
      extraMetadata = Json.obj("provider" -> "keycloak"),
      mtlsConfig = MtlsConfig(certs = Seq("cert-1"), trustedCerts = Seq("ca-1"), mtls = true, loose = true, trustAll = true),
      refreshTokens = true,
      tags = Seq("sso"),
      metadata = Map("owner" -> "security"),
      sessionCookieValues = SessionCookieValues(httpOnly = false, secure = false),
      location = customLocation,
      superAdmins = false,
      rightsOverride = Map("john@oto.tools" -> rights, "jane@oto.tools" -> UserRights.empty),
      dataOverride = Map("john@oto.tools" -> Json.obj("level" -> 1)),
      otoroshiRightsField = "rights",
      adminEntityValidatorsOverride = Map(
        "john@oto.tools" -> Map(
          "route" -> Seq(JsonPathValidator("$.enabled", JsBoolean(true))),
          "all"   -> Seq(OpaPluginValidator("opa-1"))
        )
      ),
      allowedUsers = Seq("Wildcard(*@oto.tools)"),
      deniedUsers = Seq("Contains(external)")
    )

    "write every key of a minimal module" in {
      writtenKeys(format, minimal) mustBe Set(
        "_loc",
        "type",
        "id",
        "name",
        "desc",
        "clientSideSessionEnabled",
        "sessionMaxAge",
        "userValidators",
        "remoteValidators",
        "clientId",
        "clientSecret",
        "authorizeUrl",
        "tokenUrl",
        "userInfoUrl",
        "introspectionUrl",
        "loginUrl",
        "logoutUrl",
        "scope",
        "claims",
        "useCookie",
        "useJson",
        "pkce",
        "noWildcardRedirectURI",
        "readProfileFromToken",
        "accessTokenField",
        "jwtVerifier",
        "nameField",
        "emailField",
        "apiKeyMetaField",
        "apiKeyTagsField",
        "otoroshiDataField",
        "callbackUrl",
        "oidConfig",
        "mtlsConfig",
        "proxy",
        "extraMetadata",
        "metadata",
        "tags",
        "refreshTokens",
        "sessionCookieValues",
        "superAdmins",
        "rightsOverride",
        "dataOverride",
        "otoroshiRightsField",
        "allowedUsers",
        "deniedUsers",
        "adminEntityValidatorsOverride"
      )
    }

    "write the identity and the urls of a minimal module" in {
      val json = written(format, minimal)
      (json \ "type").as[String] mustBe "oauth2"
      (json \ "id").as[String] mustBe "auth_mod_oauth2"
      (json \ "clientId").as[String] mustBe "client"
      (json \ "clientSecret").as[String] mustBe "secret"
      (json \ "authorizeUrl").as[String] mustBe "http://localhost:8082/oauth/authorize"
      (json \ "tokenUrl").as[String] mustBe "http://localhost:8082/oauth/token"
      (json \ "callbackUrl").as[String] mustBe "http://privateapps.oto.tools:8080/privateapps/generic/callback"
      (json \ "_loc").as[JsValue] mustBe defaultLocationJson
    }

    "write the missing optional values of a minimal module as null" in {
      val json = written(format, minimal)
      (json \ "pkce").as[JsValue] mustBe JsNull
      (json \ "jwtVerifier").as[JsValue] mustBe JsNull
      (json \ "oidConfig").as[JsValue] mustBe JsNull
      (json \ "proxy").as[JsValue] mustBe JsNull
    }

    "write the rights and the validators overrides of a minimal module as empty objects" in {
      val json = written(format, minimal)
      (json \ "superAdmins").as[Boolean] mustBe false
      (json \ "rightsOverride").as[JsValue] mustBe Json.obj()
      (json \ "dataOverride").as[JsValue] mustBe Json.obj()
      (json \ "adminEntityValidatorsOverride").as[JsValue] mustBe Json.obj()
    }

    "write the mtls config of a minimal module" in {
      (written(format, minimal) \ "mtlsConfig").as[JsValue] mustBe Json.obj(
        "certs"        -> Json.arr(),
        "trustedCerts" -> Json.arr(),
        "mtls"         -> false,
        "loose"        -> false,
        "trustAll"     -> false
      )
    }

    "write the rights overrides as rights arrays" in {
      (written(format, full) \ "rightsOverride").as[JsValue] mustBe Json.obj(
        "john@oto.tools" -> Json.arr(Json.obj("tenant" -> "tenant-1:rw", "teams" -> Json.arr("team-1:r"))),
        "jane@oto.tools" -> Json.arr()
      )
    }

    "write the admin entity validators overrides by user and by entity" in {
      (written(format, full) \ "adminEntityValidatorsOverride" \ "john@oto.tools" \ "all").as[JsValue] mustBe Json.arr(
        Json.obj("kind" -> "opa-plugin-validator", "ref" -> "opa-1")
      )
    }

    "write its proxy" in {
      (written(format, full) \ "proxy").as[JsValue] mustBe Json.obj(
        "host"          -> "proxy.oto.tools",
        "port"          -> 3128,
        "protocol"      -> "https",
        "principal"     -> "user",
        "password"      -> "pass",
        "ntlmDomain"    -> "domain",
        "encoding"      -> "UTF-8",
        "nonProxyHosts" -> Json.arr("localhost")
      )
    }

    "write its jwt verifier" in {
      (written(format, full) \ "jwtVerifier").as[JsValue] mustBe Json.obj(
        "type"   -> "HSAlgoSettings",
        "size"   -> 512,
        "secret" -> "a-secret",
        "base64" -> true
      )
    }

    "round trip a minimal module with the default pkce config" in {
      roundTrip(format, minimal.copy(pkce = Some(PKCEConfig())))
    }

    "round trip a module where every field is set" in {
      roundTrip(format, full)
    }

    "keep its rights overrides on a round trip" in {
      roundTrip(format, full).rightsOverride mustBe Map("john@oto.tools" -> rights, "jane@oto.tools" -> UserRights.empty)
    }

    "keep its admin entity validators overrides on a round trip" in {
      roundTrip(format, full).adminEntityValidatorsOverride mustBe full.adminEntityValidatorsOverride
    }

    "keep the super admin flag on a round trip" in {
      roundTrip(format, full.copy(superAdmins = true)).superAdmins mustBe true
      roundTrip(format, full.copy(superAdmins = false)).superAdmins mustBe false
    }

    "round trip an rsa jwt verifier" in {
      roundTrip(format, full.copy(jwtVerifier = Some(RSAlgoSettings(256, "public", Some("private")))))
    }

    "round trip a proxy with only a host and a port" in {
      roundTrip(format, full.copy(proxy = Some(DefaultWSProxyServer("proxy.oto.tools", 3128)))).proxy mustBe Some(
        DefaultWSProxyServer("proxy.oto.tools", 3128)
      )
    }

    "round trip its custom location" in {
      roundTrip(format, full.copy(location = customLocation)).location mustBe customLocation
    }

    "read the default location without _loc" in {
      readOk(format, written(format, full) - "_loc").location mustBe EntityLocation()
    }

    "read the defaults of a module with only an id and a name" in {
      val back = readOk(format, Json.obj("id" -> "auth_mod_oauth2", "name" -> "Keycloak"))
      back.desc mustBe "--"
      back.clientSideSessionEnabled mustBe true
      back.sessionMaxAge mustBe 86400
      back.clientId mustBe "client"
      back.clientSecret mustBe "secret"
      back.userInfoUrl mustBe "http://localhost:8082/userinfo"
      back.introspectionUrl mustBe "http://localhost:8082/token/introspect"
      back.loginUrl mustBe "http://localhost:8082/login"
      back.logoutUrl mustBe "http://localhost:8082/logout"
      back.scope mustBe "openid profile email name"
      back.claims mustBe "email name"
      back.accessTokenField mustBe "access_token"
      back.nameField mustBe "name"
      back.emailField mustBe "email"
      back.apiKeyMetaField mustBe "apkMeta"
      back.apiKeyTagsField mustBe "apkTags"
      back.otoroshiRightsField mustBe "otoroshi_rights"
    }

    "read the default flags and options of a module with only an id and a name" in {
      val back = readOk(format, Json.obj("id" -> "auth_mod_oauth2", "name" -> "Keycloak"))
      back.refreshTokens mustBe false
      back.useJson mustBe false
      back.useCookie mustBe false
      back.noWildcardRedirectURI mustBe false
      back.readProfileFromToken mustBe false
      back.pkce mustBe None
      back.jwtVerifier mustBe None
      back.oidConfig mustBe None
      back.proxy mustBe None
      back.mtlsConfig mustBe MtlsConfig()
      back.extraMetadata mustBe Json.obj()
      back.sessionCookieValues mustBe SessionCookieValues()
      back.rightsOverride mustBe Map.empty
      back.dataOverride mustBe Map.empty
      back.adminEntityValidatorsOverride mustBe Map.empty
      back.allowedUsers mustBe empty
      back.deniedUsers mustBe empty
    }

    "make every user a super admin when the super admins flag is missing" in {
      // backward compatibility: the case class default is false, the read default is true
      minimal.superAdmins mustBe false
      readOk(format, Json.obj("id" -> "a", "name" -> "b")).superAdmins mustBe true
    }

    "read a missing otoroshi data field with spaces around the pipe" in {
      minimal.otoroshiDataField mustBe "app_metadata|otoroshi_data"
      readOk(format, Json.obj("id" -> "a", "name" -> "b")).otoroshiDataField mustBe "app_metadata | otoroshi_data"
    }

    "not read a module without id" in {
      readError(format, Json.obj("name" -> "Keycloak"))
    }

    "not read a module without name" in {
      readError(format, Json.obj("id" -> "auth_mod_oauth2"))
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "ignore a jwt verifier of an unknown type" in {
      readOk(format, Json.obj("id" -> "a", "name" -> "b", "jwtVerifier" -> Json.obj("type" -> "Foo"))).jwtVerifier mustBe None
    }

    "ignore a proxy without port" in {
      readOk(format, Json.obj("id" -> "a", "name" -> "b", "proxy" -> Json.obj("host" -> "proxy"))).proxy mustBe None
    }

    "ignore an empty proxy optional value" in {
      val json = Json.obj("id" -> "a", "name" -> "b", "proxy" -> Json.obj("host" -> "proxy", "port" -> 1, "principal" -> " "))
      readOk(format, json).proxy mustBe Some(DefaultWSProxyServer("proxy", 1))
    }

    "read the legacy keys of the mtls config" in {
      val json = Json.obj("id" -> "a", "name" -> "b", "mtlsConfig" -> Json.obj("certId" -> "cert-1", "tls" -> true))
      readOk(format, json).mtlsConfig mustBe MtlsConfig(certs = Seq("cert-1"), mtls = true)
    }

    "fall back to the default mtls config when it is not an object" in {
      readOk(format, Json.obj("id" -> "a", "name" -> "b", "mtlsConfig" -> "x")).mtlsConfig mustBe MtlsConfig()
    }

    "fall back to the defaults for flags that are not booleans" in {
      val back = readOk(format, Json.obj("id" -> "a", "name" -> "b", "superAdmins" -> "false", "refreshTokens" -> "true"))
      back.superAdmins mustBe true
      back.refreshTokens mustBe false
    }

    "drop the rights of an override that cannot be read" in {
      val json = Json.obj(
        "id"             -> "a",
        "name"           -> "b",
        "rightsOverride" -> Json.obj("john@oto.tools" -> Json.arr(Json.obj("tenant" -> "t"), rights.rights.head.json))
      )
      readOk(format, json).rightsOverride mustBe Map("john@oto.tools" -> rights)
    }

    "ignore all the rights overrides when one of them is not an array" in {
      val json = Json.obj(
        "id"             -> "a",
        "name"           -> "b",
        "rightsOverride" -> Json.obj("john@oto.tools" -> rights.json, "jane@oto.tools" -> "*:rw")
      )
      readOk(format, json).rightsOverride mustBe Map.empty
    }

    "ignore all the data overrides when one of them is not an object" in {
      val json = Json.obj("id" -> "a", "name" -> "b", "dataOverride" -> Json.obj("a" -> Json.obj(), "b" -> 1))
      readOk(format, json).dataOverride mustBe Map.empty
    }

    "not read a module whose admin entity validators override of a user is not an object" in {
      readError(format, Json.obj("id" -> "a", "name" -> "b", "adminEntityValidatorsOverride" -> Json.obj("john" -> Json.arr())))
    }

    "not read a module whose admin entity validators override of an entity is not an array" in {
      readError(
        format,
        Json.obj("id" -> "a", "name" -> "b", "adminEntityValidatorsOverride" -> Json.obj("john" -> Json.obj("route" -> "x")))
      )
    }

    "drop the admin entity validators that cannot be read" in {
      val json = Json.obj(
        "id"                            -> "a",
        "name"                          -> "b",
        "adminEntityValidatorsOverride" -> Json.obj(
          "john" -> Json.obj("route" -> Json.arr(Json.obj("kind" -> "foo"), Json.obj("kind" -> "opa-plugin-validator", "ref" -> "o")))
        )
      )
      readOk(format, json).adminEntityValidatorsOverride mustBe Map("john" -> Map("route" -> Seq(OpaPluginValidator("o"))))
    }

    "give the read error as a left" in {
      GenericOauth2ModuleConfig.fromJson(Json.obj("id" -> "a")).isLeft mustBe true
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "auth_mod_oauth2", "Keycloak", "an oidc module", Seq("sso"), Map("owner" -> "security"))
    }

    "describe its type" in {
      full.`type` mustBe "oauth2"
      full.humanName mustBe "OAuth2 / OIDC provider"
      full.form mustBe None
    }

    "name its cookie after its id" in {
      full.cookieSuffix(null) mustBe "global-oauth-auth_mod_oauth2"
    }

    "change its location only with withLocation" in {
      minimal.withLocation(customLocation) mustBe minimal.copy(location = customLocation)
    }
  }
}
