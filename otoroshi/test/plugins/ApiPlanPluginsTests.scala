package plugins

import functional.PluginsTestSpecBase
import org.apache.pekko.actor.Props
import otoroshi.events.GatewayEvent
import otoroshi.models.ApiKey
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import otoroshi.models.{EntityLocation, GlobalJwtVerifier, HSAlgoSettings, InHeader, PassThrough, VerificationSettings}
import otoroshi.next.models.*
import otoroshi.next.plugins.*
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import play.api.libs.json.Json.JsValueWrapper

import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

// records the apikey seen by the chain, so an end to end call can prove which consumer identity the
// plan actually built
object ApiPlanApikeySeen {
  private val seen = new ConcurrentHashMap[String, String]()

  def record(tag: String, clientId: String): Unit = seen.put(tag, clientId)
  def get(tag: String): Option[String]            = Option(seen.get(tag))
  def reset(): Unit                               = seen.clear()
}

class ApiPlanApikeyProbe extends otoroshi.next.plugins.api.NgRequestTransformer {
  import otoroshi.next.plugins.api.*
  import otoroshi.env.Env
  import org.apache.pekko.stream.Materializer
  import play.api.mvc.Result
  import scala.concurrent.ExecutionContext

  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgInternal
  override def categories: Seq[NgPluginCategory]           = Seq.empty
  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest)
  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def usesCallbacks: Boolean                      = false
  override def isTransformRequestAsync: Boolean            = false

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val tag = ctx.config.select("tag").asOpt[String].getOrElse("untagged")
    ctx.attrs
      .get(otoroshi.plugins.Keys.ApiKeyKey)
      .foreach(apikey => ApiPlanApikeySeen.record(tag, apikey.clientId))
    Right(ctx.otoroshiRequest)
  }
}

class ApiPlanPluginsTests(parent: PluginsTestSpecBase) {
  import parent.*

  // -----------------------------------------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------------------------------------

  private def plan(
      id: String,
      kind: String,
      accessModeConfiguration: JsObject = Json.obj(),
      rateLimiting: Option[JsObject] = None
  ): ApiPlan = ApiPlan(
    Json
      .obj(
        "id"                             -> id,
        "name"                           -> id,
        "status"                         -> "published",
        "access_mode_configuration_type" -> kind,
        "access_mode_configuration"      -> accessModeConfiguration
      )
      .applyOnWithOpt(rateLimiting) { case (obj, rl) => obj ++ Json.obj("rateLimiting" -> rl) }
  )

