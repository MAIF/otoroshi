package entities

import otoroshi.auth.*
import otoroshi.models.*
import otoroshi.next.models.NgTlsConfig
import otoroshi.utils.{JsonPathValidator, JsonValidator, WasmPluginValidator}
import play.api.libs.json.*

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.concurrent.duration.*

class BasicAuthModuleEntityFormatSpec extends EntityFormatSpec {

  private val readOnlyRights = UserRights.varargs(
    UserRight(TenantAccess("tenant-1:r"), Seq(TeamAccess("team-1:r"))),
    UserRight(TenantAccess("tenant-2"), Seq(TeamAccess("*")))
  )

  private val validators: Map[String, Seq[JsonValidator]] = Map(
    "all"   -> Seq(JsonPathValidator("$.*", JsString("JsonContainsNot(${env.)"), Some("no el env"))),
    "route" -> Seq(JsonPathValidator("$.enabled", JsBoolean(true)), WasmPluginValidator("wasm-1"))
  )

  "WebAuthnDetails" should {

    val format = WebAuthnDetails.fmt

    "write its handle and its credentials" in {
      format.writes(WebAuthnDetails("handle-1", Map("key-1" -> Json.obj("a" -> 1)))) mustBe Json.obj(
        "handle"      -> "handle-1",
        "credentials" -> Json.obj("key-1" -> Json.obj("a" -> 1))
      )
    }

    "expose through asJson what the format writes" in {
      val details = WebAuthnDetails("handle-1", Map.empty)
      details.asJson mustBe format.writes(details)
    }

    "round trip details with several credentials" in {
      roundTrip(format, WebAuthnDetails("handle-1", Map("key-1" -> Json.obj("a" -> 1), "key-2" -> JsString("raw"))))
    }

    "read missing credentials as no credential" in {
      readOk(format, Json.obj("handle" -> "handle-1")) mustBe WebAuthnDetails("handle-1", Map.empty)
    }

    "not read details without handle" in {
      readError(format, Json.obj("credentials" -> Json.obj()))
    }

    "ignore credentials that are not an object" in {
      readOk(format, Json.obj("handle" -> "h", "credentials" -> Json.arr(1))).credentials mustBe Map.empty
    }
  }

