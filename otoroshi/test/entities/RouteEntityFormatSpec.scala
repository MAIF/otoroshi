package entities

import otoroshi.models.{EntityLocation, HealthCheck, HttpProtocols, Random}
import otoroshi.next.models.*
import otoroshi.next.plugins.{ApikeyCalls, OverrideHost}
import otoroshi.next.plugins.api.NgPluginHelper
import play.api.libs.json.*
import play.api.test.FakeRequest

class RouteEntityFormatSpec extends EntityFormatSpec {

  // the frontend, the plugin instances and the plugins chain have no format of their own, only a json and a readFrom
  private val frontendFormat: Format[NgFrontend] =
    Format(Reads(json => JsSuccess(NgFrontend.readFrom(JsDefined(json)))), Writes(_.json))

  private val pluginInstanceFormat: Format[NgPluginInstance] =
    Format(Reads(json => JsSuccess(NgPluginInstance.readFrom(json))), Writes(_.json))

  private val pluginsFormat: Format[NgPlugins] =
    Format(Reads(json => JsSuccess(NgPlugins.readFrom(JsDefined(json)))), Writes(_.json))

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

  private val fullFrontend = NgFrontend(
    domains = Seq(NgDomainAndPath("api.oto.tools/v1"), NgDomainAndPath("*.api.oto.tools")),
    headers = Map("X-Tenant" -> "acme", "X-Version" -> "Regex(v[0-9]+)"),
    query = Map("debug" -> "Exists()"),
    cookies = Map("session" -> "NotDefined()"),
    methods = Seq("GET", "POST"),
    stripPath = false,
    exact = true
  )

  // every field of the plugin index
  private val fullIndex = PluginIndex(
    sink = Some(1),
    preRoute = Some(2),
    validateAccess = Some(3),
    transformRequest = Some(4.5),
    transformResponse = Some(5),
    matchRoute = Some(6),
    handlesTunnel = Some(7),
    handlesWebsocket = Some(8),
    handlesWebsocketBackend = Some(9),
    callBackend = Some(10)
  )

  private val fullInstance = NgPluginInstance(
    plugin = overrideHost,
    enabled = false,
    debug = true,
    include = Seq("/api/.*"),
    exclude = Seq("/api/private/.*"),
    config = NgPluginInstanceConfig(Json.obj("a" -> 1, "nested" -> Json.obj("b" -> Json.arr(true, "c")))),
    pluginIndex = Some(fullIndex),
    boundListeners = Seq("listener-1")
  )

  private val fullPlugins = NgPlugins(Seq(fullInstance, NgPluginInstance(plugin = apikeyCalls)))

  private val fullBackend = NgBackend(
    targets = Seq(
      NgTarget(
        id = "target-1",
        hostname = "backend.internal",
        port = 8443,
        tls = true,
        weight = 2,
        protocol = HttpProtocols.HTTP_2_0,
        ipAddress = Some("10.0.0.1"),
        tlsConfig = NgTlsConfig(certs = Seq("cert-1"), enabled = true),
        backup = true
      ),
      NgTarget(id = "target-2", hostname = "backup.internal", port = 8080, tls = false)
    ),
    root = "/api",
    rewrite = true,
    loadBalancing = Random,
    healthCheck = Some(HealthCheck(enabled = true, url = "/health", timeout = 1000, healthyStatuses = Seq(200))),
    client = NgClientConfig(retries = 3, callTimeout = 5000)
  )

  "RouteApiRef" should {

    val format = RouteApiRef.format

    "be written as a plain string" in {
      format.writes(RouteApiRef("api_1")) mustBe JsString("api_1")
    }

    "round trip" in {
      roundTrip(format, RouteApiRef("api_1"))
    }

    "not read an empty string" in {
      readError(format, JsString(""))
    }

    "not read an object" in {
      readError(format, Json.obj("id" -> "api_1"))
    }

    "be mirrored under the Otoroshi-Api-Ref metadata key" in {
      RouteApiRef.metadataKey mustBe "Otoroshi-Api-Ref"
    }
  }

  "NgDomainAndPath" should {

    "be written as its raw value" in {
      NgDomainAndPath("api.oto.tools/v1").json mustBe JsString("api.oto.tools/v1")
    }

    "split a domain and a path" in {
      val dp = NgDomainAndPath("api.oto.tools/v1/users")
      dp.domain mustBe "api.oto.tools"
      dp.path mustBe "/v1/users"
    }

    "give the root path to a lone domain" in {
      NgDomainAndPath("api.oto.tools").path mustBe "/"
    }

    "give the root path to a domain ending with a slash, and know it ends with a slash" in {
      val dp = NgDomainAndPath("api.oto.tools/")
      dp.path mustBe "/"
      dp.pathEndsWithSlash mustBe true
      NgDomainAndPath("api.oto.tools").pathEndsWithSlash mustBe false
    }

    "give an empty domain to a lone path" in {
      val dp = NgDomainAndPath("/v1")
      dp.domain mustBe ""
      dp.path mustBe "/v1"
    }

    "lower case the domain but not the path" in {
      val dp = NgDomainAndPath("API.Oto.Tools/V1")
      dp.domainLowerCase mustBe "api.oto.tools"
      dp.domain mustBe "API.Oto.Tools"
      dp.path mustBe "/V1"
    }
  }

