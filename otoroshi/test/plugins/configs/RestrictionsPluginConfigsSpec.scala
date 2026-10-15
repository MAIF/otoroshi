package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class RestrictionsPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgRestrictions" should {

    val format = NgRestrictions.format

    val full = NgRestrictions(
      allowLast = false,
      allowed = Seq(NgRestrictionPath("GET", "/api/.*"), NgRestrictionPath("*", "/health")),
      forbidden = Seq(NgRestrictionPath("DELETE", "/api/users/.*")),
      notFound = Seq(NgRestrictionPath("GET", "/admin"), NgRestrictionPath("POST", "/admin/.*"))
    )

    "allow last and restrict nothing by default" in {
      val config = NgRestrictions()
      config.allowLast mustBe true
      config.allowed mustBe empty
      config.forbidden mustBe empty
      config.notFound mustBe empty
    }

    "write the default config with empty lists" in {
      format.writes(NgRestrictions()) mustBe Json.obj(
        "allow_last" -> true,
        "allowed"    -> Json.arr(),
        "forbidden"  -> Json.arr(),
        "not_found"  -> Json.arr()
      )
    }

    "write each path as a method and a path" in {
      format.writes(NgRestrictions(forbidden = Seq(NgRestrictionPath("DELETE", "/api/.*")))) mustBe Json.obj(
        "allow_last" -> true,
        "allowed"    -> Json.arr(),
        "forbidden"  -> Json.arr(Json.obj("method" -> "DELETE", "path" -> "/api/.*")),
        "not_found"  -> Json.arr()
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgRestrictions())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip allow_last disabled alone" in {
      roundTrip(format, NgRestrictions(allowLast = false)).allowLast mustBe false
    }

    "keep each list under its own key" in {
      val back = roundTrip(
        format,
        NgRestrictions(
          allowed = Seq(NgRestrictionPath("GET", "/a")),
          forbidden = Seq(NgRestrictionPath("GET", "/f")),
          notFound = Seq(NgRestrictionPath("GET", "/n"))
        )
      )
      back.allowed.map(_.path) mustBe Seq("/a")
      back.forbidden.map(_.path) mustBe Seq("/f")
      back.notFound.map(_.path) mustBe Seq("/n")
    }

    "round trip paths and keep their order" in {
      val paths = Seq(NgRestrictionPath("GET", "/c"), NgRestrictionPath("GET", "/a"), NgRestrictionPath("GET", "/b"))
      roundTrip(format, NgRestrictions(allowed = paths)).allowed mustBe paths
    }

    "round trip methods and paths as written" in {
      val paths = Seq(NgRestrictionPath("get", "/API/*"), NgRestrictionPath("", ""))
      roundTrip(format, NgRestrictions(notFound = paths)).notFound mustBe paths
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgRestrictions()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgRestrictions()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("GET /api")) mustBe NgRestrictions()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(Json.obj("method" -> "GET", "path" -> "/api"))) mustBe NgRestrictions()
    }

    "read allow_last alone" in {
      readOk(format, Json.obj("allow_last" -> false)) mustBe NgRestrictions(allowLast = false)
    }

    "read not_found alone" in {
      readOk(
        format,
        Json.obj("not_found" -> Json.arr(Json.obj("method" -> "GET", "path" -> "/admin")))
      ) mustBe NgRestrictions(notFound = Seq(NgRestrictionPath("GET", "/admin")))
    }

    "ignore an allow_last written as a string" in {
      readOk(format, Json.obj("allow_last" -> "false")).allowLast mustBe true
    }

    "ignore a list that is not an array" in {
      readOk(format, Json.obj("allowed" -> Json.obj("method" -> "GET", "path" -> "/api"))).allowed mustBe empty
    }

    "skip a path without method and keep the others" in {
      val json = Json.obj(
        "forbidden" -> Json.arr(
          Json.obj("path" -> "/no-method"),
          Json.obj("method" -> "GET", "path" -> "/kept")
        )
      )
      readOk(format, json).forbidden mustBe Seq(NgRestrictionPath("GET", "/kept"))
    }

    "skip a path without path" in {
      readOk(format, Json.obj("allowed" -> Json.arr(Json.obj("method" -> "GET")))).allowed mustBe empty
    }

    "skip a path written as a string" in {
      readOk(format, Json.obj("allowed" -> Json.arr("GET /api"))).allowed mustBe empty
    }

    "skip a path whose method is not a string" in {
      readOk(format, Json.obj("allowed" -> Json.arr(Json.obj("method" -> 1, "path" -> "/api")))).allowed mustBe empty
    }

    "not read the camel case keys" in {
      val json = Json.obj(
        "allowLast" -> false,
        "notFound"  -> Json.arr(Json.obj("method" -> "GET", "path" -> "/admin"))
      )
      readOk(format, json) mustBe NgRestrictions()
    }

    "turn into enabled legacy restrictions" in {
      val legacy = full.legacy
      legacy.enabled mustBe true
      legacy.allowLast mustBe false
      legacy.allowed.map(p => (p.method, p.path)) mustBe Seq("GET" -> "/api/.*", "*" -> "/health")
      legacy.forbidden.map(_.authorizedEntity) mustBe Seq(None)
      legacy.notFound.size mustBe 2
    }

    "come back from its legacy restrictions" in {
      NgRestrictions.fromLegacy(full.legacy) mustBe full
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of RoutingRestrictions" in {
      defaultConfigReadsBack(new RoutingRestrictions(), format) mustBe NgRestrictions()
    }
  }

  "NgRestrictionPath" should {

    val format = NgRestrictionPath.format

    "round trip a method and a path" in {
      roundTrip(format, NgRestrictionPath("PUT", "/api/.*"))
    }

    "fail to read an empty object because the method and the path are mandatory" in {
      readError(format, Json.obj())
    }

    "fail to read a path without method" in {
      readError(format, Json.obj("path" -> "/api"))
    }

    "fail to read a json null" in {
      readError(format, JsNull)
    }
  }
}
