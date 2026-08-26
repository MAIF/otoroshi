package plugins

import functional.PluginsTestSpec
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import otoroshi.models.{EntityLocation, GlobalJwtVerifier, HSAlgoSettings, InHeader, PassThrough, VerificationSettings}
import otoroshi.next.models.*
import otoroshi.next.plugins.*
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import java.util.concurrent.ConcurrentHashMap
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

class ApiPlanPluginsTests(parent: PluginsTestSpec) {
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
    cfg.select("client_id_prefix").asString mustBe "keyless_plan-keyless_"
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
    cfg.select("client_id_prefix").asString mustBe "jwt_plan-jwt_"
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
    cfg.select("client_id_field").asString mustBe "UID"
    cfg.select("client_id_prefix").asString mustBe "mtls_plan-mtls_"
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
        "access_mode_configuration"      -> Json.obj("verifier" -> "verifier_1"),
        "tags"                           -> Json.arr("from-plan"),
        "metadata"                       -> Json.obj("origin" -> "plan")
      )
    )
    val route = routeOf(apiWith(Seq(p)))
    val cfg   = slotOf(route, NgPluginHelper.pluginId[NgJwtApikeyExtractor]).get.config.raw
    cfg.select("apikey").select("tags").as[Seq[String]] mustBe Seq("from-plan")
    cfg.select("apikey").select("metadata").select("origin").asString mustBe "plan"
  }

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
      ApiPlanApikeySeen.get("e2e-keyless") mustBe Some("keyless_plan-e2e-kl_127.0.0.1")
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
      ApiPlanApikeySeen.get("e2e-keyless-expr") mustBe Some("keyless_plan-e2e-kl2_team-a")

      // two callers resolving to different values get two distinct identities, hence two counters
      ApiPlanApikeySeen.reset()
      callApi(api, Seq("X-Consumer" -> "team-b")).status mustBe 200
      ApiPlanApikeySeen.get("e2e-keyless-expr") mustBe Some("keyless_plan-e2e-kl2_team-b")

      // a missing header does not fail the expression: the EL resolves it to "no-header-<field>",
      // so every caller omitting it lands on one shared identity, hence one shared quota bucket
      ApiPlanApikeySeen.reset()
      callApi(api).status mustBe 200
      ApiPlanApikeySeen.get("e2e-keyless-expr") mustBe Some(
        "keyless_plan-e2e-kl2_no-header-x-consumer"
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
      ApiPlanApikeySeen.get("e2e-keyless-empty") mustBe Some("keyless_plan-e2e-kl3_team-c")
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
      ApiPlanApikeySeen.get("e2e-jwt") mustBe Some("jwt_plan-e2e-jwt_consumer-from-token")
      // create_if_missing is on by default, so that identity is also persisted as a real apikey
      await(2.seconds)
      env.datastores.apiKeyDataStore
        .findById("jwt_plan-e2e-jwt_consumer-from-token")
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
      ApiPlanApikeySeen.get("e2e-jwt-nocreate") mustBe Some("jwt_plan-e2e-jwt2_unknown-consumer")
      await(2.seconds)
      env.datastores.apiKeyDataStore
        .findById("jwt_plan-e2e-jwt2_unknown-consumer")
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
      ApiPlanApikeySeen.get("mix") mustBe Some("jwt_plan-mix-jwt_consumer-from-token")

      // a caller presenting nothing falls back on the public plan
      ApiPlanApikeySeen.reset()
      callApi(api).status mustBe 200
      ApiPlanApikeySeen.get("mix") mustBe Some("keyless_plan-mix-kl_127.0.0.1")
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
}