  "NgFrontend" should {

    "write the empty frontend" in {
      frontendFormat.writes(NgFrontend.empty) mustBe emptyFrontendJson
    }

    "strip the path and match non exact paths by default" in {
      NgFrontend.empty.stripPath mustBe true
      NgFrontend.empty.exact mustBe false
    }

    "write a frontend where every field is set" in {
      frontendFormat.writes(fullFrontend) mustBe Json.obj(
        "domains"    -> Json.arr("api.oto.tools/v1", "*.api.oto.tools"),
        "strip_path" -> false,
        "exact"      -> true,
        "headers"    -> Json.obj("X-Tenant" -> "acme", "X-Version" -> "Regex(v[0-9]+)"),
        "cookies"    -> Json.obj("session" -> "NotDefined()"),
        "query"      -> Json.obj("debug" -> "Exists()"),
        "methods"    -> Json.arr("GET", "POST")
      )
    }

    "round trip the empty frontend" in {
      roundTrip(frontendFormat, NgFrontend.empty)
    }

    "round trip a frontend where every field is set" in {
      roundTrip(frontendFormat, fullFrontend)
    }

    "keep the order of the domains and the methods" in {
      val back = roundTrip(
        frontendFormat,
        fullFrontend.copy(domains = Seq(NgDomainAndPath("z.oto.tools"), NgDomainAndPath("a.oto.tools")), methods = Seq("PUT", "GET"))
      )
      back.domains.map(_.raw) mustBe Seq("z.oto.tools", "a.oto.tools")
      back.methods mustBe Seq("PUT", "GET")
    }

    "read a missing frontend as the empty frontend" in {
      NgFrontend.readFrom(Json.obj() \ "frontend") mustBe NgFrontend.empty
    }

    "read an empty object as the empty frontend" in {
      readOk(frontendFormat, Json.obj()) mustBe NgFrontend.empty
    }

    "read a frontend that is not an object as the empty frontend" in {
      readOk(frontendFormat, JsString("api.oto.tools")) mustBe NgFrontend.empty
      readOk(frontendFormat, JsNull) mustBe NgFrontend.empty
    }

    "read the legacy single domain key" in {
      readOk(frontendFormat, Json.obj("domain" -> "api.oto.tools/v1")).domains mustBe Seq(NgDomainAndPath("api.oto.tools/v1"))
    }

    "prefer the legacy single domain over the domains array" in {
      readOk(
        frontendFormat,
        Json.obj("domain" -> "legacy.oto.tools", "domains" -> Json.arr("a.oto.tools", "b.oto.tools"))
      ).domains mustBe Seq(NgDomainAndPath("legacy.oto.tools"))
    }

    "write the domains array, not the legacy single domain" in {
      val json = frontendFormat.writes(readOk(frontendFormat, Json.obj("domain" -> "legacy.oto.tools"))).as[JsObject]
      json.keys must not contain "domain"
      (json \ "domains").as[JsArray] mustBe Json.arr("legacy.oto.tools")
    }

    "fall back to the domains array when the single domain is not a string" in {
      readOk(frontendFormat, Json.obj("domain" -> 42, "domains" -> Json.arr("a.oto.tools"))).domains mustBe Seq(
        NgDomainAndPath("a.oto.tools")
      )
    }

    "ignore domains given as a single string" in {
      readOk(frontendFormat, Json.obj("domains" -> "a.oto.tools")).domains mustBe empty
    }

    "strip the path when strip_path is not a boolean" in {
      readOk(frontendFormat, Json.obj("strip_path" -> "false")).stripPath mustBe true
    }

    "not be exact when exact is not a boolean" in {
      readOk(frontendFormat, Json.obj("exact" -> 1)).exact mustBe false
    }

    "ignore headers holding a non string value" in {
      readOk(frontendFormat, Json.obj("headers" -> Json.obj("a" -> "b", "n" -> 1))).headers mustBe Map.empty
    }

    "ignore query and cookies that are not objects" in {
      val back = readOk(frontendFormat, Json.obj("query" -> Json.arr("a"), "cookies" -> "session"))
      back.query mustBe Map.empty
      back.cookies mustBe Map.empty
    }

    "ignore methods given as a single string" in {
      readOk(frontendFormat, Json.obj("methods" -> "GET")).methods mustBe empty
    }

    "keep the case of the methods" in {
      readOk(frontendFormat, Json.obj("methods" -> Json.arr("get"))).methods mustBe Seq("get")
    }

    "give its first domain as tunnel url" in {
      fullFrontend.tunnelUrl mustBe "api.oto.tools/v1"
    }

    "have no tunnel url without domain" in {
      a[NoSuchElementException] must be thrownBy NgFrontend.empty.tunnelUrl
    }
  }

