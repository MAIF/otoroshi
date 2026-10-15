package entities

import otoroshi.models.*
import play.api.libs.json.*

class ErrorTemplateEntityFormatSpec extends EntityFormatSpec {

  val full = ErrorTemplate(
    location = customLocation,
    serviceId = "route-1",
    name = "Route 1 errors",
    description = "the error pages of route 1",
    metadata = Map("owner" -> "ops"),
    tags = Seq("prod", "eu"),
    template40x = "<h1>40x ${message}</h1>",
    template50x = "<h1>50x ${message}</h1>",
    templateBuild = "<h1>build ${message}</h1>",
    templateMaintenance = "<h1>maintenance ${message}</h1>",
    genericTemplates = Map("errors.custom" -> "<h1>custom ${cause}</h1>"),
    messages = Map("message-404" -> "nothing here", "errors.custom" -> "a custom cause")
  )

  /** a template whose four base templates name themselves, to see which one is picked */
  val picker = ErrorTemplate(
    location = EntityLocation(),
    serviceId = "route-1",
    name = "picker",
    description = "",
    metadata = Map.empty,
    tags = Seq.empty,
    template40x = "40x",
    template50x = "50x",
    templateBuild = "build",
    templateMaintenance = "maintenance",
    genericTemplates = Map.empty
  )

  /** the smallest json the format reads */
  val minimalJson: JsObject = Json.obj(
    "serviceId"           -> "route-1",
    "template40x"         -> "40x",
    "template50x"         -> "50x",
    "templateBuild"       -> "build",
    "templateMaintenance" -> "maintenance"
  )

