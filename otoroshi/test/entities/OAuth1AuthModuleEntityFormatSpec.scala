package entities

import otoroshi.auth.*
import otoroshi.models.*
import otoroshi.next.models.NgTlsConfig
import otoroshi.utils.{JsonPathValidator, WasmPluginValidator}
import play.api.libs.json.*

import scala.concurrent.duration.*

class OAuth1AuthModuleEntityFormatSpec extends EntityFormatSpec {

  "OAuth1Provider" should {

    "read post and get" in {
      OAuth1Provider("post") mustBe OAuth1Provider.Post
      OAuth1Provider("get") mustBe OAuth1Provider.Get
    }

    "read an unknown method as post" in {
      OAuth1Provider("put") mustBe OAuth1Provider.Post
    }

    "read the method case sensitively" in {
      OAuth1Provider("GET") mustBe OAuth1Provider.Post
    }

    "use the same http method for the request and the access token" in {
      OAuth1Provider.Post.methods mustBe OAuth1ProviderMethods("POST", "POST")
      OAuth1Provider.Get.methods mustBe OAuth1ProviderMethods("GET", "GET")
    }
  }

  "Oauth1ModuleConfig" should {

    val format = Oauth1ModuleConfig._fmt

    val rights = UserRights.varargs(UserRight(TenantAccess("tenant-1:r"), Seq(TeamAccess("*:r"))))

    val minimal = Oauth1ModuleConfig(
      id = "auth_mod_oauth1",
      name = "Clever",
      desc = "an oauth1 module",
      clientSideSessionEnabled = true,
      consumerKey = "key",
      consumerSecret = "secret",
      requestTokenURL = "https://api.oto.tools/request_token",
      authorizeURL = "https://api.oto.tools/authorize",
      accessTokenURL = "https://api.oto.tools/access_token",
      profileURL = "https://api.oto.tools/self",
      callbackURL = "https://otoroshi.oto.tools/callback",
      tags = Seq.empty,
      metadata = Map.empty,
      sessionCookieValues = SessionCookieValues()
    )

    val full = minimal.copy(
      clientSideSessionEnabled = false,
      sessionMaxAge = 120,
      httpMethod = OAuth1Provider.Get,
      tags = Seq("legacy"),
      metadata = Map("owner" -> "ops"),
      sessionCookieValues = SessionCookieValues(httpOnly = true, secure = false),
      userValidators = Seq(JsonPathValidator("$.admin", JsBoolean(true), Some("not an admin"))),
      remoteValidators = Seq(RemoteUserValidatorSettings("https://validator.oto.tools", Map("a" -> "b"), 5.seconds, NgTlsConfig())),
      rightsOverride = Map("john@oto.tools" -> rights),
      location = customLocation,
      adminEntityValidatorsOverride = Map("john@oto.tools" -> Map("apikey" -> Seq(WasmPluginValidator("wasm-1")))),
      allowedUsers = Seq("john@oto.tools"),
      deniedUsers = Seq("Regex(.*@external.com)")
    )

    "write a minimal module" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"                          -> defaultLocationJson,
        "type"                          -> "oauth1",
        "id"                            -> "auth_mod_oauth1",
        "name"                          -> "Clever",
        "desc"                          -> "an oauth1 module",
        "consumerKey"                   -> "key",
        "consumerSecret"                -> "secret",
        "requestTokenURL"               -> "https://api.oto.tools/request_token",
        "authorizeURL"                  -> "https://api.oto.tools/authorize",
        "profileURL"                    -> "https://api.oto.tools/self",
        "accessTokenURL"                -> "https://api.oto.tools/access_token",
        "callbackURL"                   -> "https://otoroshi.oto.tools/callback",
        "clientSideSessionEnabled"      -> true,
        "sessionMaxAge"                 -> 86400,
        "userValidators"                -> Json.arr(),
        "remoteValidators"              -> Json.arr(),
        "metadata"                      -> Json.obj(),
        "tags"                          -> Json.arr(),
        "rightsOverride"                -> Json.obj(),
        "httpMethod"                    -> "post",
        "sessionCookieValues"           -> Json.obj("httpOnly" -> true, "secure" -> true),
        "allowedUsers"                  -> Json.arr(),
        "deniedUsers"                   -> Json.arr(),
        "adminEntityValidatorsOverride" -> Json.obj()
      )
    }

    "write the http method by its name" in {
      (written(format, full) \ "httpMethod").as[String] mustBe "get"
    }

    "write the rights overrides as rights arrays" in {
      (written(format, full) \ "rightsOverride").as[JsValue] mustBe Json.obj(
        "john@oto.tools" -> Json.arr(Json.obj("tenant" -> "tenant-1:r", "teams" -> Json.arr("*:r")))
      )
    }

    "round trip a minimal module" in {
      roundTrip(format, minimal)
    }

    "round trip a module where every field is set" in {
      roundTrip(format, full)
    }

    "keep its read only rights overrides on a round trip" in {
      roundTrip(format, full).rightsOverride mustBe Map("john@oto.tools" -> rights)
    }

    "round trip its custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read the default location without _loc" in {
      readOk(format, written(format, full) - "_loc").location mustBe EntityLocation()
    }

    "read the defaults of a module with only its mandatory fields" in {
      val json = Json.obj(
        "id"              -> "auth_mod_oauth1",
        "name"            -> "Clever",
        "consumerKey"     -> "key",
        "consumerSecret"  -> "secret",
        "requestTokenURL" -> "https://api.oto.tools/request_token",
        "authorizeURL"    -> "https://api.oto.tools/authorize",
        "accessTokenURL"  -> "https://api.oto.tools/access_token"
      )
      readOk(format, json) mustBe minimal.copy(
        desc = "--",
        profileURL = "https://api.clever-cloud.com/v2/self",
        callbackURL = "http://otoroshi.oto.tools:9999/backoffice/auth0/callback"
      )
    }

    "not read a module without one of its mandatory fields" in {
      val json = written(format, minimal)
      Seq("id", "name", "consumerKey", "consumerSecret", "requestTokenURL", "authorizeURL", "accessTokenURL").foreach { key =>
        withClue(s"without $key: ") {
          readError(format, json - key)
        }
      }
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "read an unknown http method as post" in {
      readOk(format, written(format, full) ++ Json.obj("httpMethod" -> "delete")).httpMethod mustBe OAuth1Provider.Post
    }

    "read an upper case http method as post" in {
      readOk(format, written(format, full) ++ Json.obj("httpMethod" -> "GET")).httpMethod mustBe OAuth1Provider.Post
    }

    "fall back to the default session max age when it is not a number" in {
      readOk(format, written(format, full) ++ Json.obj("sessionMaxAge" -> "1")).sessionMaxAge mustBe 86400
    }

    "not read a consumer key that is not a string" in {
      readError(format, written(format, full) ++ Json.obj("consumerKey" -> 42))
    }

    "drop the rights of an override that cannot be read" in {
      val json = written(format, full) ++ Json.obj("rightsOverride" -> Json.obj("john" -> Json.arr(Json.obj("teams" -> Json.arr()))))
      readOk(format, json).rightsOverride mustBe Map("john" -> UserRights.empty)
    }

    "not read a module whose admin entity validators override of a user is not an object" in {
      readError(format, written(format, full) ++ Json.obj("adminEntityValidatorsOverride" -> Json.obj("john" -> 1)))
    }

    "give the read error as a left" in {
      Oauth1ModuleConfig.fromJson(Json.obj("id" -> "a")).isLeft mustBe true
    }

    "be built from its json" in {
      Oauth1ModuleConfig.fromJsons(full.json) mustBe full
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "auth_mod_oauth1", "Clever", "an oauth1 module", Seq("legacy"), Map("owner" -> "ops"))
    }

    "describe its type" in {
      full.`type` mustBe "oauth1"
      full.humanName mustBe "OAuth1 provider"
      full.form mustBe None
    }

    "name its cookie like an ldap module" in {
      full.cookieSuffix(null) mustBe "ldap-auth-auth_mod_oauth1"
    }

    "change its location only with withLocation" in {
      minimal.withLocation(customLocation) mustBe minimal.copy(location = customLocation)
    }
  }
}
