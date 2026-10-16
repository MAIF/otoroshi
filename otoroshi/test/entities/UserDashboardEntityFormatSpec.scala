package entities

import otoroshi.models.EntityLocation
import otoroshi.next.analytics.models.{UserDashboard, Widget}
import play.api.libs.json.*

class UserDashboardEntityFormatSpec extends EntityFormatSpec {

  val minimalWidget: Widget = Widget(id = "widget-1", title = "", query = "requests_ts")

  val fullWidget: Widget = Widget(
    id = "widget-2",
    title = "Top routes",
    query = "top_routes",
    params = Json.obj("top_n" -> 10, "filters" -> Json.obj("route_id" -> Json.arr("r1", "r2"))),
    `type` = "bar",
    width = 6,
    height = 3,
    options = Json.obj("stacked" -> true, "colors" -> Json.arr("#ff0000", "#00ff00"))
  )

  "Widget" should {

    val format = Widget.format

    "be an empty 4x2 line chart by default" in {
      minimalWidget.params mustBe Json.obj()
      minimalWidget.`type` mustBe "line"
      minimalWidget.width mustBe 4
      minimalWidget.height mustBe 2
      minimalWidget.options mustBe Json.obj()
    }

    "write a minimal widget" in {
      format.writes(minimalWidget) mustBe Json.obj(
        "id"      -> "widget-1",
        "title"   -> "",
        "query"   -> "requests_ts",
        "params"  -> Json.obj(),
        "type"    -> "line",
        "width"   -> 4,
        "height"  -> 2,
        "options" -> Json.obj()
      )
    }

    "write a widget where every field is set" in {
      format.writes(fullWidget) mustBe Json.obj(
        "id"      -> "widget-2",
        "title"   -> "Top routes",
        "query"   -> "top_routes",
        "params"  -> Json.obj("top_n" -> 10, "filters" -> Json.obj("route_id" -> Json.arr("r1", "r2"))),
        "type"    -> "bar",
        "width"   -> 6,
        "height"  -> 3,
        "options" -> Json.obj("stacked" -> true, "colors" -> Json.arr("#ff0000", "#00ff00"))
      )
    }

    "round trip a minimal widget" in {
      roundTrip(format, minimalWidget)
    }

    "round trip a widget where every field is set" in {
      roundTrip(format, fullWidget)
    }

    "read a lone query as a widget with a generated id" in {
      val back = readOk(format, Json.obj("query" -> "requests_ts"))
      back.id must not be empty
      back mustBe minimalWidget.copy(id = back.id)
    }

    "generate a new id at each read of a widget without id" in {
      val json = Json.obj("query" -> "requests_ts")
      readOk(format, json).id must not be readOk(format, json).id
    }

    "generate an id for an empty id" in {
      readOk(format, Json.obj("id" -> "", "query" -> "q")).id must not be empty
    }

    "generate an id for an id that is not a string" in {
      readOk(format, Json.obj("id" -> 42, "query" -> "q")).id must not be "42"
    }

    "not read a widget without query" in {
      readError(format, written(format, fullWidget) - "query")
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a query that is not a string" in {
      readError(format, written(format, fullWidget) ++ Json.obj("query" -> Json.obj("id" -> "top_routes")))
    }

    "read a widget without title with an empty title" in {
      readOk(format, written(format, fullWidget) - "title").title mustBe ""
    }

    "ignore a title that is not a string" in {
      readOk(format, written(format, fullWidget) ++ Json.obj("title" -> 1)).title mustBe ""
    }

    "read a widget without type as a line chart" in {
      readOk(format, written(format, fullWidget) - "type").`type` mustBe "line"
    }

    "keep a type it does not know" in {
      roundTrip(format, fullWidget.copy(`type` = "sankey")).`type` mustBe "sankey"
    }

    "read a widget without size with the default 4x2 size" in {
      val back = readOk(format, written(format, fullWidget) - "width" - "height")
      back.width mustBe 4
      back.height mustBe 2
    }

    "ignore a size written as strings" in {
      val back = readOk(format, written(format, fullWidget) ++ Json.obj("width" -> "6", "height" -> "3"))
      back.width mustBe 4
      back.height mustBe 2
    }

    "ignore a decimal width" in {
      readOk(format, written(format, fullWidget) ++ Json.obj("width" -> 4.5)).width mustBe 4
    }

    "keep a zero or negative size" in {
      val back = roundTrip(format, fullWidget.copy(width = 0, height = -1))
      back.width mustBe 0
      back.height mustBe -1
    }

    "read a widget without params nor options with empty ones" in {
      val back = readOk(format, written(format, fullWidget) - "params" - "options")
      back.params mustBe Json.obj()
      back.options mustBe Json.obj()
    }

    "ignore params that are not an object" in {
      readOk(format, written(format, fullWidget) ++ Json.obj("params" -> Json.arr("top_n", 10))).params mustBe Json.obj()
    }

    "ignore options that are not an object" in {
      readOk(format, written(format, fullWidget) ++ Json.obj("options" -> "stacked")).options mustBe Json.obj()
    }

    "expose through json what the format writes" in {
      fullWidget.json mustBe format.writes(fullWidget)
    }
  }

