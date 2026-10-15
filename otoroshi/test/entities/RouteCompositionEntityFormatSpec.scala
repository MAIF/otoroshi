package entities

import otoroshi.models.{EntityLocation, HealthCheck, Random, RoundRobin}
import otoroshi.next.models.*
import otoroshi.next.plugins.{ApikeyCalls, OverrideHost}
import otoroshi.next.plugins.api.NgPluginHelper
import play.api.libs.json.*

class RouteCompositionEntityFormatSpec extends EntityFormatSpec {

  // the minimal backend has no format of its own, only a json and a readFromJson
  private val minimalBackendFormat: Format[NgMinimalBackend] =
    Format(Reads(json => JsSuccess(NgMinimalBackend.readFromJson(json))), Writes(_.json))

  private val overrideHost = NgPluginHelper.pluginId[OverrideHost]
  private val apikeyCalls  = NgPluginHelper.pluginId[ApikeyCalls]

  private val emptyFrontendJson = Json.obj(
    "domains"    -> Json.arr(),
    "strip_path" -> true,
    "exact"      -> false,
    "headers"    -> Json.obj(),
    "cookies"    -> Json.obj(),
    "query"      -> Json.obj(),
    "methods"    -> Json.arr()
  )

  private val emptyMinimalBackendJson = Json.obj(
    "targets"        -> Json.arr(),
    "root"           -> "/",
    "rewrite"        -> false,
    "load_balancing" -> Json.obj("type" -> "RoundRobin")
  )

  private val fullFrontend = NgFrontend(
    domains = Seq(NgDomainAndPath("api.oto.tools/users/:id"), NgDomainAndPath("api.oto.tools/people/:id")),
    headers = Map("X-Tenant" -> "acme"),
    query = Map("q" -> "Exists()"),
    cookies = Map("session" -> "Exists()"),
    methods = Seq("GET", "DELETE"),
    stripPath = false,
    exact = true
  )

  private val target1 = NgTarget(id = "target-1", hostname = "users.internal", port = 8443, tls = true, weight = 3, backup = true)
  private val target2 = NgTarget(id = "target-2", hostname = "users-2.internal", port = 8080, tls = false)

  private val fullMinimalBackend = NgMinimalBackend(
    targets = Seq(target1, target2),
    root = "/users",
    rewrite = true,
    loadBalancing = Random
  )

  private val routeInstance = NgPluginInstance(
    plugin = overrideHost,
    debug = true,
    include = Seq("/users/.*"),
    config = NgPluginInstanceConfig(Json.obj("a" -> "b")),
    pluginIndex = Some(PluginIndex(transformRequest = Some(1)))
  )

  private val compositionInstance = NgPluginInstance(plugin = apikeyCalls, config = NgPluginInstanceConfig(Json.obj("strict" -> true)))

  private val minimalRoute = NgMinimalRoute(
    frontend = NgFrontend.empty,
    backend = NgMinimalBackend.empty,
    overridePlugins = false,
    plugins = NgPlugins.empty
  )

  private val fullRoute = NgMinimalRoute(
    frontend = fullFrontend,
    backend = fullMinimalBackend,
    backendRef = None,
    overridePlugins = true,
    plugins = NgPlugins(Seq(routeInstance))
  )

  private val fullClient = NgClientConfig(
    retries = 5,
    maxErrors = 10,
    callTimeout = 5000,
    globalTimeout = 6000,
    customTimeouts = Seq(NgCustomTimeouts(path = "/slow/*", callTimeout = 60000))
  )

