package entities

import org.joda.time.DateTime
import otoroshi.models.*
import otoroshi.utils.{JsonPathValidator, JsonValidator, OpaPluginValidator}
import play.api.libs.json.*

class AdminsEntityFormatSpec extends EntityFormatSpec {

  private val created = new DateTime(1700000000000L)

  private val rights = UserRights.varargs(
    UserRight(TenantAccess("tenant-1"), Seq(TeamAccess("team-1:r"))),
    UserRight(TenantAccess("tenant-2:r"), Seq(TeamAccess("*:r")))
  )

  private val validators: Map[String, Seq[JsonValidator]] = Map(
    "all"    -> Seq(JsonPathValidator("$.*", JsString("JsonContainsNot(${env.)"), Some("no el env"))),
    "apikey" -> Seq(OpaPluginValidator("opa-1"))
  )

  "OtoroshiAdminType" should {

    "write its name as a json string" in {
      OtoroshiAdminType.SimpleAdmin.json mustBe JsString("SIMPLE")
      OtoroshiAdminType.WebAuthnAdmin.json mustBe JsString("WEBAUTHN")
    }

    "read its upper case and lower case names" in {
      OtoroshiAdminType.fromJson(JsString("SIMPLE")) mustBe Some(OtoroshiAdminType.SimpleAdmin)
      OtoroshiAdminType.fromJson(JsString("simple")) mustBe Some(OtoroshiAdminType.SimpleAdmin)
      OtoroshiAdminType.fromJson(JsString("WEBAUTHN")) mustBe Some(OtoroshiAdminType.WebAuthnAdmin)
      OtoroshiAdminType.fromJson(JsString("webauthn")) mustBe Some(OtoroshiAdminType.WebAuthnAdmin)
    }

    "not read a mixed case name" in {
      OtoroshiAdminType.fromJson(JsString("WebAuthn")) mustBe None
    }

    "not read a value that is not a string" in {
      OtoroshiAdminType.fromJson(JsNumber(1)) mustBe None
    }

    "read back what it writes" in {
      OtoroshiAdminType.fromJson(OtoroshiAdminType.SimpleAdmin.json) mustBe Some(OtoroshiAdminType.SimpleAdmin)
      OtoroshiAdminType.fromJson(OtoroshiAdminType.WebAuthnAdmin.json) mustBe Some(OtoroshiAdminType.WebAuthnAdmin)
    }
  }

