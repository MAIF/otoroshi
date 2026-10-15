package entities

import org.joda.time.DateTime
import otoroshi.models.*
import play.api.libs.json.*

class PrivateAppsUserEntityFormatSpec extends EntityFormatSpec {

  "PrivateAppsUser" should {

    val format = PrivateAppsUser.fmt

    val created     = new DateTime(1700000000000L)
    val expired     = new DateTime(1700086400000L)
    val lastRefresh = new DateTime(1700003600000L)

    val minimal = PrivateAppsUser(
      randomId = "session-1",
      name = "John Doe",
      email = "john@oto.tools",
      profile = Json.obj(),
      realm = "global-oauth-auth_mod_1",
      authConfigId = "auth_mod_1",
      otoroshiData = None,
      createdAt = created,
      expiredAt = expired,
      lastRefresh = lastRefresh,
      tags = Seq.empty,
      metadata = Map.empty,
      location = EntityLocation()
    )

    val full = PrivateAppsUser(
      randomId = "session-1",
      name = "John Doe",
      email = "john@oto.tools",
      profile = Json.obj(
        "sub"     -> "sub-1",
        "user_id" -> "user-1",
        "picture" -> "https://pics.oto.tools/john.png",
        "app"     -> Json.obj("otoroshi_data" -> Json.obj("level" -> 1))
      ),
      token = Json.obj("access_token" -> "xxx", "id_token" -> "yyy"),
      realm = "global-oauth-auth_mod_1",
      authConfigId = "auth_mod_1",
      otoroshiData = Some(Json.obj("level" -> 1, "groups" -> Json.arr("admins"))),
      createdAt = created,
      expiredAt = expired,
      lastRefresh = lastRefresh,
      tags = Seq("vip"),
      metadata = Map("source" -> "keycloak"),
      location = customLocation
    )

    "write a minimal user" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"         -> defaultLocationJson,
        "randomId"     -> "session-1",
        "name"         -> "John Doe",
        "email"        -> "john@oto.tools",
        "authConfigId" -> "auth_mod_1",
        "profile"      -> Json.obj(),
        "token"        -> Json.obj(),
        "realm"        -> "global-oauth-auth_mod_1",
        "otoroshiData" -> JsNull,
        "createdAt"    -> 1700000000000L,
        "expiredAt"    -> 1700086400000L,
        "lastRefresh"  -> 1700003600000L,
        "metadata"     -> Json.obj(),
        "tags"         -> Json.arr()
      )
    }

    "write its otoroshi data as they are" in {
      (written(format, full) \ "otoroshiData").as[JsValue] mustBe Json.obj("level" -> 1, "groups" -> Json.arr("admins"))
    }

    val withEmptyData = minimal.copy(otoroshiData = Some(Json.obj()))

    "round trip a minimal user, without otoroshi data" in {
      roundTrip(format, minimal).otoroshiData mustBe None
    }

    "read a null otoroshi data as no otoroshi data" in {
      readOk(format, written(format, minimal) ++ Json.obj("otoroshiData" -> JsNull)).otoroshiData mustBe None
    }

    "round trip a minimal user with empty otoroshi data" in {
      roundTrip(format, withEmptyData)
    }

    "round trip a user where every field is set" in {
      roundTrip(format, full)
    }

    "round trip otoroshi data that are not an object" in {
      roundTrip(format, minimal.copy(otoroshiData = Some(Json.arr("a", "b")))).otoroshiData mustBe Some(Json.arr("a", "b"))
    }

    "round trip a profile that is not an object" in {
      roundTrip(format, withEmptyData.copy(profile = JsString("raw"))).profile mustBe JsString("raw")
    }

    "write its dates as milliseconds and read them back" in {
      val back = roundTrip(format, full)
      back.createdAt.getMillis mustBe 1700000000000L
      back.expiredAt.getMillis mustBe 1700086400000L
      back.lastRefresh.getMillis mustBe 1700003600000L
    }

    "round trip its custom location" in {
      roundTrip(format, withEmptyData.copy(location = customLocation)).location mustBe customLocation
    }

    "read the default location without _loc" in {
      readOk(format, written(format, full) - "_loc").location mustBe EntityLocation()
    }

    val mandatory = Json.obj(
      "randomId"    -> "session-1",
      "name"        -> "John Doe",
      "email"       -> "john@oto.tools",
      "profile"     -> Json.obj(),
      "createdAt"   -> 1700000000000L,
      "expiredAt"   -> 1700086400000L,
      "lastRefresh" -> 1700003600000L
    )

    "read the defaults of a user with only its mandatory fields" in {
      readOk(format, mandatory) mustBe minimal.copy(realm = "none", authConfigId = "none")
    }

    "not read a user without one of its mandatory fields" in {
      Seq("randomId", "name", "email", "profile", "createdAt", "expiredAt", "lastRefresh").foreach { key =>
        withClue(s"without $key: ") {
          readError(format, mandatory - key)
        }
      }
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a date that is not a number" in {
      readError(format, mandatory ++ Json.obj("expiredAt" -> "2023-11-15T22:13:20Z"))
    }

    "read a null profile as a json null" in {
      readOk(format, mandatory ++ Json.obj("profile" -> JsNull)).profile mustBe JsNull
    }

    "fall back to the default realm when it is not a string" in {
      readOk(format, mandatory ++ Json.obj("realm" -> 1)).realm mustBe "none"
    }

    "ignore metadata holding a non string value" in {
      readOk(format, mandatory ++ Json.obj("metadata" -> Json.obj("a" -> true))).metadata mustBe Map.empty
    }

    "expose through json and toJson what the format writes" in {
      entityJsonMatchesFormat(format, full)
      full.toJson mustBe format.writes(full)
    }

    "expose its fields through the generic accessors, its name as description" in {
      hasAccessors(full, "session-1", "John Doe", "John Doe", Seq("vip"), Map("source" -> "keycloak"))
    }

    "give a light json with its otoroshi data as metadata" in {
      full.lightJson mustBe Json.obj(
        "name"     -> "John Doe",
        "email"    -> "john@oto.tools",
        "profile"  -> full.profile,
        "metadata" -> Json.obj("level" -> 1, "groups" -> Json.arr("admins")),
        "tags"     -> Json.arr("vip")
      )
      full.asJsonCleaned mustBe full.lightJson
    }

    "give a light json with null metadata without otoroshi data" in {
      (minimal.lightJson \ "metadata").as[JsValue] mustBe JsNull
    }

    "read its picture and the string fields of its profile" in {
      full.picture mustBe Some("https://pics.oto.tools/john.png")
      full.field("sub") mustBe Some("sub-1")
      full.field("app") mustBe None
      minimal.picture mustBe None
    }

    "prefer the user_id of its profile over its sub as user id" in {
      full.userId mustBe Some("user-1")
      minimal.copy(profile = Json.obj("sub" -> "sub-1")).userId mustBe Some("sub-1")
      minimal.userId mustBe None
    }
  }

  "PrivateAppsUser.select" should {

    val profile = Json.obj("app_metadata" -> Json.obj("otoroshi_data" -> Json.obj("level" -> 1)), "name" -> "John")

    "select a nested value through a pipe separated path" in {
      PrivateAppsUser.select(profile, "app_metadata | otoroshi_data") mustBe Json.obj("level" -> 1)
    }

    "select a top level value" in {
      PrivateAppsUser.select(profile, "name") mustBe JsString("John")
    }

    "select a json null for a missing path" in {
      PrivateAppsUser.select(profile, "app_metadata|missing|level") mustBe JsNull
    }
  }
}
