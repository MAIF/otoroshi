package plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import functional.PluginsTestSpecBase
import org.apache.pekko.actor.Props
import otoroshi.events.GatewayEvent
import otoroshi.api.WriteAction
import otoroshi.models.*
import otoroshi.next.models.*
import otoroshi.next.plugins.{AllowedQuota, FixedWindowStrategyConfig}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import play.api.libs.ws.{WSAuthScheme, writeableOf_String}

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.util.Try

// one credential for several apis, each through a subscription of its own (#2964): a subscription sharing the api key
// of another subscription of its owner gets an alias key, built from its own plan, that the consumer calls with the
// credential it shares
class ApiAliasKeysTests(parent: PluginsTestSpecBase) {
  import parent.*

  private val subscriptionsPath = "/apis/apis.otoroshi.io/v1/apisubscriptions"

  private def plan(id: String, kind: String = "apikey", quota: Option[Long] = None): ApiPlan = ApiPlan(
    Json
      .obj(
        "id"                             -> id,
        "name"                           -> id,
        "status"                         -> "published",
        "access_mode_configuration_type" -> kind,
        "access_mode_configuration"      -> Json.obj()
      )
      .applyOnWithOpt(quota) { case (obj, q) =>
        obj ++ Json.obj(
          "rateLimiting" -> Json.obj(
            "strategy" -> FixedWindowStrategyConfig(windowDurationMs = 60000L, quota = AllowedQuota(window = q)).json
          )
        )
      }
  )