  "NgMinimalBackend" should {

    "write the empty minimal backend" in {
      minimalBackendFormat.writes(NgMinimalBackend.empty) mustBe emptyMinimalBackendJson
    }

    "write a minimal backend where every field is set" in {
      minimalBackendFormat.writes(fullMinimalBackend) mustBe Json.obj(
        "targets"        -> Json.arr(target1.json, target2.json),
        "root"           -> "/users",
        "rewrite"        -> true,
        "load_balancing" -> Json.obj("type" -> "Random")
      )
    }

    "round trip the empty minimal backend" in {
      roundTrip(minimalBackendFormat, NgMinimalBackend.empty)
    }

    "round trip a minimal backend where every field is set" in {
      roundTrip(minimalBackendFormat, fullMinimalBackend)
    }

    "keep the order of the targets" in {
      roundTrip(minimalBackendFormat, fullMinimalBackend.copy(targets = Seq(target2, target1))).targets mustBe Seq(target2, target1)
    }

    "read an empty object as the empty minimal backend" in {
      readOk(minimalBackendFormat, Json.obj()) mustBe NgMinimalBackend.empty
    }

    "read a minimal backend that is not an object as the empty one" in {
      readOk(minimalBackendFormat, JsNull) mustBe NgMinimalBackend.empty
      readOk(minimalBackendFormat, JsString("https://users.internal")) mustBe NgMinimalBackend.empty
    }

    "fail to read a missing minimal backend" in {
      a[JsResultException] must be thrownBy NgMinimalBackend.readFrom(Json.obj() \ "backend")
    }

    "not read the single url shortcut of the full backend" in {
      readOk(minimalBackendFormat, Json.obj("url" -> "https://users.internal/users")) mustBe NgMinimalBackend.empty
    }

    "round robin when the load balancing is unknown or not an object" in {
      readOk(minimalBackendFormat, Json.obj("load_balancing" -> Json.obj("type" -> "Unknown"))).loadBalancing mustBe RoundRobin
      readOk(minimalBackendFormat, Json.obj("load_balancing" -> "Random")).loadBalancing mustBe RoundRobin
    }

    "use the root path when the root is not a string" in {
      readOk(minimalBackendFormat, Json.obj("root" -> 1)).root mustBe "/"
    }

    "not rewrite when rewrite is not a boolean" in {
      readOk(minimalBackendFormat, Json.obj("rewrite" -> "true")).rewrite mustBe false
    }

    "become a full backend with a client and a health check" in {
      val hc = HealthCheck(enabled = true, url = "/health")
      fullMinimalBackend.toBackend(fullClient, Some(hc)) mustBe NgBackend(
        targets = Seq(target1, target2),
        root = "/users",
        rewrite = true,
        loadBalancing = Random,
        healthCheck = Some(hc),
        client = fullClient
      )
    }

    "be what a full backend keeps once its client and health check are dropped" in {
      val backend = fullMinimalBackend.toBackend(fullClient, Some(HealthCheck(enabled = true, url = "/health")))
      backend.minimalBackend mustBe fullMinimalBackend
    }

    "list each target once in all its targets" in {
      fullMinimalBackend.copy(targets = Seq(target1, target2, target1)).allTargets mustBe Seq(target1, target2)
    }
  }