  "PluginIndex" should {

    val format = PluginIndex.format

    "write an empty index as an empty object" in {
      format.writes(PluginIndex()) mustBe Json.obj()
    }

    "write every phase under its snake case key" in {
      format.writes(fullIndex) mustBe Json.obj(
        "sink"               -> 1,
        "pre_route"          -> 2,
        "validate_access"    -> 3,
        "transform_request"  -> 4.5,
        "transform_response" -> 5,
        "match_route"        -> 6,
        "handles_tunnel"     -> 7,
        "handles_websocket"  -> 8,
        "handles_websocket_backend" -> 9,
        "call_backend"       -> 10
      )
    }

    "read the websocket backend and call backend phases" in {
      readOk(format, Json.obj("handles_websocket_backend" -> 9, "call_backend" -> 10.5)) mustBe PluginIndex(
        handlesWebsocketBackend = Some(9),
        callBackend = Some(10.5)
      )
    }

    "keep the call backend phase of a plugin on a round trip" in {
      roundTrip(format, PluginIndex(callBackend = Some(2))).callBackend mustBe Some(2)
    }

    "keep the websocket backend phase of a plugin on a round trip" in {
      roundTrip(format, PluginIndex(handlesWebsocketBackend = Some(3))).handlesWebsocketBackend mustBe Some(3)
    }

    "expose through json what the format writes" in {
      fullIndex.json mustBe format.writes(fullIndex)
    }

    "round trip an empty index" in {
      roundTrip(format, PluginIndex())
    }

    "round trip an index where every phase is set" in {
      roundTrip(format, fullIndex)
    }

    "round trip a single phase" in {
      roundTrip(format, PluginIndex(transformResponse = Some(-1))) mustBe PluginIndex(transformResponse = Some(-1))
    }

    "read integers as doubles" in {
      readOk(format, Json.obj("sink" -> 3)).sink mustBe Some(3.0)
    }

    "read decimals" in {
      readOk(format, Json.obj("pre_route" -> 1.25)).preRoute mustBe Some(1.25)
    }

    "read the call_backend key" in {
      readOk(format, Json.obj("call_backend" -> 2)).callBackend mustBe Some(2.0)
    }

    "ignore a phase that is not a number" in {
      readOk(format, Json.obj("sink" -> "1", "match_route" -> true)) mustBe PluginIndex()
    }

    "ignore camel case keys" in {
      readOk(format, Json.obj("preRoute" -> 1, "validateAccess" -> 2)) mustBe PluginIndex()
    }

    "read a json null as an empty index" in {
      readOk(format, JsNull) mustBe PluginIndex()
    }
  }