  private def apiWith(plans: ApiPlan*): Api = {
    given otoroshi.env.Env = env
    val backend            = ApiBackend.empty
    val flow               = ApiFlows.empty
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

  private def deploy(apis: Api*): Unit = {
    apis.foreach(api => env.datastores.apiDataStore.set(api).futureValue)
    await(4.seconds)
  }

  private def undeploy(apis: Api*): Unit =
    apis.foreach(api => env.datastores.apiDataStore.delete(api).futureValue)

  private def call(api: Api, clientId: String, secret: String): Int = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders("Host" -> api.domain, "Otoroshi-Client-Id" -> clientId, "Otoroshi-Client-Secret" -> secret)
    .get()
    .futureValue
    .status

  private def waitFor(what: String)(cond: => Boolean): Unit = {
    val deadline = System.currentTimeMillis() + 15000
    while (!cond && System.currentTimeMillis() < deadline) await(100.millis)
    withClue(what)(cond mustBe true)
  }

  private lazy val template: JsObject =
    otoroshiApiCall("GET", s"$subscriptionsPath/_template").futureValue._1.as[JsObject]

  private def subscriptionBody(
      api: Api,
      planId: String,
      owner: String,
      tokenRefs: Seq[JsValue] = Seq.empty,
      kind: String = "apikey"
  ): JsObject = {
    val id = s"api-subscription_${IdGenerator.uuid}"
    template ++ Json.obj(
      "id"                -> id,
      "name"              -> id,
      "api_ref"           -> api.id,
      "plan_ref"          -> planId,
      "owner_ref"         -> owner,
      "subscription_kind" -> kind,
      "status"            -> "enabled",
      "token_refs"        -> tokenRefs
    )
  }

  private def subscribe(
      api: Api,
      planId: String,
      owner: String,
      tokenRefs: Seq[JsValue] = Seq.empty,
      kind: String = "apikey"
  ): (JsValue, Int) =
    otoroshiApiCall("POST", subscriptionsPath, Some(subscriptionBody(api, planId, owner, tokenRefs, kind))).futureValue

  private def subscription(id: String): JsObject =
    otoroshiApiCall("GET", s"$subscriptionsPath/$id").futureValue._1.as[JsObject]

  private def update(id: String, fields: JsObject): (JsValue, Int) =
    otoroshiApiCall("PUT", s"$subscriptionsPath/$id", Some(subscription(id) ++ fields)).futureValue

  private def unsubscribe(id: String): Int =
    otoroshiApiCall("DELETE", s"$subscriptionsPath/$id").futureValue._2

  private def shared(clientId: String): JsValue = Json.obj("apikey" -> clientId, "shared" -> true)

  private def key(clientId: String): Option[ApiKey] = env.datastores.apiKeyDataStore.findById(clientId).futureValue

  // the api key a subscription holds, not the one it shares
  private def ownKeyOf(subscription: JsValue): ApiKey = {
    val ids = subscription
      .select("token_refs")
      .as[Seq[JsValue]]
      .filterNot(_.select("shared").asOpt[Boolean].contains(true))
      .flatMap(_.select("apikey").asOpt[String])
    ids must have size 1
    key(ids.head).get
  }

  private def idOf(subscription: JsValue): String = subscription.select("id").asString

  private def synced(clientId: String)(cond: Option[ApiKey] => Boolean): Unit =
    waitFor(s"the proxy state sees the change of api key '$clientId'")(cond(env.proxyState.apikey(clientId)))

  // a consumer holding K on A shares it with a subscription to B: K calls A as K and B as its alias, each with the
  // quotas of its own plan, and the events tell the subscription of each call and the credential it was made with
  def oneCredentialSeveralApis(): Unit = {
    val events    = new ConcurrentLinkedQueue[GatewayEvent]()
    val collector = env.analyticsActorSystem.actorOf(Props(new ClientIpAddressTests.EventsCollector(events)))
    env.analyticsActorSystem.eventStream.subscribe(collector, classOf[GatewayEvent])
    val apiA      = apiWith(plan("plan_a", quota = Some(3)))
    val apiB      = apiWith(plan("plan_b", quota = Some(2)))
    deploy(apiA, apiB)
    try {
      val (subA, statusA) = subscribe(apiA, "plan_a", "client-1")
      statusA mustBe 201
      val k               = ownKeyOf(subscription(idOf(subA)))

      val (subB, statusB) = subscribe(apiB, "plan_b", "client-1", Seq(shared(k.clientId)))
      withClue(subB)(statusB mustBe 201)
      val storedB         = subscription(idOf(subB))
      // the subscription lists the credential it shares, and the key minted for it
      storedB.select("token_refs").as[Seq[JsValue]] must contain(shared(k.clientId))
      val kb              = ownKeyOf(storedB)
      kb.clientId must not be k.clientId
      kb.apiRef mustBe Some(ApiRef(apiB.id, "plan_b", idOf(subB), Some(k.clientId)))
      kb.authorizedEntities mustBe Seq(ApiIdentifier(apiB.id))
      kb.rotation.enabled mustBe false
      // the credential is left alone
      key(k.clientId).get.apiRef mustBe Some(ApiRef(apiA.id, "plan_a", idOf(subA)))
      synced(kb.clientId)(_.isDefined)
      waitFor("the alias index")(env.proxyState.apikeyAlias(k.clientId, apiB.id).contains(kb.clientId))

      // each api counts on the counter of its own plan: the 2 calls of plan_b do not count against the 3 of plan_a
      events.clear()
      call(apiB, k.clientId, k.clientSecret) mustBe 200
      call(apiB, k.clientId, k.clientSecret) mustBe 200
      call(apiB, k.clientId, k.clientSecret) mustBe 429
      call(apiA, k.clientId, k.clientSecret) mustBe 200
      call(apiA, k.clientId, k.clientSecret) mustBe 200
      call(apiA, k.clientId, k.clientSecret) mustBe 200
      call(apiA, k.clientId, k.clientSecret) mustBe 429

      waitFor("the events of the calls")(events.asScala.count(_.status == 200) >= 5)
      val served = events.asScala.toSeq.filter(_.status == 200)
      served.filter(_.to.host == apiB.domain).flatMap(_.identity).map(i => (i.identity, i.apiRef)).distinct mustBe
      Seq((kb.clientId, Some(ApiRef(apiB.id, "plan_b", idOf(subB), Some(k.clientId)))))
      served.filter(_.to.host == apiA.domain).flatMap(_.identity).map(i => (i.identity, i.apiRef)).distinct mustBe
      Seq((k.clientId, Some(ApiRef(apiA.id, "plan_a", idOf(subA)))))

      // the alias has no secret of its own: called with its own client id, it is refused
      call(apiB, kb.clientId, kb.clientSecret) mustBe 401
      // a wrong secret of the credential is refused on both apis
      call(apiB, k.clientId, "wrong-secret") mustBe 401
      call(apiA, k.clientId, "wrong-secret") mustBe 401

      Seq(subB, subA).foreach(sub => unsubscribe(idOf(sub)) mustBe 200)
    } finally {
      undeploy(apiA, apiB)
      env.analyticsActorSystem.eventStream.unsubscribe(collector)
      env.analyticsActorSystem.stop(collector)
    }
  }

  // the quotas consumed above make the new secret answer 429: it is checked on fresh apis
  def newSecretCallsEveryApi(): Unit = {
    val apiA = apiWith(plan("plan_a"))
    val apiB = apiWith(plan("plan_b"))
    deploy(apiA, apiB)
    try {
      val (subA, _) = subscribe(apiA, "plan_a", "client-1")
      val k         = ownKeyOf(subscription(idOf(subA)))
      val (subB, _) = subscribe(apiB, "plan_b", "client-1", Seq(shared(k.clientId)))
      val kb        = ownKeyOf(subscription(idOf(subB)))
      synced(kb.clientId)(_.isDefined)
      call(apiB, k.clientId, k.clientSecret) mustBe 200

      key(k.clientId).get.copy(clientSecret = "the-new-secret-of-k").save().futureValue
      synced(k.clientId)(_.exists(_.clientSecret == "the-new-secret-of-k"))
      call(apiA, k.clientId, "the-new-secret-of-k") mustBe 200
      call(apiB, k.clientId, "the-new-secret-of-k") mustBe 200
      call(apiA, k.clientId, k.clientSecret) mustBe 401
      call(apiB, k.clientId, k.clientSecret) mustBe 401

      // the next secret of a rotation calls both apis too
      key(k.clientId).get
        .copy(rotation = ApiKeyRotation(enabled = true, nextSecret = Some("the-next-secret-of-k")))
        .save()
        .futureValue
      synced(k.clientId)(_.exists(_.rotation.nextSecret.contains("the-next-secret-of-k")))
      call(apiB, k.clientId, "the-next-secret-of-k") mustBe 200

      Seq(subB, subA).foreach(sub => unsubscribe(idOf(sub)) mustBe 200)
    } finally undeploy(apiA, apiB)
  }

  // what a subscription can share: one key, of an apikey or oauth2-local subscription of the same owner, that does
  // not call its api yet and has no alias on it, chosen at the creation once and for all
  def sharingChecks(): Unit = {
    val apiA = apiWith(plan("plan_a"))
    val apiB = apiWith(plan("plan_b"))
    val apiC = apiWith(plan("plan_c"), plan("plan_free", kind = "keyless"))
    deploy(apiA, apiB, apiC)
    try {
      val (subA, _)     = subscribe(apiA, "plan_a", "client-1")
      val k             = ownKeyOf(subscription(idOf(subA)))
      val (other, _)    = subscribe(apiC, "plan_c", "client-1")
      val (subB, statB) = subscribe(apiB, "plan_b", "client-1", Seq(shared(k.clientId)))
      statB mustBe 201
      val kb            = ownKeyOf(subscription(idOf(subB)))

      def refused(body: (JsValue, Int), message: String): Unit = {
        withClue(body._1)(body._2 mustBe 400)
        body._1.select("error").asString must include(message)
      }

      refused(subscribe(apiB, "plan_b", "client-1", Seq(shared(k.clientId))), "already shared")
      refused(subscribe(apiA, "plan_a", "client-1", Seq(shared(k.clientId))), "already calls")
      refused(subscribe(apiC, "plan_c", "client-1", Seq(shared(kb.clientId))), "share that one")
      refused(subscribe(apiC, "plan_c", "client-2", Seq(shared(k.clientId))), "another owner")
      refused(subscribe(apiC, "plan_c", "client-1", Seq(shared("not-a-key"))), "not found")
      refused(
        subscribe(apiC, "plan_c", "client-1", Seq(shared(k.clientId), shared(ownKeyOf(subscription(idOf(other))).clientId))),
        "one api key at most"
      )
      refused(subscribe(apiC, "plan_free", "client-1", Seq(shared(k.clientId)), kind = "keyless"), "cannot share")

      // the shared key never changes: neither removed nor replaced
      refused(update(idOf(subB), Json.obj("token_refs" -> Json.arr(Json.obj("apikey" -> kb.clientId)))), "cannot change")
      // anything else does
      update(idOf(subB), Json.obj("name" -> "renamed"))._2 mustBe 200
      key(kb.clientId).get.apiRef.flatMap(_.credential) mustBe Some(k.clientId)

      // without shared, nothing changes: a key of another subscription named in the token_refs is left alone
      val (plain, plainStatus) = subscribe(apiC, "plan_c", "client-1", Seq(Json.obj("apikey" -> k.clientId)))
      plainStatus mustBe 201
      val plainRefs            = subscription(idOf(plain)).select("token_refs").as[Seq[JsValue]]
      plainRefs.flatMap(_.select("apikey").asOpt[String]) must not contain k.clientId
      ownKeyOf(subscription(idOf(plain))).apiRef.flatMap(_.credential) mustBe None

      Seq(plain, subB, other, subA).foreach(sub => unsubscribe(idOf(sub)) mustBe 200)
    } finally undeploy(apiA, apiB, apiC)
  }

  // suspending or deleting a subscription only changes its own key. The credential of a deleted subscription that
  // other subscriptions share is kept for them, authorized on no api, and goes away with its last alias
  def lifecycle(): Unit = {
    val apiA = apiWith(plan("plan_a"))
    val apiB = apiWith(plan("plan_b"))
    val apiC = apiWith(plan("plan_c"))
    deploy(apiA, apiB, apiC)
    try {
      val (subA, _) = subscribe(apiA, "plan_a", "client-1")
      val k         = ownKeyOf(subscription(idOf(subA)))
      val (subB, _) = subscribe(apiB, "plan_b", "client-1", Seq(shared(k.clientId)))
      val kb        = ownKeyOf(subscription(idOf(subB)))
      synced(kb.clientId)(_.isDefined)
      call(apiB, k.clientId, k.clientSecret) mustBe 200

      // sub_b suspended: only its key
      update(idOf(subB), Json.obj("status" -> "disabled"))._2 mustBe 200
      key(kb.clientId).get.enabled mustBe false
      synced(kb.clientId)(_.exists(!_.enabled))
      call(apiB, k.clientId, k.clientSecret) mustBe 401
      call(apiA, k.clientId, k.clientSecret) mustBe 200
      update(idOf(subB), Json.obj("status" -> "enabled"))._2 mustBe 200
      synced(kb.clientId)(_.exists(_.enabled))
      call(apiB, k.clientId, k.clientSecret) mustBe 200

      // sub_b deleted: its key goes, the credential stays
      unsubscribe(idOf(subB)) mustBe 200
      key(kb.clientId) mustBe None
      key(k.clientId).isDefined mustBe true
      synced(kb.clientId)(_.isEmpty)
      call(apiB, k.clientId, k.clientSecret) mustBe 401
      call(apiA, k.clientId, k.clientSecret) mustBe 200

      val (subC, _) = subscribe(apiC, "plan_c", "client-1", Seq(shared(k.clientId)))
      val kc        = ownKeyOf(subscription(idOf(subC)))
      synced(kc.clientId)(_.isDefined)
      call(apiC, k.clientId, k.clientSecret) mustBe 200

      // sub_a suspended: the credential no longer calls A, still calls C through the alias of sub_c
      update(idOf(subA), Json.obj("status" -> "disabled"))._2 mustBe 200
      synced(k.clientId)(_.exists(!_.enabled))
      call(apiA, k.clientId, k.clientSecret) mustBe 401
      call(apiC, k.clientId, k.clientSecret) mustBe 200

      // sub_a deleted: the credential is kept for sub_c, authorized on no api
      unsubscribe(idOf(subA)) mustBe 200
      val kept = key(k.clientId).get
      kept.enabled mustBe false
      kept.authorizedEntities mustBe empty
      kept.apiRef mustBe None
      synced(k.clientId)(_.exists(_.apiRef.isEmpty))
      call(apiA, k.clientId, k.clientSecret) mustBe 401
      call(apiC, k.clientId, k.clientSecret) mustBe 200

      // a credential kept for its aliases can still be shared
      val (subB2, statB2) = subscribe(apiB, "plan_b", "client-1", Seq(shared(k.clientId)))
      withClue(subB2)(statB2 mustBe 201)
      val kb2             = ownKeyOf(subscription(idOf(subB2)))

      // its last alias takes it with it
      unsubscribe(idOf(subC)) mustBe 200
      key(kc.clientId) mustBe None
      key(k.clientId).isDefined mustBe true
      unsubscribe(idOf(subB2)) mustBe 200
      key(kb2.clientId) mustBe None
      key(k.clientId) mustBe None
    } finally undeploy(apiA, apiB, apiC)
  }

  // the users of the admin api: one who only writes in its own tenant and team, where none of the keys minted here are
  // located, and a super admin
  private val tenant = TenantId("alias-tenant")
  private val team   = TeamId("alias-team")
  private val scoped = BackOfficeUser(
    randomId = "alias@otoroshi.io",
    name = "alias@otoroshi.io",
    email = "alias@otoroshi.io",
    profile = Json.obj(),
    authConfigId = "basic",
    simpleLogin = true,
    tags = Seq.empty,
    metadata = Map.empty,
    rights = UserRights(Seq(UserRight(TenantAccess(tenant.value), Seq(TeamAccess(team.value))))),
    adminEntityValidators = Map()
  )
  private val superAdmin = scoped.copy(
    randomId = "alias-admin@otoroshi.io",
    name = "alias-admin@otoroshi.io",
    email = "alias-admin@otoroshi.io",
    rights = UserRights(Seq(UserRight(TenantAccess("*"), Seq(TeamAccess("*")))))
  )
  private val located = Json.obj("_loc" -> Json.obj("tenant" -> tenant.value, "teams" -> Json.arr(team.value)))

  private val apikeysPath = "/apis/apim.otoroshi.io/v1/apikeys"
  private val draftsPath  = "/apis/proxy.otoroshi.io/v1/drafts"

  private def asUser(user: BackOfficeUser, method: String, path: String, payload: JsValue): (JsValue, Int) = {
    val token    = JWT
      .create()
      .withClaim("user", Json.stringify(user.toJson))
      .withExpiresAt(java.time.Instant.now().plusSeconds(30))
      .sign(Algorithm.HMAC512(env.otoroshiSecret))
    val response = ws
      .url(s"http://localhost:$port$path")
      .withHttpHeaders(
        "Host"                     -> "otoroshi-api.oto.tools",
        "Accept"                   -> "application/json",
        "Content-Type"             -> "application/json",
        "Otoroshi-Admin-Profile"   -> Base64.getUrlEncoder.encodeToString(
          Json.stringify(user.profile).getBytes(StandardCharsets.UTF_8)
        ),
        "Otoroshi-Tenant"          -> tenant.value,
        "Otoroshi-BackOffice-User" -> token
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .withMethod(method)
      .withBody(Json.stringify(payload))
      .execute()
      .futureValue
    (Try(response.json).getOrElse(JsNull), response.status)
  }

  private def aliasesOf(credential: String): Seq[ApiKey] =
    env.datastores.apiKeyDataStore.findAll(force = true).futureValue.filter(_.apiRef.exists(_.credential.contains(credential)))

  private def refusedWith(response: (JsValue, Int), status: Int, message: String): Unit = {
    withClue(response._1)(response._2 mustBe status)
    response._1.toString must include(message)
  }

  // sharing a key requires the rights to write it, the owner_ref being free text
  def sharingRights(): Unit = {
    val apiA = apiWith(plan("plan_a"))
    val apiB = apiWith(plan("plan_b"))
    deploy(apiA, apiB)
    try {
      val (subA, _) = subscribe(apiA, "plan_a", "client-1")
      val k         = ownKeyOf(subscription(idOf(subA)))
      synced(k.clientId)(_.isDefined)

      // the user writes subscriptions where it is located, not the key it would share
      refusedWith(
        asUser(scoped, "POST", subscriptionsPath, subscriptionBody(apiB, "plan_b", "client-1", Seq(shared(k.clientId))) ++ located),
        400,
        "you can't grant rights you don't have yourself"
      )

      // the draft of a subscription shares nothing, whoever writes it: its keys are real keys of the live api
      given otoroshi.env.Env = env
      env.datastores.draftsDataStore
        .set(Draft(id = apiB.id, kind = "api", content = apiB.json, name = apiB.id, description = ""))
        .futureValue
      waitFor("the proxy state sees the draft of the api")(env.proxyState.draft(apiB.id).isDefined)
      val body  = subscriptionBody(apiB, "plan_b", "client-1", Seq(shared(k.clientId)))
      val draft = Json.obj(
        "id"          -> idOf(body),
        "name"        -> "draft",
        "description" -> "",
        "kind"        -> "api-subscription",
        "content"     -> body,
        "metadata"    -> Json.obj(),
        "tags"        -> Json.arr()
      )
      ApiSubscription
        .check(apiB.id, ApiSubscription.format.reads(body).get, WriteAction.Create, isDraft = true)
        .futureValue
        .left
        .toOption mustBe Some("the draft of a subscription cannot share an api key: share it with the subscription")
      // the admin api answers the refusal of the draft of a subscription with a 500, its error being a string
      otoroshiApiCall("POST", draftsPath, Some(draft)).futureValue._2 must not be 201
      asUser(scoped, "POST", draftsPath, draft ++ located)._2 must not be 201
      env.datastores.draftsDataStore.findById(idOf(body)).futureValue mustBe None
      aliasesOf(k.clientId) mustBe empty
      env.datastores.draftsDataStore.delete(apiB.id).futureValue

      // once the key is located where the user writes, it shares it
      key(k.clientId).get.copy(location = EntityLocation(tenant, Seq(team))).save().futureValue
      synced(k.clientId)(_.exists(_.location.tenant == tenant))
      val (created, status) =
        asUser(scoped, "POST", subscriptionsPath, subscriptionBody(apiB, "plan_b", "client-1", Seq(shared(k.clientId))) ++ located)
      withClue(created)(status mustBe 201)

      Seq(created, subA).foreach(sub => unsubscribe(idOf(sub)) mustBe 200)
    } finally undeploy(apiA, apiB)
  }

  // an alias written through the api keys: it gives the access of its credential, so it takes the rights to write the
  // credential, its credential never changes, and it serves only once authorized on its api
  def aliasesWrittenByHand(): Unit = {
    val apiA = apiWith(plan("plan_a"))
    val apiB = apiWith(plan("plan_b"))
    deploy(apiA, apiB)
    try {
      val (subA, _) = subscribe(apiA, "plan_a", "client-1")
      val k         = ownKeyOf(subscription(idOf(subA)))
      synced(k.clientId)(_.isDefined)
      call(apiB, k.clientId, k.clientSecret) must not be 200

      def forged(authorized: Boolean) = ApiKey(
        clientId = s"forged-${IdGenerator.lowerCaseToken(8)}",
        clientSecret = "whatever",
        clientName = "forged",
        authorizedEntities = if (authorized) Seq(ApiIdentifier(apiB.id)) else Seq.empty,
        apiRef = Some(ApiRef(apiB.id, "plan_b", "no-such-subscription", Some(k.clientId))),
        location = EntityLocation(tenant, Seq(team))
      )

      // a user who cannot write the credential cannot write an alias of it
      refusedWith(asUser(scoped, "POST", apikeysPath, forged(authorized = true).toJson), 400, "you can't grant rights you don't have yourself")
      aliasesOf(k.clientId) mustBe empty

      // a super admin can: an import, a restoration. Minted by no subscription sharing the credential, the alias serves
      // nothing, authorized on the api or not
      val byAdmin           = forged(authorized = true)
      val (created, status) = asUser(superAdmin, "POST", apikeysPath, byAdmin.toJson)
      withClue(created)(status mustBe 201)
      waitFor("the alias index")(env.proxyState.apikeyAlias(k.clientId, apiB.id).contains(byAdmin.clientId))
      call(apiB, k.clientId, k.clientSecret) must not be 200

      // the credential of an alias never changes
      refusedWith(
        otoroshiApiCall(
          "PUT",
          s"$apikeysPath/${byAdmin.clientId}",
          Some(key(byAdmin.clientId).get.copy(apiRef = byAdmin.apiRef.map(_.copy(credential = Some("another-key")))).toJson)
        ).futureValue,
        400,
        "the credential of an alias key cannot change"
      )
      key(byAdmin.clientId).get.apiRef.flatMap(_.credential) mustBe Some(k.clientId)

      // nor does it keep its credential: the subscription of the credential deleted, both go
      unsubscribe(idOf(subA)) mustBe 200
      key(k.clientId) mustBe None
      key(byAdmin.clientId) mustBe None
    } finally undeploy(apiA, apiB)
  }

  // a credential deleted by hand takes its aliases with it: nobody calls them again with a key created under its
  // client id, a secret of its own
  def deletedCredentialTakesItsAliases(): Unit = {
    val apiA = apiWith(plan("plan_a"))
    val apiB = apiWith(plan("plan_b"))
    deploy(apiA, apiB)
    try {
      def sharedPair(): (JsValue, ApiKey, JsValue, ApiKey) = {
        val (subA, _) = subscribe(apiA, "plan_a", "client-1")
        val k         = ownKeyOf(subscription(idOf(subA)))
        val (subB, _) = subscribe(apiB, "plan_b", "client-1", Seq(shared(k.clientId)))
        val kb        = ownKeyOf(subscription(idOf(subB)))
        waitFor("the alias index")(env.proxyState.apikeyAlias(k.clientId, apiB.id).contains(kb.clientId))
        call(apiB, k.clientId, k.clientSecret) mustBe 200
        (subA, k, subB, kb)
      }

      // through the generic api
      val (subA, k, subB, kb) = sharedPair()
      otoroshiApiCall("DELETE", s"$apikeysPath/${k.clientId}").futureValue._2 mustBe 200
      key(k.clientId) mustBe None
      key(kb.clientId) mustBe None
      synced(k.clientId)(_.isEmpty)
      waitFor("the alias index forgets the credential")(!env.proxyState.hasApikeyAliases(k.clientId))

      // a user of another tenant takes the client id: it calls nothing
      val takeover          = ApiKey(
        clientId = k.clientId,
        clientSecret = "the-secret-of-the-attacker",
        clientName = "takeover",
        authorizedEntities = Seq.empty,
        location = EntityLocation(tenant, Seq(team))
      )
      val (created, status) = asUser(scoped, "POST", apikeysPath, takeover.toJson)
      withClue(created)(status mustBe 201)
      synced(k.clientId)(_.exists(_.clientSecret == "the-secret-of-the-attacker"))
      call(apiB, k.clientId, "the-secret-of-the-attacker") must not be 200
      env.datastores.apiKeyDataStore.delete(k.clientId).futureValue
      Seq(subB, subA).foreach(sub => unsubscribe(idOf(sub)) mustBe 200)

      // through the typed api
      val (subA2, k2, subB2, kb2) = sharedPair()
      otoroshiApiCall("DELETE", s"/api/apikeys/${k2.clientId}").futureValue._2 mustBe 200
      key(k2.clientId) mustBe None
      key(kb2.clientId) mustBe None
      Seq(subB2, subA2).foreach(sub => unsubscribe(idOf(sub)) mustBe 200)

      // an alias that outlived its credential, removed without its aliases: no key is created under its client id
      val (subA3, k3, subB3, kb3) = sharedPair()
      env.datastores.rawDataStore.del(Seq(env.datastores.apiKeyDataStore.key(k3.clientId))).futureValue
      synced(k3.clientId)(_.isEmpty)
      env.proxyState.hasApikeyAliases(k3.clientId) mustBe true
      refusedWith(
        asUser(superAdmin, "POST", apikeysPath, takeover.copy(clientId = k3.clientId).toJson),
        400,
        "is the credential of alias keys"
      )
      env.datastores.apiKeyDataStore.delete(kb3.clientId).futureValue
      Seq(subB3, subA3).foreach(sub => unsubscribe(idOf(sub)))
    } finally undeploy(apiA, apiB)
  }

  // the index of the aliases is built with the api keys of the proxy state
  def aliasesIndex(): Unit = {
    val k  = ApiKey(clientId = "k", clientSecret = "s", clientName = "k", authorizedEntities = Seq.empty)
    val kb = k.copy(clientId = "kb", apiRef = Some(ApiRef("api-b", "plan-b", "sub-b", Some("k"))))
    val kc = k.copy(clientId = "kc", apiRef = Some(ApiRef("api-c", "plan-c", "sub-c", Some("k"))))
    val ka = k.copy(clientId = "ka", apiRef = Some(ApiRef("api-a", "plan-a", "sub-a")))
    otoroshi.next.proxy.NgProxyState.apikeyAliases(Seq(k, ka, kb, kc)) mustBe Map(
      "k" -> Map("api-b" -> "kb", "api-c" -> "kc")
    )
    otoroshi.next.proxy.NgProxyState.apikeyAliases(Seq(k, ka)) mustBe Map.empty
    // the credential is written only when there is one, the json of the other keys is unchanged
    ka.apiRef.get.json mustBe Json.obj("api" -> "api-a", "plan" -> "plan-a", "sub" -> "sub-a")
    ApiRef.format.reads(kb.apiRef.get.json).get mustBe kb.apiRef.get
  }
}
