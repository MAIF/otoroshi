package entities

import otoroshi.models.*
import play.api.libs.json.*

class ServiceGroupEntityFormatSpec extends EntityFormatSpec {

  "ServiceGroup" should {

    val format = ServiceGroup._fmt

    val full = ServiceGroup(
      id = "group-1",
      name = "Group 1",
      description = "the first group",
      tags = Seq("prod", "eu"),
      metadata = Map("owner" -> "ops", "cost-center" -> "42"),
      location = customLocation
    )

    "write a group with its location" in {
      format.writes(full) mustBe Json.obj(
        "_loc"        -> customLocationJson,
        "id"          -> "group-1",
        "name"        -> "Group 1",
        "description" -> "the first group",
        "tags"        -> Json.arr("prod", "eu"),
        "metadata"    -> Json.obj("owner" -> "ops", "cost-center" -> "42")
      )
    }

    "write a minimal group with the constructor defaults" in {
      format.writes(ServiceGroup(id = "group-1", name = "Group 1")) mustBe Json.obj(
        "_loc"        -> defaultLocationJson,
        "id"          -> "group-1",
        "name"        -> "Group 1",
        "description" -> "No description",
        "tags"        -> Json.arr(),
        "metadata"    -> Json.obj()
      )
    }

    "be in the default location by default" in {
      ServiceGroup(id = "group-1", name = "Group 1").location mustBe EntityLocation()
    }

    "get a random 64 characters id by default" in {
      val first  = ServiceGroup(name = "Group 1")
      val second = ServiceGroup(name = "Group 1")
      first.id.length mustBe 64
      first.id must not be second.id
    }

    "round trip a group where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a minimal group" in {
      roundTrip(format, ServiceGroup(id = "group-1", name = "Group 1"))
    }

    "round trip an empty description" in {
      roundTrip(format, full.copy(description = "")).description mustBe ""
    }

    "round trip a name and a description with quotes and unicode" in {
      roundTrip(format, full.copy(name = "le groupe \"ops\" été", description = "multi\nline"))
    }

    "keep the order of the tags" in {
      roundTrip(format, full.copy(tags = Seq("z", "a", "m"))).tags mustBe Seq("z", "a", "m")
    }

    "keep duplicated tags" in {
      roundTrip(format, full.copy(tags = Seq("a", "a"))).tags mustBe Seq("a", "a")
    }

    "round trip a custom location" in {
      roundTrip(format, ServiceGroup(id = "group-1", name = "Group 1", location = customLocation)).location mustBe
        customLocation
    }

    "round trip the default location" in {
      roundTrip(format, full.copy(location = EntityLocation())).location mustBe EntityLocation()
    }

    "read a group without _loc in the default location" in {
      readOk(format, Json.obj("id" -> "group-1", "name" -> "Group 1")).location mustBe EntityLocation()
    }

    "read a group whose _loc is not an object in the default location" in {
      readOk(format, Json.obj("id" -> "group-1", "name" -> "Group 1", "_loc" -> "tenant-1")).location mustBe
        EntityLocation()
    }

    "read a lone id and name with an empty description, not the constructor one" in {
      readOk(format, Json.obj("id" -> "group-1", "name" -> "Group 1")) mustBe ServiceGroup(
        id = "group-1",
        name = "Group 1",
        description = "",
        tags = Seq.empty,
        metadata = Map.empty,
        location = EntityLocation()
      )
    }

    "keep the case of the id and the name" in {
      val back = readOk(format, Json.obj("id" -> "Group-1", "name" -> "GROUP"))
      back.id mustBe "Group-1"
      back.name mustBe "GROUP"
    }

    "not read a group without id" in {
      readError(format, Json.obj("name" -> "Group 1"))
    }

    "not read a group without name" in {
      readError(format, Json.obj("id" -> "group-1"))
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a json array" in {
      readError(format, Json.arr(written(format, full)))
    }

    "not read an id that is not a string" in {
      readError(format, Json.obj("id" -> 1, "name" -> "Group 1"))
    }

    "not read a name that is not a string" in {
      readError(format, Json.obj("id" -> "group-1", "name" -> true))
    }

    "not read a json null name" in {
      readError(format, Json.obj("id" -> "group-1", "name" -> JsNull))
    }

    "read a description that is not a string as an empty one" in {
      readOk(format, Json.obj("id" -> "group-1", "name" -> "Group 1", "description" -> 42)).description mustBe ""
    }

    "read a json null description as an empty one" in {
      readOk(format, Json.obj("id" -> "group-1", "name" -> "Group 1", "description" -> JsNull)).description mustBe ""
    }

    "ignore metadata holding a non string value" in {
      readOk(
        format,
        Json.obj("id" -> "group-1", "name" -> "Group 1", "metadata" -> Json.obj("a" -> "b", "n" -> 1))
      ).metadata mustBe Map.empty
    }

    "ignore metadata given as an array" in {
      readOk(format, Json.obj("id" -> "group-1", "name" -> "Group 1", "metadata" -> Json.arr("a"))).metadata mustBe
        Map.empty
    }

    "ignore tags given as a single string" in {
      readOk(format, Json.obj("id" -> "group-1", "name" -> "Group 1", "tags" -> "prod")).tags mustBe empty
    }

    "ignore tags holding a non string value" in {
      readOk(format, Json.obj("id" -> "group-1", "name" -> "Group 1", "tags" -> Json.arr("prod", 1))).tags mustBe empty
    }

    "ignore unknown keys" in {
      readOk(format, written(format, full) ++ Json.obj("services" -> Json.arr("service-1"), "foo" -> "bar")) mustBe full
    }

    "keep a metadata key that is an empty string" in {
      roundTrip(format, full.copy(metadata = Map("" -> "v"))).metadata mustBe Map("" -> "v")
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(
        full,
        "group-1",
        "Group 1",
        "the first group",
        Seq("prod", "eu"),
        Map("owner" -> "ops", "cost-center" -> "42")
      )
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "expose through toJson what the format writes" in {
      full.toJson mustBe format.writes(full)
      ServiceGroup.toJson(full) mustBe format.writes(full)
    }

    "be built from its json" in {
      ServiceGroup.fromJsons(full.json) mustBe full
    }

    "fail to be built from a json without name" in {
      a[Throwable] must be thrownBy ServiceGroup.fromJsons(Json.obj("id" -> "group-1"))
    }

    "be read safely from its json" in {
      ServiceGroup.fromJsonSafe(full.json) mustBe JsSuccess(full)
    }

    "be read safely as an error from a json without id" in {
      ServiceGroup.fromJsonSafe(Json.obj("name" -> "Group 1")).isError mustBe true
    }
  }
}
