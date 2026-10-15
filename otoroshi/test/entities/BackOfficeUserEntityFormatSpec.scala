package entities

import org.joda.time.DateTime
import otoroshi.models.*
import otoroshi.utils.{JsonPathValidator, JsonValidator, OpaPluginValidator, WasmPluginValidator}
import play.api.libs.json.*

class BackOfficeUserEntityFormatSpec extends EntityFormatSpec {

  "BackOfficeUser" should {

    val format = BackOfficeUser.fmt

    val created     = new DateTime(1700000000000L)
    val expired     = new DateTime(1700086400000L)
    val lastRefresh = new DateTime(1700003600000L)

    val rights = UserRights.varargs(
      UserRight(TenantAccess("tenant-1"), Seq(TeamAccess("team-1"), TeamAccess("team-2:r"))),
      UserRight(TenantAccess("tenant-2:r"), Seq(TeamAccess("*:r")))
    )

    val validators: Map[String, Seq[JsonValidator]] = Map(
      "all"   -> Seq(JsonPathValidator("$.*", JsString("JsonContainsNot(${vault://)"), Some("no vault value"))),
      "route" -> Seq(JsonPathValidator("$.enabled", JsBoolean(true)), WasmPluginValidator("wasm-1"), OpaPluginValidator("opa-1"))
    )

    val minimal = BackOfficeUser(
      randomId = "session-1",
      name = "John Doe",
      email = "john@oto.tools",
      profile = Json.obj(),
      authConfigId = "auth_mod_1",
      simpleLogin = true,
      createdAt = created,
      expiredAt = expired,
      lastRefresh = lastRefresh,
      tags = Seq.empty,
      metadata = Map.empty,
      rights = UserRights.default,
      adminEntityValidators = Map.empty
    )

    val full = BackOfficeUser(
      randomId = "session-1",
      name = "John Doe",
      email = "john@oto.tools",
      profile = Json.obj("name" -> "John Doe", "email" -> "john@oto.tools", "groups" -> Json.arr("admins")),
      token = Json.obj("access_token" -> "xxx", "refresh_token" -> "yyy", "expires_in" -> 3600),
      authConfigId = "auth_mod_1",
      simpleLogin = false,
      createdAt = created,
      expiredAt = expired,
      lastRefresh = lastRefresh,
      tags = Seq("admin", "ops"),
      metadata = Map("team" -> "ops"),
      rights = rights,
      adminEntityValidators = validators,
      location = customLocation
    )

    "write a minimal user" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"                  -> defaultLocationJson,
        "randomId"              -> "session-1",
        "name"                  -> "John Doe",
        "email"                 -> "john@oto.tools",
        "authConfigId"          -> "auth_mod_1",
        "profile"               -> Json.obj(),
        "token"                 -> Json.obj(),
        "simpleLogin"           -> true,
        "createdAt"             -> 1700000000000L,
        "expiredAt"             -> 1700086400000L,
        "lastRefresh"           -> 1700003600000L,
        "metadata"              -> Json.obj(),
        "tags"                  -> Json.arr(),
        "rights"                -> Json.arr(Json.obj("tenant" -> "default:rw", "teams" -> Json.arr("default:rw"))),
        "adminEntityValidators" -> Json.obj()
      )
    }

    "write its admin entity validators by entity" in {
      (written(format, full) \ "adminEntityValidators" \ "route").as[JsValue] mustBe Json.arr(
        Json.obj("kind" -> "json-path-validator", "path" -> "$.enabled", "value" -> true, "error" -> JsNull),
        Json.obj("kind" -> "wasm-plugin-validator", "ref" -> "wasm-1", "error" -> JsNull),
        Json.obj("kind" -> "opa-plugin-validator", "ref" -> "opa-1", "error" -> JsNull)
      )
    }

    "round trip a minimal user" in {
      roundTrip(format, minimal)
    }

    "round trip a user where every field is set" in {
      roundTrip(format, full)
    }

    "keep its rights on a round trip" in {
      roundTrip(format, full).rights mustBe rights
    }

    "keep its admin entity validators on a round trip" in {
      roundTrip(format, full).adminEntityValidators mustBe validators
    }

    "round trip a user without any right" in {
      roundTrip(format, minimal.copy(rights = UserRights.empty)).rights mustBe UserRights.empty
    }

    "round trip a super admin" in {
      roundTrip(format, minimal.copy(rights = UserRights.superAdmin)).rights mustBe UserRights.superAdmin
    }

    "round trip a user logged in through an auth. module" in {
      roundTrip(format, minimal.copy(simpleLogin = false)).simpleLogin mustBe false
    }

    "round trip a profile and a token that are not objects" in {
      roundTrip(format, minimal.copy(profile = JsString("raw"), token = Json.arr("a"))).token mustBe Json.arr("a")
    }

    "write its dates as milliseconds and read them back" in {
      val back = roundTrip(format, full)
      back.createdAt.getMillis mustBe 1700000000000L
      back.expiredAt.getMillis mustBe 1700086400000L
      back.lastRefresh.getMillis mustBe 1700003600000L
    }

    "round trip its custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read the default location without _loc" in {
      readOk(format, written(format, full) - "_loc").location mustBe EntityLocation()
    }

    "read the defaults of a user with only a random id, a name and an email" in {
      val before = System.currentTimeMillis()
      val back   = readOk(format, Json.obj("randomId" -> "session-1", "name" -> "John Doe", "email" -> "john@oto.tools"))
      val after  = System.currentTimeMillis()
      back.authConfigId mustBe "none"
      back.profile mustBe Json.obj()
      back.token mustBe Json.obj()
      back.simpleLogin mustBe true
      back.tags mustBe empty
      back.metadata mustBe Map.empty
      back.adminEntityValidators mustBe Map.empty
      back.location mustBe EntityLocation()
      Seq(back.createdAt, back.expiredAt, back.lastRefresh).foreach { date =>
        date.getMillis must (be >= before and be <= after)
      }
    }

    "read a user without rights as a super admin" in {
      readOk(format, Json.obj("randomId" -> "s", "name" -> "n", "email" -> "e")).rights mustBe UserRights.superAdmin
    }

    "read a user whose rights are null as a super admin" in {
      readOk(format, written(format, minimal) ++ Json.obj("rights" -> JsNull)).rights mustBe UserRights.superAdmin
    }

    "read a user whose rights are a string as a super admin" in {
      readOk(format, written(format, minimal) ++ Json.obj("rights" -> "default:r")).rights mustBe UserRights.superAdmin
    }

    "read a user whose rights cannot be read as a user without any right" in {
      readOk(format, written(format, minimal) ++ Json.obj("rights" -> Json.arr("default:rw"))).rights mustBe UserRights.empty
    }

    "read a user with an empty rights array as a user without any right" in {
      readOk(format, written(format, minimal) ++ Json.obj("rights" -> Json.arr())).rights mustBe UserRights.empty
    }

    "read rights without permission suffix as read and write rights" in {
      val json = written(format, minimal) ++ Json.obj("rights" -> Json.arr(Json.obj("tenant" -> "tenant-1", "teams" -> Json.arr("team-1"))))
      readOk(format, json).rights mustBe UserRights.varargs(UserRight(TenantAccess("tenant-1", true, true), Seq(TeamAccess("team-1", true, true))))
    }

    "not read a user without random id" in {
      readError(format, Json.obj("name" -> "n", "email" -> "e"))
    }

    "not read a user without name" in {
      readError(format, Json.obj("randomId" -> "s", "email" -> "e"))
    }

    "not read a user without email" in {
      readError(format, Json.obj("randomId" -> "s", "name" -> "n"))
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "fall back to now for a date that is not a number" in {
      val before = System.currentTimeMillis()
      val back   = readOk(format, written(format, minimal) ++ Json.obj("createdAt" -> "2023-11-14T22:13:20Z"))
      back.createdAt.getMillis must be >= before
    }

    "fall back to a simple login when the flag is not a boolean" in {
      readOk(format, written(format, full) ++ Json.obj("simpleLogin" -> "false")).simpleLogin mustBe true
    }

    "ignore metadata holding a non string value" in {
      readOk(format, written(format, full) ++ Json.obj("metadata" -> Json.obj("a" -> 1))).metadata mustBe Map.empty
    }

    "drop the admin entity validators that cannot be read" in {
      val json = written(format, minimal) ++ Json.obj(
        "adminEntityValidators" -> Json.obj("route" -> Json.arr(Json.obj("kind" -> "foo"), Json.obj("kind" -> "wasm-plugin-validator")))
      )
      readOk(format, json).adminEntityValidators mustBe Map("route" -> Seq.empty)
    }

    "not read a user whose admin entity validators of an entity are not an array" in {
      readError(format, written(format, minimal) ++ Json.obj("adminEntityValidators" -> Json.obj("route" -> Json.obj())))
    }

    "ignore admin entity validators that are not an object" in {
      readOk(format, written(format, minimal) ++ Json.obj("adminEntityValidators" -> Json.arr())).adminEntityValidators mustBe Map.empty
    }

    "expose through json and toJson what the format writes" in {
      entityJsonMatchesFormat(format, full)
      full.toJson mustBe format.writes(full)
    }

    "expose its fields through the generic accessors, its name as description" in {
      hasAccessors(full, "session-1", "John Doe", "John Doe", Seq("admin", "ops"), Map("team" -> "ops"))
    }
  }
}