  "NgMinimalRoute" should {

    val format = NgMinimalRoute.fmt

    val minimalJson = Json.obj("backend" -> Json.obj())

    "write a minimal route without override_plugins nor plugins" in {
      format.writes(minimalRoute) mustBe Json.obj(
        "frontend"    -> emptyFrontendJson,
        "backend"     -> emptyMinimalBackendJson,
        "backend_ref" -> JsNull
      )
    }

    "write a route where every field is set" in {
      format.writes(fullRoute) mustBe Json.obj(
        "frontend"         -> fullFrontend.json,
        "backend"          -> fullMinimalBackend.json,
        "backend_ref"      -> JsNull,
        "override_plugins" -> true,
        "plugins"          -> Json.arr(routeInstance.json)
      )
    }

    "write the plugins without override_plugins when they do not override" in {
      val json = written(format, fullRoute.copy(overridePlugins = false))
      json.keys must not contain "override_plugins"
      json \ "plugins" mustBe JsDefined(Json.arr(routeInstance.json))
    }

    "write override_plugins without plugins when there is no plugin" in {
      val json = written(format, minimalRoute.copy(overridePlugins = true))
      json \ "override_plugins" mustBe JsDefined(JsTrue)
      json.keys must not contain "plugins"
    }

    "write the backend ref as a string" in {
      written(format, minimalRoute.copy(backendRef = Some("backend_1"))) \ "backend_ref" mustBe JsDefined(JsString("backend_1"))
    }

    "round trip a minimal route" in {
      roundTrip(format, minimalRoute)
    }

    "round trip a route where every field is set" in {
      roundTrip(format, fullRoute)
    }

    "round trip a route whose plugins do not override the composition ones" in {
      roundTrip(format, fullRoute.copy(overridePlugins = false)).overridePlugins mustBe false
    }

    "read a lone backend with every default" in {
      readOk(format, minimalJson) mustBe minimalRoute
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a route without backend nor backend ref" in {
      readError(format, Json.obj("frontend" -> fullFrontend.json))
    }

    "read a json null backend as the empty minimal backend" in {
      readOk(format, Json.obj("backend" -> JsNull)).backend mustBe NgMinimalBackend.empty
    }

    "read a json null backend ref as no backend ref" in {
      readOk(format, minimalJson ++ Json.obj("backend_ref" -> JsNull)).backendRef mustBe None
    }

    "not override the plugins when override_plugins is not a boolean" in {
      readOk(format, minimalJson ++ Json.obj("override_plugins" -> "true")).overridePlugins mustBe false
    }

    "ignore camel case keys" in {
      readOk(format, minimalJson ++ Json.obj("overridePlugins" -> true)).overridePlugins mustBe false
    }

    "read plugins that are not an array as no plugin" in {
      readOk(format, minimalJson ++ Json.obj("plugins" -> Json.obj("plugin" -> overrideHost))).plugins mustBe NgPlugins.empty
    }

    "read a frontend that is not an object as the empty frontend" in {
      readOk(format, minimalJson ++ Json.obj("frontend" -> "api.oto.tools")).frontend mustBe NgFrontend.empty
    }

    "not read a route whose plugins hold a slot without plugin id" in {
      readError(format, minimalJson ++ Json.obj("plugins" -> Json.arr(Json.obj("debug" -> true))))
    }
  }