  private def apiWith(plans: Seq[ApiPlan], flowPlugins: NgPlugins = NgPlugins(Seq.empty)): Api = {
    given otoroshi.env.Env = env
    val backend            = ApiBackend.empty
    val flow               = ApiFlows.empty.copy(plugins = NgPlugins(ApiFlows.empty.plugins.slots ++ flowPlugins.slots))
    val id                 = s"api_${IdGenerator.uuid}"
    Api(
      location = EntityLocation.default,
      id = id,
      name = id,
      description = "",
      domain = s"$id.oto.tools",
      contextPath = "",
      version = "0.0.1",
      debugFlow = false,
      capture = false,
      exportReporting = false,
      groups = Seq.empty,
      state = ApiPublished,
      blueprint = ApiBlueprint.REST,
      testing = ApiTesting(),
      backends = Seq(backend),
      flows = Seq(flow),
      plans = plans,
      routes = Seq(
        ApiRoute(
          id = s"apiroute_${IdGenerator.uuid}",
          frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("/"))),
          flowRef = flow.id,
          backend = backend.id
        )
      )
    )
  }

  // the single generated route of an api
  private def routeOf(api: Api): NgRoute = {
    val routes = api.toRoutes(using env).futureValue
    routes.size mustBe 1
    routes.head
  }

  private def slotOf(route: NgRoute, pluginId: String): Option[NgPluginInstance] =
    route.plugins.slots.find(_.plugin == pluginId)

  // -----------------------------------------------------------------------------------------------
  // route generation: which plugin each plan kind injects, and with which configuration
  // -----------------------------------------------------------------------------------------------

  def keylessPlanGeneration(): Unit = {
    val p     = plan("plan-keyless", "keyless", Json.obj("expr" -> "${req.headers.x-consumer}"))
    val api   = apiWith(Seq(p))
    val route = routeOf(api)
    val slot  = slotOf(route, NgPluginHelper.pluginId[NgExpressionApikeyExtractor])
    slot.isDefined mustBe true
    val cfg   = slot.get.config.raw
    cfg.select("expression").asString mustBe "${req.headers.x-consumer}"
    // the prefix is scoped by plan, so two keyless plans of the same api do not share a counter
    cfg.select("client_id_prefix").asString mustBe s"keyless_${api.id}_plan-keyless_"
    cfg.select("api_id").asString mustBe api.id
    cfg.select("plan_id").asString mustBe "plan-keyless"
    cfg.select("create_if_missing").asOpt[Boolean] mustBe Some(true)
    // a keyless plan requires a consumer too: the extractor above always builds one, so the enforcer
    // is what makes the plan quotas unavoidable rather than best effort
    slotOf(route, NgPluginHelper.pluginId[NgApiConsumerEnforcer]).isDefined mustBe true
  }

  def keylessPlanDefaultExpression(): Unit = {
    val route = routeOf(apiWith(Seq(plan("plan-kl2", "keyless"))))
    val cfg   = slotOf(route, NgPluginHelper.pluginId[NgExpressionApikeyExtractor]).get.config.raw
    cfg.select("expression").asString mustBe "${req.ip}"
  }

  def jwtPlanGeneration(): Unit = {
    val p     = plan(
      "plan-jwt",
      "jwt",
      Json.obj("verifier" -> "verifier_1", "client_id_path" -> "$.azp", "create_if_missing" -> false)
    )
    val api   = apiWith(Seq(p))
    val route = routeOf(api)
    val slot  = slotOf(route, NgPluginHelper.pluginId[NgJwtApikeyExtractor])
    slot.isDefined mustBe true
    val cfg   = slot.get.config.raw
    cfg.select("verifier").asString mustBe "verifier_1"
    cfg.select("client_id_path").asString mustBe "$.azp"
    // the client id comes from a token, so it is namespaced by plan just like the keyless and the
    // mtls ones: two apis behind the same idp must not share a consumer identity
    cfg.select("client_id_prefix").asString mustBe s"jwt_${api.id}_plan-jwt_"
    cfg.select("create_if_missing").asOpt[Boolean] mustBe Some(false)
    cfg.select("api_id").asString mustBe api.id
    cfg.select("plan_id").asString mustBe "plan-jwt"
    // a non keyless plan requires a consumer, checked and counted last
    val consumer = slotOf(route, NgPluginHelper.pluginId[NgApiConsumerEnforcer])
    consumer.isDefined mustBe true
    consumer.get.pluginIndex.flatMap(_.validateAccess) mustBe Some(1000.0)
  }

  def mtlsPlanGeneration(): Unit = {
    val p     = plan(
      "plan-mtls",
      "mtls",
      Json.obj(
        "regex_subject_dns" -> Json.arr(".*CN=client.*"),
        "regex_issuer_dns"  -> Json.arr(".*CN=ca.*"),
        "dn_match"          -> "all",
        "trusted_ca_refs"   -> Json.arr("partners-ca"),
        "client_id_field"   -> "UID"
      )
    )
    val api   = apiWith(Seq(p))
    val route = routeOf(api)
    val slot  = slotOf(route, NgPluginHelper.pluginId[NgClientCertApikeyExtractor])
    slot.isDefined mustBe true
    val cfg   = slot.get.config.raw
    cfg.select("regex_subject_dns").as[Seq[String]] mustBe Seq(".*CN=client.*")
    cfg.select("regex_issuer_dns").as[Seq[String]] mustBe Seq(".*CN=ca.*")
    cfg.select("dn_match").asString mustBe "all"
    cfg.select("trusted_ca_refs").as[Seq[String]] mustBe Seq("partners-ca")
    cfg.select("client_id_field").asString mustBe "UID"
    cfg.select("client_id_prefix").asString mustBe s"mtls_${api.id}_plan-mtls_"
    cfg.select("api_id").asString mustBe api.id
  }

  def apikeyPlanGeneration(): Unit = {
    val p     = plan("plan-apikey", "apikey", Json.obj("readOnly" -> true))
    val route = routeOf(apiWith(Seq(p)))
    // the apikey plan keeps the classic plugin
    slotOf(route, NgPluginHelper.pluginId[ApikeyCalls]).isDefined mustBe true
    slotOf(route, NgPluginHelper.pluginId[NgJwtApikeyExtractor]).isDefined mustBe false
    slotOf(route, NgPluginHelper.pluginId[NgApiConsumerEnforcer]).isDefined mustBe true
  }

  def planSettingsArePropagated(): Unit = {
    val p     = plan(
      "plan-quotas",
      "jwt",
      Json.obj("verifier" -> "verifier_1"),
      rateLimiting = Json
        .obj(
          "strategy" -> Json.obj(
            "id"               -> "FixedWindowStrategyConfig",
            "windowDurationMs" -> 7000,
            "quota"            -> Json.obj("window" -> 7, "daily" -> 8, "monthly" -> 9)
          )
        )
        .some
    )
    val route = routeOf(apiWith(Seq(p)))
    val cfg   = slotOf(route, NgPluginHelper.pluginId[NgJwtApikeyExtractor]).get.config.raw
    // the throttling of the plan reaches the plugin, so an apikey minted from a token is rate
    // limited exactly like one obtained through a subscription
    cfg.select("throttling_strategy").asOpt[JsObject].isDefined mustBe true
    cfg.select("throttling_strategy").select("id").asString mustBe "FixedWindowStrategyConfig"
    cfg.select("throttling_strategy").select("quota").select("window").asOpt[Int] mustBe Some(7)
  }

  def apikeyTemplateFallsBackOnPlanFields(): Unit = {
    // a jwt plan carries no apikey template, so the plugin gets what the plan itself declares
    val p     = ApiPlan(
      Json.obj(
        "id"                             -> "plan-tpl",
        "name"                           -> "plan-tpl",
        "status"                         -> "published",
        "access_mode_configuration_type" -> "jwt",
        "access_mode_configuration"      -> Json.obj("verifier" -> "verifier_1", "restrictions" -> forbiddenAdmin.json),
        "tags"                           -> Json.arr("from-plan"),
        "metadata"                       -> Json.obj("origin" -> "plan")
      )
    )
    val route = routeOf(apiWith(Seq(p)))
    val cfg   = slotOf(route, NgPluginHelper.pluginId[NgJwtApikeyExtractor]).get.config.raw
    cfg.select("apikey").select("tags").as[Seq[String]] mustBe Seq("from-plan")
    cfg.select("apikey").select("metadata").select("origin").asString mustBe "plan"
    // and the restrictions of its access mode
    otoroshi.models.Restrictions.format.reads(cfg.select("apikey").select("restrictions").get).get mustBe forbiddenAdmin
  }

  private val forbiddenAdmin = otoroshi.models.Restrictions(
    enabled = true,
    forbidden = Seq(otoroshi.models.RestrictionPath("*", "/admin/.*"))
  )

  def severalPublishedPlansStack(): Unit = {
    val route = routeOf(
      apiWith(
        Seq(
          plan("plan-a", "keyless"),
          plan("plan-b", "jwt", Json.obj("verifier" -> "verifier_1"))
        )
      )
    )
    // every published plan contributes its own extractor to the same route
    slotOf(route, NgPluginHelper.pluginId[NgExpressionApikeyExtractor]).isDefined mustBe true
    slotOf(route, NgPluginHelper.pluginId[NgJwtApikeyExtractor]).isDefined mustBe true
    // one plan being non keyless is enough to require a consumer
    slotOf(route, NgPluginHelper.pluginId[NgApiConsumerEnforcer]).isDefined mustBe true
  }

  def onlyPublishedPlansApply(): Unit = {
    val staging = ApiPlan(
      Json.obj(
        "id"                             -> "plan-staging",
        "name"                           -> "plan-staging",
        "status"                         -> "staging",
        "access_mode_configuration_type" -> "keyless"
      )
    )
    val route   = routeOf(apiWith(Seq(staging)))
    slotOf(route, NgPluginHelper.pluginId[NgExpressionApikeyExtractor]).isDefined mustBe false
    // but the route still ends with the consumer enforcer: an api without a served plan is not open
    slotOf(route, NgPluginHelper.pluginId[NgApiConsumerEnforcer]).map(_.config.raw) mustBe Some(
      Json.obj("no_published_plan" -> true)
    )
  }

  // every route of a live api ends with the consumer enforcer, told whether the api has a served plan
  def consumerEnforcerOnEveryRoute(): Unit = {
    val enforcer = NgPluginHelper.pluginId[NgApiConsumerEnforcer]
    slotOf(routeOf(apiWith(Seq.empty)), enforcer).map(_.config.raw) mustBe Some(Json.obj("no_published_plan" -> true))
    slotOf(routeOf(apiWith(Seq(ApiPlan(plan("plan-closed", "apikey").raw ++ Json.obj("status" -> "closed"))))), enforcer)
      .map(_.config.raw) mustBe Some(Json.obj("no_published_plan" -> true))
    slotOf(routeOf(apiWith(Seq(plan("plan-published", "keyless")))), enforcer).map(_.config.raw) mustBe Some(Json.obj())
    // a deprecated plan is served
    slotOf(routeOf(apiWith(Seq(ApiPlan(plan("plan-deprecated", "apikey").raw ++ Json.obj("status" -> "deprecated"))))), enforcer)
      .map(_.config.raw) mustBe Some(Json.obj())
  }

  // an api without a published or a deprecated plan refuses the calls that no plugin of its flows identified
  def apiWithoutServedPlan(): Unit = {
    def withStatus(id: String, kind: String, status: String): ApiPlan =
      ApiPlan(plan(id, kind).raw ++ Json.obj("status" -> status))
    val events    = new ConcurrentLinkedQueue[GatewayEvent]()
    val collector = env.analyticsActorSystem.actorOf(Props(new ClientIpAddressTests.EventsCollector(events)))
    env.analyticsActorSystem.eventStream.subscribe(collector, classOf[GatewayEvent])
    try {
      Seq(
        "a staging plan" -> Seq(withStatus("plan-ns-staging", "apikey", "staging")),
        "a closed plan"  -> Seq(withStatus("plan-ns-closed", "apikey", "closed")),
        "no plan"        -> Seq.empty
      ).foreach { case (what, plans) =>
        val api = apiWith(plans)
        deploy(api)
        try {
          withClue(what)(callApi(api).status mustBe 401)
          val deadline = System.currentTimeMillis() + 10000
          def refused  = events.asScala.exists(e =>
            e.route.exists(_.id.contains(api.routes.head.id)) &&
            e.extraAnalyticsData.exists(_.select("apikey_rejection_reason").asOpt[String].contains("the api has no published plan"))
          )
          while (!refused && System.currentTimeMillis() < deadline) await(100.millis)
          withClue(s"$what: the reason of the refusal")(refused mustBe true)
        } finally undeploy(api)
      }
      // a deprecated plan is served: the caller has to be one of its consumers
      val deprecated = apiWith(Seq(withStatus("plan-ns-deprecated", "apikey", "deprecated")))
      deploy(deprecated)
      try callApi(deprecated).status mustBe 401
      finally undeploy(deprecated)
    } finally {
      env.analyticsActorSystem.eventStream.unsubscribe(collector)
      env.analyticsActorSystem.stop(collector)
    }
  }

  // an api without a served plan whose flow identifies its callers with ApikeyCalls: they go through, and their quotas
  // are consumed once, by ApikeyCalls. A published keyless plan serves the anonymous callers
  def apiWithoutServedPlanIdentifiedByItsFlow(): Unit = {
    // with an index: the access validators without one run after the consumer enforcer (index 1000). Not mandatory:
    // it identifies the callers with an api key, and counts their calls, without refusing the others
    val apikeyCalls = NgPluginInstance(
      plugin = NgPluginHelper.pluginId[ApikeyCalls],
      config = NgPluginInstanceConfig(NgApikeyCallsConfig(mandatory = false).json.as[JsObject]),
      pluginIndex = Some(PluginIndex(validateAccess = Some(1.0)))
    )
    val api         = apiWith(Seq.empty, flowPlugins = NgPlugins(Seq(apikeyCalls)))
    val apikey      = otoroshi.models.ApiKey(
      clientId = s"client_${IdGenerator.uuid}",
      clientSecret = "flow-secret",
      clientName = "flow consumer",
      authorizedEntities = Seq(otoroshi.models.ApiIdentifier(api.id)),
      // the quotas ApikeyCalls enforces
      dailyQuota = 2
    )
    createOtoroshiApiKey(apikey).futureValue
    deploy(api)
    def withKey() = callApi(api, Seq("Otoroshi-Client-Id" -> apikey.clientId, "Otoroshi-Client-Secret" -> "flow-secret"))
    try {
      // without credentials: identified by nothing, refused by the consumer enforcer
      callApi(api).status mustBe 401
      // a quota of 2 calls: counted once per call, the third one is refused
      Seq(withKey().status, withKey().status, withKey().status) mustBe Seq(200, 200, 429)

      val public = api.copy(
        plans = Seq(plan("plan-ns-public", "keyless", Json.obj("create_if_missing" -> false))),
        routes = api.routes.map(_.copy(id = s"apiroute_${IdGenerator.uuid}"))
      )
      deploy(public)
      callApi(public).status mustBe 200
    } finally {
      deleteOtoroshiApiKey(apikey).futureValue
      undeploy(api)
    }
  }

  // a published plan set back to staging closes the api again
  def planBackToStagingClosesTheApi(): Unit = {
    val published = apiWith(Seq(plan("plan-ns-back", "keyless", Json.obj("create_if_missing" -> false))))
    deploy(published)
    try {
      callApi(published).status mustBe 200
      otoroshiApiCall(
        "PUT",
        s"/apis/apis.otoroshi.io/v1/apis/${published.id}",
        Some(published.copy(plans = Seq(ApiPlan(published.plans.head.raw ++ Json.obj("status" -> "staging")))).json)
      ).futureValue._2 mustBe 200
      await(4.seconds)
      callApi(published).status mustBe 401
    } finally undeploy(published)
  }

  // -----------------------------------------------------------------------------------------------
  // end to end: the plan actually puts an apikey in the context of a real call
  // -----------------------------------------------------------------------------------------------

  private def probe(tag: String): NgPluginInstance = NgPluginInstance(
    plugin = NgPluginHelper.pluginId[ApiPlanApikeyProbe],
    config = NgPluginInstanceConfig(Json.obj("tag" -> tag))
  )

  // writing straight to the datastore lets the proxy state job generate the routes, without going
  // through the state transitions the admin api enforces on an api
  private def deploy(api: Api): Unit = {
    env.datastores.apiDataStore.set(api).futureValue
    await(4.seconds)
  }

  private def undeploy(api: Api): Unit = {
    env.datastores.apiDataStore.delete(api).futureValue
    await(2.seconds)
  }

  private def callApi(api: Api, headers: Seq[(String, String)] = Seq.empty) = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders(Seq("Host" -> api.domain) ++ headers*)
    .get()
    .futureValue

  def keylessEndToEnd(): Unit = {
    ApiPlanApikeySeen.reset()
    val api = apiWith(
      Seq(plan("plan-e2e-kl", "keyless")),
      flowPlugins = NgPlugins(Seq(probe("e2e-keyless")))
    )
    deploy(api)
    try {
      val resp = callApi(api)
      resp.status mustBe 200
      // a public call with no credential at all still reaches the backend with a consumer identity
      // derived from its ip, so the quotas of the plan can be enforced
      ApiPlanApikeySeen.get("e2e-keyless") mustBe Some(s"keyless_${api.id}_plan-e2e-kl_127.0.0.1")
    } finally undeploy(api)
  }

  def keylessEndToEndWithCustomExpression(): Unit = {
    ApiPlanApikeySeen.reset()
    val api = apiWith(
      Seq(plan("plan-e2e-kl2", "keyless", Json.obj("expr" -> "${req.headers.x-consumer}"))),
      flowPlugins = NgPlugins(Seq(probe("e2e-keyless-expr")))
    )
    deploy(api)
    try {
      callApi(api, Seq("X-Consumer" -> "team-a")).status mustBe 200
      ApiPlanApikeySeen.get("e2e-keyless-expr") mustBe Some(s"keyless_${api.id}_plan-e2e-kl2_team-a")

      // two callers resolving to different values get two distinct identities, hence two counters
      ApiPlanApikeySeen.reset()
      callApi(api, Seq("X-Consumer" -> "team-b")).status mustBe 200
      ApiPlanApikeySeen.get("e2e-keyless-expr") mustBe Some(s"keyless_${api.id}_plan-e2e-kl2_team-b")

      // a missing header does not fail the expression: the EL resolves it to "no-header-<field>",
      // so every caller omitting it lands on one shared identity, hence one shared quota bucket
      ApiPlanApikeySeen.reset()
      callApi(api).status mustBe 200
      ApiPlanApikeySeen.get("e2e-keyless-expr") mustBe Some(
        s"keyless_${api.id}_plan-e2e-kl2_no-header-x-consumer"
      )
    } finally undeploy(api)
  }

  def keylessExpressionWithEmptyDefault(): Unit = {
    ApiPlanApikeySeen.reset()
    // the EL empty default is how a plan opts out of the shared identity above: an absent header
    // resolves to nothing, the plugin mints no apikey, and the consumer enforcer rejects
    val api = apiWith(
      Seq(plan("plan-e2e-kl3", "keyless", Json.obj("expr" -> "${req.headers.x-consumer:}"))),
      flowPlugins = NgPlugins(Seq(probe("e2e-keyless-empty")))
    )
    deploy(api)
    try {
      callApi(api).status mustBe 401
      ApiPlanApikeySeen.get("e2e-keyless-empty") mustBe None

      callApi(api, Seq("X-Consumer" -> "team-c")).status mustBe 200
      ApiPlanApikeySeen.get("e2e-keyless-empty") mustBe Some(s"keyless_${api.id}_plan-e2e-kl3_team-c")
    } finally undeploy(api)
  }

  def jwtEndToEnd(): Unit = {
    ApiPlanApikeySeen.reset()
    val verifierId = s"verifier_${IdGenerator.uuid}"
    createOtoroshiVerifier(
      GlobalJwtVerifier(
        id = verifierId,
        name = verifierId,
        desc = verifierId,
        strict = true,
        source = InHeader(name = "Authorization", remove = "Bearer "),
        algoSettings = HSAlgoSettings(512, "secret"),
        strategy = PassThrough(verificationSettings = VerificationSettings(Map("iss" -> "foo")))
      )
    ).futureValue

    val api = apiWith(
      Seq(plan("plan-e2e-jwt", "jwt", Json.obj("verifier" -> verifierId, "client_id_path" -> "client_id"))),
      flowPlugins = NgPlugins(Seq(probe("e2e-jwt")))
    )
    deploy(api)
    try {
      val token = JWT
        .create()
        .withIssuer("foo")
        .withClaim("client_id", "consumer-from-token")
        .sign(Algorithm.HMAC512("secret"))

      val resp = callApi(api, Seq("Authorization" -> s"Bearer $token"))
      resp.status mustBe 200
      // the client id carried by the token becomes the consumer identity, in the namespace of the plan
      ApiPlanApikeySeen.get("e2e-jwt") mustBe Some(s"jwt_${api.id}_plan-e2e-jwt_consumer-from-token")
      // create_if_missing is on by default, so that identity is also persisted as a real apikey
      await(2.seconds)
      env.datastores.apiKeyDataStore
        .findById(s"jwt_${api.id}_plan-e2e-jwt_consumer-from-token")
        .futureValue
        .isDefined mustBe true

      // no token at all: nothing is extracted, and the consumer enforcer rejects the call
      ApiPlanApikeySeen.reset()
      callApi(api).status mustBe 401
      ApiPlanApikeySeen.get("e2e-jwt") mustBe None

      // a token signed with the wrong key is refused too
      ApiPlanApikeySeen.reset()
      val badToken = JWT.create().withIssuer("foo").withClaim("client_id", "x").sign(Algorithm.HMAC512("wrong"))
      callApi(api, Seq("Authorization" -> s"Bearer $badToken")).status mustBe 401
      ApiPlanApikeySeen.get("e2e-jwt") mustBe None
    } finally undeploy(api)
  }

  def jwtEndToEndWithoutCreation(): Unit = {
    ApiPlanApikeySeen.reset()
    val verifierId = s"verifier_${IdGenerator.uuid}"
    createOtoroshiVerifier(
      GlobalJwtVerifier(
        id = verifierId,
        name = verifierId,
        desc = verifierId,
        strict = true,
        source = InHeader(name = "Authorization", remove = "Bearer "),
        algoSettings = HSAlgoSettings(512, "secret"),
        strategy = PassThrough(verificationSettings = VerificationSettings(Map("iss" -> "foo")))
      )
    ).futureValue

    val api = apiWith(
      Seq(
        plan(
          "plan-e2e-jwt2",
          "jwt",
          Json.obj("verifier" -> verifierId, "client_id_path" -> "client_id", "create_if_missing" -> false)
        )
      ),
      flowPlugins = NgPlugins(Seq(probe("e2e-jwt-nocreate")))
    )
    deploy(api)
    try {
      val token = JWT
        .create()
        .withIssuer("foo")
        .withClaim("client_id", "unknown-consumer")
        .sign(Algorithm.HMAC512("secret"))
      // create_if_missing only says whether the apikey reaches the datastore: the consumer is
      // identified and served either way, so the plan can hand its quotas to a caller without
      // filling the datastore with one entry per client id of the idp
      callApi(api, Seq("Authorization" -> s"Bearer $token")).status mustBe 200
      ApiPlanApikeySeen.get("e2e-jwt-nocreate") mustBe Some(s"jwt_${api.id}_plan-e2e-jwt2_unknown-consumer")
      await(2.seconds)
      env.datastores.apiKeyDataStore
        .findById(s"jwt_${api.id}_plan-e2e-jwt2_unknown-consumer")
        .futureValue
        .isDefined mustBe false
    } finally undeploy(api)
  }

  def keylessRunsLastAmongExtractors(): Unit = {
    val route    = routeOf(
      apiWith(
        Seq(
          plan("plan-order-kl", "keyless"),
          plan("plan-order-jwt", "jwt", Json.obj("verifier" -> "verifier_1"))
        )
      )
    )
    val keyless  = slotOf(route, NgPluginHelper.pluginId[NgExpressionApikeyExtractor]).get
    val jwt      = slotOf(route, NgPluginHelper.pluginId[NgJwtApikeyExtractor]).get
    val consumer = slotOf(route, NgPluginHelper.pluginId[NgApiConsumerEnforcer]).get
    // the keyless extractor always resolves an identity, so it must come after the credential based
    // ones, otherwise it would claim every call before they get a chance
    val keylessIdx  = keyless.pluginIndex.flatMap(_.validateAccess).get
    val jwtIdx      = jwt.pluginIndex.flatMap(_.validateAccess).get
    val consumerIdx = consumer.pluginIndex.flatMap(_.validateAccess).get
    (jwtIdx < keylessIdx) mustBe true
    (keylessIdx < consumerIdx) mustBe true
  }

  def credentialWinsOverKeyless(): Unit = {
    ApiPlanApikeySeen.reset()
    val verifierId = s"verifier_${IdGenerator.uuid}"
    createOtoroshiVerifier(
      GlobalJwtVerifier(
        id = verifierId,
        name = verifierId,
        desc = verifierId,
        strict = true,
        source = InHeader(name = "Authorization", remove = "Bearer "),
        algoSettings = HSAlgoSettings(512, "secret"),
        strategy = PassThrough(verificationSettings = VerificationSettings(Map("iss" -> "foo")))
      )
    ).futureValue

    // one api, one route, a keyless plan and a jwt plan: both extractors run on every call
    val api = apiWith(
      Seq(
        plan("plan-mix-kl", "keyless"),
        plan("plan-mix-jwt", "jwt", Json.obj("verifier" -> verifierId, "client_id_path" -> "client_id"))
      ),
      flowPlugins = NgPlugins(Seq(probe("mix")))
    )
    deploy(api)
    try {
      val token = JWT
        .create()
        .withIssuer("foo")
        .withClaim("client_id", "consumer-from-token")
        .sign(Algorithm.HMAC512("secret"))

      // a caller presenting a credential keeps the identity of that credential
      callApi(api, Seq("Authorization" -> s"Bearer $token")).status mustBe 200
      ApiPlanApikeySeen.get("mix") mustBe Some(s"jwt_${api.id}_plan-mix-jwt_consumer-from-token")

      // a caller presenting nothing falls back on the public plan
      ApiPlanApikeySeen.reset()
      callApi(api).status mustBe 200
      ApiPlanApikeySeen.get("mix") mustBe Some(s"keyless_${api.id}_plan-mix-kl_127.0.0.1")
    } finally undeploy(api)
  }

  def identicalPluginsAreDeduped(): Unit = {
    val route = routeOf(
      apiWith(
        Seq(
          plan("plan-dedupe-apikey", "apikey"),
          plan("plan-dedupe-oauth2", "oauth2-local")
        )
      )
    )
    // both plans yield an ApikeyCalls whose effective config is identical, so a single instance
    // ends up on the route
    route.plugins.slots.count(_.plugin == NgPluginHelper.pluginId[ApikeyCalls]) mustBe 1
  }

  def differentConfigsAreKept(): Unit = {
    val route = routeOf(
      apiWith(
        Seq(
          plan("plan-keep-a", "keyless", Json.obj("expr" -> "${req.ip}")),
          plan("plan-keep-b", "keyless", Json.obj("expr" -> "${req.headers.x-consumer}"))
        )
      )
    )
    // two keyless plans differ at least by their client id prefix, so neither is dropped
    val slots = route.plugins.slots.filter(_.plugin == NgPluginHelper.pluginId[NgExpressionApikeyExtractor])
    slots.size mustBe 2
    slots.map(_.config.raw.select("expression").asString).toSet mustBe Set(
      "${req.ip}",
      "${req.headers.x-consumer}"
    )
  }

  // -----------------------------------------------------------------------------------------------
  // a plan that requires a subscription
  // -----------------------------------------------------------------------------------------------

  // a jwt plan that requires a subscription only serves the consumers its subscriptions provisioned a key for
  def jwtSubscriptionRequired(): Unit = {
    ApiPlanApikeySeen.reset()
    val subscriptionsPath = "/apis/apis.otoroshi.io/v1/apisubscriptions"
    val verifierId        = s"verifier_${IdGenerator.uuid}"
    createOtoroshiVerifier(
      GlobalJwtVerifier(
        id = verifierId,
        name = verifierId,
        desc = verifierId,
        strict = true,
        source = InHeader(name = "Authorization", remove = "Bearer "),
        algoSettings = HSAlgoSettings(512, "secret"),
        strategy = PassThrough(verificationSettings = VerificationSettings())
      )
    ).futureValue

    def jwtConf(required: Boolean) =
      Json.obj("verifier" -> verifierId, "client_id_path" -> "client_id", "subscription_required" -> required)
    // another plan of the api on the same verifier, not served: its subscriptions do not open the plan under test
    val otherPlan = ApiPlan(plan("plan-sub-other", "jwt", jwtConf(true)).raw ++ Json.obj("status" -> "staging"))
    val base      = apiWith(
      Seq(plan("plan-sub-jwt", "jwt", jwtConf(false)), otherPlan),
      flowPlugins = NgPlugins(Seq(probe("sub-jwt")))
    )
    // every variant gets a route id of its own: the configs of the plugins of a route are cached by route id
    def variant(plans: ApiPlan*): Api =
      base.copy(plans = plans, routes = base.routes.map(_.copy(id = s"apiroute_${IdGenerator.uuid}")))
    val required = variant(plan("plan-sub-jwt", "jwt", jwtConf(true)), otherPlan)

    // the extractor of the plan carries the option
    routeOf(required).plugins.slots
      .filter(_.plugin == NgPluginHelper.pluginId[NgJwtApikeyExtractor])
      .map(_.config.raw.select("subscription_required").asOpt[Boolean]) mustBe Seq(Some(true))

    val events    = new ConcurrentLinkedQueue[GatewayEvent]()
    val collector = env.analyticsActorSystem.actorOf(Props(new ClientIpAddressTests.EventsCollector(events)))
    env.analyticsActorSystem.eventStream.subscribe(collector, classOf[GatewayEvent])

    def keyOf(clientId: String): String = s"jwt_${base.id}_plan-sub-jwt_$clientId"
    def call(clientId: String): Int = {
      val token = JWT.create().withClaim("client_id", clientId).sign(Algorithm.HMAC512("secret"))
      callApi(base, Seq("Authorization" -> s"Bearer $token")).status
    }
    def stored(clientId: String): Option[ApiKey] = env.datastores.apiKeyDataStore.findById(keyOf(clientId)).futureValue
    def waitUntil(what: String)(cond: => Boolean): Unit = {
      val deadline = System.currentTimeMillis() + 15000
      while (!cond && System.currentTimeMillis() < deadline) await(100.millis)
      withClue(what)(cond mustBe true)
    }
    // the extractor reads the key and the subscription from the proxy state
    def awaitState(subscription: String, status: Option[String], clientId: String): Unit = waitUntil(
      s"the proxy state holds the subscription $subscription as $status and the key of $clientId as stored"
    ) {
      env.proxyState.apiSubscription(subscription).map(_.status.name) == status &&
      env.proxyState.apikey(keyOf(clientId)).map(_.enabled) == stored(clientId).map(_.enabled)
    }
    val template = otoroshiApiCall("GET", s"$subscriptionsPath/_template").futureValue._1.as[JsObject]
    def subscribe(id: String, planId: String, clientId: String): Unit = {
      val (body, status) = otoroshiApiCall(
        "POST",
        subscriptionsPath,
        Some(
          template ++ Json.obj(
            "id"                -> id,
            "name"              -> id,
            "api_ref"           -> base.id,
            "plan_ref"          -> planId,
            "owner_ref"         -> "client-1",
            "subscription_kind" -> "jwt",
            "status"            -> "enabled",
            "client_id"         -> clientId
          )
        )
      ).futureValue
      withClue(body.stringify)(status mustBe 201)
    }
    def setStatus(id: String, status: String): Unit = {
      val current = otoroshiApiCall("GET", s"$subscriptionsPath/$id").futureValue._1.as[JsObject]
      otoroshiApiCall("PUT", s"$subscriptionsPath/$id", Some(current ++ Json.obj("status" -> status))).futureValue._2 mustBe 200
    }

    // the option off: any valid token is a consumer, its key is minted on the fly
    deploy(variant(plan("plan-sub-jwt", "jwt", jwtConf(false)), otherPlan))
    try {
      call("client-2") mustBe 200
      waitUntil("the key of client-2 is minted")(stored("client-2").isDefined)
      waitUntil("the proxy state holds the key of client-2")(env.proxyState.apikey(keyOf("client-2")).isDefined)
      val mintedBefore = stored("client-2").map(_.json)

      deploy(required)

      // no subscription: not identified by the plan, nothing is minted, and the event says why
      call("client-1") mustBe 401
      stored("client-1") mustBe None
      waitUntil("the event of the refused call") {
        events.asScala.exists(_.extraAnalyticsData.exists(_.select("apikey_rejection_reason").asOpt[String].contains(
          s"client id '${keyOf("client-1")}' is not subscribed to plan 'plan-sub-jwt'"
        )))
      }

      // an enabled subscription with the client id of the caller: served, as the key it provisioned
      subscribe("sub-required-c1", "plan-sub-jwt", "client-1")
      awaitState("sub-required-c1", Some("enabled"), "client-1")
      ApiPlanApikeySeen.reset()
      call("client-1") mustBe 200
      ApiPlanApikeySeen.get("sub-jwt") mustBe Some(keyOf("client-1"))
      stored("client-1").flatMap(_.apiRef).map(_.subscription) mustBe Some("sub-required-c1")

      Seq("disabled" -> 401, "pending" -> 401, "enabled" -> 200).foreach { case (status, expected) =>
        setStatus("sub-required-c1", status)
        awaitState("sub-required-c1", Some(status), "client-1")
        withClue(status)(call("client-1") mustBe expected)
      }
      otoroshiApiCall("DELETE", s"$subscriptionsPath/sub-required-c1").futureValue._2 mustBe 200
      awaitState("sub-required-c1", None, "client-1")
      call("client-1") mustBe 401

      // the key minted on the fly while the option was off is not a subscription, and is left as it is
      call("client-2") mustBe 401
      stored("client-2").map(_.json) mustBe mintedBefore

      // a subscription to another plan of the api does not open this one
      subscribe("sub-required-c3", "plan-sub-other", "client-3")
      waitUntil("the proxy state holds the subscription to the other plan") {
        env.proxyState.apiSubscription("sub-required-c3").isDefined
      }
      call("client-3") mustBe 401

      // with a keyless plan next to it, an unsubscribed token is served by the keyless plan, as any caller the jwt
      // plan does not identify
      deploy(variant(plan("plan-sub-jwt", "jwt", jwtConf(true)), otherPlan, plan("plan-sub-kl", "keyless")))
      ApiPlanApikeySeen.reset()
      call("client-4") mustBe 200
      ApiPlanApikeySeen.get("sub-jwt") mustBe Some(s"keyless_${base.id}_plan-sub-kl_127.0.0.1")
    } finally {
      env.analyticsActorSystem.eventStream.unsubscribe(collector)
      env.analyticsActorSystem.stop(collector)
      otoroshiApiCall("DELETE", s"$subscriptionsPath/sub-required-c3").futureValue
      env.datastores.apiKeyDataStore.findAll().futureValue.filter(_.apiRef.exists(_.api == base.id)).foreach { key =>
        env.datastores.apiKeyDataStore.delete(key.clientId).futureValue
      }
      undeploy(base)
    }
  }

  // -----------------------------------------------------------------------------------------------
  // the restrictions of a keyless, jwt, mtls or oauth2-remote plan
  // -----------------------------------------------------------------------------------------------

  private def callPath(api: Api, method: String, path: String, headers: Seq[(String, String)] = Seq.empty): Int = ws
    .url(s"http://127.0.0.1:$port$path")
    .withHttpHeaders(Seq("Host" -> api.domain) ++ headers*)
    .withMethod(method)
    .execute()
    .futureValue
    .status

  private def restrictionsVerifier(): String = {
    val verifierId = s"verifier_${IdGenerator.uuid}"
    createOtoroshiVerifier(
      GlobalJwtVerifier(
        id = verifierId,
        name = verifierId,
        desc = verifierId,
        strict = true,
        source = InHeader(name = "Authorization", remove = "Bearer "),
        algoSettings = HSAlgoSettings(512, "secret"),
        strategy = PassThrough(verificationSettings = VerificationSettings())
      )
    ).futureValue
    verifierId
  }

  private def bearer(clientId: String): (String, String) =
    "Authorization" -> s"Bearer ${JWT.create().withClaim("client_id", clientId).sign(Algorithm.HMAC512("secret"))}"

  private def waitFor(what: String)(cond: => Boolean): Unit = {
    val deadline = System.currentTimeMillis() + 15000
    while (!cond && System.currentTimeMillis() < deadline) await(100.millis)
    withClue(what)(cond mustBe true)
  }

  // the restrictions of a jwt plan apply to its consumers, and their keys carry them
  def jwtPlanRestrictions(): Unit = {
    val verifierId = restrictionsVerifier()
    val api        = apiWith(
      Seq(plan("plan-rs-jwt", "jwt", Json.obj("verifier" -> verifierId, "restrictions" -> forbiddenAdmin.json)))
    )
    deploy(api)
    try {
      callPath(api, "GET", "/users", Seq(bearer("client-1"))) mustBe 200
      callPath(api, "GET", "/admin/users", Seq(bearer("client-1"))) mustBe 403
      val key = env.datastores.apiKeyDataStore.findById(s"jwt_${api.id}_plan-rs-jwt_client-1").futureValue
      key.map(_.restrictions) mustBe Some(forbiddenAdmin)
    } finally undeploy(api)
  }

  // the restrictions of a keyless plan apply to the anonymous callers
  def keylessPlanRestrictions(): Unit = {
    val noPost = otoroshi.models.Restrictions(
      enabled = true,
      forbidden = Seq(otoroshi.models.RestrictionPath("POST", "/.*"))
    )
    val api    = apiWith(Seq(plan("plan-rs-kl", "keyless", Json.obj("restrictions" -> noPost.json))))
    deploy(api)
    try {
      callPath(api, "GET", "/x") mustBe 200
      callPath(api, "POST", "/x") mustBe 403
    } finally undeploy(api)
  }

  // the same restrictions on an apikey plan and on a jwt plan of one api answer the same calls the same way
  def sameRestrictionsOnEveryKind(): Unit = {
    val verifierId = restrictionsVerifier()
    val api        = apiWith(
      Seq(
        plan("plan-rs-ak", "apikey", Json.obj("restrictions" -> forbiddenAdmin.json)),
        plan("plan-rs-jwt2", "jwt", Json.obj("verifier" -> verifierId, "restrictions" -> forbiddenAdmin.json))
      )
    )
    deploy(api)
    val subscriptionsPath = "/apis/apis.otoroshi.io/v1/apisubscriptions"
    try {
      val template         = otoroshiApiCall("GET", s"$subscriptionsPath/_template").futureValue._1.as[JsObject]
      val (created, _)     = otoroshiApiCall(
        "POST",
        subscriptionsPath,
        Some(
          template ++ Json.obj(
            "id"                -> "sub-rs-ak",
            "name"              -> "sub-rs-ak",
            "api_ref"           -> api.id,
            "plan_ref"          -> "plan-rs-ak",
            "owner_ref"         -> "client-1",
            "subscription_kind" -> "apikey",
            "status"            -> "enabled"
          )
        )
      ).futureValue
      val clientId         = created.select("token_refs").as[Seq[JsValue]].head.select("apikey").asString
      val apikey           = env.datastores.apiKeyDataStore.findById(clientId).futureValue.get
      waitFor("the router sees the api key of the subscription")(env.proxyState.apikey(clientId).isDefined)
      val withApikey       = Seq("Otoroshi-Client-Id" -> apikey.clientId, "Otoroshi-Client-Secret" -> apikey.clientSecret)
      Seq("/users", "/admin/users").map(path => callPath(api, "GET", path, withApikey)) mustBe
      Seq("/users", "/admin/users").map(path => callPath(api, "GET", path, Seq(bearer("client-2"))))
      callPath(api, "GET", "/admin/users", withApikey) mustBe 403
    } finally {
      otoroshiApiCall("DELETE", s"$subscriptionsPath/sub-rs-ak").futureValue
      undeploy(api)
    }
  }

  // the consumers already minted follow the restrictions and the rate limiting of their plan; the keys of another
  // plan and the keys saved by hand are left alone
  def restrictionsReachMintedConsumers(): Unit = {
    val verifierId = restrictionsVerifier()
    val jwtConf    = Json.obj("verifier" -> verifierId)
    val api        = apiWith(Seq(plan("plan-rs-up", "jwt", jwtConf), plan("plan-rs-other", "keyless")))
    val keyId      = s"jwt_${api.id}_plan-rs-up_client-1"
    val otherId    = s"keyless_${api.id}_plan-rs-other_127.0.0.1"
    val byHand     = otoroshi.models.ApiKey(
      clientId = s"by-hand_${IdGenerator.uuid}",
      clientSecret = "secret",
      clientName = "by hand",
      authorizedEntities = Seq(otoroshi.models.ApiIdentifier(api.id)),
      metadata = Map("created_by" -> "apikey-extractor")
    )
    deploy(api)
    try {
      createOtoroshiApiKey(byHand).futureValue
      // minted before the restrictions are set
      callPath(api, "GET", "/admin/users", Seq(bearer("client-1"))) mustBe 200
      callPath(api, "GET", "/x") mustBe 200
      waitFor("the keys are minted") {
        env.datastores.apiKeyDataStore.findById(keyId).futureValue.isDefined &&
        env.datastores.apiKeyDataStore.findById(otherId).futureValue.isDefined
      }
      val otherBefore  = env.datastores.apiKeyDataStore.findById(otherId).futureValue.map(_.json)
      val byHandBefore = env.datastores.apiKeyDataStore.findById(byHand.clientId).futureValue.map(_.json)
      val mintedBefore = env.datastores.apiKeyDataStore.findById(keyId).futureValue.get

      val strategy   = FixedWindowStrategyConfig(windowDurationMs = 60000L, quota = AllowedQuota(window = 50))
      val restricted = api.copy(
        plans = Seq(
          ApiPlan(
            plan("plan-rs-up", "jwt", jwtConf ++ Json.obj("restrictions" -> forbiddenAdmin.json)).raw ++
            Json.obj("rateLimiting" -> Json.obj("strategy" -> strategy.json))
          ),
          plan("plan-rs-other", "keyless")
        )
      )
      otoroshiApiCall("PUT", s"/apis/apis.otoroshi.io/v1/apis/${api.id}", Some(restricted.json)).futureValue._2 mustBe 200

      val minted = env.datastores.apiKeyDataStore.findById(keyId).futureValue.get
      minted.restrictions mustBe forbiddenAdmin
      minted.throttlingStrategy mustBe Some(strategy)
      // everything else of the key is kept
      minted.copy(restrictions = mintedBefore.restrictions, throttlingStrategy = mintedBefore.throttlingStrategy).json mustBe
      mintedBefore.json
      waitFor("the router sees the restrictions of the key")(
        env.proxyState.apikey(keyId).map(_.restrictions).contains(forbiddenAdmin)
      )
      callPath(restricted, "GET", "/admin/users", Seq(bearer("client-1"))) mustBe 403

      env.datastores.apiKeyDataStore.findById(otherId).futureValue.map(_.json) mustBe otherBefore
      env.datastores.apiKeyDataStore.findById(byHand.clientId).futureValue.map(_.json) mustBe byHandBefore
    } finally {
      Seq(keyId, otherId, byHand.clientId).foreach(id => env.datastores.apiKeyDataStore.delete(id).futureValue)
      undeploy(api)
    }
  }

  // -----------------------------------------------------------------------------------------------
  // the plan of a call, its quota counter, its circuit breaker
  // -----------------------------------------------------------------------------------------------

  private def withFields(p: ApiPlan, fields: (String, JsValueWrapper)*): ApiPlan = ApiPlan(p.raw ++ Json.obj(fields*))

  private def windowOf(quota: Long): JsObject =
    Json.obj("strategy" -> FixedWindowStrategyConfig(windowDurationMs = 60000L, quota = AllowedQuota(window = quota)).json)

  // two keyless plans: a partner plan keyed on a header, applied only to the calls carrying it, then a public one
  def keylessPlanSelection(): Unit = {
    ApiPlanApikeySeen.reset()
    val partner = withFields(
      plan("plan_partner", "keyless", Json.obj("expr" -> "${req.headers.X-Partner-Id}")),
      "selection" -> Json.arr(Json.obj("path" -> "$.request.headers.x-partner-id", "value" -> "IsDefined()"))
    )
    val api     = apiWith(Seq(partner, plan("plan_free", "keyless")), flowPlugins = NgPlugins(Seq(probe("sel-kl"))))
    deploy(api)
    try {
      callApi(api, Seq("X-Partner-Id" -> "acme")).status mustBe 200
      ApiPlanApikeySeen.get("sel-kl") mustBe Some(s"keyless_${api.id}_plan_partner_acme")
      ApiPlanApikeySeen.reset()
      callApi(api).status mustBe 200
      ApiPlanApikeySeen.get("sel-kl") mustBe Some(s"keyless_${api.id}_plan_free_127.0.0.1")
    } finally undeploy(api)
  }

  // two jwt plans on the same verifier: the tier claim of the token picks the plan, and its quotas
  def jwtPlanSelection(): Unit = {
    ApiPlanApikeySeen.reset()
    val verifierId = restrictionsVerifier()
    val gold       = withFields(
      plan("plan_gold", "jwt", Json.obj("verifier" -> verifierId)),
      "selection"    -> Json.arr(Json.obj("path" -> "$.token.tier", "value" -> "gold")),
      "rateLimiting" -> windowOf(10)
    )
    val free       = withFields(plan("plan_free", "jwt", Json.obj("verifier" -> verifierId)), "rateLimiting" -> windowOf(100))
    def tiered(clientId: String, tier: String): (String, String) =
      "Authorization" -> s"Bearer ${JWT.create().withClaim("client_id", clientId).withClaim("tier", tier).sign(Algorithm.HMAC512("secret"))}"
    def windowOfKey(id: String): Option[Long] =
      env.datastores.apiKeyDataStore.findById(id).futureValue.flatMap(_.throttlingStrategy).map(_.quota.window)

    val api = apiWith(Seq(gold, free), flowPlugins = NgPlugins(Seq(probe("sel-jwt"))))
    deploy(api)
    try {
      callApi(api, Seq(tiered("client-g", "gold"))).status mustBe 200
      ApiPlanApikeySeen.get("sel-jwt") mustBe Some(s"jwt_${api.id}_plan_gold_client-g")
      ApiPlanApikeySeen.reset()
      callApi(api, Seq(tiered("client-f", "free"))).status mustBe 200
      ApiPlanApikeySeen.get("sel-jwt") mustBe Some(s"jwt_${api.id}_plan_free_client-f")
      await(1.second)
      windowOfKey(s"jwt_${api.id}_plan_gold_client-g") mustBe Some(10L)
      windowOfKey(s"jwt_${api.id}_plan_free_client-f") mustBe Some(100L)
    } finally undeploy(api)

    // alone on the api, a plan whose selection fails for a call identifies no one: refused by the enforcer
    val alone = apiWith(Seq(gold))
    deploy(alone)
    try callApi(alone, Seq(tiered("client-f", "free"))).status mustBe 401
    finally undeploy(alone)
  }

  // a selection on a plan whose callers present a key naming its plan, or one that cannot be read, is refused
  def invalidPlanSelection(): Unit = {
    val onApikey   = apiWith(
      Seq(withFields(plan("plan_ak", "apikey"), "selection" -> Json.arr(Json.obj("path" -> "$.client_id", "value" -> "x"))))
    ).copy(state = ApiStaging)
    val unreadable = apiWith(Seq(withFields(plan("plan_kl", "keyless"), "selection" -> Json.arr(Json.obj("value" -> "x")))))
      .copy(state = ApiStaging)
    Seq(
      onApikey   -> "plan 'plan_ak': selection is not supported on 'apikey' plans",
      unreadable -> "plan 'plan_kl': selection[0] is not a valid json path validator"
    ).foreach { case (api, description) =>
      val (body, status) = otoroshiApiCall("POST", "/apis/apis.otoroshi.io/v1/apis", Some(api.json)).futureValue
      withClue(body.stringify) {
        status mustBe 400
        body.select("error").asString mustBe "invalid_plan"
        body.select("error_description").asString mustBe description
      }
    }
  }

  // a strategy standing in for the quota counter of a consumer
  private class CounterStub(answer: () => scala.concurrent.Future[ThrottlingResult]) extends ThrottlingStrategy {
    override def client(): otoroshi.storage.RedisLike   = env.datastores.redis
    override def config: ThrottlingStrategyConfig        = FixedWindowStrategyConfig()
    override def checkAndIncrement(key: String, increment: Long, allowedQuotas: AllowedQuota, expirationSeconds: Int)(
        using env: otoroshi.env.Env,
        ec: scala.concurrent.ExecutionContext
    ): scala.concurrent.Future[ThrottlingResult] = answer()
  }

  // what a plan does when the counter of its quotas cannot be reached, within a timeout or not
  def counterFailure(): Unit = {
    val down: () => scala.concurrent.Future[ThrottlingResult] =
      () => scala.concurrent.Future.failed(new RuntimeException("counter down"))
    val slow: () => scala.concurrent.Future[ThrottlingResult] = () =>
      org.apache.pekko.pattern.after(2.seconds, env.otoroshiScheduler)(
        scala.concurrent.Future.failed(new RuntimeException("counter too slow"))
      )(using env.otoroshiExecutionContext)
    val events    = new ConcurrentLinkedQueue[GatewayEvent]()
    val collector = env.analyticsActorSystem.actorOf(Props(new ClientIpAddressTests.EventsCollector(events)))
    env.analyticsActorSystem.eventStream.subscribe(collector, classOf[GatewayEvent])
    def extras(api: Api, key: String): Option[JsObject] = {
      val deadline = System.currentTimeMillis() + 10000
      def find()   = events.asScala.find(e => e.route.exists(_.id.contains(api.routes.head.id)) && e.extraAnalyticsData.exists(_.keys.contains(key)))
      while (find().isEmpty && System.currentTimeMillis() < deadline) await(100.millis)
      find().flatMap(_.extraAnalyticsData)
    }
    try {
      Seq(
        ("deny", Some(Json.obj("mode" -> "deny")), down, 503),
        ("allow", Some(Json.obj("mode" -> "allow")), down, 200),
        ("absent", None, down, 503),
        ("deny-timeout", Some(Json.obj("mode" -> "deny", "timeout" -> 200)), slow, 503),
        ("allow-timeout", Some(Json.obj("mode" -> "allow", "timeout" -> 200)), slow, 200)
      ).foreach { case (what, counterFailure, answer, expected) =>
        val keyless = withFields(plan(s"plan_cf_${what.replace("-", "_")}", "keyless"), "rateLimiting" -> windowOf(100))
        val api     = apiWith(
          Seq(counterFailure.map(cf => withFields(keyless, "counter_failure" -> cf)).getOrElse(keyless))
        )
        env.rateLimiter.overrideStrategy(s"keyless_${api.id}_${keyless.id}_127.0.0.1", new CounterStub(answer))
        deploy(api)
        try {
          val start = System.currentTimeMillis()
          withClue(what)(callApi(api).status mustBe expected)
          if (what.endsWith("timeout")) withClue(s"$what: answered within the timeout")(System.currentTimeMillis() - start must be < 1500L)
          if (expected == 503) {
            withClue(s"$what: the reason of the refusal")(
              extras(api, "apikey_rejection_reason").flatMap(_.select("apikey_rejection_reason").asOpt[String]) mustBe
              Some(s"counter unavailable for apikey 'keyless_${api.id}_${keyless.id}_127.0.0.1'")
            )
          } else {
            withClue(s"$what: the skipped check")(
              extras(api, "quota_check_skipped").flatMap(_.select("quota_check_skipped").asOpt[String]) mustBe
              Some("counter unavailable")
            )
          }
        } finally undeploy(api)
      }
    } finally {
      env.analyticsActorSystem.eventStream.unsubscribe(collector)
      env.analyticsActorSystem.stop(collector)
    }
  }

  // a consumer that makes the backend fail opens the breaker of the route for everybody, or only its own
  def circuitBreakerScope(): Unit = {
    val backendServer = functional.TargetService
      .full(
        None,
        "/",
        "application/json",
        request => {
          if (request.headers.exists(_.name().equalsIgnoreCase("X-Break"))) Thread.sleep(2000)
          (200, Json.obj("ok" -> true).stringify, List.empty)
        }
      )
      .await()
    val client        = ApiBackendClient(
      "cb_client",
      "cb_client",
      // a single attempt per call: retries counts the attempts
      NgClientConfig(retries = 1, maxErrors = 2, callTimeout = 500, globalTimeout = 500, sampleInterval = 10000)
    )
    val backend       = ApiBackend(
      id = "cb_backend",
      name = "cb_backend",
      backend = NgBackend.empty.copy(
        targets = Seq(NgTarget(id = "cb_target", hostname = "127.0.0.1", port = backendServer.port, tls = false))
      ),
      client = client.id
    )
    def apiScoped(scope: Option[String]): Api = {
      val consumers = plan("plan_cb", "keyless", Json.obj("expr" -> "${req.headers.x-consumer}"))
      val base      = apiWith(Seq(scope.map(s => withFields(consumers, "circuit_breaker_scope" -> s)).getOrElse(consumers)))
      base.copy(
        backends = Seq(backend),
        clientsBackendConfig = Seq(client),
        routes = base.routes.map(_.copy(backend = backend.id))
      )
    }
    def call(api: Api, consumer: String, break: Boolean): Int =
      callApi(api, Seq("x-consumer" -> consumer) ++ (if (break) Seq("X-Break" -> "true") else Seq.empty)).status
    env.circuitBeakersHolder.consumerScopeTtl = 3.seconds
    try {
      Seq(Some("consumer") -> true, Some("route") -> false, None -> false).foreach { case (scope, otherServed) =>
        val api = apiScoped(scope)
        deploy(api)
        try {
          withClue(s"$scope: the other consumer is served before")(call(api, "b", break = false) mustBe 200)
          (1 to 3).foreach(_ => call(api, "a", break = true))
          withClue(s"$scope: the breaking consumer is refused")(call(api, "a", break = false) must not be 200)
          withClue(s"$scope: the other consumer")((call(api, "b", break = false) == 200) mustBe otherServed)
          if (scope.contains("consumer")) {
            withClue("the breaker of the consumer is kept")(
              env.circuitBeakersHolder.consumerScopedKeys().exists(_.endsWith("_plan_cb_a")) mustBe true
            )
          }
        } finally undeploy(api)
      }
      // the breakers of the consumers not used for longer than the ttl are forgotten
      await(4.seconds)
      env.circuitBeakersHolder.consumerScopedKeys().filter(_.contains("_plan_cb_")) mustBe empty
    } finally {
      env.circuitBeakersHolder.consumerScopeTtl = 10.minutes
      backendServer.stop()
    }
  }
}