  "NgPluginInstance" should {

    "write a minimal instance without plugin index" in {
      pluginInstanceFormat.writes(NgPluginInstance(plugin = overrideHost)) mustBe Json.obj(
        "enabled"         -> true,
        "debug"           -> false,
        "plugin"          -> overrideHost,
        "include"         -> Json.arr(),
        "exclude"         -> Json.arr(),
        "config"          -> Json.obj(),
        "bound_listeners" -> Json.arr()
      )
    }

    "write the plugin index when there is one" in {
      written(pluginInstanceFormat, NgPluginInstance(plugin = overrideHost, pluginIndex = Some(PluginIndex(sink = Some(1))))) \
        "plugin_index" mustBe JsDefined(Json.obj("sink" -> 1))
    }

    "be enabled, not debugged, with an empty config by default" in {
      val instance = NgPluginInstance(plugin = overrideHost)
      instance.enabled mustBe true
      instance.debug mustBe false
      instance.config mustBe NgPluginInstanceConfig(Json.obj())
      instance.instanceId mustBe -1
    }

    "round trip a minimal instance" in {
      roundTrip(pluginInstanceFormat, NgPluginInstance(plugin = overrideHost))
    }

    "round trip an instance where every field is set" in {
      roundTrip(pluginInstanceFormat, fullInstance)
    }

    "read a lone plugin id with every default" in {
      readOk(pluginInstanceFormat, Json.obj("plugin" -> overrideHost)) mustBe NgPluginInstance(plugin = overrideHost)
    }

    "fail to read an instance without plugin id" in {
      a[JsResultException] must be thrownBy NgPluginInstance.readFrom(Json.obj("enabled" -> true))
    }

    "fail to read a plugin id that is not a string" in {
      a[JsResultException] must be thrownBy NgPluginInstance.readFrom(Json.obj("plugin" -> 42))
    }

    "be enabled when enabled is not a boolean" in {
      readOk(pluginInstanceFormat, Json.obj("plugin" -> overrideHost, "enabled" -> "false")).enabled mustBe true
    }

    "read an empty config when the config is not an object" in {
      readOk(pluginInstanceFormat, Json.obj("plugin" -> overrideHost, "config" -> Json.arr(1))).config.raw mustBe Json.obj()
    }

    "ignore include, exclude and bound listeners given as single strings" in {
      val back = readOk(
        pluginInstanceFormat,
        Json.obj("plugin" -> overrideHost, "include" -> "/a", "exclude" -> "/b", "bound_listeners" -> "l1")
      )
      back.include mustBe empty
      back.exclude mustBe empty
      back.boundListeners mustBe empty
    }

    "read a plugin index that is not an object as an empty index" in {
      readOk(pluginInstanceFormat, Json.obj("plugin" -> overrideHost, "plugin_index" -> "first")).pluginIndex mustBe Some(
        PluginIndex()
      )
    }

    "not write the instance id, an index computed at runtime" in {
      val instance = NgPluginInstance(plugin = overrideHost, instanceId = 5)
      written(pluginInstanceFormat, instance).keys must not contain "instance_id"
      readOk(pluginInstanceFormat, pluginInstanceFormat.writes(instance)).instanceId mustBe -1
    }

    "keep the config untouched" in {
      val config = Json.obj("deep" -> Json.obj("list" -> Json.arr(1, "two", JsNull)), "flag" -> false)
      roundTrip(pluginInstanceFormat, NgPluginInstance(plugin = overrideHost, config = NgPluginInstanceConfig(config))).config.raw mustBe config
    }

    "default to the override host plugin, first of the request transformers" in {
      NgPluginInstance.default mustBe NgPluginInstance(
        plugin = "cp:otoroshi.next.plugins.OverrideHost",
        pluginIndex = Some(PluginIndex(transformRequest = Some(0)))
      )
    }

    "match every request without include nor exclude" in {
      NgPluginInstance(plugin = overrideHost).matches(FakeRequest("GET", "/anything")) mustBe true
    }

    "match only the included paths" in {
      val instance = NgPluginInstance(plugin = overrideHost, include = Seq("/api/.*"))
      instance.matches(FakeRequest("GET", "/api/users")) mustBe true
      instance.matches(FakeRequest("GET", "/other")) mustBe false
    }

    "not match the excluded paths" in {
      val instance = NgPluginInstance(plugin = overrideHost, exclude = Seq("/api/private/.*"))
      instance.matches(FakeRequest("GET", "/api/private/x")) mustBe false
      instance.matches(FakeRequest("GET", "/api/public/x")) mustBe true
    }

    "let the exclusion win over the inclusion" in {
      fullInstance.matches(FakeRequest("GET", "/api/private/x")) mustBe false
      fullInstance.matches(FakeRequest("GET", "/api/public/x")) mustBe true
    }

    "match the include patterns against the whole path, without the query" in {
      val instance = NgPluginInstance(plugin = overrideHost, include = Seq("/api"))
      instance.matches(FakeRequest("GET", "/api?foo=bar")) mustBe true
      instance.matches(FakeRequest("GET", "/api/users")) mustBe false
    }
  }

  "NgPlugins" should {

    "write the empty chain as an empty array" in {
      pluginsFormat.writes(NgPlugins.empty) mustBe Json.arr()
    }

    "write each slot in order" in {
      pluginsFormat.writes(fullPlugins) mustBe Json.arr(fullInstance.json, NgPluginInstance(plugin = apikeyCalls).json)
    }

    "round trip the empty chain" in {
      roundTrip(pluginsFormat, NgPlugins.empty)
    }

    "round trip a chain where every field of a slot is set" in {
      roundTrip(pluginsFormat, fullPlugins)
    }

    "keep the order of the slots, duplicates included" in {
      val chain = NgPlugins(
        Seq(NgPluginInstance(plugin = apikeyCalls), NgPluginInstance(plugin = overrideHost), NgPluginInstance(plugin = apikeyCalls))
      )
      roundTrip(pluginsFormat, chain).slots.map(_.plugin) mustBe Seq(apikeyCalls, overrideHost, apikeyCalls)
    }

    "read a missing chain as the empty chain" in {
      NgPlugins.readFrom(Json.obj() \ "plugins") mustBe NgPlugins.empty
    }

    "read a chain that is not an array as the empty chain" in {
      readOk(pluginsFormat, Json.obj("plugin" -> overrideHost)) mustBe NgPlugins.empty
    }

    "fail to read a chain holding a slot without plugin id" in {
      a[JsResultException] must be thrownBy NgPlugins.readFrom(JsDefined(Json.arr(Json.obj("enabled" -> true))))
    }

    "fail to read a chain holding a slot that is not an object" in {
      a[JsResultException] must be thrownBy NgPlugins.readFrom(JsDefined(Json.arr(overrideHost)))
    }

    "default to the override host plugin alone" in {
      NgPlugins.default mustBe NgPlugins(Seq(NgPluginInstance.default))
    }

    "tell whether it is empty" in {
      NgPlugins.empty.isEmpty mustBe true
      NgPlugins.empty.nonEmpty mustBe false
      fullPlugins.isEmpty mustBe false
      fullPlugins.nonEmpty mustBe true
    }

    "add a slot at the end" in {
      NgPlugins.empty.add(fullInstance).add(NgPluginInstance(plugin = apikeyCalls)) mustBe fullPlugins
    }

    "find an enabled plugin by its class" in {
      val chain = NgPlugins(Seq(NgPluginInstance(plugin = apikeyCalls)))
      chain.hasPlugin[ApikeyCalls] mustBe true
      chain.getPluginByClass[ApikeyCalls] mustBe Some(NgPluginInstance(plugin = apikeyCalls))
      chain.hasPlugin[OverrideHost] mustBe false
    }

    "not find a disabled plugin by its class" in {
      NgPlugins(Seq(fullInstance)).hasPlugin[OverrideHost] mustBe false
    }

    "only look at the first slot of a plugin when finding it by its class" in {
      val chain = NgPlugins(Seq(fullInstance, NgPluginInstance(plugin = overrideHost)))
      chain.getPluginByClass[OverrideHost] mustBe None
    }
  }