  "SimpleOtoroshiAdmin" should {

    val format = SimpleOtoroshiAdmin.fmt

    val minimal = SimpleOtoroshiAdmin(
      username = "admin@oto.tools",
      password = "$2a$10$hash",
      label = "the admin",
      createdAt = created,
      typ = OtoroshiAdminType.SimpleAdmin,
      metadata = Map.empty,
      rights = UserRights.default,
      adminEntityValidators = Map.empty
    )

    val full = SimpleOtoroshiAdmin(
      username = "admin@oto.tools",
      password = "$2a$10$hash",
      label = "the admin",
      createdAt = created,
      typ = OtoroshiAdminType.SimpleAdmin,
      tags = Seq("ops", "root"),
      metadata = Map("phone" -> "0102030405"),
      rights = rights,
      location = customLocation,
      adminEntityValidators = validators
    )

    "write a minimal admin" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"                  -> defaultLocationJson,
        "username"              -> "admin@oto.tools",
        "password"              -> "$2a$10$hash",
        "label"                 -> "the admin",
        "createdAt"             -> 1700000000000L,
        "type"                  -> "SIMPLE",
        "metadata"              -> Json.obj(),
        "tags"                  -> Json.arr(),
        "rights"                -> Json.arr(Json.obj("tenant" -> "default:rw", "teams" -> Json.arr("default:rw"))),
        "adminEntityValidators" -> Json.obj()
      )
    }

    "write its admin entity validators by entity" in {
      (written(format, full) \ "adminEntityValidators" \ "apikey").as[JsValue] mustBe Json.arr(
        Json.obj("kind" -> "opa-plugin-validator", "ref" -> "opa-1")
      )
    }

    "round trip a minimal admin" in {
      roundTrip(format, minimal)
    }

    "round trip an admin where every field is set" in {
      roundTrip(format, full)
    }

    "keep its read only rights on a round trip" in {
      roundTrip(format, full).rights mustBe rights
    }

    "round trip an admin without any right" in {
      roundTrip(format, minimal.copy(rights = UserRights.empty)).rights mustBe UserRights.empty
    }

    "round trip a super admin" in {
      roundTrip(format, minimal.copy(rights = UserRights.superAdmin)).rights mustBe UserRights.superAdmin
    }

    "keep its admin entity validators on a round trip" in {
      roundTrip(format, full).adminEntityValidators mustBe validators
    }

    "round trip an admin typed as a webauthn admin through its own format" in {
      roundTrip(format, minimal.copy(typ = OtoroshiAdminType.WebAuthnAdmin)).typ mustBe OtoroshiAdminType.WebAuthnAdmin
    }

    "round trip its custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read the default location without _loc" in {
      readOk(format, written(format, full) - "_loc").location mustBe EntityLocation()
    }

    "read the defaults of an admin with only a username, a password and a label" in {
      val before = System.currentTimeMillis()
      val back   = readOk(format, Json.obj("username" -> "admin@oto.tools", "password" -> "$2a$10$hash", "label" -> "the admin"))
      back.typ mustBe OtoroshiAdminType.SimpleAdmin
      back.tags mustBe empty
      back.metadata mustBe Map.empty
      back.adminEntityValidators mustBe Map.empty
      back.location mustBe EntityLocation()
      back.createdAt.getMillis must be >= before
    }

    "read an admin without rights as a super admin" in {
      readOk(format, Json.obj("username" -> "u", "password" -> "p", "label" -> "l")).rights mustBe UserRights.superAdmin
    }

    "read an admin whose rights are null as a super admin" in {
      readOk(format, written(format, minimal) ++ Json.obj("rights" -> JsNull)).rights mustBe UserRights.superAdmin
    }

    "read an admin whose rights cannot be read as an admin without any right" in {
      readOk(format, written(format, minimal) ++ Json.obj("rights" -> Json.arr(Json.obj("teams" -> Json.arr("*"))))).rights mustBe
        UserRights.empty
    }

    "not read an admin without username" in {
      readError(format, Json.obj("password" -> "p", "label" -> "l"))
    }

    "not read an admin without password" in {
      readError(format, Json.obj("username" -> "u", "label" -> "l"))
    }

    "not read an admin without label" in {
      readError(format, Json.obj("username" -> "u", "password" -> "p"))
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "read a lower case type" in {
      readOk(format, written(format, minimal) ++ Json.obj("type" -> "webauthn")).typ mustBe OtoroshiAdminType.WebAuthnAdmin
    }

    "read an unknown type as a simple admin" in {
      readOk(format, written(format, minimal) ++ Json.obj("type" -> "LDAP")).typ mustBe OtoroshiAdminType.SimpleAdmin
    }

    "fall back to now for a creation date that is not a number" in {
      val before = System.currentTimeMillis()
      readOk(format, written(format, minimal) ++ Json.obj("createdAt" -> "yesterday")).createdAt.getMillis must be >= before
    }

    "ignore tags given as a single string" in {
      readOk(format, written(format, minimal) ++ Json.obj("tags" -> "ops")).tags mustBe empty
    }

    "not read an admin whose admin entity validators of an entity are not an array" in {
      readError(format, written(format, minimal) ++ Json.obj("adminEntityValidators" -> Json.obj("all" -> "x")))
    }

    "be a simple admin" in {
      full.isSimple mustBe true
      full.isWebAuthn mustBe false
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "expose its fields through the generic accessors, its username as id and name" in {
      hasAccessors(full, "admin@oto.tools", "admin@oto.tools", "the admin", Seq("ops", "root"), Map("phone" -> "0102030405"))
    }
  }

  "WebAuthnOtoroshiAdmin" should {

    val format = WebAuthnOtoroshiAdmin.fmt

    val credential = Json.obj(
      "keyId"          -> Json.obj("id" -> "a2V5LTE", "type" -> "public-key", "transports" -> Json.arr("usb")),
      "publicKeyCose"  -> "cHVibGlj",
      "signatureCount" -> 3
    )

    val minimal = WebAuthnOtoroshiAdmin(
      username = "admin@oto.tools",
      password = "$2a$10$hash",
      label = "the admin",
      handle = "handle-1",
      credentials = Map.empty,
      createdAt = created,
      typ = OtoroshiAdminType.WebAuthnAdmin,
      metadata = Map.empty,
      rights = UserRights.default,
      adminEntityValidators = Map.empty
    )

    val full = minimal.copy(
      credentials = Map("a2V5LTE" -> credential, "a2V5LTI" -> Json.obj("legacy" -> true)),
      tags = Seq("root"),
      metadata = Map("device" -> "yubikey"),
      rights = rights,
      location = customLocation,
      adminEntityValidators = validators
    )

    "write a minimal admin" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"                  -> defaultLocationJson,
        "username"              -> "admin@oto.tools",
        "password"              -> "$2a$10$hash",
        "label"                 -> "the admin",
        "handle"                -> "handle-1",
        "credentials"           -> Json.obj(),
        "createdAt"             -> 1700000000000L,
        "type"                  -> "WEBAUTHN",
        "metadata"              -> Json.obj(),
        "tags"                  -> Json.arr(),
        "rights"                -> Json.arr(Json.obj("tenant" -> "default:rw", "teams" -> Json.arr("default:rw"))),
        "adminEntityValidators" -> Json.obj()
      )
    }

    "write its credentials by key id" in {
      (written(format, full) \ "credentials" \ "a2V5LTE").as[JsValue] mustBe credential
    }

    "round trip a minimal admin" in {
      roundTrip(format, minimal)
    }

    "round trip an admin where every field is set" in {
      roundTrip(format, full)
    }

    "keep its rights and its admin entity validators on a round trip" in {
      val back = roundTrip(format, full)
      back.rights mustBe rights
      back.adminEntityValidators mustBe validators
    }

    "round trip its custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read the default location without _loc" in {
      readOk(format, written(format, full) - "_loc").location mustBe EntityLocation()
    }

    "read a missing type as a webauthn admin" in {
      readOk(format, written(format, minimal) - "type").typ mustBe OtoroshiAdminType.WebAuthnAdmin
    }

    "read an admin without credentials as an admin without credential" in {
      readOk(format, written(format, minimal) - "credentials").credentials mustBe Map.empty
    }

    "read a legacy single credential under its key id" in {
      readOk(format, written(format, minimal) - "credentials" ++ Json.obj("credential" -> credential)).credentials mustBe Map(
        "a2V5LTE" -> credential
      )
    }

    "prefer the credentials over the legacy single credential" in {
      val json = written(format, full) ++ Json.obj("credential" -> Json.obj("keyId" -> Json.obj("id" -> "other")))
      readOk(format, json).credentials mustBe full.credentials
    }

    "not read a legacy single credential without key id" in {
      readError(format, written(format, minimal) - "credentials" ++ Json.obj("credential" -> Json.obj("publicKeyCose" -> "x")))
    }

    "read an admin without rights as a super admin" in {
      readOk(format, written(format, minimal) - "rights").rights mustBe UserRights.superAdmin
    }

    "not read an admin without handle" in {
      readError(format, written(format, minimal) - "handle")
    }

    "not read an admin without label" in {
      readError(format, written(format, minimal) - "label")
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "be a webauthn admin" in {
      full.isSimple mustBe false
      full.isWebAuthn mustBe true
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "expose its fields through the generic accessors, its username as id and name" in {
      hasAccessors(full, "admin@oto.tools", "admin@oto.tools", "the admin", Seq("root"), Map("device" -> "yubikey"))
    }
  }

  "OtoroshiAdmin" should {

    val format = OtoroshiAdmin.format

    val simple = SimpleOtoroshiAdmin(
      username = "simple@oto.tools",
      password = "p",
      label = "simple",
      createdAt = created,
      typ = OtoroshiAdminType.SimpleAdmin,
      metadata = Map.empty,
      rights = rights,
      adminEntityValidators = Map.empty
    )

    val webauthn = WebAuthnOtoroshiAdmin(
      username = "webauthn@oto.tools",
      password = "p",
      label = "webauthn",
      handle = "handle-1",
      credentials = Map.empty,
      createdAt = created,
      typ = OtoroshiAdminType.WebAuthnAdmin,
      metadata = Map.empty,
      rights = rights,
      adminEntityValidators = Map.empty
    )

    "write what each admin writes" in {
      format.writes(simple) mustBe simple.json
      format.writes(webauthn) mustBe webauthn.json
    }

    "read a simple admin" in {
      readOk(format, simple.json) mustBe simple
    }

    "read a webauthn admin" in {
      readOk(format, webauthn.json) mustBe webauthn
    }

    "read an admin without type as a simple admin" in {
      readOk(format, simple.json.as[JsObject] - "type") mustBe simple
    }

    "read a lower case webauthn type as a simple admin typed as a webauthn admin" in {
      val back = readOk(format, webauthn.json.as[JsObject] ++ Json.obj("type" -> "webauthn"))
      back.isSimple mustBe true
      back.typ mustBe OtoroshiAdminType.WebAuthnAdmin
    }

    "not read a simple admin typed as a webauthn admin, as it has no handle" in {
      readError(format, simple.copy(typ = OtoroshiAdminType.WebAuthnAdmin).json)
    }
  }

  "AdminPreferences" should {

    val format = AdminPreferences.format

    val prefs = AdminPreferences("admin@oto.tools", Map("theme" -> JsString("dark"), "shortcuts" -> Json.arr("routes")))

    "write its user id and its preferences in snake case" in {
      format.writes(prefs) mustBe Json.obj(
        "user_id"     -> "admin@oto.tools",
        "preferences" -> Json.obj("theme" -> "dark", "shortcuts" -> Json.arr("routes"))
      )
    }

    "expose through json what the format writes" in {
      prefs.json mustBe format.writes(prefs)
    }

    "round trip preferences" in {
      roundTrip(format, prefs)
    }

    "round trip no preference" in {
      roundTrip(format, AdminPreferences("admin@oto.tools", Map.empty))
    }

    "read missing preferences as no preference" in {
      readOk(format, Json.obj("user_id" -> "admin@oto.tools")) mustBe AdminPreferences("admin@oto.tools", Map.empty)
    }

    "not read preferences without user id" in {
      readError(format, Json.obj("preferences" -> Json.obj()))
    }

    "not read a camel case user id" in {
      readError(format, Json.obj("userId" -> "admin@oto.tools"))
    }

    "set and delete a preference" in {
      val updated = prefs.set("theme", JsString("light")).set("lang", JsString("fr"))
      updated.preferences mustBe Map("theme" -> JsString("light"), "shortcuts" -> Json.arr("routes"), "lang" -> JsString("fr"))
      updated.delete("theme").delete("missing").preferences.keySet mustBe Set("shortcuts", "lang")
    }

    "give the sidebar shortcuts by default" in {
      val default = AdminPreferences.defaultValue("admin@oto.tools")
      default.userId mustBe "admin@oto.tools"
      default.preferences.keySet mustBe Set("backoffice_sidebar_shortcuts")
      default.preferences("backoffice_sidebar_shortcuts").as[Seq[String]] must contain allOf ("routes", "apikeys", "danger zone")
      roundTrip(format, default)
    }
  }
}
