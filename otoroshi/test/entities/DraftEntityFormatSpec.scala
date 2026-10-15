package entities

import otoroshi.models.{Draft, EntityLocation}
import play.api.libs.json.*

class DraftEntityFormatSpec extends EntityFormatSpec {

  private val apiContent = Json.obj(
    "_loc"  -> customLocationJson,
    "kind"  -> "Api",
    "id"    -> "api-1",
    "name"  -> "Users API",
    "state" -> "staging",
    "plans" -> Json.arr(Json.obj("id" -> "plan-1", "name" -> "gold", "access_mode_configuration_type" -> "apikey"))
  )

  private val full = Draft(
    id = "api-1",
    kind = "apis",
    content = apiContent,
    name = "draft of the users api",
    description = "the next version of the users api",
    tags = Seq("wip", "users"),
    metadata = Map("author" -> "jane"),
    location = customLocation
  )

  private val minimal = Draft(id = "draft-1", kind = "", content = Json.obj(), name = "draft", description = "")

  private val minimalJson = Json.obj("id" -> "draft-1", "name" -> "draft", "description" -> "")

  "Draft" should {

    val format = Draft.format

    "write a draft" in {
      format.writes(full) mustBe Json.obj(
        "_loc"        -> customLocationJson,
        "id"          -> "api-1",
        "name"        -> "draft of the users api",
        "description" -> "the next version of the users api",
        "content"     -> apiContent,
        "kind"        -> "apis",
        "metadata"    -> Json.obj("author" -> "jane"),
        "tags"        -> Json.arr("wip", "users")
      )
    }

    "write a minimal draft" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"        -> defaultLocationJson,
        "id"          -> "draft-1",
        "name"        -> "draft",
        "description" -> "",
        "content"     -> Json.obj(),
        "kind"        -> "",
        "metadata"    -> Json.obj(),
        "tags"        -> Json.arr()
      )
    }

    "be in the default location by default" in {
      minimal.location mustBe EntityLocation()
    }

    "round trip a draft where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a minimal draft" in {
      roundTrip(format, minimal)
    }

    "keep its content exactly as given" in {
      val content = apiContent ++ Json.obj("unknown" -> Json.obj("nested" -> Json.arr(1, "two", JsNull, true)))
      roundTrip(format, full.copy(content = content)).content mustBe content
    }

    "round trip a content that is not an object" in {
      roundTrip(format, full.copy(content = Json.arr("a", "b"))).content mustBe Json.arr("a", "b")
      roundTrip(format, full.copy(content = JsString("raw"))).content mustBe JsString("raw")
    }

    "round trip a null content" in {
      roundTrip(format, full.copy(content = JsNull)).content mustBe JsNull
    }

    "keep the location of its content apart from its own" in {
      val back = roundTrip(format, full.copy(location = EntityLocation()))
      back.location mustBe EntityLocation()
      EntityLocation.readFromKey(back.content) mustBe customLocation
    }

    "round trip its custom location" in {
      roundTrip(format, full).location mustBe customLocation
    }

    "read the default location without _loc" in {
      readOk(format, minimalJson).location mustBe EntityLocation()
    }

    "not read the location from a location key" in {
      readOk(format, minimalJson ++ Json.obj("location" -> customLocationJson)).location mustBe EntityLocation()
    }

    "read a lone id, name and description with the defaults" in {
      readOk(format, minimalJson) mustBe minimal
    }

    "read a missing content as an empty object" in {
      readOk(format, minimalJson).content mustBe Json.obj()
    }

    "read a missing kind as an empty kind" in {
      readOk(format, minimalJson).kind mustBe ""
    }

    "read a kind that is not a string as an empty kind" in {
      readOk(format, minimalJson ++ Json.obj("kind" -> 1)).kind mustBe ""
    }

    "not read a draft without id" in {
      readError(format, minimalJson - "id")
    }

    "not read a draft without name" in {
      readError(format, minimalJson - "name")
    }

    "not read a draft without description" in {
      readError(format, minimalJson - "description")
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read an id that is not a string" in {
      readError(format, minimalJson ++ Json.obj("id" -> 1))
    }

    "ignore metadata holding a non string value" in {
      readOk(format, minimalJson ++ Json.obj("metadata" -> Json.obj("a" -> "b", "n" -> 1))).metadata mustBe empty
    }

    "ignore tags given as a single string" in {
      readOk(format, minimalJson ++ Json.obj("tags" -> "wip")).tags mustBe empty
    }

    "keep the order of the tags" in {
      roundTrip(format, full.copy(tags = Seq("z", "a", "m"))).tags mustBe Seq("z", "a", "m")
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "api-1", "draft of the users api", "the next version of the users api", Seq("wip", "users"), Map("author" -> "jane"))
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "be built from its json" in {
      Draft.fromJsons(full.json) mustBe full
    }

    "fail to be built from a json without id" in {
      a[Throwable] must be thrownBy Draft.fromJsons(minimalJson - "id")
    }
  }
}