  "ErrorTemplate format" should {

    val format = ErrorTemplate.format

    "write a template with its location" in {
      format.writes(full) mustBe Json.obj(
        "_loc"                -> customLocationJson,
        "serviceId"           -> "route-1",
        "name"                -> "Route 1 errors",
        "description"         -> "the error pages of route 1",
        "metadata"            -> Json.obj("owner" -> "ops"),
        "tags"                -> Json.arr("prod", "eu"),
        "template40x"         -> "<h1>40x ${message}</h1>",
        "template50x"         -> "<h1>50x ${message}</h1>",
        "templateBuild"       -> "<h1>build ${message}</h1>",
        "templateMaintenance" -> "<h1>maintenance ${message}</h1>",
        "genericTemplates"    -> Json.obj("errors.custom" -> "<h1>custom ${cause}</h1>"),
        "messages"            -> Json.obj("message-404" -> "nothing here", "errors.custom" -> "a custom cause")
      )
    }

    "write empty generic templates and messages as empty objects" in {
      val obj = written(format, picker)
      (obj \ "genericTemplates").get mustBe Json.obj()
      (obj \ "messages").get mustBe Json.obj()
    }

    "have no message by default" in {
      picker.messages mustBe Map.empty
    }

    "be the same format under its two names" in {
      ErrorTemplate.fmt mustBe ErrorTemplate.format
    }

    "round trip a template where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a template without generic templates, messages, tags nor metadata" in {
      roundTrip(format, picker)
    }

    "round trip empty templates" in {
      roundTrip(format, picker.copy(template40x = "", template50x = "", templateBuild = "", templateMaintenance = ""))
    }

    "round trip html templates with quotes, new lines and unicode" in {
      roundTrip(format, picker.copy(template40x = "<div class=\"err\">\n  erreur réseau ${status}\n</div>"))
    }

    "round trip a custom location" in {
      roundTrip(format, picker.copy(location = customLocation)).location mustBe customLocation
    }

    "read a template without _loc in the default location" in {
      readOk(format, minimalJson).location mustBe EntityLocation()
    }

    "read the smallest json, named and described after its route" in {
      readOk(format, minimalJson) mustBe ErrorTemplate(
        location = EntityLocation(),
        serviceId = "route-1",
        name = "route-1",
        description = "route-1",
        metadata = Map.empty,
        tags = Seq.empty,
        template40x = "40x",
        template50x = "50x",
        templateBuild = "build",
        templateMaintenance = "maintenance",
        genericTemplates = Map.empty,
        messages = Map.empty
      )
    }

    "read a json null name and description as the route id" in {
      val back = readOk(format, minimalJson ++ Json.obj("name" -> JsNull, "description" -> JsNull))
      back.name mustBe "route-1"
      back.description mustBe "route-1"
    }

    "keep an empty description instead of the route id" in {
      readOk(format, minimalJson ++ Json.obj("description" -> "")).description mustBe ""
    }

    "not read a template without serviceId" in {
      readError(format, minimalJson - "serviceId")
    }

    "not read a template missing one of its four base templates" in {
      Seq("template40x", "template50x", "templateBuild", "templateMaintenance").foreach { key =>
        readError(format, minimalJson - key)
      }
    }

    "not read a base template that is not a string" in {
      readError(format, minimalJson ++ Json.obj("template50x" -> 500))
    }

    "not read a json null base template" in {
      readError(format, minimalJson ++ Json.obj("templateBuild" -> JsNull))
    }

    "not read a serviceId that is not a string" in {
      readError(format, minimalJson ++ Json.obj("serviceId" -> 1))
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a json string" in {
      readError(format, JsString("route-1"))
    }

    "ignore generic templates holding a non string value" in {
      readOk(
        format,
        minimalJson ++ Json.obj("genericTemplates" -> Json.obj("errors.a" -> "<p>a</p>", "errors.b" -> 1))
      ).genericTemplates mustBe Map.empty
    }

    "ignore messages holding a non string value" in {
      readOk(format, minimalJson ++ Json.obj("messages" -> Json.obj("message-404" -> "x", "message-500" -> false))).messages mustBe
        Map.empty
    }

    "ignore metadata holding a non string value" in {
      readOk(format, minimalJson ++ Json.obj("metadata" -> Json.obj("a" -> 1))).metadata mustBe Map.empty
    }

    "ignore tags given as a single string" in {
      readOk(format, minimalJson ++ Json.obj("tags" -> "prod")).tags mustBe empty
    }

    "keep the order of the tags" in {
      roundTrip(format, picker.copy(tags = Seq("z", "a", "m"))).tags mustBe Seq("z", "a", "m")
    }

    "ignore unknown keys" in {
      readOk(format, written(format, full) ++ Json.obj("id" -> "something-else", "foo" -> "bar")) mustBe full
    }

    "expose its fields through the generic accessors, with the route id as id" in {
      hasAccessors(full, "route-1", "Route 1 errors", "the error pages of route 1", Seq("prod", "eu"), Map("owner" -> "ops"))
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "expose through toJson what the format writes" in {
      full.toJson mustBe format.writes(full)
      ErrorTemplate.toJson(full) mustBe format.writes(full)
    }

    "be built from its json" in {
      ErrorTemplate.fromJsons(full.json) mustBe full
    }

    "fail to be built from a json without base templates" in {
      a[Throwable] must be thrownBy ErrorTemplate.fromJsons(Json.obj("serviceId" -> "route-1"))
    }

    "be read safely from its json" in {
      ErrorTemplate.fromJsonSafe(full.json) mustBe JsSuccess(full)
    }
  }

  "ErrorTemplate renderHtml" should {

    "use the 40x template for a 4xx status" in {
      picker.renderHtml(404, "errors.not.found", "Not found", "err-1") mustBe "40x"
      picker.renderHtml(499, "errors.x", "x", "err-1") mustBe "40x"
    }

    "use the 50x template for a 5xx status" in {
      picker.renderHtml(502, "errors.bad.gateway", "Bad gateway", "err-1") mustBe "50x"
      picker.renderHtml(500, "errors.x", "x", "err-1") mustBe "50x"
    }

    "use the 50x template for a status that is not an error" in {
      picker.renderHtml(200, "errors.x", "x", "err-1") mustBe "50x"
      picker.renderHtml(302, "errors.x", "x", "err-1") mustBe "50x"
      picker.renderHtml(600, "errors.x", "x", "err-1") mustBe "50x"
    }

    "use the maintenance template for a route in maintenance, whatever the status" in {
      picker.renderHtml(503, "errors.service.in.maintenance", "x", "err-1") mustBe "maintenance"
      picker.renderHtml(404, "errors.service.in.maintenance", "x", "err-1") mustBe "maintenance"
    }

    "use the build template for a route under construction, whatever the status" in {
      picker.renderHtml(503, "errors.service.under.construction", "x", "err-1") mustBe "build"
      picker.renderHtml(400, "errors.service.under.construction", "x", "err-1") mustBe "build"
    }

    "prefer a generic template matching the cause exactly" in {
      val tmpl = picker.copy(genericTemplates = Map("errors.service.in.maintenance" -> "generic"))
      tmpl.renderHtml(503, "errors.service.in.maintenance", "x", "err-1") mustBe "generic"
    }

    "use a generic template whose key is a wildcard matching the cause" in {
      val tmpl = picker.copy(genericTemplates = Map("errors.errtmplspec.*" -> "wildcard"))
      tmpl.renderHtml(404, "errors.errtmplspec.not.found", "x", "err-1") mustBe "wildcard"
    }

    "read the dots of a generic template key literally" in {
      val tmpl = picker.copy(genericTemplates = Map("errors.errtmplspecdot.a*" -> "wildcard"))
      tmpl.renderHtml(404, "errors.errtmplspecdotXa", "x", "err-1") mustBe "40x"
    }

    "prefer the exact generic template over a wildcard one" in {
      val tmpl = picker.copy(genericTemplates = Map("errors.errtmplspecboth.*" -> "wildcard", "errors.errtmplspecboth.a" -> "exact"))
      tmpl.renderHtml(404, "errors.errtmplspecboth.a", "x", "err-1") mustBe "exact"
    }

    "fall back to the status templates when no generic template matches" in {
      val tmpl = picker.copy(genericTemplates = Map("errors.errtmplspecother" -> "generic"))
      tmpl.renderHtml(404, "errors.not.found", "x", "err-1") mustBe "40x"
    }

    "replace every placeholder" in {
      val tmpl = picker.copy(template40x = "${status}|${message}|${cause}|${otoroshiMessage}|${errorId}")
      tmpl.renderHtml(404, "errors.not.found", "Not found", "err-1") mustBe "404|Not found|errors.not.found|Not found|err-1"
    }

    "replace every occurrence of a placeholder" in {
      val tmpl = picker.copy(template40x = "${errorId} and ${errorId}")
      tmpl.renderHtml(404, "errors.not.found", "x", "err-1") mustBe "err-1 and err-1"
    }

    "take the message of the status from the messages" in {
      val tmpl = picker.copy(template40x = "${message}|${otoroshiMessage}", messages = Map("message-404" -> "nothing here"))
      tmpl.renderHtml(404, "errors.not.found", "Not found", "err-1") mustBe "nothing here|Not found"
    }

    "not take the message of another status" in {
      val tmpl = picker.copy(template50x = "${message}", messages = Map("message-404" -> "nothing here"))
      tmpl.renderHtml(502, "errors.bad.gateway", "Bad gateway", "err-1") mustBe "Bad gateway"
    }

    "take the cause from the messages" in {
      val tmpl = picker.copy(template40x = "${cause}", messages = Map("errors.not.found" -> "it is gone"))
      tmpl.renderHtml(404, "errors.not.found", "Not found", "err-1") mustBe "it is gone"
    }

    "leave a template without placeholder as is" in {
      picker.copy(template40x = "<p>plain</p>").renderHtml(404, "errors.x", "x", "err-1") mustBe "<p>plain</p>"
    }

    "not escape the html of the inserted values" in {
      val tmpl = picker.copy(template40x = "${message}")
      tmpl.renderHtml(404, "errors.x", "<b>x</b>", "err-1") mustBe "<b>x</b>"
    }

    // the values are inserted one placeholder after the other, so a value holding a placeholder
    // that is replaced later gets replaced as well
    "replace the placeholders found in an inserted value" in {
      val tmpl = picker.copy(template40x = "${message}")
      tmpl.renderHtml(404, "errors.x", "status is ${status}, id is ${errorId}", "err-1") mustBe
        "status is 404, id is err-1"
    }
  }

  "ErrorTemplate renderJson" should {

    "describe the error" in {
      picker.renderJson(502, "errors.bad.gateway", "Bad gateway", "err-1") mustBe Json.obj(
        "otoroshi-error-id"  -> "err-1",
        "otoroshi-error"     -> "Bad gateway",
        "otoroshi-cause"     -> "errors.bad.gateway",
        "otoroshi-raw-error" -> "Bad gateway"
      )
    }

    "take the message and the cause from the messages, and keep the raw error" in {
      full.renderJson(404, "errors.custom", "Not found", "err-1") mustBe Json.obj(
        "otoroshi-error-id"  -> "err-1",
        "otoroshi-error"     -> "nothing here",
        "otoroshi-cause"     -> "a custom cause",
        "otoroshi-raw-error" -> "Not found"
      )
    }

    "not use the templates" in {
      val obj = full.renderJson(503, "errors.service.in.maintenance", "Maintenance", "err-1")
      obj.toString must not include "maintenance ${message}"
    }
  }
}
