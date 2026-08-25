package plugins

import functional.PluginsTestSpec
import org.apache.pekko.stream.Materializer
import otoroshi.env.Env
import otoroshi.models.{ApiKey, EntityLocation, RouteIdentifier}
import otoroshi.next.models.{
  Api,
  ApiBlueprint,
  ApiFlows,
  ApiPlan,
  ApiPlanPlugins,
  ApiStaging,
  ApiRef,
  ApiTesting,
  NgPluginInstance,
  NgPluginInstanceConfig,
  NgPlugins,
  NgPluginsWithOverride
}
import otoroshi.next.plugins.api.*
import otoroshi.next.plugins.{ApikeyCalls, NgApikeyCallsConfig, OverrideHost}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import play.api.libs.ws.WSAuthScheme
import play.api.libs.ws.WSBodyWritables.writeableOf_String
import play.api.mvc.{Result, Results}

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

// counters keyed by the "tag" of each plugin instance, so a single probe class can stand for the
// route plugins, the apikey flow plugins and the global plugins at the same time.
object ApikeyFlowProbes {
  private val counters = new ConcurrentHashMap[String, AtomicInteger]()
  private val order    = new ConcurrentLinkedQueue[String]()

  private def key(phase: String, tag: String): String = s"$phase:$tag"

  def bump(phase: String, tag: String): Unit = {
    counters.computeIfAbsent(key(phase, tag), _ => new AtomicInteger(0)).incrementAndGet()
    order.add(key(phase, tag))
  }

  def count(phase: String, tag: String): Int =
    Option(counters.get(key(phase, tag))).map(_.get()).getOrElse(0)

  // tags of the plugins that transformed the request, in execution order
  def transformOrder: Seq[String] =
    order.asScala.toSeq.filter(_.startsWith("transform:")).map(_.substring("transform:".length))

  def reset(): Unit = {
    counters.clear()
    order.clear()
  }

  def tagOf(config: JsValue): String = config.select("tag").asOpt[String].getOrElse("untagged")
}

// a transformer with callbacks on (the NgRequestTransformer default), so it also exercises the
// beforeRequest/afterRequest pairing.
class ApikeyFlowProbe extends NgRequestTransformer {
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgInternal
  override def categories: Seq[NgPluginCategory]           = Seq.empty
  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest)
  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def isTransformRequestAsync: Boolean            = false

  override def beforeRequest(
      ctx: NgBeforeRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    ApikeyFlowProbes.bump("before", ApikeyFlowProbes.tagOf(ctx.config))
    ().vfuture
  }

  override def afterRequest(
      ctx: NgAfterRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    ApikeyFlowProbes.bump("after", ApikeyFlowProbes.tagOf(ctx.config))
    ().vfuture
  }

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    ApikeyFlowProbes.bump("transform", ApikeyFlowProbes.tagOf(ctx.config))
    Right(ctx.otoroshiRequest)
  }
}

// an access validator, to check that an overriding flow keeps the route access validators that
// already passed, and that a flow validator can reject the call.
class ApikeyFlowValidatorProbe extends NgAccessValidator {
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgInternal
  override def categories: Seq[NgPluginCategory]           = Seq.empty
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def isAccessAsync: Boolean                      = false

  override def accessSync(ctx: NgAccessContext)(using env: Env, ec: ExecutionContext): NgAccess = {
    val tag = ApikeyFlowProbes.tagOf(ctx.config)
    ApikeyFlowProbes.bump("access", tag)
    if (ctx.config.select("deny").asOpt[Boolean].getOrElse(false)) {
      NgAccess.NgDenied(Results.Forbidden(Json.obj("error" -> "denied-by-flow", "tag" -> tag)))
    } else {
      NgAccess.NgAllowed
    }
  }
}

class ApikeyPluginsFlowTests(parent: PluginsTestSpec) {
  import parent.*

