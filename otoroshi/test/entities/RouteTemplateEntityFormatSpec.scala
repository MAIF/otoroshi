package entities

import otoroshi.models.{EntityLocation, HealthCheck, RoundRobin, TeamId, TenantId}
import otoroshi.next.models.*
import play.api.libs.json.*

class RouteTemplateEntityFormatSpec extends EntityFormatSpec {

  private val routeLocation = EntityLocation(TenantId("tenant-2"), Seq(TeamId("team-3")))

  private val route = NgRoute(
    location = routeLocation,
    id = "route-1",
    name = "users route",
    description = "the users route",
    tags = Seq("users"),
    metadata = Map("owner" -> "team-users"),
    enabled = false,
    debugFlow = true,
    capture = true,
    exportReporting = true,
    groups = Seq("group-1"),
    boundListeners = Seq("listener-1"),
    frontend = NgFrontend(
      domains = Seq(NgDomainAndPath("users.oto.tools/api")),
      headers = Map("x-env" -> "prod"),
      query = Map.empty,
      cookies = Map.empty,
      methods = Seq("GET"),
      stripPath = false,
      exact = true
    ),
    backend = NgBackend(
      targets = Seq(NgTarget(id = "target-1", hostname = "users.internal", port = 8080, tls = false)),
      root = "/",
      rewrite = false,
      loadBalancing = RoundRobin,
      // a backend without health check reads back with a disabled one, see NgBackend
      healthCheck = Some(HealthCheck(enabled = true, url = "/health")),
      client = NgClientConfig.default
    ),
    backendRef = None,
    apiRef = Some(RouteApiRef("api-1")),
    plugins = NgPlugins(Seq(NgPluginInstance(plugin = "cp:otoroshi.next.plugins.OverrideHost")))
  )

  private val full = RouteTemplate(
    location = customLocation,
    id = "route-template-1",
    name = "users template",
    description = "a template for the users routes",
    tags = Seq("template", "users"),
    metadata = Map("category" -> "users"),
    route = route
  )

  private val minimalJson = Json.obj(
    "_loc"        -> defaultLocationJson,
    "id"          -> "route-template-1",
    "name"        -> "template",
    "description" -> ""
  )

  "RouteTemplate" should {

    val format = RouteTemplate.format

    "write a route template" in {
      format.writes(full) mustBe Json.obj(
        "_loc"        -> customLocationJson,
        "id"          -> "route-template-1",
        "name"        -> "users template",
        "description" -> "a template for the users routes",
        "tags"        -> Json.arr("template", "users"),
        "metadata"    -> Json.obj("category" -> "users"),
        "route"       -> route.json
      )
    }

    "write its route with the route format" in {
      (written(format, full) \ "route").get mustBe NgRoute.fmt.writes(route)
    }

    "round trip a route template where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a route template without tags nor metadata" in {
      roundTrip(format, full.copy(tags = Seq.empty, metadata = Map.empty))
    }

    "round trip its custom location" in {
      roundTrip(format, full).location mustBe customLocation
    }

    "keep the location of its route apart from its own" in {
      val back = roundTrip(format, full.copy(location = EntityLocation()))
      back.location mustBe EntityLocation()
      back.route.location mustBe routeLocation
    }

    "keep the api reference of its route" in {
      roundTrip(format, full).route.apiRef mustBe Some(RouteApiRef("api-1"))
    }

    "draw a random id by default" in {
      val a = RouteTemplate(location = EntityLocation(), name = "t", description = "", tags = Seq.empty, metadata = Map.empty, route = route)
      val b = RouteTemplate(location = EntityLocation(), name = "t", description = "", tags = Seq.empty, metadata = Map.empty, route = route)
      a.id must not be empty
      a.id must not be b.id
    }

    "read a minimal route template with an empty route" in {
      val back = readOk(format, minimalJson)
      back.location mustBe EntityLocation()
      back.tags mustBe empty
      back.metadata mustBe empty
      back.route.name mustBe "empty route"
      back.route.id must startWith("route_")
    }

    "read a route template without route with a new empty route each time" in {
      readOk(format, minimalJson).route.id must not be readOk(format, minimalJson).route.id
    }

    "read a route that cannot be read as an empty route" in {
      val back = readOk(format, minimalJson ++ Json.obj("route" -> Json.obj("id" -> "route-1")))
      back.route.id must not be "route-1"
      back.route.name mustBe "empty route"
    }

    "read a route that is not an object as an empty route" in {
      readOk(format, minimalJson ++ Json.obj("route" -> "route-1")).route.name mustBe "empty route"
    }

    "read a route template without _loc in the default location" in {
      readOk(format, minimalJson - "_loc").location mustBe EntityLocation()
    }

    "read the location from the _loc key" in {
      readOk(format, minimalJson ++ Json.obj("_loc" -> customLocationJson)).location mustBe customLocation
    }

    "read a null location as the default location" in {
      readOk(format, minimalJson ++ Json.obj("_loc" -> JsNull)).location mustBe EntityLocation()
    }

    "ignore the location key it used to be written under" in {
      readOk(format, (minimalJson - "_loc") ++ Json.obj("location" -> customLocationJson)).location mustBe EntityLocation()
    }

    "read its location from _loc when the location key says something else" in {
      val json = minimalJson ++ Json.obj("_loc" -> customLocationJson, "location" -> defaultLocationJson)
      readOk(format, json).location mustBe customLocation
    }

    "store the location the admin api checks the rights on" in {
      val json = minimalJson ++ Json.obj("_loc" -> defaultLocationJson, "location" -> customLocationJson)
      readOk(format, json).location mustBe EntityLocation.readFromKey(json)
    }

    "not write the location key anymore" in {
      writtenKeys(format, full) must not contain "location"
    }

    "expose its location to the json based rights checks" in {
      EntityLocation.readFromKey(full.json) mustBe customLocation
    }

    "read a location that is not an object as the default location" in {
      readOk(format, minimalJson ++ Json.obj("_loc" -> "tenant-1")).location mustBe EntityLocation()
    }

    "not read a route template without id" in {
      readError(format, minimalJson - "id")
    }

    "not read a route template without name" in {
      readError(format, minimalJson - "name")
    }

    "not read a route template without description" in {
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
      readOk(format, minimalJson ++ Json.obj("tags" -> "users")).tags mustBe empty
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "route-template-1", "users template", "a template for the users routes", Seq("template", "users"), Map("category" -> "users"))
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }
  }
}