  "NgRoute" should {

    val format = NgRoute.fmt

    val minimal = NgRoute(
      location = EntityLocation(),
      id = "route_1",
      name = "Route 1",
      description = "",
      tags = Seq.empty,
      metadata = Map.empty,
      enabled = true,
      debugFlow = false,
      capture = false,
      exportReporting = false,
      frontend = NgFrontend.empty,
      backend = NgBackend.empty,
      plugins = NgPlugins.empty
    )

    val full = NgRoute(
      location = customLocation,
      id = "route_full",
      name = "Full route",
      description = "a route where every field is set",
      tags = Seq("prod", "eu"),
      metadata = Map("owner" -> "ops", "cost-center" -> "42"),
      enabled = false,
      debugFlow = true,
      capture = true,
      exportReporting = true,
      groups = Seq("group-1", "group-2"),
      boundListeners = Seq("listener-1"),
      frontend = fullFrontend,
      backend = fullBackend,
      backendRef = None,
      apiRef = Some(RouteApiRef("api_1")),
      plugins = fullPlugins
    )

    val minimalJson = Json.obj("id" -> "route_1", "name" -> "Route 1", "backend" -> Json.obj())

    "write a minimal route" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"             -> defaultLocationJson,
        "id"               -> "route_1",
        "name"             -> "Route 1",
        "description"      -> "",
        "tags"             -> Json.arr(),
        "metadata"         -> Json.obj(),
        "enabled"          -> true,
        "debug_flow"       -> false,
        "export_reporting" -> false,
        "capture"          -> false,
        "groups"           -> Json.arr("default"),
        "bound_listeners"  -> Json.arr(),
        "frontend"         -> emptyFrontendJson,
        "backend"          -> NgBackend.empty.json,
        "backend_ref"      -> JsNull,
        "api_ref"          -> JsNull,
        "plugins"          -> Json.arr()
      )
    }

    "write the same keys whatever the fields set" in {
      writtenKeys(format, full) mustBe writtenKeys(format, minimal)
    }

    "write a route where every field is set" in {
      val json = written(format, full)
      json \ "_loc" mustBe JsDefined(customLocationJson)
      json \ "id" mustBe JsDefined(JsString("route_full"))
      json \ "tags" mustBe JsDefined(Json.arr("prod", "eu"))
      json \ "metadata" mustBe JsDefined(Json.obj("owner" -> "ops", "cost-center" -> "42"))
      json \ "enabled" mustBe JsDefined(JsFalse)
      json \ "debug_flow" mustBe JsDefined(JsTrue)
      json \ "capture" mustBe JsDefined(JsTrue)
      json \ "export_reporting" mustBe JsDefined(JsTrue)
      json \ "groups" mustBe JsDefined(Json.arr("group-1", "group-2"))
      json \ "bound_listeners" mustBe JsDefined(Json.arr("listener-1"))
      json \ "frontend" mustBe JsDefined(fullFrontend.json)
      json \ "backend" mustBe JsDefined(fullBackend.json)
      json \ "api_ref" mustBe JsDefined(JsString("api_1"))
      json \ "plugins" mustBe JsDefined(fullPlugins.json)
    }

    "round trip a route where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a minimal route" in {
      roundTrip(format, minimal)
    }

    "round trip the empty route" in {
      roundTrip(format, NgRoute.empty)
    }

    "round trip the default route" in {
      roundTrip(format, NgRoute.default)
    }

    "round trip a route with an empty name and description" in {
      roundTrip(format, full.copy(name = "", description = ""))
    }

    "round trip a name and a description with quotes and unicode" in {
      roundTrip(format, full.copy(name = "l'équipe \"ops\"", description = "multi\nline"))
    }

    "keep the order of the tags, the groups and the bound listeners" in {
      val back = roundTrip(format, full.copy(tags = Seq("z", "a"), groups = Seq("g2", "g1"), boundListeners = Seq("l2", "l1")))
      back.tags mustBe Seq("z", "a")
      back.groups mustBe Seq("g2", "g1")
      back.boundListeners mustBe Seq("l2", "l1")
    }

    "round trip a route without any group" in {
      roundTrip(format, full.copy(groups = Seq.empty)).groups mustBe empty
    }

    "round trip a route in a custom location" in {
      roundTrip(format, full).location mustBe customLocation
    }

    "round trip a route in the default location" in {
      roundTrip(format, full.copy(location = EntityLocation())).location mustBe EntityLocation()
    }

    "round trip a route without plugin, api ref nor bound listener" in {
      roundTrip(format, full.copy(plugins = NgPlugins.empty, apiRef = None, boundListeners = Seq.empty))
    }

    "round trip a route whose backend has no target" in {
      roundTrip(format, full.copy(backend = fullBackend.copy(targets = Seq.empty))).backend.targets mustBe empty
    }

    "read a route without _loc in the default location" in {
      readOk(format, minimalJson).location mustBe EntityLocation()
    }

    "read a lone id, name and backend with every default" in {
      readOk(format, minimalJson) mustBe minimal
    }

    "be enabled and in the default group by default" in {
      val back = readOk(format, minimalJson)
      back.enabled mustBe true
      back.groups mustBe Seq("default")
    }

    "read an empty groups array as no group, not as the default group" in {
      readOk(format, minimalJson ++ Json.obj("groups" -> Json.arr())).groups mustBe empty
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a route without id" in {
      readError(format, minimalJson - "id")
    }

    "not read a route without name" in {
      readError(format, minimalJson - "name")
    }

    "not read an id that is not a string" in {
      readError(format, minimalJson ++ Json.obj("id" -> 1))
    }

    "not read a name that is not a string" in {
      readError(format, minimalJson ++ Json.obj("name" -> true))
    }

    "not read a route without backend nor backend ref" in {
      readError(format, minimalJson - "backend")
    }

    "read a json null backend as the empty backend" in {
      readOk(format, minimalJson ++ Json.obj("backend" -> JsNull)).backend mustBe NgBackend.empty
    }

    "read a backend that is not an object as the empty backend" in {
      readOk(format, minimalJson ++ Json.obj("backend" -> "https://backend.internal")).backend mustBe NgBackend.empty
    }

    "read an inline backend given as a single url" in {
      val back = readOk(format, minimalJson ++ Json.obj("backend" -> Json.obj("url" -> "https://backend.internal/api")))
      back.backend.targets.map(t => (t.hostname, t.port, t.tls)) mustBe Seq(("backend.internal", 443, true))
      back.backend.root mustBe "/api"
    }

    "not read a route whose plugins chain holds a slot without plugin id" in {
      readError(format, minimalJson ++ Json.obj("plugins" -> Json.arr(Json.obj("enabled" -> true))))
    }

    "use an empty description when the description is not a string" in {
      readOk(format, minimalJson ++ Json.obj("description" -> 42)).description mustBe ""
    }

    "be enabled when enabled is not a boolean" in {
      readOk(format, minimalJson ++ Json.obj("enabled" -> "false")).enabled mustBe true
    }

    "not debug, capture nor export reporting when the flags are not booleans" in {
      val back = readOk(format, minimalJson ++ Json.obj("debug_flow" -> "true", "capture" -> 1, "export_reporting" -> "yes"))
      back.debugFlow mustBe false
      back.capture mustBe false
      back.exportReporting mustBe false
    }

    "ignore camel case flags" in {
      val back = readOk(format, minimalJson ++ Json.obj("debugFlow" -> true, "exportReporting" -> true, "boundListeners" -> Json.arr("l1")))
      back.debugFlow mustBe false
      back.exportReporting mustBe false
      back.boundListeners mustBe empty
    }

    "be in the default group when groups is a single string" in {
      readOk(format, minimalJson ++ Json.obj("groups" -> "group-1")).groups mustBe Seq("default")
    }

    "ignore tags and bound listeners given as single strings" in {
      val back = readOk(format, minimalJson ++ Json.obj("tags" -> "prod", "bound_listeners" -> "listener-1"))
      back.tags mustBe empty
      back.boundListeners mustBe empty
    }

    "ignore metadata holding a non string value" in {
      readOk(format, minimalJson ++ Json.obj("metadata" -> Json.obj("a" -> "b", "n" -> 1))).metadata mustBe Map.empty
    }

    "read a frontend that is not an object as the empty frontend" in {
      readOk(format, minimalJson ++ Json.obj("frontend" -> "api.oto.tools")).frontend mustBe NgFrontend.empty
    }

    "read plugins that are not an array as no plugin" in {
      readOk(format, minimalJson ++ Json.obj("plugins" -> Json.obj("plugin" -> overrideHost))).plugins mustBe NgPlugins.empty
    }

    "read a json null backend ref as no backend ref" in {
      val back = readOk(format, minimalJson ++ Json.obj("backend_ref" -> JsNull, "backend" -> fullBackend.json))
      back.backendRef mustBe None
      back.backend mustBe fullBackend
    }

    "write the backend ref as a string, next to the inline backend" in {
      val json = written(format, minimal.copy(backendRef = Some("backend_1"), backend = fullBackend))
      json \ "backend_ref" mustBe JsDefined(JsString("backend_1"))
      json \ "backend" mustBe JsDefined(fullBackend.json)
    }

    "read the api ref" in {
      readOk(format, minimalJson ++ Json.obj("api_ref" -> "api_1")).apiRef mustBe Some(RouteApiRef("api_1"))
    }

    "read the legacy api ref from the metadata" in {
      readOk(format, minimalJson ++ Json.obj("metadata" -> Json.obj("Otoroshi-Api-Ref" -> "api_legacy"))).apiRef mustBe Some(
        RouteApiRef("api_legacy")
      )
    }

    "read the legacy api ref from the metadata when the api ref is a json null" in {
      readOk(
        format,
        minimalJson ++ Json.obj("api_ref" -> JsNull, "metadata" -> Json.obj("Otoroshi-Api-Ref" -> "api_legacy"))
      ).apiRef mustBe Some(RouteApiRef("api_legacy"))
    }

    "prefer the api ref over the legacy one of the metadata" in {
      readOk(
        format,
        minimalJson ++ Json.obj("api_ref" -> "api_1", "metadata" -> Json.obj("Otoroshi-Api-Ref" -> "api_legacy"))
      ).apiRef mustBe Some(RouteApiRef("api_1"))
    }

    "keep the legacy api ref in the metadata" in {
      readOk(format, minimalJson ++ Json.obj("metadata" -> Json.obj("Otoroshi-Api-Ref" -> "api_legacy"))).metadata mustBe Map(
        "Otoroshi-Api-Ref" -> "api_legacy"
      )
    }

    "not mirror the api ref in the written metadata" in {
      (written(format, full) \ "metadata").as[JsObject].keys must not contain RouteApiRef.metadataKey
    }

    "read no api ref from an empty api ref, without looking at the metadata" in {
      readOk(
        format,
        minimalJson ++ Json.obj("api_ref" -> "", "metadata" -> Json.obj("Otoroshi-Api-Ref" -> "api_legacy"))
      ).apiRef mustBe None
    }

    "read no api ref from an empty legacy metadata value" in {
      readOk(format, minimalJson ++ Json.obj("metadata" -> Json.obj("Otoroshi-Api-Ref" -> ""))).apiRef mustBe None
    }

    "read an api ref that is not a string from the metadata" in {
      readOk(
        format,
        minimalJson ++ Json.obj("api_ref" -> 42, "metadata" -> Json.obj("Otoroshi-Api-Ref" -> "api_legacy"))
      ).apiRef mustBe Some(RouteApiRef("api_legacy"))
    }

    "read back an api ref from the metadata of a route written without api ref" in {
      val route = minimal.copy(metadata = Map("Otoroshi-Api-Ref" -> "api_legacy"))
      readOk(format, format.writes(route)).apiRef mustBe Some(RouteApiRef("api_legacy"))
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "route_full", "Full route", "a route where every field is set", Seq("prod", "eu"), Map("owner" -> "ops", "cost-center" -> "42"))
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "be built from its json" in {
      NgRoute.fromJsons(full.json) mustBe full
    }

    "fail to be built from a json without id" in {
      a[Throwable] must be thrownBy NgRoute.fromJsons(minimalJson - "id")
    }

    "build empty routes with a fresh id each, on the empty.oto.tools domain" in {
      val route = NgRoute.empty
      route.id must startWith("route_")
      NgRoute.empty.id must not be route.id
      route.frontend.domains mustBe Seq(NgDomainAndPath("empty.oto.tools"))
      route.backend.targets mustBe empty
      route.plugins mustBe NgPlugins.empty
      route.enabled mustBe true
    }

    "target www.otoroshi.io in the default route" in {
      val route = NgRoute.default
      route.backend.targets mustBe Seq(NgTarget.default)
      route.plugins mustBe NgPlugins.empty
      route.frontend mustBe NgRoute.empty.frontend
    }

    "be cacheable under its own id" in {
      minimal.cacheableId mustBe "route_1"
      minimal.originalRouteId mustBe None
    }

    "be cacheable under the id of the route it was generated from" in {
      val route = minimal.copy(metadata = Map("otoroshi-core-original-route-id" -> "route_origin"))
      route.originalRouteId mustBe Some("route_origin")
      route.cacheableId mustBe "route_origin"
    }

    "ignore an empty original route id" in {
      minimal.copy(metadata = Map("otoroshi-core-original-route-id" -> "")).cacheableId mustBe "route_1"
    }

    "not be bound to a listener without bound listeners" in {
      minimal.notBoundToListener mustBe true
      minimal.boundToListener("listener-1") mustBe false
    }

    "be bound to its listeners whatever the case of the asked name" in {
      full.notBoundToListener mustBe false
      full.boundToListener("listener-1") mustBe true
      full.boundToListener("Listener-1") mustBe true
      full.boundToListener("listener-2") mustBe false
    }

    "never be bound to a listener stored with upper case letters" in {
      minimal.copy(boundListeners = Seq("Listener-1")).boundToListener("Listener-1") mustBe false
    }

    "use the ahc client unless the metadata asks for another one" in {
      minimal.useAhcClient mustBe true
      minimal.useAkkaHttpClient mustBe false
      minimal.useNettyClient mustBe false
      minimal.useAkkaHttpWsClient mustBe false
    }

    "use the http clients asked by the metadata" in {
      val akka = minimal.copy(metadata = Map("otoroshi-core-use-akka-http-client" -> "true", "otoroshi-core-use-akka-http-ws-client" -> "true"))
      akka.useAkkaHttpClient mustBe true
      akka.useAhcClient mustBe false
      akka.useAkkaHttpWsClient mustBe true
      val netty = minimal.copy(metadata = Map("otoroshi-core-use-netty-http-client" -> "true"))
      netty.useNettyClient mustBe true
      netty.useAhcClient mustBe false
    }

    "only take the exact true value as a metadata flag" in {
      minimal.copy(metadata = Map("otoroshi-core-user-facing" -> "TRUE")).userFacing mustBe false
      minimal.copy(metadata = Map("otoroshi-core-user-facing" -> "true")).userFacing mustBe true
    }

    "read the certificate flags from the metadata" in {
      val route = minimal.copy(metadata =
        Map(
          "otoroshi-core-issue-lets-encrypt-certificate" -> "true",
          "otoroshi-core-issue-certificate"              -> "true",
          "otoroshi-core-issue-certificate-ca"           -> "ca_1"
        )
      )
      route.issueLetsEncryptCertificate mustBe true
      route.issueCertificate mustBe true
      route.issueCertificateCA mustBe Some("ca_1")
      minimal.issueLetsEncryptCertificate mustBe false
      minimal.issueCertificate mustBe false
    }

    "ignore an empty certificate authority and an empty openapi url" in {
      val route = minimal.copy(metadata = Map("otoroshi-core-issue-certificate-ca" -> "", "otoroshi-core-openapi-url" -> ""))
      route.issueCertificateCA mustBe None
      route.openapiUrl mustBe None
    }

    "read the openapi url from the metadata" in {
      minimal.copy(metadata = Map("otoroshi-core-openapi-url" -> "https://api.oto.tools/openapi.json")).openapiUrl mustBe Some(
        "https://api.oto.tools/openapi.json"
      )
    }

    "read the deployment places as trimmed comma separated lists" in {
      val route = minimal.copy(metadata =
        Map(
          "otoroshi-deployment-providers" -> "aws, gcp",
          "otoroshi-deployment-regions"   -> "eu-west-1",
          "otoroshi-deployment-zones"     -> "a ,b",
          "otoroshi-deployment-dcs"       -> "dc1,dc2",
          "otoroshi-deployment-racks"     -> "r1"
        )
      )
      route.deploymentProviders mustBe Seq("aws", "gcp")
      route.deploymentRegions mustBe Seq("eu-west-1")
      route.deploymentZones mustBe Seq("a", "b")
      route.deploymentDatacenters mustBe Seq("dc1", "dc2")
      route.deploymentRacks mustBe Seq("r1")
      route.hasDeploymentProviders mustBe true
      route.hasDeploymentRacks mustBe true
    }

    "have no deployment place without metadata or with empty ones" in {
      minimal.deploymentProviders mustBe empty
      minimal.hasDeploymentProviders mustBe false
      val route = minimal.copy(metadata = Map("otoroshi-deployment-regions" -> "", "otoroshi-deployment-zones" -> ""))
      route.hasDeploymentRegions mustBe false
      route.hasDeploymentZones mustBe false
      route.hasDeploymentDatacenters mustBe false
    }
  }
}