  private def probe(tag: String): NgPluginInstance = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[ApikeyFlowProbe],
    config = NgPluginInstanceConfig(Json.obj("tag" -> tag))
  )

  private def validator(tag: String, deny: Boolean = false): NgPluginInstance = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[ApikeyFlowValidatorProbe],
    config = NgPluginInstanceConfig(Json.obj("tag" -> tag, "deny" -> deny))
  )

  private val overrideHost: NgPluginInstance = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[OverrideHost]
  )

  // an overriding flow replaces the route transformers, OverrideHost included, so a flow that wants
  // to reach the same backend has to declare it again. that is the point of an override.
  private def createApi(api: Api): Unit = {
    ws.url(s"http://localhost:$port/apis/apis.otoroshi.io/v1/apis")
      .withHttpHeaders("Host" -> "otoroshi-api.oto.tools", "Content-Type" -> "application/json")
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .post(Json.stringify(api.json))
      .futureValue
      .status mustBe 201
    await(2.seconds)
  }

  private def deleteApi(api: Api): Unit = {
    ws.url(s"http://localhost:$port/apis/apis.otoroshi.io/v1/apis/${api.id}")
      .withHttpHeaders("Host" -> "otoroshi-api.oto.tools")
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .delete()
      .futureValue
  }

  private def apikeyCalls(wipe: Boolean = true): NgPluginInstance = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[ApikeyCalls],
    config = NgPluginInstanceConfig(NgApikeyCallsConfig(wipeBackendRequest = wipe).json.as[JsObject])
  )

  private def flow(overrides: Boolean, plugins: NgPluginInstance*): NgPluginsWithOverride =
    NgPluginsWithOverride(NgPlugins(plugins.toSeq), overrides)

  private def planFlow(overrides: Boolean, plugins: NgPluginInstance*): ApiPlanPlugins =
    ApiPlanPlugins(NgPlugins(plugins.toSeq), overrides)

  private def callWith(routeDomain: String, apikey: ApiKey) = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host"                   -> routeDomain,
      "Otoroshi-Client-Id"     -> apikey.clientId,
      "Otoroshi-Client-Secret" -> apikey.clientSecret
    )
    .get()
    .futureValue

  // ---------------------------------------------------------------------------------------------
  // resolution of the flow itself, without going through the engine
  // ---------------------------------------------------------------------------------------------

  private def planWith(id: String, plugins: Option[ApiPlanPlugins]): ApiPlan = ApiPlan(
    Json
      .obj("id" -> id, "name" -> id, "access_mode_configuration_type" -> "keyless")
      .applyOnWithOpt(plugins) { case (obj, plgs) => obj ++ Json.obj("plugins" -> plgs.json) }
  )

  private def apiWith(id: String, plans: Seq[ApiPlan]): Api = Api(
    location = EntityLocation.default,
    id = id,
    name = id,
    description = "",
    domain = "api.oto.tools",
    contextPath = "/v1",
    version = "0.0.1",
    debugFlow = false,
    capture = false,
    exportReporting = false,
    groups = Seq.empty,
    state = ApiStaging,
    blueprint = ApiBlueprint.REST,
    testing = ApiTesting(),
    plans = plans
  )

  private def keyWith(
      apiRef: Option[ApiRef] = None,
      plugins: Option[NgPluginsWithOverride] = None
  ): ApiKey = ApiKey(
    clientId = s"client-${IdGenerator.uuid}",
    clientSecret = "1234",
    clientName = s"name-${IdGenerator.uuid}",
    authorizedEntities = Seq.empty,
    apiRef = apiRef,
    plugins = plugins
  )

  def planComputedPlugins(): Unit = {
    // no plugins at all
    planWith("p1", None).hasPlugins mustBe false
    // an empty plugin list is not "having plugins"
    planWith("p2", planFlow(overrides = false).some).hasPlugins mustBe false
    // an override with an empty list is not an instruction either
    planWith("p3", planFlow(overrides = true).some).hasPlugins mustBe false
    // an actual plugin
    val plan = planWith("p4", planFlow(overrides = true, probe("plan")).some)
    plan.hasPlugins mustBe true
    val api = apiWith(s"api_${IdGenerator.uuid}", Seq(plan))
    plan.computedPlugins(api).plugins.slots.size mustBe 1
    plan.computedPlugins(api).overrides mustBe true
  }

  def pluginFlowResolution(): Unit = {
    // nothing to do at all
    keyWith().pluginFlow(env) mustBe None
    // an empty local plugin list resolves to nothing
    keyWith(plugins = flow(overrides = false).some).pluginFlow(env) mustBe None

    // local plugins only, no api reference
    val localOnly = keyWith(plugins = flow(overrides = false, probe("local")).some).pluginFlow(env)
    localOnly.map(_.plugins.slots.size) mustBe Some(1)
    localOnly.map(_.overrides) mustBe Some(false)

    // an api reference that resolves to nothing falls back on the local plugins
    val danglingRef = keyWith(
      apiRef = ApiRef("missing-api", "missing-plan", "sub").some,
      plugins = flow(overrides = false, probe("local")).some
    ).pluginFlow(env)
    danglingRef.map(_.plugins.slots.size) mustBe Some(1)

    // a dangling reference with no local plugins resolves to nothing
    keyWith(apiRef = ApiRef("missing-api", "missing-plan", "sub").some).pluginFlow(env) mustBe None

    val apiId = s"api_${IdGenerator.uuid}"
    env.proxyState.updateApis(
      Seq(
        apiWith(
          apiId,
          Seq(
            planWith("plan-plain", planFlow(overrides = false, probe("plan")).some),
            planWith("plan-override", planFlow(overrides = true, probe("plan")).some),
            planWith("plan-empty", None)
          )
        )
      )
    )

    // plan plugins alone
    val planOnly = keyWith(apiRef = ApiRef(apiId, "plan-plain", "sub").some).pluginFlow(env)
    planOnly.map(_.plugins.slots.size) mustBe Some(1)
    planOnly.map(_.overrides) mustBe Some(false)

    // plan plugins come first, local ones are appended
    val planAndLocal = keyWith(
      apiRef = ApiRef(apiId, "plan-plain", "sub").some,
      plugins = flow(overrides = false, probe("local")).some
    ).pluginFlow(env)
    planAndLocal.map(_.plugins.slots.size) mustBe Some(2)
    planAndLocal.map(_.plugins.slots.map(s => s.config.raw.select("tag").asString)) mustBe Some(Seq("plan", "local"))

    // override is the OR of the plan one and the local one
    keyWith(apiRef = ApiRef(apiId, "plan-override", "sub").some).pluginFlow(env).map(_.overrides) mustBe Some(true)
    keyWith(
      apiRef = ApiRef(apiId, "plan-plain", "sub").some,
      plugins = flow(overrides = true, probe("local")).some
    ).pluginFlow(env).map(_.overrides) mustBe Some(true)

    // a plan without plugins behaves like no plan at all
    keyWith(apiRef = ApiRef(apiId, "plan-empty", "sub").some).pluginFlow(env) mustBe None
    keyWith(
      apiRef = ApiRef(apiId, "plan-empty", "sub").some,
      plugins = flow(overrides = false, probe("local")).some
    ).pluginFlow(env).map(_.plugins.slots.size) mustBe Some(1)
  }

  // ---------------------------------------------------------------------------------------------
  // the flow as applied by the engine
  // ---------------------------------------------------------------------------------------------

  def noFlow(): Unit = {
    ApikeyFlowProbes.reset()
    val route  = createRouteWithExternalTarget(
      Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]), apikeyCalls(), probe("nf-route"))
    ).futureValue
    val apikey = keyWith().copy(authorizedEntities = Seq(RouteIdentifier(route.id)))
    createOtoroshiApiKey(apikey).futureValue

    val resp = callWith(route.frontend.domains.head.domain, apikey)
    resp.status mustBe 200
    // an apikey with no flow leaves the chain strictly alone
    ApikeyFlowProbes.count("transform", "nf-route") mustBe 1
    ApikeyFlowProbes.count("before", "nf-route") mustBe 1
    ApikeyFlowProbes.count("after", "nf-route") mustBe 1

    deleteOtoroshiApiKey(apikey).futureValue
    deleteOtoroshiRoute(route).futureValue
  }

  def mergeWithoutOverride(): Unit = {
    ApikeyFlowProbes.reset()
    val route  = createRouteWithExternalTarget(
      Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]), apikeyCalls(), probe("mg-route"))
    ).futureValue
    val apikey = keyWith(plugins = flow(overrides = false, probe("mg-flow")).some)
      .copy(authorizedEntities = Seq(RouteIdentifier(route.id)))
    createOtoroshiApiKey(apikey).futureValue

    val resp = callWith(route.frontend.domains.head.domain, apikey)
    resp.status mustBe 200
    // both the route plugin and the flow plugin transform the request
    ApikeyFlowProbes.count("transform", "mg-route") mustBe 1
    ApikeyFlowProbes.count("transform", "mg-flow") mustBe 1
    // and both get their callback pair
    ApikeyFlowProbes.count("before", "mg-flow") mustBe 1
    ApikeyFlowProbes.count("after", "mg-flow") mustBe 1
    ApikeyFlowProbes.count("after", "mg-route") mustBe 1

    deleteOtoroshiApiKey(apikey).futureValue
    deleteOtoroshiRoute(route).futureValue
  }

  def overrideDropsRouteTransformers(): Unit = {
    ApikeyFlowProbes.reset()
    val route  = createRouteWithExternalTarget(
      Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]), apikeyCalls(), probe("ov-route"))
    ).futureValue
    val apikey = keyWith(plugins = flow(overrides = true, overrideHost, probe("ov-flow")).some)
      .copy(authorizedEntities = Seq(RouteIdentifier(route.id)))
    createOtoroshiApiKey(apikey).futureValue

    val resp = callWith(route.frontend.domains.head.domain, apikey)
    resp.status mustBe 200
    // the flow replaces the route transformers
    ApikeyFlowProbes.count("transform", "ov-flow") mustBe 1
    ApikeyFlowProbes.count("transform", "ov-route") mustBe 0
    // but the evicted route plugin still gets its afterRequest, since it got its beforeRequest
    ApikeyFlowProbes.count("before", "ov-route") mustBe 1
    ApikeyFlowProbes.count("after", "ov-route") mustBe 1

    deleteOtoroshiApiKey(apikey).futureValue
    deleteOtoroshiRoute(route).futureValue
  }

  def overrideKeepsAccessValidators(): Unit = {
    ApikeyFlowProbes.reset()
    val route  = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        apikeyCalls(),
        validator("ov-validator"),
        probe("ov2-route")
      )
    ).futureValue
    val apikey = keyWith(plugins = flow(overrides = true, overrideHost, probe("ov2-flow")).some)
      .copy(authorizedEntities = Seq(RouteIdentifier(route.id)))
    createOtoroshiApiKey(apikey).futureValue

    val resp = callWith(route.frontend.domains.head.domain, apikey)
    resp.status mustBe 200
    // the route access validator ran, and was kept in the merged chain rather than dropped
    ApikeyFlowProbes.count("access", "ov-validator") mustBe 1
    ApikeyFlowProbes.count("transform", "ov2-route") mustBe 0
    // ApikeyCalls is an access validator too, so it survives the override and still wipes the
    // credential from the backend request
    getInHeader(resp, "Otoroshi-Client-Id") mustBe None

    deleteOtoroshiApiKey(apikey).futureValue
    deleteOtoroshiRoute(route).futureValue
  }

  def flowCanRejectTheCall(): Unit = {
    ApikeyFlowProbes.reset()
    val route  = createRouteWithExternalTarget(
      Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]), apikeyCalls(), probe("rj-route"))
    ).futureValue
    val apikey = keyWith(plugins = flow(overrides = false, validator("rj-flow", deny = true)).some)
      .copy(authorizedEntities = Seq(RouteIdentifier(route.id)))
    createOtoroshiApiKey(apikey).futureValue

    val resp = callWith(route.frontend.domains.head.domain, apikey)
    // the flow access validation breaks the call
    resp.status mustBe 403
    ApikeyFlowProbes.count("access", "rj-flow") mustBe 1
    // and the backend is never called, so no transformation happens
    ApikeyFlowProbes.count("transform", "rj-route") mustBe 0

    deleteOtoroshiApiKey(apikey).futureValue
    deleteOtoroshiRoute(route).futureValue
  }

  def extraPluginOnlyForThatApikey(): Unit = {
    val route    = createRouteWithExternalTarget(
      Seq(overrideHost, apikeyCalls(), probe("sc-route"))
    ).futureValue
    val withFlow = keyWith(plugins = flow(overrides = false, probe("sc-flow")).some)
      .copy(authorizedEntities = Seq(RouteIdentifier(route.id)))
    val plainKey = keyWith().copy(authorizedEntities = Seq(RouteIdentifier(route.id)))
    createOtoroshiApiKey(withFlow).futureValue
    createOtoroshiApiKey(plainKey).futureValue
    val domain   = route.frontend.domains.head.domain

    // same route, same call: the apikey carrying the flow gets one extra plugin on top of the
    // route chain
    ApikeyFlowProbes.reset()
    callWith(domain, withFlow).status mustBe 200
    ApikeyFlowProbes.count("transform", "sc-route") mustBe 1
    ApikeyFlowProbes.count("transform", "sc-flow") mustBe 1

    // the other apikey runs the route chain and nothing else
    ApikeyFlowProbes.reset()
    callWith(domain, plainKey).status mustBe 200
    ApikeyFlowProbes.count("transform", "sc-route") mustBe 1
    ApikeyFlowProbes.count("transform", "sc-flow") mustBe 0

    // and the flow does not leak to the next call made with the flow-carrying apikey either
    ApikeyFlowProbes.reset()
    callWith(domain, withFlow).status mustBe 200
    ApikeyFlowProbes.count("transform", "sc-flow") mustBe 1

    deleteOtoroshiApiKey(withFlow).futureValue
    deleteOtoroshiApiKey(plainKey).futureValue
    deleteOtoroshiRoute(route).futureValue
  }

  def overrideOnlyForThatApikey(): Unit = {
    val route    = createRouteWithExternalTarget(
      Seq(overrideHost, apikeyCalls(), probe("so-route"))
    ).futureValue
    val withFlow = keyWith(plugins = flow(overrides = true, overrideHost, probe("so-flow")).some)
      .copy(authorizedEntities = Seq(RouteIdentifier(route.id)))
    val plainKey = keyWith().copy(authorizedEntities = Seq(RouteIdentifier(route.id)))
    createOtoroshiApiKey(withFlow).futureValue
    createOtoroshiApiKey(plainKey).futureValue
    val domain   = route.frontend.domains.head.domain

    // the override replaces the route chain for that apikey only
    ApikeyFlowProbes.reset()
    callWith(domain, withFlow).status mustBe 200
    ApikeyFlowProbes.count("transform", "so-flow") mustBe 1
    ApikeyFlowProbes.count("transform", "so-route") mustBe 0

    // the other apikey still gets the full route chain on the very same route
    ApikeyFlowProbes.reset()
    callWith(domain, plainKey).status mustBe 200
    ApikeyFlowProbes.count("transform", "so-route") mustBe 1
    ApikeyFlowProbes.count("transform", "so-flow") mustBe 0

    deleteOtoroshiApiKey(withFlow).futureValue
    deleteOtoroshiApiKey(plainKey).futureValue
    deleteOtoroshiRoute(route).futureValue
  }

  // ---------------------------------------------------------------------------------------------
  // full matrix: where the flow comes from x who declares the override
  // ---------------------------------------------------------------------------------------------

  private case class FlowCase(
      name: String,
      planPlugins: Option[ApiPlanPlugins],
      keyPlugins: Option[NgPluginsWithOverride],
      expectedOrder: Seq[String]
  )

  private def runFlowCase(c: FlowCase): Unit = withClue(s"case '${c.name}': ") {
    val route    = createRouteWithExternalTarget(Seq(overrideHost, apikeyCalls(), probe("route"))).futureValue
    val domain   = route.frontend.domains.head.domain
    val api      = c.planPlugins.map { plgs =>
      val a = apiWith(s"api_${IdGenerator.uuid}", Seq(planWith("plan-1", plgs.some)))
      createApi(a)
      a
    }
    val apikey   = keyWith(
      apiRef = api.map(a => ApiRef(a.id, "plan-1", "sub")),
      plugins = c.keyPlugins
    ).copy(authorizedEntities = Seq(RouteIdentifier(route.id)))
    val plainKey = keyWith().copy(authorizedEntities = Seq(RouteIdentifier(route.id)))
    createOtoroshiApiKey(apikey).futureValue
    createOtoroshiApiKey(plainKey).futureValue

    // the apikey carrying the flow
    ApikeyFlowProbes.reset()
    callWith(domain, apikey).status mustBe 200
    ApikeyFlowProbes.transformOrder mustBe c.expectedOrder
    // ApikeyCalls survives every case, so the credential never reaches the backend
    getInHeader(callWith(domain, apikey), "Otoroshi-Client-Id") mustBe None

    // the very same route, called with an apikey that carries no flow at all, is untouched
    ApikeyFlowProbes.reset()
    callWith(domain, plainKey).status mustBe 200
    ApikeyFlowProbes.transformOrder mustBe Seq("route")

    deleteOtoroshiApiKey(apikey).futureValue
    deleteOtoroshiApiKey(plainKey).futureValue
    deleteOtoroshiRoute(route).futureValue
    api.foreach(deleteApi)
  }

  // an overriding flow drops the route transformers, OverrideHost included, so whichever side
  // overrides has to declare it again for the backend to stay reachable.
  private val cases: Seq[FlowCase] = Seq(
    FlowCase(
      "apikey only, no override",
      planPlugins = None,
      keyPlugins = flow(overrides = false, probe("key")).some,
      expectedOrder = Seq("route", "key")
    ),
    FlowCase(
      "apikey only, override",
      planPlugins = None,
      keyPlugins = flow(overrides = true, overrideHost, probe("key")).some,
      expectedOrder = Seq("key")
    ),
    FlowCase(
      "plan only, no override",
      planPlugins = planFlow(overrides = false, probe("plan")).some,
      keyPlugins = None,
      expectedOrder = Seq("route", "plan")
    ),
    FlowCase(
      "plan only, override on the plan",
      planPlugins = planFlow(overrides = true, overrideHost, probe("plan")).some,
      keyPlugins = None,
      expectedOrder = Seq("plan")
    ),
    FlowCase(
      "plan and apikey, no override",
      planPlugins = planFlow(overrides = false, probe("plan")).some,
      keyPlugins = flow(overrides = false, probe("key")).some,
      expectedOrder = Seq("route", "plan", "key")
    ),
    FlowCase(
      "plan and apikey, override on the plan",
      planPlugins = planFlow(overrides = true, overrideHost, probe("plan")).some,
      keyPlugins = flow(overrides = false, probe("key")).some,
      expectedOrder = Seq("plan", "key")
    ),
    FlowCase(
      "plan and apikey, override on the apikey",
      planPlugins = planFlow(overrides = false, overrideHost, probe("plan")).some,
      keyPlugins = flow(overrides = true, probe("key")).some,
      expectedOrder = Seq("plan", "key")
    ),
    FlowCase(
      "plan and apikey, override on both",
      planPlugins = planFlow(overrides = true, overrideHost, probe("plan")).some,
      keyPlugins = flow(overrides = true, probe("key")).some,
      expectedOrder = Seq("plan", "key")
    )
  )

  def flowMatrix(): Unit = cases.foreach(runFlowCase)

  def flowFromApiPlan(): Unit = {
    ApikeyFlowProbes.reset()
    val route = createRouteWithExternalTarget(
      Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]), apikeyCalls(), probe("pl-route"))
    ).futureValue

    val apiId = s"api_${IdGenerator.uuid}"
    val api   = apiWith(apiId, Seq(planWith("plan-1", planFlow(overrides = false, probe("pl-plan")).some)))
    createApi(api)

    val apikey = keyWith(apiRef = ApiRef(apiId, "plan-1", "sub").some)
      .copy(authorizedEntities = Seq(RouteIdentifier(route.id)))
    createOtoroshiApiKey(apikey).futureValue

    val resp = callWith(route.frontend.domains.head.domain, apikey)
    resp.status mustBe 200
    // the plugins declared on the plan are applied to the call
    ApikeyFlowProbes.count("transform", "pl-plan") mustBe 1
    ApikeyFlowProbes.count("transform", "pl-route") mustBe 1

    deleteOtoroshiApiKey(apikey).futureValue
    deleteOtoroshiRoute(route).futureValue
    deleteApi(api)
  }

  def planCanReferenceAFlow(): Unit = {
    val shared = ApiFlows(
      id = "shared-chain",
      name = "shared-chain",
      plugins = NgPlugins(Seq(probe("from-flow")))
    )
    val apiId  = s"api_${IdGenerator.uuid}"

    // a plan pointing at a flow of its api gets the plugins of that flow
    val refOnly = planWith("plan-ref", ApiPlanPlugins(NgPlugins(Seq.empty), false, "shared-chain".some).some)
    val api1    = apiWith(apiId, Seq(refOnly)).copy(flows = Seq(shared))
    refOnly.hasPlugins mustBe true
    refOnly.computedPlugins(api1).plugins.slots.map(_.config.raw.select("tag").asString) mustBe Seq("from-flow")

    // an inline chain extends the referenced one rather than replacing it, flow first
    val both = planWith(
      "plan-both",
      ApiPlanPlugins(NgPlugins(Seq(probe("inline"))), false, "shared-chain".some).some
    )
    val api2 = apiWith(apiId, Seq(both)).copy(flows = Seq(shared))
    both.computedPlugins(api2).plugins.slots.map(_.config.raw.select("tag").asString) mustBe Seq(
      "from-flow",
      "inline"
    )

    // a reference that resolves to nothing simply brings nothing
    val dangling = planWith("plan-dangling", ApiPlanPlugins(NgPlugins(Seq.empty), false, "nope".some).some)
    val api3     = apiWith(apiId, Seq(dangling)).copy(flows = Seq(shared))
    dangling.computedPlugins(api3).plugins.slots.isEmpty mustBe true
  }
}
