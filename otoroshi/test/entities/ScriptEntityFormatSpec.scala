package entities

import otoroshi.models.EntityLocation
import otoroshi.script.{PluginType, Script}
import play.api.libs.json.*

class ScriptEntityFormatSpec extends EntityFormatSpec {

  "PluginType" should {

    "name every plugin type" in {
      PluginType.AppType.name mustBe "app"
      PluginType.TransformerType.name mustBe "transformer"
      PluginType.AccessValidatorType.name mustBe "validator"
      PluginType.PreRoutingType.name mustBe "preroute"
      PluginType.RequestSinkType.name mustBe "sink"
      PluginType.EventListenerType.name mustBe "listener"
      PluginType.JobType.name mustBe "job"
      PluginType.DataExporterType.name mustBe "exporter"
      PluginType.TunnelHandlerType.name mustBe "tunnel-handler"
      PluginType.RequestHandlerType.name mustBe "request-handler"
      PluginType.CompositeType.name mustBe "composite"
    }

    "give a distinct name to every plugin type" in {
      val all = Seq(
        PluginType.AppType,
        PluginType.TransformerType,
        PluginType.AccessValidatorType,
        PluginType.PreRoutingType,
        PluginType.RequestSinkType,
        PluginType.EventListenerType,
        PluginType.JobType,
        PluginType.DataExporterType,
        PluginType.TunnelHandlerType,
        PluginType.RequestHandlerType,
        PluginType.CompositeType
      )
      all.map(_.name).distinct.size mustBe all.size
    }
  }