  "BasicAuthUser" should {

    val format = BasicAuthUser.fmt

    val full = BasicAuthUser(
      name = "John Doe",
      password = "$2a$10$hash",
      email = "john@oto.tools",
      webauthn = Some(WebAuthnDetails("handle-1", Map("key-1" -> Json.obj("a" -> 1)))),
      metadata = Json.obj("team" -> "ops", "level" -> 3),
      tags = Seq("admin", "ops"),
      rights = readOnlyRights,
      adminEntityValidators = validators
    )

    val minimal = BasicAuthUser(
      name = "John Doe",
      password = "$2a$10$hash",
      email = "john@oto.tools",
      tags = Seq.empty,
      rights = UserRights.default,
      adminEntityValidators = Map.empty
    )

    "write a user without webauthn device" in {
      format.writes(minimal) mustBe Json.obj(
        "name"                  -> "John Doe",
        "password"              -> "$2a$10$hash",
        "email"                 -> "john@oto.tools",
        "metadata"              -> Json.obj(),
        "tags"                  -> Json.arr(),
        "webauthn"              -> JsNull,
        "rights"                -> Json.arr(Json.obj("tenant" -> "default:rw", "teams" -> Json.arr("default:rw"))),
        "adminEntityValidators" -> Json.obj()
      )
    }

    "expose through asJson what the format writes" in {
      full.asJson mustBe format.writes(full)
    }

    "round trip a minimal user" in {
      roundTrip(format, minimal)
    }

    "round trip a user where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a user without any right" in {
      roundTrip(format, minimal.copy(rights = UserRights.empty)).rights mustBe UserRights.empty
    }

    "round trip the super admin rights" in {
      roundTrip(format, minimal.copy(rights = UserRights.superAdmin)).rights mustBe UserRights.superAdmin
    }

    "keep its read only rights on a round trip" in {
      roundTrip(format, minimal.copy(rights = readOnlyRights)).rights.rights.head.teams mustBe Seq(
        TeamAccess("team-1", true, false)
      )
    }

    "read a user without rights as a super admin" in {
      readOk(format, Json.obj("name" -> "n", "password" -> "p", "email" -> "e")).rights mustBe UserRights.superAdmin
    }

    "read a user whose rights are null as a super admin" in {
      readOk(format, Json.obj("name" -> "n", "password" -> "p", "email" -> "e", "rights" -> JsNull)).rights mustBe
        UserRights.superAdmin
    }

    "read a user whose rights are an object as a super admin" in {
      readOk(
        format,
        Json.obj("name" -> "n", "password" -> "p", "email" -> "e", "rights" -> Json.obj("tenant" -> "t", "teams" -> Json.arr("a")))
      ).rights mustBe UserRights.superAdmin
    }

    "read a user whose rights cannot be read as a user without any right" in {
      readOk(
        format,
        Json.obj("name" -> "n", "password" -> "p", "email" -> "e", "rights" -> Json.arr(Json.obj("tenant" -> 1)))
      ).rights mustBe UserRights.empty
    }

    "read the defaults of a user with only a name, a password and an email" in {
      readOk(format, Json.obj("name" -> "n", "password" -> "p", "email" -> "e")) mustBe BasicAuthUser(
        name = "n",
        password = "p",
        email = "e",
        webauthn = None,
        metadata = Json.obj(),
        tags = Seq.empty,
        rights = UserRights.superAdmin,
        adminEntityValidators = Map.empty
      )
    }

    "not read a user without password" in {
      readError(format, Json.obj("name" -> "n", "email" -> "e"))
    }

    "not read a user without email" in {
      readError(format, Json.obj("name" -> "n", "password" -> "p"))
    }

    "not read a user without name" in {
      readError(format, Json.obj("password" -> "p", "email" -> "e"))
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "ignore a webauthn device without handle" in {
      readOk(format, format.writes(minimal).as[JsObject] ++ Json.obj("webauthn" -> Json.obj("credentials" -> Json.obj())))
        .webauthn mustBe None
    }

    "ignore metadata that are not an object" in {
      readOk(format, format.writes(minimal).as[JsObject] ++ Json.obj("metadata" -> "ops")).metadata mustBe Json.obj()
    }

    "drop the admin entity validators that cannot be read" in {
      val json = format.writes(minimal).as[JsObject] ++ Json.obj(
        "adminEntityValidators" -> Json.obj("route" -> Json.arr(Json.obj("kind" -> "foo"), Json.obj("path" -> "$.a", "value" -> 1)))
      )
      readOk(format, json).adminEntityValidators mustBe Map("route" -> Seq(JsonPathValidator("$.a", JsNumber(1))))
    }

    "not read a user whose admin entity validators of a kind are not an array" in {
      readError(format, format.writes(minimal).as[JsObject] ++ Json.obj("adminEntityValidators" -> Json.obj("route" -> "x")))
    }
  }

  "BasicAuthModuleConfig" should {

    val format = BasicAuthModuleConfig._fmt

    val user = BasicAuthUser(
      name = "John Doe",
      password = "$2a$10$hash",
      email = "john@oto.tools",
      tags = Seq("ops"),
      rights = readOnlyRights,
      adminEntityValidators = validators
    )

    val minimal = BasicAuthModuleConfig(
      id = "auth_mod_basic",
      name = "Basic",
      desc = "a basic module",
      clientSideSessionEnabled = true,
      tags = Seq.empty,
      metadata = Map.empty,
      sessionCookieValues = SessionCookieValues()
    )

    val full = BasicAuthModuleConfig(
      id = "auth_mod_basic",
      name = "Basic",
      desc = "a basic module",
      users = Seq(user, user.copy(email = "jane@oto.tools", webauthn = Some(WebAuthnDetails("h", Map.empty)))),
      clientSideSessionEnabled = false,
      sessionMaxAge = 3600,
      userValidators = Seq(JsonPathValidator("$.email", JsString("Regex(.*@oto.tools)"), Some("bad domain"))),
      remoteValidators = Seq(
        RemoteUserValidatorSettings("https://validator.oto.tools", Map("X-Foo" -> "bar"), 3.seconds, NgTlsConfig(enabled = true))
      ),
      basicAuth = true,
      webauthn = true,
      tags = Seq("prod", "internal"),
      metadata = Map("owner" -> "ops"),
      sessionCookieValues = SessionCookieValues(httpOnly = false, secure = false),
      location = customLocation,
      allowedUsers = Seq("Regex(.*@oto.tools)"),
      deniedUsers = Seq("jane@oto.tools")
    )

    "write a minimal module" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"                     -> defaultLocationJson,
        "type"                     -> "basic",
        "id"                       -> "auth_mod_basic",
        "name"                     -> "Basic",
        "desc"                     -> "a basic module",
        "basicAuth"                -> false,
        "webauthn"                 -> false,
        "clientSideSessionEnabled" -> true,
        "sessionMaxAge"            -> 86400,
        "metadata"                 -> Json.obj(),
        "tags"                     -> Json.arr(),
        "users"                    -> Json.arr(),
        "sessionCookieValues"      -> Json.obj("httpOnly" -> true, "secure" -> true),
        "userValidators"           -> Json.arr(),
        "allowedUsers"             -> Json.arr(),
        "deniedUsers"              -> Json.arr(),
        "remoteValidators"         -> Json.arr()
      )
    }