  "NgRouteComposition" should {

    val format = NgRouteComposition.fmt

    val minimal = NgRouteComposition(
      location = EntityLocation(),
      id = "route-comp_1",
      name = "Comp 1",
      description = "",
      tags = Seq.empty,
      metadata = Map.empty,
      enabled = true,
      debugFlow = false,
      capture = false,
      exportReporting = false,
      routes = Seq.empty,
      client = NgClientConfig.default,
      plugins = NgPlugins.empty
    )

    val full = NgRouteComposition(
      location = customLocation,
      id = "route-comp_full",
      name = "Full comp",
      description = "a composition where every field is set",
      tags = Seq("prod", "eu"),
      metadata = Map("owner" -> "ops"),
      enabled = false,
      debugFlow = true,
      capture = true,
      exportReporting = true,
      groups = Seq("group-1", "group-2"),
      routes = Seq(fullRoute, minimalRoute),
      client = fullClient,
      plugins = NgPlugins(Seq(compositionInstance))
    )

    val minimalJson = Json.obj("id" -> "route-comp_1", "name" -> "Comp 1")

    "write a minimal composition" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"             -> defaultLocationJson,
        "id"               -> "route-comp_1",
        "name"             -> "Comp 1",
        "description"      -> "",
        "tags"             -> Json.arr(),
        "metadata"         -> Json.obj(),
        "enabled"          -> true,
        "debug_flow"       -> false,
        "export_reporting" -> false,
        "capture"          -> false,
        "groups"           -> Json.arr("default"),
        "routes"           -> Json.arr(),
        "client"           -> NgClientConfig.default.json,
        "plugins"          -> Json.arr()
      )
    }

    "write the same keys whatever the fields set" in {
      writtenKeys(format, full) mustBe writtenKeys(format, minimal)
    }

    "write a composition where every field is set" in {
      val json = written(format, full)
      json \ "_loc" mustBe JsDefined(customLocationJson)
      json \ "enabled" mustBe JsDefined(JsFalse)
      json \ "debug_flow" mustBe JsDefined(JsTrue)
      json \ "capture" mustBe JsDefined(JsTrue)
      json \ "export_reporting" mustBe JsDefined(JsTrue)
      json \ "groups" mustBe JsDefined(Json.arr("group-1", "group-2"))
      json \ "routes" mustBe JsDefined(Json.arr(fullRoute.json, minimalRoute.json))
      json \ "client" mustBe JsDefined(fullClient.json)
      json \ "plugins" mustBe JsDefined(Json.arr(compositionInstance.json))
    }

    "round trip a minimal composition" in {
      roundTrip(format, minimal)
    }

    "round trip a composition where every field is set" in {
      roundTrip(format, full)
    }

    "keep the order of the routes" in {
      roundTrip(format, full.copy(routes = Seq(minimalRoute, fullRoute))).routes mustBe Seq(minimalRoute, fullRoute)
    }

    "round trip a composition without any group" in {
      roundTrip(format, full.copy(groups = Seq.empty)).groups mustBe empty
    }

    "round trip a composition in a custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read a composition without _loc in the default location" in {
      readOk(format, minimalJson).location mustBe EntityLocation()
    }

    "read a lone id and name with every default" in {
      readOk(format, minimalJson) mustBe minimal
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a composition without id" in {
      readError(format, minimalJson - "id")
    }

    "not read a composition without name" in {
      readError(format, minimalJson - "name")
    }

    "not read an id that is not a string" in {
      readError(format, minimalJson ++ Json.obj("id" -> 1))
    }

    "drop the routes that cannot be read and keep the others" in {
      val json = minimalJson ++ Json.obj(
        "routes" -> Json.arr(Json.obj("frontend" -> fullFrontend.json), fullRoute.json, JsNull, minimalRoute.json)
      )
      readOk(format, json).routes mustBe Seq(fullRoute, minimalRoute)
    }

    "read routes that are not an array as no route" in {
      readOk(format, minimalJson ++ Json.obj("routes" -> fullRoute.json)).routes mustBe empty
    }

    "read a client that is not an object as the default client" in {
      readOk(format, minimalJson ++ Json.obj("client" -> "fast")).client mustBe NgClientConfig.default
    }

    "use the default value of a client field that has a wrong type" in {
      val back = readOk(format, minimalJson ++ Json.obj("client" -> Json.obj("retries" -> "3", "max_errors" -> 7)))
      back.client.retries mustBe 1
      back.client.maxErrors mustBe 7
    }

    "read plugins that are not an array as no plugin" in {
      readOk(format, minimalJson ++ Json.obj("plugins" -> Json.obj("plugin" -> apikeyCalls))).plugins mustBe NgPlugins.empty
    }

    "not read a composition whose plugins hold a slot without plugin id" in {
      readError(format, minimalJson ++ Json.obj("plugins" -> Json.arr(Json.obj("enabled" -> true))))
    }

    "be enabled when enabled is not a boolean" in {
      readOk(format, minimalJson ++ Json.obj("enabled" -> "false")).enabled mustBe true
    }

    "not debug, capture nor export reporting when the flags are not booleans" in {
      val back = readOk(format, minimalJson ++ Json.obj("debug_flow" -> 1, "capture" -> "true", "export_reporting" -> "yes"))
      back.debugFlow mustBe false
      back.capture mustBe false
      back.exportReporting mustBe false
    }

    "be in the default group when groups is a single string" in {
      readOk(format, minimalJson ++ Json.obj("groups" -> "group-1")).groups mustBe Seq("default")
    }

    "ignore tags given as a single string and metadata holding a non string value" in {
      val back = readOk(format, minimalJson ++ Json.obj("tags" -> "prod", "metadata" -> Json.obj("a" -> "b", "n" -> 1)))
      back.tags mustBe empty
      back.metadata mustBe Map.empty
    }

    "use an empty description when the description is not a string" in {
      readOk(format, minimalJson ++ Json.obj("description" -> 42)).description mustBe ""
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "route-comp_full", "Full comp", "a composition where every field is set", Seq("prod", "eu"), Map("owner" -> "ops"))
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "be built from its json" in {
      NgRouteComposition.fromJsons(full.json) mustBe full
    }

    "fail to be built from a json without id" in {
      a[Throwable] must be thrownBy NgRouteComposition.fromJsons(minimalJson - "id")
    }

    "build empty compositions with a fresh id each, without route" in {
      val comp = NgRouteComposition.empty
      comp.id must startWith("route_")
      NgRouteComposition.empty.id must not be comp.id
      comp.routes mustBe empty
      comp.client mustBe NgClientConfig.default
      comp.enabled mustBe true
    }

    "round trip the empty composition" in {
      roundTrip(format, NgRouteComposition.empty)
    }

    "give no route when disabled" in {
      full.toRoutes mustBe empty
    }

    "give one route per route of the composition, in order" in {
      val routes = full.copy(enabled = true).toRoutes
      routes.map(_.frontend) mustBe Seq(fullFrontend, NgFrontend.empty)
    }

    "give its routes the id, location and settings of the composition" in {
      val route = full.copy(enabled = true).toRoutes.head
      route.id mustBe "route-comp_full"
      route.location mustBe customLocation
      route.description mustBe "a composition where every field is set"
      route.tags mustBe Seq("prod", "eu")
      route.enabled mustBe true
      route.debugFlow mustBe true
      route.capture mustBe true
      route.exportReporting mustBe true
      route.groups mustBe Seq("group-1", "group-2")
      route.apiRef mustBe None
      route.boundListeners mustBe empty
    }

    "name its routes after the composition, the methods and the paths" in {
      full.copy(enabled = true).toRoutes.map(_.name) mustBe Seq("Full comp - GET, DELETE - /users/:id, /people/:id", "Full comp -  - ")
    }

    "add the original and cacheable route ids to the metadata of its routes" in {
      full.copy(enabled = true).toRoutes.map(_.metadata) mustBe Seq(
        Map(
          "owner"                            -> "ops",
          "otoroshi-core-original-route-id"  -> "route-comp_full",
          "otoroshi-core-cacheable-route-id" -> "route-comp_full-0"
        ),
        Map(
          "owner"                            -> "ops",
          "otoroshi-core-original-route-id"  -> "route-comp_full",
          "otoroshi-core-cacheable-route-id" -> "route-comp_full-1"
        )
      )
    }

    "override metadata of the composition holding the route ids" in {
      val comp = minimal.copy(metadata = Map("otoroshi-core-original-route-id" -> "other"), routes = Seq(minimalRoute))
      comp.toRoutes.head.metadata("otoroshi-core-original-route-id") mustBe "route-comp_1"
    }

    "make every route of the composition cacheable under the composition id" in {
      full.copy(enabled = true).toRoutes.map(_.cacheableId) mustBe Seq("route-comp_full", "route-comp_full")
    }

    "give its routes the composition client and no health check" in {
      val route = full.copy(enabled = true).toRoutes.head
      route.backend mustBe NgBackend(Seq(target1, target2), "/users", true, Random, None, fullClient)
    }

    "keep the backend ref of its routes" in {
      val comp = minimal.copy(routes = Seq(minimalRoute.copy(backendRef = Some("backend_1"))))
      comp.toRoutes.head.backendRef mustBe Some("backend_1")
    }

    "only give its routes their own plugins when they override the composition ones" in {
      full.copy(enabled = true).toRoutes.head.plugins mustBe NgPlugins(Seq(routeInstance))
    }

    "append the composition plugins after the plugins of a route that does not override them" in {
      val comp = full.copy(enabled = true, routes = Seq(fullRoute.copy(overridePlugins = false), minimalRoute))
      comp.toRoutes.map(_.plugins) mustBe Seq(
        NgPlugins(Seq(routeInstance, compositionInstance)),
        NgPlugins(Seq(compositionInstance))
      )
    }
  }
}