  "Script" should {

    val format = Script._fmt

    val minimal = Script(
      id = "script-1",
      name = "Script 1",
      desc = "",
      code = "",
      `type` = PluginType.TransformerType
    )

    val full = Script(
      id = "script-2",
      name = "My validator",
      desc = "validates the access",
      code = "class MyValidator extends AccessValidator {}\nnew MyValidator()",
      `type` = PluginType.AccessValidatorType,
      tags = Seq("security", "prod"),
      metadata = Map("owner" -> "ops", "ticket" -> "42"),
      location = customLocation
    )

    "have no tags, no metadata and the default location by default" in {
      minimal.tags mustBe empty
      minimal.metadata mustBe empty
      minimal.location mustBe EntityLocation()
    }

    "write a minimal script" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"     -> defaultLocationJson,
        "id"       -> "script-1",
        "name"     -> "Script 1",
        "desc"     -> "",
        "code"     -> "",
        "type"     -> "transformer",
        "metadata" -> Json.obj(),
        "tags"     -> Json.arr()
      )
    }

    "write a script where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "_loc"     -> customLocationJson,
        "id"       -> "script-2",
        "name"     -> "My validator",
        "desc"     -> "validates the access",
        "code"     -> "class MyValidator extends AccessValidator {}\nnew MyValidator()",
        "type"     -> "validator",
        "metadata" -> Json.obj("owner" -> "ops", "ticket" -> "42"),
        "tags"     -> Json.arr("security", "prod")
      )
    }

    "write the description under the desc key only" in {
      val json = written(format, full)
      (json \ "desc").as[String] mustBe "validates the access"
      json.keys must not contain "description"
    }

    "round trip a minimal script" in {
      roundTrip(format, minimal)
    }

    "round trip a script where every field is set" in {
      roundTrip(format, full)
    }

    "read an event listener script as an event listener" in {
      readOk(format, written(format, minimal) ++ Json.obj("type" -> "listener")).`type` mustBe PluginType.EventListenerType
    }

    "read a tunnel handler script as a tunnel handler" in {
      readOk(format, written(format, minimal) ++ Json.obj("type" -> "tunnel-handler")).`type` mustBe PluginType.TunnelHandlerType
    }

    "round trip every plugin type" in {
      val types = Seq(
        PluginType.AppType,
        PluginType.TransformerType,
        PluginType.AccessValidatorType,
        PluginType.PreRoutingType,
        PluginType.RequestSinkType,
        PluginType.EventListenerType,
        PluginType.JobType,
        PluginType.DataExporterType,
        PluginType.TunnelHandlerType,
        PluginType.RequestHandlerType,
        PluginType.CompositeType
      )
      types.foreach { t =>
        roundTrip(format, minimal.copy(`type` = t)).`type` mustBe t
      }
    }

    "round trip a multiline code with quotes and unicode" in {
      val code = "val msg = \"héllo wörld\"\n\t// l'été\nnew Foo()"
      roundTrip(format, minimal.copy(code = code)).code mustBe code
    }

    "round trip a name and a description with quotes and unicode" in {
      roundTrip(format, full.copy(name = "l'équipe \"ops\"", desc = "multi\nline"))
    }

    "keep the order of the tags" in {
      roundTrip(format, full.copy(tags = Seq("z", "a", "m"))).tags mustBe Seq("z", "a", "m")
    }

    "read a script without type as a transformer" in {
      val json = written(format, minimal.copy(`type` = PluginType.AppType)) - "type"
      readOk(format, json).`type` mustBe PluginType.TransformerType
    }

    "read a null type as a transformer" in {
      val json = written(format, minimal) ++ Json.obj("type" -> JsNull)
      readOk(format, json).`type` mustBe PluginType.TransformerType
    }

    "read an unknown type as a transformer" in {
      val json = written(format, minimal) ++ Json.obj("type" -> "foo")
      readOk(format, json).`type` mustBe PluginType.TransformerType
    }

    "read a type that is not a string as a transformer" in {
      val json = written(format, minimal) ++ Json.obj("type" -> 1)
      readOk(format, json).`type` mustBe PluginType.TransformerType
    }

    "read the type case sensitively" in {
      val json = written(format, minimal) ++ Json.obj("type" -> "App")
      readOk(format, json).`type` mustBe PluginType.TransformerType
    }

    "read the validator type from its short name only" in {
      readOk(format, written(format, minimal) ++ Json.obj("type" -> "validator")).`type` mustBe PluginType.AccessValidatorType
      readOk(format, written(format, minimal) ++ Json.obj("type" -> "access-validator")).`type` mustBe PluginType.TransformerType
    }

    "read a script with only its mandatory fields" in {
      readOk(format, Json.obj("id" -> "s", "name" -> "S", "desc" -> "d", "code" -> "c")) mustBe Script(
        id = "s",
        name = "S",
        desc = "d",
        code = "c",
        `type` = PluginType.TransformerType,
        tags = Seq.empty,
        metadata = Map.empty,
        location = EntityLocation()
      )
    }

    "read empty strings as mandatory fields" in {
      readOk(format, Json.obj("id" -> "", "name" -> "", "desc" -> "", "code" -> "")).id mustBe ""
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a json string" in {
      readError(format, JsString("script-1"))
    }

    "not read a json array" in {
      readError(format, Json.arr(written(format, minimal)))
    }

    "not read a script without id" in {
      readError(format, written(format, minimal) - "id")
    }

    "not read a script without name" in {
      readError(format, written(format, minimal) - "name")
    }

    "not read a script without desc" in {
      readError(format, written(format, minimal) - "desc")
    }

    "not read a script without code" in {
      readError(format, written(format, minimal) - "code")
    }

    "not read the description key in place of desc" in {
      readError(format, written(format, minimal) - "desc" ++ Json.obj("description" -> "d"))
    }

    "not read an id that is not a string" in {
      readError(format, written(format, minimal) ++ Json.obj("id" -> 1))
    }

    "not read a null name" in {
      readError(format, written(format, minimal) ++ Json.obj("name" -> JsNull))
    }

    "not read a code that is not a string" in {
      readError(format, written(format, minimal) ++ Json.obj("code" -> Json.arr("line 1", "line 2")))
    }

    "read a script without tags with no tags" in {
      readOk(format, written(format, full) - "tags").tags mustBe empty
    }

    "read a script without metadata with no metadata" in {
      readOk(format, written(format, full) - "metadata").metadata mustBe empty
    }

    "ignore tags given as a single string" in {
      readOk(format, written(format, minimal) ++ Json.obj("tags" -> "prod")).tags mustBe empty
    }

    "ignore tags holding a non string value" in {
      readOk(format, written(format, minimal) ++ Json.obj("tags" -> Json.arr("prod", 1))).tags mustBe empty
    }

    "ignore metadata holding a non string value" in {
      readOk(format, written(format, minimal) ++ Json.obj("metadata" -> Json.obj("a" -> "b", "n" -> 1))).metadata mustBe empty
    }

    "ignore metadata given as an array" in {
      readOk(format, written(format, minimal) ++ Json.obj("metadata" -> Json.arr("a", "b"))).metadata mustBe empty
    }

    "ignore unknown keys" in {
      readOk(format, written(format, full) ++ Json.obj("enabled" -> false, "foo" -> "bar")) mustBe full
    }

    "round trip a custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read a script without _loc in the default location" in {
      readOk(format, written(format, full) - "_loc").location mustBe EntityLocation()
    }

    "read a script whose _loc is not an object in the default location" in {
      readOk(format, written(format, full) ++ Json.obj("_loc" -> "tenant-1")).location mustBe EntityLocation()
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "script-2", "My validator", "validates the access", Seq("security", "prod"), Map("owner" -> "ops", "ticket" -> "42"))
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "expose through toJson what the format writes" in {
      full.toJson mustBe format.writes(full)
      Script.toJson(full) mustBe format.writes(full)
    }

    "be built from its json" in {
      Script.fromJsons(full.json) mustBe full
    }

    "fail to be built from a json without id" in {
      a[Throwable] must be thrownBy Script.fromJsons(written(format, full) - "id")
    }

    "be read safely from its json" in {
      Script.fromJsonSafe(full.json) mustBe Right(full)
    }

    "give the read errors when read safely from an invalid json" in {
      Script.fromJsonSafe(Json.obj("id" -> "s")).isLeft mustBe true
    }

    "hash its code with sha-256 in lower case hex" in {
      minimal.copy(code = "abc").hash mustBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }

    "hash an empty code" in {
      minimal.hash mustBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }

    "hash only its code" in {
      full.copy(code = "abc").hash mustBe minimal.copy(code = "abc").hash
    }

    "change its hash when its code changes" in {
      minimal.copy(code = "abc").hash must not be minimal.copy(code = "abd").hash
    }

    "use a sha-256 digest" in {
      Script.digest.getAlgorithm mustBe "SHA-256"
    }
  }
}