    "write its users with their rights" in {
      (written(format, full) \ "users" \ 0 \ "rights").as[JsValue] mustBe readOnlyRights.json
    }

    "round trip a minimal module" in {
      roundTrip(format, minimal)
    }

    "round trip a module where every field is set" in {
      roundTrip(format, full)
    }

    "keep the rights and the admin entity validators of its users on a round trip" in {
      val back = roundTrip(format, full)
      back.users.map(_.rights) mustBe Seq(readOnlyRights, readOnlyRights)
      back.users.map(_.adminEntityValidators) mustBe Seq(validators, validators)
    }

    "keep its user validators, allowed and denied users on a round trip" in {
      val back = roundTrip(format, full)
      back.userValidators mustBe full.userValidators
      back.remoteValidators mustBe full.remoteValidators
      back.allowedUsers mustBe Seq("Regex(.*@oto.tools)")
      back.deniedUsers mustBe Seq("jane@oto.tools")
    }

    "round trip its custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read the default location without _loc" in {
      readOk(format, written(format, minimal.copy(location = customLocation)) - "_loc").location mustBe EntityLocation()
    }

    "read the defaults of a module with only an id and a name" in {
      readOk(format, Json.obj("id" -> "auth_mod_basic", "name" -> "Basic")) mustBe BasicAuthModuleConfig(
        id = "auth_mod_basic",
        name = "Basic",
        desc = "--",
        users = Seq.empty,
        clientSideSessionEnabled = true,
        sessionMaxAge = 86400,
        userValidators = Seq.empty,
        remoteValidators = Seq.empty,
        basicAuth = false,
        webauthn = false,
        tags = Seq.empty,
        metadata = Map.empty,
        sessionCookieValues = SessionCookieValues(),
        location = EntityLocation(),
        allowedUsers = Seq.empty,
        deniedUsers = Seq.empty
      )
    }

    "not need the type key to be read by its own format" in {
      readOk(format, Json.obj("id" -> "a", "name" -> "b", "type" -> "ldap")).`type` mustBe "basic"
    }

    "not read a module without id" in {
      readError(format, Json.obj("name" -> "Basic"))
    }

    "not read a module without name" in {
      readError(format, Json.obj("id" -> "auth_mod_basic"))
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read an id that is not a string" in {
      readError(format, Json.obj("id" -> 1, "name" -> "Basic"))
    }

