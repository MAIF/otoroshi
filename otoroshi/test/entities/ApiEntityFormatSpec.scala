package entities

import org.joda.time.DateTime
import otoroshi.models.{EntityLocation, HealthCheck, RoundRobin}
import otoroshi.next.models.*
import play.api.libs.json.*

class ApiEntityFormatSpec extends EntityFormatSpec {

  private val frontend = NgFrontend(
    domains = Seq(NgDomainAndPath("/users")),
    headers = Map("x-env" -> "prod"),
    query = Map("q" -> "1"),
    cookies = Map("session" -> "abc"),
    methods = Seq("GET", "POST"),
    stripPath = false,
    exact = true
  )

  private val route = ApiRoute(
    id = "route-1",
    enabled = false,
    name = Some("users"),
    frontend = frontend,
    flowRef = "flow-1",
    backend = "backend-1"
  )

  private val backend = ApiBackend(
    id = "backend-1",
    name = "users backend",
    backend = NgBackend(
      targets = Seq(NgTarget(id = "target-1", hostname = "users.internal", port = 8443, tls = true)),
      root = "/api",
      rewrite = true,
      loadBalancing = RoundRobin,
      // a backend without health check reads back with a disabled one, see NgBackend
      healthCheck = Some(HealthCheck(enabled = true, url = "/health")),
      client = NgClientConfig.default
    ),
    client = "client-1"
  )

  private val flow = ApiFlows(
    id = "flow-1",
    name = "main flow",
    plugins = NgPlugins(
      Seq(
        NgPluginInstance(
          plugin = "cp:otoroshi.next.plugins.OverrideHost",
          include = Seq("/users.*"),
          config = NgPluginInstanceConfig(Json.obj("a" -> 1))
        )
      )
    )
  )

  private val backendClient = ApiBackendClient("client-1", "fast client", NgClientConfig(retries = 3, callTimeout = 5000))

  private val documentation = ApiDocumentation(
    enabled = false,
    home = ApiDocumentationResource(Json.obj("text_content" -> "# Users")),
    tags = Seq("doc"),
    metadata = Map("k" -> "v")
  )

  private val deployment = ApiDeployment(
    id = "deployment-1",
    apiRef = "api-1",
    owner = "admin",
    at = new DateTime(1700000000000L),
    apiDefinition = Json.obj("routes" -> Json.arr()),
    version = "1.0.0"
  )

  private val apiClient = ApiClient("client-app-1", "mobile app", Some("the mobile app"), Seq("mobile"), Map("team" -> "app"))

  private val testing = ApiTesting(enabled = true, headerKey = "X-TEST", headerValue = "secret-value")

  private val hook = ApiStateHook("hook-1", Json.obj("url" -> "https://hooks.oto.tools"))

  private val planJson = Json.obj(
    "id"                             -> "plan-1",
    "name"                           -> "gold",
    "access_mode_configuration_type" -> "apikey",
    "status"                         -> "published",
    "access_mode_configuration"      -> Json.obj("enabled" -> true)
  )

  private val full = Api(
    kind = "Api",
    location = customLocation,
    id = "api-1",
    name = "Users API",
    description = "the users api",
    domain = "api.oto.tools",
    contextPath = "/users",
    tags = Seq("users", "v1"),
    metadata = Map("owner" -> "team-users"),
    owner = UserRef("user-1", Json.obj("email" -> "jane@oto.tools")),
    members = Seq(UserRef("user-2", Json.obj())),
    visibility = ApiVisibility(ApiVisibilityKind.SemiPublic, Json.obj("teams" -> Json.arr("t1"))),
    version = "1.2.0",
    versions = Seq("1.0.0", "1.2.0"),
    debugFlow = true,
    capture = true,
    exportReporting = true,
    groups = Seq("group-1"),
    state = ApiPublished,
    enabled = false,
    blueprint = ApiBlueprint.GraphQL,
    routes = Seq(route),
    backends = Seq(backend),
    plans = Seq(ApiPlan(planJson)),
    flows = Seq(flow),
    clientsBackendConfig = Seq(backendClient),
    documentation = Some(documentation),
    deployments = Seq(deployment),
    clients = Seq(apiClient),
    testing = testing,
    hooks = Seq(hook)
  )

  private val minimal = Api(
    location = EntityLocation(),
    id = "api-min",
    name = "Min",
    description = "",
    domain = "",
    contextPath = "",
    version = "0.0.1",
    versions = Seq.empty,
    debugFlow = false,
    capture = false,
    exportReporting = false,
    groups = Seq.empty,
    state = ApiStaging,
    blueprint = ApiBlueprint.REST,
    clientsBackendConfig = Seq(ApiBackendClient.defaultClient),
    testing = ApiTesting(headerValue = "fixed")
  )

  private val minimalJson = Json.obj("id" -> "api-1", "name" -> "Users API", "description" -> "the users api")