  "UserDashboard" should {

    val format = UserDashboard.format

    val minimal = UserDashboard(
      location = EntityLocation(),
      id = "dashboard-1",
      name = "Dashboard 1",
      description = "",
      tags = Seq.empty,
      metadata = Map.empty,
      enabled = true,
      widgets = Seq.empty,
      defaults = Json.obj()
    )

    val full = UserDashboard(
      location = customLocation,
      id = "dashboard-2",
      name = "Traffic",
      description = "the traffic of the prod routes",
      tags = Seq("prod", "traffic"),
      metadata = Map("owner" -> "ops", "ticket" -> "42"),
      enabled = false,
      widgets = Seq(fullWidget, minimalWidget),
      defaults = Json.obj("from" -> "now-1h", "to" -> "now", "filters" -> Json.obj("route_id" -> "r1"))
    )

    "write a minimal dashboard" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"        -> defaultLocationJson,
        "id"          -> "dashboard-1",
        "name"        -> "Dashboard 1",
        "description" -> "",
        "tags"        -> Json.arr(),
        "metadata"    -> Json.obj(),
        "enabled"     -> true,
        "widgets"     -> Json.arr(),
        "defaults"    -> Json.obj()
      )
    }

    "write a dashboard where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "_loc"        -> customLocationJson,
        "id"          -> "dashboard-2",
        "name"        -> "Traffic",
        "description" -> "the traffic of the prod routes",
        "tags"        -> Json.arr("prod", "traffic"),
        "metadata"    -> Json.obj("owner" -> "ops", "ticket" -> "42"),
        "enabled"     -> false,
        "widgets"     -> Json.arr(fullWidget.json, minimalWidget.json),
        "defaults"    -> Json.obj("from" -> "now-1h", "to" -> "now", "filters" -> Json.obj("route_id" -> "r1"))
      )
    }

    "round trip a minimal dashboard" in {
      roundTrip(format, minimal)
    }

    "round trip a dashboard where every field is set" in {
      roundTrip(format, full)
    }

    "round trip an enabled dashboard" in {
      roundTrip(format, full.copy(enabled = true)).enabled mustBe true
    }

    "round trip a name and a description with quotes and unicode" in {
      roundTrip(format, full.copy(name = "l'équipe \"ops\"", description = "multi\nline"))
    }

    "keep the order of the widgets" in {
      roundTrip(format, full.copy(widgets = Seq(minimalWidget, fullWidget))).widgets.map(_.id) mustBe Seq("widget-1", "widget-2")
    }

    "keep widgets sharing the same id" in {
      roundTrip(format, full.copy(widgets = Seq(minimalWidget, minimalWidget))).widgets.size mustBe 2
    }

    "keep the order of the tags" in {
      roundTrip(format, full.copy(tags = Seq("z", "a", "m"))).tags mustBe Seq("z", "a", "m")
    }

    "read an empty object as a new enabled dashboard" in {
      val back = readOk(format, Json.obj())
      back mustBe minimal.copy(id = back.id, name = "")
    }

    "generate a dashboard id for a dashboard without id" in {
      val id = readOk(format, Json.obj("name" -> "D")).id
      // a single uuid after the prefix
      id must startWith("dashboard_")
      id.split("_").length mustBe 2
    }

    "generate a new id at each read of a dashboard without id" in {
      readOk(format, Json.obj()).id must not be readOk(format, Json.obj()).id
    }

    "generate an id for an empty id" in {
      readOk(format, written(format, full) ++ Json.obj("id" -> "")).id must startWith("dashboard_")
    }

    "generate an id for an id that is not a string" in {
      readOk(format, written(format, full) ++ Json.obj("id" -> 42)).id must startWith("dashboard_")
    }

    "read a json null as a new dashboard" in {
      val back = readOk(format, JsNull)
      back.id must startWith("dashboard_")
      back.widgets mustBe empty
    }

    "read a json string as a new dashboard" in {
      readOk(format, JsString("dashboard-1")).id must startWith("dashboard_")
    }

    "read a dashboard without name nor description with empty ones" in {
      val back = readOk(format, written(format, full) - "name" - "description")
      back.name mustBe ""
      back.description mustBe ""
    }

    "ignore a name that is not a string" in {
      readOk(format, written(format, full) ++ Json.obj("name" -> Json.arr("Traffic"))).name mustBe ""
    }

    "read a dashboard without enabled flag as enabled" in {
      readOk(format, written(format, full) - "enabled").enabled mustBe true
    }

    "ignore an enabled flag that is not a boolean" in {
      readOk(format, written(format, full) ++ Json.obj("enabled" -> "false")).enabled mustBe true
    }

    "read a dashboard without widgets with no widget" in {
      readOk(format, written(format, full) - "widgets").widgets mustBe empty
    }

    "skip the widgets that cannot be read" in {
      val widgets = Json.arr(fullWidget.json, Json.obj("id" -> "no-query"), "widget", minimalWidget.json)
      readOk(format, written(format, full) ++ Json.obj("widgets" -> widgets)).widgets mustBe Seq(fullWidget, minimalWidget)
    }

    "give an id to the widgets without id" in {
      val back = readOk(format, written(format, full) ++ Json.obj("widgets" -> Json.arr(Json.obj("query" -> "q"))))
      back.widgets.size mustBe 1
      back.widgets.head.id must not be empty
    }

    "ignore widgets that are not an array" in {
      readOk(format, written(format, full) ++ Json.obj("widgets" -> fullWidget.json)).widgets mustBe empty
    }

    "read a dashboard without defaults with empty defaults" in {
      readOk(format, written(format, full) - "defaults").defaults mustBe Json.obj()
    }

    "ignore defaults that are not an object" in {
      readOk(format, written(format, full) ++ Json.obj("defaults" -> Json.arr("now-1h"))).defaults mustBe Json.obj()
    }

    "read a dashboard without tags with no tags" in {
      readOk(format, written(format, full) - "tags").tags mustBe empty
    }

    "read a dashboard without metadata with no metadata" in {
      readOk(format, written(format, full) - "metadata").metadata mustBe empty
    }

    "ignore tags given as a single string" in {
      readOk(format, written(format, full) ++ Json.obj("tags" -> "prod")).tags mustBe empty
    }

    "ignore metadata holding a non string value" in {
      readOk(format, written(format, full) ++ Json.obj("metadata" -> Json.obj("a" -> "b", "n" -> 1))).metadata mustBe empty
    }

    "ignore unknown keys" in {
      readOk(format, written(format, full) ++ Json.obj("refresh" -> 30, "foo" -> "bar")) mustBe full
    }

    "round trip a custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read a dashboard without _loc in the default location" in {
      readOk(format, written(format, full) - "_loc").location mustBe EntityLocation()
    }

    "read a dashboard whose _loc is not an object in the default location" in {
      readOk(format, written(format, full) ++ Json.obj("_loc" -> "tenant-1")).location mustBe EntityLocation()
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(
        full,
        "dashboard-2",
        "Traffic",
        "the traffic of the prod routes",
        Seq("prod", "traffic"),
        Map("owner" -> "ops", "ticket" -> "42")
      )
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }
  }
}