    "fall back to the default session max age when it is not a number" in {
      readOk(format, Json.obj("id" -> "a", "name" -> "b", "sessionMaxAge" -> "3600")).sessionMaxAge mustBe 86400
    }

    "fall back to a client side session when the flag is not a boolean" in {
      readOk(format, Json.obj("id" -> "a", "name" -> "b", "clientSideSessionEnabled" -> "false")).clientSideSessionEnabled mustBe true
    }

    "drop all its users when one of them cannot be read" in {
      val json = written(format, full) ++ Json.obj("users" -> Json.arr(user.asJson, Json.obj("name" -> "no email")))
      readOk(format, json).users mustBe empty
    }

    "drop the user validators that cannot be read" in {
      val json = written(format, minimal) ++ Json.obj(
        "userValidators" -> Json.arr(Json.obj("value" -> 1), Json.obj("path" -> "$.a", "value" -> 1))
      )
      readOk(format, json).userValidators mustBe Seq(JsonPathValidator("$.a", JsNumber(1)))
    }

    "drop the remote validators that cannot be read" in {
      val json = written(format, minimal) ++ Json.obj(
        "remoteValidators" -> Json.arr(Json.obj("timeout" -> 1), Json.obj("url" -> "http://localhost"))
      )
      readOk(format, json).remoteValidators.map(_.url) mustBe Seq("http://localhost")
    }

    "ignore allowed users given as a single string" in {
      readOk(format, Json.obj("id" -> "a", "name" -> "b", "allowedUsers" -> "john@oto.tools")).allowedUsers mustBe empty
    }

    "be built from its json" in {
      BasicAuthModuleConfig.fromJsons(full.json) mustBe full
    }

    "give the read error as a left" in {
      BasicAuthModuleConfig.fromJson(Json.obj("name" -> "b")).isLeft mustBe true
    }

    "fail to be built from a json without id" in {
      a[Throwable] must be thrownBy BasicAuthModuleConfig.fromJsons(Json.obj("name" -> "b"))
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "auth_mod_basic", "Basic", "a basic module", Seq("prod", "internal"), Map("owner" -> "ops"))
    }

    "describe its type" in {
      full.`type` mustBe "basic"
      full.humanName mustBe "In memory auth. provider"
      full.form mustBe None
    }

    "name its cookie after its id" in {
      full.cookieSuffix(null) mustBe "basic-auth-auth_mod_basic"
    }

    "change its location only with withLocation" in {
      minimal.withLocation(customLocation) mustBe minimal.copy(location = customLocation)
    }

    "build a default config enabling client side sessions" in {
      val default = BasicAuthModule.defaultConfig
      default.id must startWith("auth_mod_")
      default.name mustBe "New auth. module"
      default.clientSideSessionEnabled mustBe true
      default.users mustBe empty
      roundTrip(format, default)
    }
  }

  "BasicAuthModule" should {

    val module = BasicAuthModule(BasicAuthModule.defaultConfig)

    def header(value: String): String =
      "Basic " + Base64.getUrlEncoder.encodeToString(value.getBytes(StandardCharsets.UTF_8))

    "extract the username and the password of a basic auth header" in {
      module.extractUsernamePassword(header("john@oto.tools:secret")) mustBe Some(("john@oto.tools", "secret"))
    }

    "keep the colons of the password" in {
      module.extractUsernamePassword(header("john@oto.tools:se:cr:et")) mustBe Some(("john@oto.tools", "se:cr:et"))
    }

    "accept a lower case scheme" in {
      module.extractUsernamePassword(header("john:secret").replace("Basic ", "basic ")) mustBe Some(("john", "secret"))
    }

    "not extract a header without password" in {
      module.extractUsernamePassword(header("john@oto.tools")) mustBe None
    }

    "not extract a header with an empty password" in {
      module.extractUsernamePassword(header("john@oto.tools:")) mustBe None
    }
  }
}