  "Api" should {

    val format = Api.format

    "write a minimal api" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"                   -> defaultLocationJson,
        "kind"                   -> "Api",
        "id"                     -> "api-min",
        "name"                   -> "Min",
        "description"            -> "",
        "domain"                 -> "",
        "contextPath"            -> "",
        "metadata"               -> Json.obj(),
        "tags"                   -> Json.arr(),
        "visibility"             -> Json.obj("kind" -> "public", "config" -> Json.obj()),
        "owner"                  -> Json.obj("ref" -> "owner", "config" -> Json.obj()),
        "members"                -> Json.arr(),
        "version"                -> "0.0.1",
        "debug_flow"             -> false,
        "capture"                -> false,
        "export_reporting"       -> false,
        "groups"                 -> Json.arr(),
        "state"                  -> "staging",
        "enabled"                -> true,
        "blueprint"              -> "REST",
        "plans"                  -> Json.arr(),
        "routes"                 -> Json.arr(),
        "backends"               -> Json.arr(),
        "flows"                  -> Json.arr(),
        "clients_backend_config" -> Json.arr(ApiBackendClient._fmt.writes(ApiBackendClient.defaultClient)),
        "documentation"          -> JsNull,
        "deployments"            -> Json.arr(),
        "versions"               -> Json.arr(),
        "testing"                -> Json.obj("enabled" -> false, "headerKey" -> "X-OTOROSHI-TESTING", "headerValue" -> "fixed"),
        "clients"                -> Json.arr(),
        "hooks"                  -> Json.arr()
      )
    }

    "write the important values of a full api" in {
      val json = written(format, full)
      (json \ "_loc").as[JsObject] mustBe customLocationJson
      (json \ "state").as[String] mustBe "published"
      (json \ "blueprint").as[String] mustBe "GraphQL"
      (json \ "enabled").as[Boolean] mustBe false
      (json \ "visibility").as[JsObject] mustBe Json.obj("kind" -> "semi_public", "config" -> Json.obj("teams" -> Json.arr("t1")))
      (json \ "owner").as[JsObject] mustBe Json.obj("ref" -> "user-1", "config" -> Json.obj("email" -> "jane@oto.tools"))
      (json \ "members").as[JsArray] mustBe Json.arr(Json.obj("ref" -> "user-2", "config" -> Json.obj()))
      (json \ "plans").as[JsArray] mustBe Json.arr(planJson)
      (json \ "versions").as[Seq[String]] mustBe Seq("1.0.0", "1.2.0")
      (json \ "hooks").as[JsArray] mustBe Json.arr(Json.obj("ref" -> "hook-1", "config" -> Json.obj("url" -> "https://hooks.oto.tools")))
    }

    "write the nested entities with their own formats" in {
      val json = written(format, full)
      (json \ "routes").as[JsArray] mustBe Json.arr(ApiRoute._fmt.writes(route))
      (json \ "backends").as[JsArray] mustBe Json.arr(ApiBackend._fmt.writes(backend))
      (json \ "flows").as[JsArray] mustBe Json.arr(ApiFlows._fmt.writes(flow))
      (json \ "clients_backend_config").as[JsArray] mustBe Json.arr(ApiBackendClient._fmt.writes(backendClient))
      (json \ "documentation").as[JsObject] mustBe ApiDocumentation._fmt.writes(documentation)
      (json \ "deployments").as[JsArray] mustBe Json.arr(ApiDeployment._fmt.writes(deployment))
      (json \ "clients").as[JsArray] mustBe Json.arr(ApiClient.format.writes(apiClient))
      (json \ "testing").as[JsObject] mustBe ApiTesting._fmt.writes(testing)
    }

    "write the same keys for a minimal and a full api" in {
      writtenKeys(format, full) mustBe writtenKeys(format, minimal)
    }

    "write the context path in camel case and the flags in snake case" in {
      val keys = writtenKeys(format, full)
      keys must contain allOf ("contextPath", "debug_flow", "export_reporting", "clients_backend_config")
      keys must contain noneOf ("context_path", "debugFlow", "exportReporting", "location")
    }

    "round trip a full api" in {
      roundTrip(format, full)
    }

    "round trip a flow whose plugin sets every plugin index" in {
      val index = PluginIndex(
        sink = Some(1),
        preRoute = Some(2),
        validateAccess = Some(3),
        transformRequest = Some(4),
        transformResponse = Some(5),
        matchRoute = Some(6),
        handlesTunnel = Some(7),
        handlesWebsocket = Some(8),
        handlesWebsocketBackend = Some(9),
        callBackend = Some(10)
      )
      val indexed = flow.copy(plugins = NgPlugins(flow.plugins.slots.map(_.copy(pluginIndex = Some(index)))))
      roundTrip(ApiFlows._fmt, indexed).plugins.slots.head.pluginIndex mustBe Some(index)
    }

    "round trip every state" in {
      Seq(ApiStaging, ApiPublished, ApiDeprecated, ApiRemoved).foreach { state =>
        roundTrip(format, full.copy(state = state)).state mustBe state
      }
    }

    "round trip every blueprint" in {
      Seq(ApiBlueprint.REST, ApiBlueprint.GraphQL, ApiBlueprint.gRPC, ApiBlueprint.Http, ApiBlueprint.Websocket).foreach {
        blueprint => roundTrip(format, full.copy(blueprint = blueprint)).blueprint mustBe blueprint
      }
    }

    "round trip every visibility kind" in {
      Seq(ApiVisibilityKind.Public, ApiVisibilityKind.SemiPublic, ApiVisibilityKind.Private, ApiVisibilityKind.Custom)
        .foreach { kind =>
          roundTrip(format, full.copy(visibility = ApiVisibility(kind))).visibility.kind mustBe kind
        }
    }

    "round trip an enabled api" in {
      roundTrip(format, full.copy(enabled = true)).enabled mustBe true
    }

    "round trip a custom kind" in {
      roundTrip(format, full.copy(kind = "ApiV2")).kind mustBe "ApiV2"
    }

    "round trip an api with several routes, backends and flows in order" in {
      val api = full.copy(
        routes = Seq(route.copy(id = "r2"), route, route.copy(id = "r0")),
        backends = Seq(backend.copy(id = "b2"), backend),
        flows = Seq(flow.copy(id = "f2"), flow)
      )
      val back = roundTrip(format, api)
      back.routes.map(_.id) mustBe Seq("r2", "route-1", "r0")
      back.backends.map(_.id) mustBe Seq("b2", "backend-1")
      back.flows.map(_.id) mustBe Seq("f2", "flow-1")
    }

    "round trip a plan with keys the plan model does not know" in {
      val plan = planJson ++ Json.obj("custom" -> Json.obj("x" -> 1), "rateLimiting" -> Json.obj("strategy" -> "none"))
      roundTrip(format, full.copy(plans = Seq(ApiPlan(plan)))).plans.head.raw mustBe plan
    }

    "keep the access rules of its plans on a round trip" in {
      val plans = Seq(
        ApiPlan(planJson),
        ApiPlan(Json.obj("id" -> "free", "name" -> "free", "access_mode_configuration_type" -> "keyless", "status" -> "staging")),
        ApiPlan(Json.obj("id" -> "jwt", "name" -> "jwt", "access_mode_configuration_type" -> "jwt", "status" -> "closed"))
      )
      val back  = roundTrip(format, full.copy(plans = plans)).plans
      back.map(_.accessModeConfigurationType) mustBe Seq("apikey", "keyless", "jwt")
      back.map(_.status) mustBe Seq(ApiPlanStatus.Published, ApiPlanStatus.Staging, ApiPlanStatus.Closed)
    }

    "round trip several clients backend configurations" in {
      val configs = Seq(ApiBackendClient.defaultClient, backendClient)
      roundTrip(format, full.copy(clientsBackendConfig = configs)).clientsBackendConfig mustBe configs
    }

    "write the default backend client when it has none" in {
      (written(format, full.copy(clientsBackendConfig = Seq.empty)) \ "clients_backend_config").as[JsArray] mustBe
        Json.arr(ApiBackendClient._fmt.writes(ApiBackendClient.defaultClient))
    }

    "read back an api without backend client with the default one" in {
      val back = readOk(format, format.writes(full.copy(clientsBackendConfig = Seq.empty)))
      back.clientsBackendConfig mustBe Seq(ApiBackendClient.defaultClient)
    }

    "read a missing clients backend config as the default client" in {
      readOk(format, minimalJson).clientsBackendConfig mustBe Seq(ApiBackendClient.defaultClient)
    }

    "read an empty clients backend config as no client" in {
      readOk(format, minimalJson ++ Json.obj("clients_backend_config" -> Json.arr())).clientsBackendConfig mustBe empty
    }

    "round trip its custom location" in {
      roundTrip(format, full.copy(location = customLocation)).location mustBe customLocation
    }

    "read the default location without _loc" in {
      readOk(format, minimalJson).location mustBe EntityLocation()
    }

    "not read the location from a location key" in {
      readOk(format, minimalJson ++ Json.obj("location" -> customLocationJson)).location mustBe EntityLocation()
    }

    "read a lone id, name and description with the defaults" in {
      val back = readOk(format, minimalJson)
      back.kind mustBe "Api"
      back.domain mustBe ""
      back.contextPath mustBe ""
      back.tags mustBe empty
      back.metadata mustBe empty
      back.visibility mustBe ApiVisibility.Public
      back.owner mustBe UserRef.empty
      back.members mustBe empty
      back.debugFlow mustBe false
      back.capture mustBe false
      back.exportReporting mustBe false
      back.groups mustBe empty
      back.enabled mustBe true
      back.routes mustBe empty
      back.backends mustBe empty
      back.plans mustBe empty
      back.flows mustBe empty
      back.documentation mustBe None
      back.deployments mustBe empty
      back.clients mustBe empty
      back.hooks mustBe empty
    }

    "read a missing state as staging" in {
      readOk(format, minimalJson).state mustBe ApiStaging
    }

    "read a missing blueprint as REST" in {
      readOk(format, minimalJson).blueprint mustBe ApiBlueprint.REST
    }

    "read a missing version as 0.0.1" in {
      readOk(format, minimalJson).version mustBe "0.0.1"
    }

    "read missing versions as no version, unlike the constructor default" in {
      val constructed = Api(
        location = EntityLocation(),
        id = "x",
        name = "x",
        description = "",
        domain = "",
        contextPath = "",
        version = "0.0.1",
        debugFlow = false,
        capture = false,
        exportReporting = false,
        groups = Seq.empty,
        state = ApiStaging,
        blueprint = ApiBlueprint.REST,
        testing = testing
      )
      constructed.versions mustBe Seq("0.0.1")
      readOk(format, minimalJson).versions mustBe empty
    }

    "read a missing testing as a disabled testing with a random header value" in {
      val first  = readOk(format, minimalJson).testing
      val second = readOk(format, minimalJson).testing
      first.enabled mustBe false
      first.headerKey mustBe "X-OTOROSHI-TESTING"
      first.headerValue must not be empty
      first.headerValue must not be second.headerValue
    }

    "not read an api without id" in {
      readError(format, minimalJson - "id")
    }

    "not read an api without name" in {
      readError(format, minimalJson - "name")
    }

    "not read an api without description" in {
      readError(format, minimalJson - "description")
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read an id that is not a string" in {
      readError(format, minimalJson ++ Json.obj("id" -> 42))
    }

    "read an unknown state as staging" in {
      readOk(format, minimalJson ++ Json.obj("state" -> "archived")).state mustBe ApiStaging
    }

    "read the state case sensitively" in {
      readOk(format, minimalJson ++ Json.obj("state" -> "PUBLISHED")).state mustBe ApiStaging
    }

    "read a state that is not a string as staging" in {
      readOk(format, minimalJson ++ Json.obj("state" -> 1)).state mustBe ApiStaging
    }

    "not read an unknown blueprint" in {
      readError(format, minimalJson ++ Json.obj("blueprint" -> "SOAP"))
    }

    "not read a blueprint in another case" in {
      readError(format, minimalJson ++ Json.obj("blueprint" -> "rest"))
    }

    "read a blueprint that is not a string as REST" in {
      readOk(format, minimalJson ++ Json.obj("blueprint" -> 1)).blueprint mustBe ApiBlueprint.REST
    }

    "ignore an enabled flag that is not a boolean" in {
      readOk(format, minimalJson ++ Json.obj("enabled" -> "false")).enabled mustBe true
    }

    "ignore flags that are not booleans" in {
      val back = readOk(format, minimalJson ++ Json.obj("debug_flow" -> "true", "capture" -> 1, "export_reporting" -> "yes"))
      back.debugFlow mustBe false
      back.capture mustBe false
      back.exportReporting mustBe false
    }

    "ignore a domain and a context path that are not strings" in {
      val back = readOk(format, minimalJson ++ Json.obj("domain" -> 1, "contextPath" -> true))
      back.domain mustBe ""
      back.contextPath mustBe ""
    }

    "not read the context path from a snake case key" in {
      readOk(format, minimalJson ++ Json.obj("context_path" -> "/users")).contextPath mustBe ""
    }

    "ignore versions given as a single string" in {
      readOk(format, minimalJson ++ Json.obj("versions" -> "1.0.0")).versions mustBe empty
    }

    "ignore metadata holding a non string value" in {
      readOk(format, minimalJson ++ Json.obj("metadata" -> Json.obj("a" -> "b", "n" -> 1))).metadata mustBe empty
    }

    "drop the routes that cannot be read" in {
      val routes = Json.arr(ApiRoute._fmt.writes(route), written(ApiRoute._fmt, route) - "backend", "not a route")
      readOk(format, minimalJson ++ Json.obj("routes" -> routes)).routes mustBe Seq(route)
    }

    "drop the backends that cannot be read" in {
      val backends = Json.arr(written(ApiBackend._fmt, backend) - "backend", ApiBackend._fmt.writes(backend))
      readOk(format, minimalJson ++ Json.obj("backends" -> backends)).backends mustBe Seq(backend)
    }

    "drop the flows that cannot be read" in {
      val flows = Json.arr(ApiFlows._fmt.writes(flow), written(ApiFlows._fmt, flow) - "name")
      readOk(format, minimalJson ++ Json.obj("flows" -> flows)).flows mustBe Seq(flow)
    }

    "drop the clients that cannot be read" in {
      val clients = Json.arr(ApiClient.format.writes(apiClient), Json.obj("id" -> "no-name"))
      readOk(format, minimalJson ++ Json.obj("clients" -> clients)).clients mustBe Seq(apiClient)
    }

    "drop the deployments that cannot be read" in {
      val deployments = Json.arr(Json.obj("id" -> "no-owner", "apiRef" -> "api-1"), ApiDeployment._fmt.writes(deployment))
      readOk(format, minimalJson ++ Json.obj("deployments" -> deployments)).deployments mustBe Seq(deployment)
    }

    "drop the members that cannot be read" in {
      val members = Json.arr(Json.obj("ref" -> "user-1"), Json.obj("config" -> Json.obj()))
      readOk(format, minimalJson ++ Json.obj("members" -> members)).members mustBe Seq(UserRef("user-1", Json.obj()))
    }

    "drop every member when one of them is not an object" in {
      val members = Json.arr(Json.obj("ref" -> "user-1"), "user-2")
      readOk(format, minimalJson ++ Json.obj("members" -> members)).members mustBe empty
    }

    "read an owner without ref as the default owner" in {
      readOk(format, minimalJson ++ Json.obj("owner" -> Json.obj("config" -> Json.obj("a" -> 1)))).owner mustBe UserRef.empty
    }

    "read an owner that is not an object as the default owner" in {
      readOk(format, minimalJson ++ Json.obj("owner" -> "user-1")).owner mustBe UserRef.empty
    }

    "read a visibility that is not an object as public" in {
      readOk(format, minimalJson ++ Json.obj("visibility" -> "private")).visibility mustBe ApiVisibility.Public
    }

    "read a plan without id, failing only when its id is used" in {
      val back = readOk(format, minimalJson ++ Json.obj("plans" -> Json.arr(Json.obj("name" -> "no id"))))
      back.plans.size mustBe 1
      a[JsResultException] must be thrownBy back.plans.head.id
    }

    "drop every plan when one of them is not an object" in {
      val plans = Json.arr(planJson, "plan-2")
      readOk(format, minimalJson ++ Json.obj("plans" -> plans)).plans mustBe empty
    }

    "read a hook without ref, and a hook that is not an object, as hooks with an empty ref" in {
      val hooks = Json.arr(Json.obj("config" -> Json.obj("a" -> 1)), "hook")
      readOk(format, minimalJson ++ Json.obj("hooks" -> hooks)).hooks mustBe Seq(
        ApiStateHook("", Json.obj("a" -> 1)),
        ApiStateHook("", Json.obj())
      )
    }

    "read a documentation that is not an object as the default documentation" in {
      readOk(format, minimalJson ++ Json.obj("documentation" -> "doc")).documentation mustBe Some(ApiDocumentation())
    }

    "read a testing that is not an object as a disabled testing" in {
      readOk(format, minimalJson ++ Json.obj("testing" -> "on")).testing.enabled mustBe false
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "api-1", "Users API", "the users api", Seq("users", "v1"), Map("owner" -> "team-users"))
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "be built from its json" in {
      Api.fromJsons(full.json) mustBe full
    }

    "fail to be built from a json without id" in {
      a[Throwable] must be thrownBy Api.fromJsons(minimalJson - "id")
    }
  }

  "Api states" should {

    "be named" in {
      ApiStaging.name mustBe "staging"
      ApiPublished.name mustBe "published"
      ApiDeprecated.name mustBe "deprecated"
      ApiRemoved.name mustBe "removed"
    }

    "only leave staging for published through a deploy" in {
      Api.isValidStateTransition(ApiStaging, ApiStaging, viaDeploy = false) mustBe true
      Api.isValidStateTransition(ApiStaging, ApiPublished, viaDeploy = false) mustBe false
      Api.isValidStateTransition(ApiStaging, ApiPublished, viaDeploy = true) mustBe true
      Api.isValidStateTransition(ApiStaging, ApiDeprecated, viaDeploy = true) mustBe false
      Api.isValidStateTransition(ApiStaging, ApiRemoved, viaDeploy = true) mustBe false
    }

    "move a published api to deprecated or removed but not back to staging" in {
      Api.isValidStateTransition(ApiPublished, ApiPublished, viaDeploy = false) mustBe true
      Api.isValidStateTransition(ApiPublished, ApiDeprecated, viaDeploy = false) mustBe true
      Api.isValidStateTransition(ApiPublished, ApiRemoved, viaDeploy = false) mustBe true
      Api.isValidStateTransition(ApiPublished, ApiStaging, viaDeploy = true) mustBe false
    }

    "republish a deprecated api without a deploy" in {
      Api.isValidStateTransition(ApiDeprecated, ApiPublished, viaDeploy = false) mustBe true
      Api.isValidStateTransition(ApiDeprecated, ApiDeprecated, viaDeploy = false) mustBe true
      Api.isValidStateTransition(ApiDeprecated, ApiRemoved, viaDeploy = false) mustBe true
      Api.isValidStateTransition(ApiDeprecated, ApiStaging, viaDeploy = true) mustBe false
    }

    "only reopen a removed api in staging" in {
      Api.isValidStateTransition(ApiRemoved, ApiStaging, viaDeploy = false) mustBe true
      Api.isValidStateTransition(ApiRemoved, ApiRemoved, viaDeploy = false) mustBe true
      Api.isValidStateTransition(ApiRemoved, ApiPublished, viaDeploy = true) mustBe false
      Api.isValidStateTransition(ApiRemoved, ApiDeprecated, viaDeploy = true) mustBe false
    }

    "describe a refused transition" in {
      Api.transitionError(ApiStaging, ApiRemoved) mustBe Json.obj(
        "error"             -> "invalid_state_transition",
        "error_description" -> "transition from 'staging' to 'removed' is not allowed",
        "from"              -> "staging",
        "to"                -> "removed",
        "http_status_code"  -> 400
      )
    }
  }

  "Api production locked fields" should {

    "find no difference between an api and itself" in {
      Api.diffProtectedFields(full, full) mustBe empty
    }

    "list each locked field that changed, in order" in {
      val changed = full.copy(
        domain = "other.oto.tools",
        contextPath = "/other",
        routes = Seq.empty,
        backends = Seq.empty,
        flows = Seq.empty,
        documentation = None,
        testing = testing.copy(enabled = false)
      )
      Api.diffProtectedFields(full, changed) mustBe Seq(
        "domain",
        "contextPath",
        "routes",
        "backends",
        "flows",
        "documentation",
        "testing"
      )
    }

    "leave the state, plans, clients and metadata freely editable" in {
      val changed = full.copy(
        state = ApiDeprecated,
        plans = Seq.empty,
        clients = Seq.empty,
        metadata = Map.empty,
        name = "renamed",
        enabled = true,
        visibility = ApiVisibility.Public
      )
      Api.diffProtectedFields(full, changed) mustBe empty
    }

    "write the locked fields only in a deployment definition" in {
      Api.deploymentDefinition(full) mustBe Json.obj(
        "routes"        -> Json.arr(ApiRoute._fmt.writes(route)),
        "backends"      -> Json.arr(ApiBackend._fmt.writes(backend)),
        "flows"         -> Json.arr(ApiFlows._fmt.writes(flow)),
        "documentation" -> ApiDocumentation._fmt.writes(documentation)
      )
    }

    "write a null documentation in the deployment definition of an api without one" in {
      (Api.deploymentDefinition(full.copy(documentation = None)) \ "documentation").get mustBe JsNull
    }

    "tell the apis written by a remote catalog" in {
      Api.isWrittenByCatalog(full.copy(metadata = Map("created_by" -> "remote_catalog=cat-1"))) mustBe true
      Api.isWrittenByCatalog(full.copy(metadata = Map("created_by" -> "admin"))) mustBe false
      Api.isWrittenByCatalog(full.copy(metadata = Map.empty)) mustBe false
    }
  }

  "Api computed plugins" should {

    "leave out the plans without plugins" in {
      full.computedPluginsByPlan mustBe empty
    }

    "resolve the flow a plan points at" in {
      val plan = ApiPlan(planJson ++ Json.obj("plugins" -> Json.obj("plugins" -> Json.arr(), "flow_ref" -> "flow-1")))
      full.copy(plans = Seq(plan)).computedPluginsByPlan mustBe Map(
        "plan-1" -> NgPluginsWithOverride(flow.plugins, overrides = false)
      )
    }
  }

  "ApiRoute" should {

    val format = ApiRoute._fmt

    "write a route" in {
      format.writes(route) mustBe Json.obj(
        "id"       -> "route-1",
        "enabled"  -> false,
        "name"     -> "users",
        "frontend" -> frontend.json,
        "backend"  -> "backend-1",
        "flow_ref" -> "flow-1"
      )
    }

    "write a missing name as null" in {
      (written(format, route.copy(name = None)) \ "name").get mustBe JsNull
    }

    "round trip a route" in {
      roundTrip(format, route)
    }

    "round trip a route without name" in {
      roundTrip(format, route.copy(name = None)).name mustBe None
    }

    "read a minimal route with the defaults" in {
      readOk(format, Json.obj("id" -> "r", "backend" -> "b")) mustBe ApiRoute(
        id = "r",
        enabled = true,
        name = None,
        frontend = NgFrontend.empty,
        flowRef = "",
        backend = "b"
      )
    }

    "read the legacy plugin_chain key as the flow reference" in {
      readOk(format, Json.obj("id" -> "r", "backend" -> "b", "plugin_chain" -> "legacy-flow")).flowRef mustBe "legacy-flow"
    }

    "prefer the legacy plugin_chain key over flow_ref" in {
      readOk(format, Json.obj("id" -> "r", "backend" -> "b", "plugin_chain" -> "legacy", "flow_ref" -> "modern")).flowRef mustBe "legacy"
    }

    "write the legacy flow reference under flow_ref" in {
      val back = readOk(format, Json.obj("id" -> "r", "backend" -> "b", "plugin_chain" -> "legacy-flow"))
      written(format, back).keys must contain("flow_ref")
      written(format, back).keys must not contain "plugin_chain"
    }

    "not read a route without backend" in {
      readError(format, Json.obj("id" -> "r"))
    }

    "not read a route without id" in {
      readError(format, Json.obj("backend" -> "b"))
    }

    "ignore an enabled flag that is not a boolean" in {
      readOk(format, Json.obj("id" -> "r", "backend" -> "b", "enabled" -> "false")).enabled mustBe true
    }

    "read a frontend that is not an object as the empty frontend" in {
      readOk(format, Json.obj("id" -> "r", "backend" -> "b", "frontend" -> "/users")).frontend mustBe NgFrontend.empty
    }
  }

  "ApiBackend" should {

    val format = ApiBackend._fmt

    "write a backend" in {
      format.writes(backend) mustBe Json.obj(
        "id"      -> "backend-1",
        "name"    -> "users backend",
        "backend" -> backend.backend.json,
        "client"  -> "client-1"
      )
    }

    "round trip a backend" in {
      roundTrip(format, backend)
    }

    "read a missing client as the default backend client" in {
      readOk(format, written(format, backend) - "client").client mustBe "default_backend_client"
    }

    "not read a backend without backend definition" in {
      readError(format, written(format, backend) - "backend")
    }

    "read a backend definition that is not an object as the empty backend" in {
      readOk(format, written(format, backend) ++ Json.obj("backend" -> "http://users")).backend mustBe NgBackend.empty
    }

    "not read a backend without name" in {
      readError(format, written(format, backend) - "name")
    }

    "not read a backend without id" in {
      readError(format, written(format, backend) - "id")
    }
  }

  "ApiFlows" should {

    val format = ApiFlows._fmt

    "write a flow" in {
      format.writes(flow) mustBe Json.obj("id" -> "flow-1", "name" -> "main flow", "plugins" -> flow.plugins.json)
    }

    "round trip a flow" in {
      roundTrip(format, flow)
    }

    "round trip a flow without plugins" in {
      roundTrip(format, ApiFlows("f", "f", NgPlugins.empty))
    }

    "read missing plugins as no plugin" in {
      readOk(format, Json.obj("id" -> "f", "name" -> "f")).plugins mustBe NgPlugins.empty
    }

    "not read a flow without id" in {
      readError(format, Json.obj("name" -> "f"))
    }
  }

  "ApiBackendClient" should {

    val format = ApiBackendClient._fmt

    "have a default client" in {
      ApiBackendClient.defaultClient mustBe ApiBackendClient("default_backend_client", "default_backend_client", NgClientConfig.default)
    }

    "write a client" in {
      format.writes(backendClient) mustBe Json.obj("id" -> "client-1", "name" -> "fast client", "client" -> backendClient.client.json)
    }

    "round trip a client" in {
      roundTrip(format, backendClient)
    }

    "read a missing client config as the default one" in {
      readOk(format, Json.obj("id" -> "c", "name" -> "c")).client mustBe NgClientConfig.default
    }

    "not read a client without name" in {
      readError(format, Json.obj("id" -> "c"))
    }
  }

  "ApiTesting" should {

    val format = ApiTesting._fmt

    "be disabled by default with the otoroshi testing header" in {
      val default = ApiTesting()
      default.enabled mustBe false
      default.headerKey mustBe "X-OTOROSHI-TESTING"
    }

    "draw a new header value for each default testing" in {
      ApiTesting().headerValue must not be ApiTesting().headerValue
    }

    "write a testing" in {
      format.writes(testing) mustBe Json.obj("enabled" -> true, "headerKey" -> "X-TEST", "headerValue" -> "secret-value")
    }

    "round trip a testing" in {
      roundTrip(format, testing)
    }

    "read a missing header key as the otoroshi testing header" in {
      readOk(format, Json.obj("enabled" -> true, "headerValue" -> "v")) mustBe ApiTesting(true, "X-OTOROSHI-TESTING", "v")
    }

    "read a missing header value as a random one" in {
      readOk(format, Json.obj()).headerValue must not be readOk(format, Json.obj()).headerValue
    }

    "not read the header key from a snake case key" in {
      readOk(format, Json.obj("header_key" -> "X-OTHER", "headerValue" -> "v")).headerKey mustBe "X-OTOROSHI-TESTING"
    }

    "ignore an enabled flag that is not a boolean" in {
      readOk(format, Json.obj("enabled" -> "true", "headerValue" -> "v")).enabled mustBe false
    }
  }

  "UserRef" should {

    val format = UserRef.format

    "default to the owner ref" in {
      UserRef.empty mustBe UserRef("owner", Json.obj())
    }

    "write a user ref" in {
      format.writes(UserRef("user-1", Json.obj("a" -> 1))) mustBe Json.obj("ref" -> "user-1", "config" -> Json.obj("a" -> 1))
    }

    "round trip a user ref" in {
      roundTrip(format, UserRef("user-1", Json.obj("a" -> 1)))
    }

    "read a missing config as an empty one" in {
      readOk(format, Json.obj("ref" -> "user-1")) mustBe UserRef("user-1", Json.obj())
    }

    "not read a user ref without ref" in {
      readError(format, Json.obj("config" -> Json.obj()))
    }

    "expose through json what the format writes" in {
      UserRef("u", Json.obj()).json mustBe format.writes(UserRef("u", Json.obj()))
    }
  }

  "ApiVisibility" should {

    val format = ApiVisibility.format

    "name its kinds" in {
      Seq(ApiVisibilityKind.Public, ApiVisibilityKind.SemiPublic, ApiVisibilityKind.Private, ApiVisibilityKind.Custom)
        .map(_.name) mustBe Seq("public", "semi_public", "private", "custom")
    }

    "read an unknown kind as private" in {
      ApiVisibilityKind("everyone") mustBe ApiVisibilityKind.Private
    }

    "write a visibility" in {
      format.writes(ApiVisibility(ApiVisibilityKind.Custom, Json.obj("a" -> 1))) mustBe Json.obj(
        "kind"   -> "custom",
        "config" -> Json.obj("a" -> 1)
      )
    }

    "round trip a visibility" in {
      roundTrip(format, ApiVisibility(ApiVisibilityKind.Private, Json.obj("a" -> 1)))
    }

    "read a missing kind as public" in {
      readOk(format, Json.obj()) mustBe ApiVisibility.Public
    }

    "read an unknown kind in json as private" in {
      readOk(format, Json.obj("kind" -> "PUBLIC")).kind mustBe ApiVisibilityKind.Private
    }

    "read a config that is not an object as an empty config" in {
      readOk(format, Json.obj("kind" -> "public", "config" -> "x")).config mustBe Json.obj()
    }
  }

  "ApiStateHook" should {

    val format = ApiStateHook.format

    "write a hook" in {
      format.writes(hook) mustBe Json.obj("ref" -> "hook-1", "config" -> Json.obj("url" -> "https://hooks.oto.tools"))
    }

    "round trip a hook" in {
      roundTrip(format, hook)
    }

    "read an empty object as an empty hook" in {
      readOk(format, Json.obj()) mustBe ApiStateHook("", Json.obj())
    }

    "expose through json what the format writes" in {
      hook.json mustBe format.writes(hook)
    }
  }

  "ApiClient" should {

    val format = ApiClient.format

    "write a client" in {
      format.writes(apiClient) mustBe Json.obj(
        "id"          -> "client-app-1",
        "name"        -> "mobile app",
        "description" -> "the mobile app",
        "tags"        -> Json.arr("mobile"),
        "metadata"    -> Json.obj("team" -> "app")
      )
    }

    "write a missing description as null" in {
      (written(format, ApiClient("c", "c")) \ "description").get mustBe JsNull
    }

    "round trip a client" in {
      roundTrip(format, apiClient)
    }

    "round trip a client without description" in {
      roundTrip(format, ApiClient("c", "c")).description mustBe None
    }

    "not read a client without id" in {
      readError(format, Json.obj("name" -> "c"))
    }
  }

  "ApiDeployment" should {

    val format = ApiDeployment._fmt

    "write a deployment" in {
      format.writes(deployment) mustBe Json.obj(
        "id"            -> "deployment-1",
        "apiRef"        -> "api-1",
        "owner"         -> "admin",
        "at"            -> 1700000000000L,
        "apiDefinition" -> Json.obj("routes" -> Json.arr()),
        "version"       -> "1.0.0"
      )
    }

    "round trip a deployment" in {
      roundTrip(format, deployment)
    }

    "read a deployment without id, date nor version with the defaults" in {
      val before = System.currentTimeMillis()
      val back   = readOk(format, Json.obj("apiRef" -> "api-1", "owner" -> "admin", "apiDefinition" -> Json.obj()))
      back.id must startWith("api_deployment_")
      back.at.getMillis must be >= before
      back.version mustBe "0.0.1"
    }

    "not read a deployment without api definition" in {
      readError(format, written(format, deployment) - "apiDefinition")
    }

    "not read a deployment without owner" in {
      readError(format, written(format, deployment) - "owner")
    }

    "read a date that is not a number as now" in {
      val before = System.currentTimeMillis()
      readOk(format, written(format, deployment) ++ Json.obj("at" -> "2024-01-01")).at.getMillis must be >= before
    }
  }
}
