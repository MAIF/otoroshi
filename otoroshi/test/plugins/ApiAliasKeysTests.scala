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

// one credential for several apis, each through a subscription of its own (#2964): the consumer has a shared
// credential, an api key attached to nothing, and every subscription sharing it gets an alias key, built from its own
// plan, that the consumer calls with the credential
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

  // {"shared": true}: the credential of the owner of the subscription, created when there is none
  private val ownerShared: JsValue = Json.obj("shared" -> true)

  // an owner of its own for each test: its credentials are only deleted by hand
  private def newOwner(): String = s"client-${IdGenerator.lowerCaseToken(8)}"

  // minted: as if a subscription had created it, the only ones {"shared": true} takes
  private def credentialOf(
      owner: String,
      location: EntityLocation = EntityLocation.default,
      minted: Boolean = false
  ): ApiKey = {
    val credential = ApiKey(
      clientId = IdGenerator.lowerCaseToken(16),
      clientSecret = IdGenerator.lowerCaseToken(64),
      clientName = s"credential of $owner",
      authorizedEntities = Seq.empty,
      sharedCredential = Some(SharedCredential(owner, minted)),
      location = location
    )
    credential.save().futureValue mustBe true
    synced(credential.clientId)(_.isDefined)
    credential
  }

  private def stored(subscription: JsValue): ApiSubscription =
    env.datastores.apiSubscriptionDataStore.findById(idOf(subscription)).futureValue.get

  // the credential a subscription shares
  private def sharedOf(subscription: JsValue): ApiKey = key(ApiSubscription.sharedCredential(stored(subscription)).get).get

  // the proxy state serves the alias of a subscription: its credential, its alias, and the subscription itself
  private def ready(credential: ApiKey, api: Api, subscription: JsValue, alias: ApiKey): Unit =
    waitFor(s"the proxy state serves the alias '${alias.clientId}'")(
      env.proxyState.apikey(credential.clientId).isDefined &&
      env.proxyState.apikeyAlias(credential.clientId, api.id).contains(alias.clientId) &&
      env.proxyState.apiSubscription(idOf(subscription)).isDefined
    )

  private def dropKeys(clientIds: String*): Unit =
    clientIds.foreach(id => env.datastores.apiKeyDataStore.delete(id).futureValue)

  // a consumer subscribes to A and to B sharing its credential: created with the first subscription, it calls A and B
  // through the alias of each, with the quotas of its own plan, and the events tell the subscription of each call and
  // the credential it was made with
  def oneCredentialSeveralApis(): Unit = {
    val events    = new ConcurrentLinkedQueue[GatewayEvent]()
    val collector = env.analyticsActorSystem.actorOf(Props(new ClientIpAddressTests.EventsCollector(events)))
    env.analyticsActorSystem.eventStream.subscribe(collector, classOf[GatewayEvent])
    val owner     = newOwner()
    val apiA      = apiWith(plan("plan_a", quota = Some(3)))
    val apiB      = apiWith(plan("plan_b", quota = Some(2)))
    deploy(apiA, apiB)
    try {
      val (subA, statusA) = subscribe(apiA, "plan_a", owner, Seq(ownerShared))
      withClue(subA)(statusA mustBe 201)
      // the credential of the owner is created with its first subscription, attached to nothing
      val k               = sharedOf(subA)
      k.sharedCredential mustBe Some(SharedCredential(owner, minted = true))
      k.authorizedEntities mustBe empty
      k.apiRef mustBe None
      k.enabled mustBe true
      val ka              = ownKeyOf(subscription(idOf(subA)))

      // the next one shares the same
      val (subB, statusB) = subscribe(apiB, "plan_b", owner, Seq(ownerShared))
      withClue(subB)(statusB mustBe 201)
      val storedB         = subscription(idOf(subB))
      // the subscription names the credential it shares, and the key minted for it
      storedB.select("token_refs").as[Seq[JsValue]] must contain(shared(k.clientId))
      val kb              = ownKeyOf(storedB)
      kb.clientId must not be k.clientId
      kb.apiRef mustBe Some(ApiRef(apiB.id, "plan_b", idOf(subB), Some(k.clientId)))
      kb.authorizedEntities mustBe Seq(ApiIdentifier(apiB.id))
      kb.rotation.enabled mustBe false
      ready(k, apiA, subA, ka)
      ready(k, apiB, subB, kb)

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
      Seq((ka.clientId, Some(ApiRef(apiA.id, "plan_a", idOf(subA), Some(k.clientId)))))

      // an alias has no secret of its own: called with its own client id, it is refused
      call(apiB, kb.clientId, kb.clientSecret) mustBe 401
      call(apiA, ka.clientId, ka.clientSecret) mustBe 401
      // a wrong secret of the credential is refused on both apis
      call(apiB, k.clientId, "wrong-secret") mustBe 401
      call(apiA, k.clientId, "wrong-secret") mustBe 401

      // the credential does not go with the subscriptions
      Seq(subB, subA).foreach(sub => unsubscribe(idOf(sub)) mustBe 200)
      key(k.clientId).isDefined mustBe true
      dropKeys(k.clientId)
    } finally {
      undeploy(apiA, apiB)
      env.analyticsActorSystem.eventStream.unsubscribe(collector)
      env.analyticsActorSystem.stop(collector)
    }
  }

  // the secret is the one of the credential, on every api
  def newSecretCallsEveryApi(): Unit = {
    val owner = newOwner()
    val apiA  = apiWith(plan("plan_a"))
    val apiB  = apiWith(plan("plan_b"))
    deploy(apiA, apiB)
    try {
      val k         = credentialOf(owner)
      val (subA, _) = subscribe(apiA, "plan_a", owner, Seq(shared(k.clientId)))
      val (subB, _) = subscribe(apiB, "plan_b", owner, Seq(shared(k.clientId)))
      ready(k, apiA, subA, ownKeyOf(subscription(idOf(subA))))
      ready(k, apiB, subB, ownKeyOf(subscription(idOf(subB))))
      call(apiA, k.clientId, k.clientSecret) mustBe 200
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
      call(apiA, k.clientId, "the-next-secret-of-k") mustBe 200
      call(apiB, k.clientId, "the-next-secret-of-k") mustBe 200

      Seq(subB, subA).foreach(sub => unsubscribe(idOf(sub)) mustBe 200)
      dropKeys(k.clientId)
    } finally undeploy(apiA, apiB)
  }

  // what a subscription can share: one shared credential of its owner, never the api key of a subscription, once per
  // api, chosen at the creation once and for all
  def sharingChecks(): Unit = {
    val owner = newOwner()
    val apiA  = apiWith(plan("plan_a"))
    val apiB  = apiWith(plan("plan_b"))
    val apiC  = apiWith(plan("plan_c"), plan("plan_free", kind = "keyless"))
    deploy(apiA, apiB, apiC)
    try {
      val k             = credentialOf(owner, minted = true)
      val (other, _)    = subscribe(apiC, "plan_c", owner)
      val otherKey      = ownKeyOf(subscription(idOf(other)))
      val (subB, statB) = subscribe(apiB, "plan_b", owner, Seq(shared(k.clientId)))
      withClue(subB)(statB mustBe 201)
      val kb            = ownKeyOf(subscription(idOf(subB)))

      def refused(body: (JsValue, Int), message: String): Unit = {
        withClue(body._1)(body._2 mustBe 400)
        body._1.select("error").asString must include(message)
      }

      refused(subscribe(apiB, "plan_b", owner, Seq(shared(k.clientId))), "already shared")
      refused(subscribe(apiB, "plan_b", owner, Seq(ownerShared)), "already shared")
      // only a shared credential is shared: neither an alias, nor the api key of a subscription
      refused(subscribe(apiC, "plan_c", owner, Seq(shared(kb.clientId))), "is not a shared credential")
      refused(subscribe(apiA, "plan_a", owner, Seq(shared(otherKey.clientId))), "is not a shared credential")
      refused(subscribe(apiC, "plan_c", newOwner(), Seq(shared(k.clientId))), "another owner")
      refused(subscribe(apiC, "plan_c", owner, Seq(shared("not-a-key"))), "not found")
      refused(subscribe(apiC, "plan_c", owner, Seq(shared(k.clientId), ownerShared)), "one api key at most")
      refused(subscribe(apiC, "plan_free", owner, Seq(ownerShared), kind = "keyless"), "cannot share")

      // an owner with several credentials names the one it shares
      val k2            = credentialOf(owner, minted = true)
      refused(subscribe(apiA, "plan_a", owner, Seq(ownerShared)), "several shared credentials")
      val (subA, statA) = subscribe(apiA, "plan_a", owner, Seq(shared(k2.clientId)))
      withClue(subA)(statA mustBe 201)

      // the shared credential never changes: neither removed nor replaced
      refused(update(idOf(subB), Json.obj("token_refs" -> Json.arr(Json.obj("apikey" -> kb.clientId)))), "cannot change")
      refused(
        update(idOf(subB), Json.obj("token_refs" -> Json.arr(shared(k2.clientId), Json.obj("apikey" -> kb.clientId)))),
        "cannot change"
      )
      refused(
        update(idOf(subB), Json.obj("token_refs" -> Json.arr(ownerShared, Json.obj("apikey" -> kb.clientId)))),
        "cannot change"
      )
      // nor do the api and the owner of a subscription that shares one: the credential was checked for them
      refused(update(idOf(subB), Json.obj("api_ref" -> apiC.id, "plan_ref" -> "plan_c")), "cannot change")
      refused(update(idOf(subB), Json.obj("owner_ref" -> newOwner())), "cannot change")
      key(kb.clientId).get.authorizedEntities mustBe Seq(ApiIdentifier(apiB.id))
      // a subscription that shares nothing still changes its owner
      update(idOf(other), Json.obj("owner_ref" -> newOwner()))._2 mustBe 200
      // anything else does
      update(idOf(subB), Json.obj("name" -> "renamed"))._2 mustBe 200
      key(kb.clientId).get.apiRef.flatMap(_.credential) mustBe Some(k.clientId)

      // without shared, nothing changes: a key that is not its own named in the token_refs is left alone
      val (plain, plainStatus) = subscribe(apiC, "plan_c", owner, Seq(Json.obj("apikey" -> k.clientId)))
      plainStatus mustBe 201
      val plainRefs            = subscription(idOf(plain)).select("token_refs").as[Seq[JsValue]]
      plainRefs.flatMap(_.select("apikey").asOpt[String]) must not contain k.clientId
      ownKeyOf(subscription(idOf(plain))).apiRef.flatMap(_.credential) mustBe None

      Seq(plain, subA, subB, other).foreach(sub => unsubscribe(idOf(sub)) mustBe 200)
      dropKeys(k.clientId, k2.clientId)
    } finally undeploy(apiA, apiB, apiC)
  }

  // suspending or deleting a subscription only changes its own alias. The credential is its own: disabled, it calls
  // nothing, like any api key, and it only goes when it is deleted, with its aliases
  def lifecycle(): Unit = {
    val owner = newOwner()
    val apiA  = apiWith(plan("plan_a"))
    val apiB  = apiWith(plan("plan_b"))
    val apiC  = apiWith(plan("plan_c"))
    deploy(apiA, apiB, apiC)
    try {
      val k         = credentialOf(owner, minted = true)
      val (subA, _) = subscribe(apiA, "plan_a", owner, Seq(shared(k.clientId)))
      val ka        = ownKeyOf(subscription(idOf(subA)))
      val (subB, _) = subscribe(apiB, "plan_b", owner, Seq(shared(k.clientId)))
      val kb        = ownKeyOf(subscription(idOf(subB)))
      ready(k, apiA, subA, ka)
      ready(k, apiB, subB, kb)
      call(apiA, k.clientId, k.clientSecret) mustBe 200
      call(apiB, k.clientId, k.clientSecret) mustBe 200

      // sub_b suspended: only its alias
      update(idOf(subB), Json.obj("status" -> "disabled"))._2 mustBe 200
      key(kb.clientId).get.enabled mustBe false
      key(k.clientId).get.enabled mustBe true
      synced(kb.clientId)(_.exists(!_.enabled))
      call(apiB, k.clientId, k.clientSecret) mustBe 401
      call(apiA, k.clientId, k.clientSecret) mustBe 200
      update(idOf(subB), Json.obj("status" -> "enabled"))._2 mustBe 200
      synced(kb.clientId)(_.exists(_.enabled))
      call(apiB, k.clientId, k.clientSecret) mustBe 200

      // the credential disabled: nothing calls, on any api, like any disabled api key
      key(k.clientId).get.copy(enabled = false).save().futureValue
      synced(k.clientId)(_.exists(!_.enabled))
      call(apiA, k.clientId, k.clientSecret) mustBe 401
      call(apiB, k.clientId, k.clientSecret) mustBe 401
      // the aliases are untouched
      key(ka.clientId).get.enabled mustBe true
      key(kb.clientId).get.enabled mustBe true
      key(k.clientId).get.copy(enabled = true).save().futureValue
      synced(k.clientId)(_.exists(_.enabled))
      call(apiA, k.clientId, k.clientSecret) mustBe 200
      call(apiB, k.clientId, k.clientSecret) mustBe 200

      // sub_b deleted: its alias goes, the credential stays
      unsubscribe(idOf(subB)) mustBe 200
      key(kb.clientId) mustBe None
      key(k.clientId).isDefined mustBe true
      synced(kb.clientId)(_.isEmpty)
      call(apiB, k.clientId, k.clientSecret) mustBe 401
      call(apiA, k.clientId, k.clientSecret) mustBe 200

      // its last subscription deleted, the credential is still there, unchanged, and still shared
      unsubscribe(idOf(subA)) mustBe 200
      key(ka.clientId) mustBe None
      val left = key(k.clientId).get
      left.enabled mustBe true
      left.sharedCredential mustBe Some(SharedCredential(owner, minted = true))
      synced(ka.clientId)(_.isEmpty)
      call(apiA, k.clientId, k.clientSecret) mustBe 401

      val (subC, statC) = subscribe(apiC, "plan_c", owner, Seq(ownerShared))
      withClue(subC)(statC mustBe 201)
      sharedOf(subC).clientId mustBe k.clientId
      val kc            = ownKeyOf(subscription(idOf(subC)))
      ready(k, apiC, subC, kc)
      call(apiC, k.clientId, k.clientSecret) mustBe 200

      // deleted, it takes its aliases with it
      otoroshiApiCall("DELETE", s"$apikeysPath/${k.clientId}").futureValue._2 mustBe 200
      key(k.clientId) mustBe None
      key(kc.clientId) mustBe None
      unsubscribe(idOf(subC)) mustBe 200
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

  // sharing a credential requires the rights to write it, the owner_ref being free text: named, or found for the owner
  def sharingRights(): Unit = {
    val owner = newOwner()
    val apiA  = apiWith(plan("plan_a"))
    val apiB  = apiWith(plan("plan_b"))
    deploy(apiA, apiB)
    try {
      val k = credentialOf(owner)

      // the user writes subscriptions where it is located, not the credential it would share
      refusedWith(
        asUser(scoped, "POST", subscriptionsPath, subscriptionBody(apiB, "plan_b", owner, Seq(shared(k.clientId))) ++ located),
        400,
        "you can't grant rights you don't have yourself"
      )

      // the draft of a subscription shares nothing, whoever writes it: its keys are real keys of the live api
      given otoroshi.env.Env = env
      env.datastores.draftsDataStore
        .set(Draft(id = apiB.id, kind = "api", content = apiB.json, name = apiB.id, description = ""))
        .futureValue
      waitFor("the proxy state sees the draft of the api")(env.proxyState.draft(apiB.id).isDefined)
      Seq(shared(k.clientId), ownerShared).foreach { entry =>
        val body  = subscriptionBody(apiB, "plan_b", owner, Seq(entry))
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
        refusedWith(otoroshiApiCall("POST", draftsPath, Some(draft)).futureValue, 400, "cannot share an api key")
        refusedWith(asUser(scoped, "POST", draftsPath, draft ++ located), 400, "cannot share an api key")
        env.datastores.draftsDataStore.findById(idOf(body)).futureValue mustBe None
        // as any other refusal of the draft of a subscription
        val unknownPlan = body ++ Json.obj("plan_ref" -> "no-such-plan", "token_refs" -> Json.arr())
        refusedWith(
          otoroshiApiCall("POST", draftsPath, Some(draft ++ Json.obj("content" -> unknownPlan))).futureValue,
          400,
          "plan not found"
        )
      }
      aliasesOf(k.clientId) mustBe empty
      env.datastores.draftsDataStore.delete(apiB.id).futureValue

      // the credential of the owner in the tenant of the user, in a team it does not write: not shared either, even
      // when the subscription does not name it
      val k2 = credentialOf(owner, EntityLocation(tenant, Seq(TeamId("alias-other-team"))), minted = true)
      refusedWith(
        asUser(scoped, "POST", subscriptionsPath, subscriptionBody(apiB, "plan_b", owner, Seq(ownerShared)) ++ located),
        400,
        "you can't grant rights you don't have yourself"
      )
      aliasesOf(k2.clientId) mustBe empty

      // once it is located where the user writes, it shares it, named or not
      key(k2.clientId).get.copy(location = EntityLocation(tenant, Seq(team))).save().futureValue
      synced(k2.clientId)(_.exists(_.location.teams.contains(team)))
      val (subB, statusB) =
        asUser(scoped, "POST", subscriptionsPath, subscriptionBody(apiB, "plan_b", owner, Seq(ownerShared)) ++ located)
      withClue(subB)(statusB mustBe 201)
      sharedOf(subB).clientId mustBe k2.clientId
      val (subA, statusA) =
        asUser(scoped, "POST", subscriptionsPath, subscriptionBody(apiA, "plan_a", owner, Seq(shared(k2.clientId))) ++ located)
      withClue(subA)(statusA mustBe 201)

      // an owner without credential in the tenant of the subscription gets one there, whatever the other tenants have
      val lone            = newOwner()
      val elsewhere       = credentialOf(lone, minted = true)
      val (subL, statusL) =
        asUser(scoped, "POST", subscriptionsPath, subscriptionBody(apiA, "plan_a", lone, Seq(ownerShared)) ++ located)
      withClue(subL)(statusL mustBe 201)
      val k3              = sharedOf(subL)
      k3.clientId must not be elsewhere.clientId
      k3.location mustBe EntityLocation(tenant, Seq(team))
      k3.sharedCredential mustBe Some(SharedCredential(lone, minted = true))

      Seq(subL, subA, subB).foreach(sub => unsubscribe(idOf(sub)) mustBe 200)
      dropKeys(k.clientId, k2.clientId, k3.clientId, elsewhere.clientId)
    } finally undeploy(apiA, apiB)
  }

  // what is written by hand through the api keys: an alias only by the users who can write its credential, and it
  // serves nothing, and a shared credential is created as one, attached to nothing, and stays one
  def aliasesWrittenByHand(): Unit = {
    val owner = newOwner()
    val apiA  = apiWith(plan("plan_a"))
    val apiB  = apiWith(plan("plan_b"))
    deploy(apiA, apiB)
    try {
      val k = credentialOf(owner)
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

      // nor does it refuse or hide anything: a subscription still shares the credential on that api, and its own alias
      // serves
      val (subB, statusB) = subscribe(apiB, "plan_b", owner, Seq(shared(k.clientId)))
      withClue(subB)(statusB mustBe 201)
      val kb              = ownKeyOf(subscription(idOf(subB)))
      ready(k, apiB, subB, kb)
      call(apiB, k.clientId, k.clientSecret) mustBe 200
      // an alias written by hand naming that subscription is not the one it minted either
      ApiSubscription.mintedAlias(stored(subB), kb) mustBe true
      ApiSubscription.mintedAlias(
        stored(subB),
        byAdmin.copy(apiRef = Some(ApiRef(apiB.id, "plan_b", idOf(subB), Some(k.clientId))))
      ) mustBe false

      def put(apikey: ApiKey): (JsValue, Int) =
        otoroshiApiCall("PUT", s"$apikeysPath/${apikey.clientId}", Some(apikey.toJson)).futureValue

      // the credential of an alias never changes
      refusedWith(
        put(key(byAdmin.clientId).get.copy(apiRef = byAdmin.apiRef.map(_.copy(credential = Some("another-key"))))),
        400,
        "the credential of an alias key cannot change"
      )
      key(byAdmin.clientId).get.apiRef.flatMap(_.credential) mustBe Some(k.clientId)

      // a shared credential stays one, for its owner, attached to nothing
      refusedWith(put(key(k.clientId).get.copy(sharedCredential = None)), 400, "stays one")
      refusedWith(put(key(k.clientId).get.copy(sharedCredential = Some(SharedCredential(newOwner())))), 400, "stays one")
      refusedWith(
        put(key(k.clientId).get.copy(authorizedEntities = Seq(ApiIdentifier(apiA.id)))),
        400,
        "authorized on nothing"
      )
      put(key(k.clientId).get.copy(description = "still a credential"))._2 mustBe 200
      key(k.clientId).get.sharedCredential mustBe Some(SharedCredential(owner))
      // and no other api key becomes one
      refusedWith(put(key(kb.clientId).get.copy(sharedCredential = Some(SharedCredential(owner)))), 400, "stays one")

      // it is created as one through the api keys, attached to nothing
      val viaApi = ApiKey(
        clientId = IdGenerator.lowerCaseToken(16),
        clientSecret = IdGenerator.lowerCaseToken(64),
        clientName = "credential",
        authorizedEntities = Seq.empty,
        sharedCredential = Some(SharedCredential(newOwner()))
      )
      refusedWith(
        otoroshiApiCall("POST", apikeysPath, Some(viaApi.copy(authorizedEntities = Seq(ApiIdentifier(apiA.id))).toJson)).futureValue,
        400,
        "authorized on nothing"
      )
      val (posted, postedStatus) = otoroshiApiCall("POST", apikeysPath, Some(viaApi.toJson)).futureValue
      withClue(posted)(postedStatus mustBe 201)
      posted.select("sharedCredential").select("owner").asOpt[String] mustBe viaApi.sharedCredential.map(_.owner)
      key(viaApi.clientId).get.sharedCredential mustBe viaApi.sharedCredential

      // an api key that is not a shared credential calls through no alias, even one a super admin wrote for it
      val plain      = ApiKey(
        clientId = IdGenerator.lowerCaseToken(16),
        clientSecret = IdGenerator.lowerCaseToken(64),
        clientName = "plain",
        authorizedEntities = Seq.empty
      )
      plain.save().futureValue
      val plainAlias = byAdmin.copy(
        clientId = s"forged-${IdGenerator.lowerCaseToken(8)}",
        apiRef = Some(ApiRef(apiB.id, "plan_b", idOf(subB), Some(plain.clientId)))
      )
      plainAlias.save().futureValue
      waitFor("the alias index")(env.proxyState.apikeyAlias(plain.clientId, apiB.id).contains(plainAlias.clientId))
      call(apiB, plain.clientId, plain.clientSecret) must not be 200

      // the credential deleted, its aliases go, written by hand or not
      otoroshiApiCall("DELETE", s"$apikeysPath/${k.clientId}").futureValue._2 mustBe 200
      key(byAdmin.clientId) mustBe None
      key(kb.clientId) mustBe None
      unsubscribe(idOf(subB)) mustBe 200
      dropKeys(viaApi.clientId, plain.clientId)
      key(plainAlias.clientId) mustBe None
    } finally undeploy(apiA, apiB)
  }

  // a credential deleted takes its aliases with it: nobody calls them again with a key created under its client id, a
  // secret of its own
  def deletedCredentialTakesItsAliases(): Unit = {
    val apiB = apiWith(plan("plan_b"))
    deploy(apiB)
    try {
      def sharedPair(): (ApiKey, JsValue, ApiKey) = {
        val k         = credentialOf(newOwner())
        val (subB, _) = subscribe(apiB, "plan_b", k.sharedCredential.get.owner, Seq(shared(k.clientId)))
        val kb        = ownKeyOf(subscription(idOf(subB)))
        ready(k, apiB, subB, kb)
        call(apiB, k.clientId, k.clientSecret) mustBe 200
        (k, subB, kb)
      }

      // through the generic api
      val (k, subB, kb) = sharedPair()
      otoroshiApiCall("DELETE", s"$apikeysPath/${k.clientId}").futureValue._2 mustBe 200
      key(k.clientId) mustBe None
      key(kb.clientId) mustBe None
      synced(k.clientId)(_.isEmpty)
      waitFor("the alias index forgets the credential")(!env.proxyState.hasApikeyAliases(k.clientId))

      // a user of another tenant takes the client id, as a shared credential of the owner: it calls nothing
      val takeover          = ApiKey(
        clientId = k.clientId,
        clientSecret = "the-secret-of-the-attacker",
        clientName = "takeover",
        authorizedEntities = Seq.empty,
        sharedCredential = k.sharedCredential,
        location = EntityLocation(tenant, Seq(team))
      )
      val (created, status) = asUser(scoped, "POST", apikeysPath, takeover.toJson)
      withClue(created)(status mustBe 201)
      synced(k.clientId)(_.exists(_.clientSecret == "the-secret-of-the-attacker"))
      call(apiB, k.clientId, "the-secret-of-the-attacker") must not be 200
      dropKeys(k.clientId)
      unsubscribe(idOf(subB)) mustBe 200

      // through the typed api
      val (k2, subB2, kb2) = sharedPair()
      otoroshiApiCall("DELETE", s"/api/apikeys/${k2.clientId}").futureValue._2 mustBe 200
      key(k2.clientId) mustBe None
      key(kb2.clientId) mustBe None
      unsubscribe(idOf(subB2)) mustBe 200

      // an alias that outlived its credential, removed without its aliases: only a super admin creates a key under
      // its client id (a restoration, whatever the order of the keys)
      val (k3, subB3, kb3) = sharedPair()
      env.datastores.rawDataStore.del(Seq(env.datastores.apiKeyDataStore.key(k3.clientId))).futureValue
      synced(k3.clientId)(_.isEmpty)
      env.proxyState.hasApikeyAliases(k3.clientId) mustBe true
      val restoring        = takeover.copy(clientId = k3.clientId, clientSecret = "restored", sharedCredential = k3.sharedCredential)
      refusedWith(asUser(scoped, "POST", apikeysPath, restoring.toJson), 400, "you can't grant rights you don't have yourself")
      key(k3.clientId) mustBe None
      val (restored, restoredStatus) = asUser(superAdmin, "POST", apikeysPath, restoring.toJson)
      withClue(restored)(restoredStatus mustBe 201)
      synced(k3.clientId)(_.exists(_.clientSecret == "restored"))
      call(apiB, k3.clientId, "restored") mustBe 200
      dropKeys(k3.clientId)
      key(kb3.clientId) mustBe None
      unsubscribe(idOf(subB3))
    } finally undeploy(apiB)
  }

  // the index of the aliases is built with the api keys of the proxy state
  def aliasesIndex(): Unit = {
    val k  = ApiKey(clientId = "k", clientSecret = "s", clientName = "k", authorizedEntities = Seq.empty)
    val kb = k.copy(clientId = "kb", apiRef = Some(ApiRef("api-b", "plan-b", "sub-b", Some("k"))))
    val kc = k.copy(clientId = "kc", apiRef = Some(ApiRef("api-c", "plan-c", "sub-c", Some("k"))))
    val ka = k.copy(clientId = "ka", apiRef = Some(ApiRef("api-a", "plan-a", "sub-a")))
    otoroshi.next.proxy.NgProxyState.apikeyAliases(Seq(k, ka, kb, kc)) mustBe Map(
      "k" -> Map("api-b" -> Seq("kb"), "api-c" -> Seq("kc"))
    )
    // a second alias of a credential on an api, written by hand, does not replace the first one
    otoroshi.next.proxy.NgProxyState.apikeyAliases(Seq(kb, kb.copy(clientId = "kb2"))) mustBe Map(
      "k" -> Map("api-b" -> Seq("kb", "kb2"))
    )
    otoroshi.next.proxy.NgProxyState.apikeyAliases(Seq(k, ka)) mustBe Map.empty
    // the credential is written only when there is one, the json of the other keys is unchanged
    ka.apiRef.get.json mustBe Json.obj("api" -> "api-a", "plan" -> "plan-a", "sub" -> "sub-a")
    ApiRef.format.reads(kb.apiRef.get.json).get mustBe kb.apiRef.get
  }

  // the consumers of an oauth2-local plan exchange their api key for a token (the client credentials flow) and call
  // with it: the shared credential is the one exchanged, and its token calls every api the credential is shared on,
  // through the alias of each, with the quotas of its own plan
  def oauth2Local(): Unit = {
    import otoroshi.next.plugins.{NgClientCredentialTokenEndpoint, NgClientCredentialTokenEndpointConfig}
    import otoroshi.next.plugins.api.NgPluginHelper

    val owner         = newOwner()
    val apiA          = apiWith(plan("plan_oauth", kind = "oauth2-local", quota = Some(2)))
    val apiB          = apiWith(plan("plan_b", quota = Some(3)))
    val tokenEndpoint = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[NgClientCredentialTokenEndpoint],
          config = NgPluginInstanceConfig(NgClientCredentialTokenEndpointConfig.default.json.as[JsObject])
        )
      )
    ).futureValue
    deploy(apiA, apiB)

    // the client credentials flow: the status of the token endpoint, and the token it gave
    def token(clientId: String, secret: String): (Int, Option[String]) = {
      val response = ws
        .url(s"http://127.0.0.1:$port/")
        .withHttpHeaders(
          "Host"         -> tokenEndpoint.frontend.domains.head.domain,
          "Content-Type" -> "application/x-www-form-urlencoded"
        )
        .post(s"grant_type=client_credentials&client_id=$clientId&client_secret=$secret")
        .futureValue
      (response.status, Try(response.json.select("access_token").asOpt[String]).toOption.flatten)
    }

    def callWith(api: Api, accessToken: String): Int = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> api.domain, "Authorization" -> s"Bearer $accessToken")
      .get()
      .futureValue
      .status

    try {
      // an oauth2-local subscription shares the credential of its owner like an apikey one, and creates it
      val (subA, statusA) = subscribe(apiA, "plan_oauth", owner, Seq(ownerShared), kind = "oauth2-local")
      withClue(subA)(statusA mustBe 201)
      val k               = sharedOf(subA)
      k.sharedCredential mustBe Some(SharedCredential(owner, minted = true))
      val ka              = ownKeyOf(subscription(idOf(subA)))
      ka.apiRef mustBe Some(ApiRef(apiA.id, "plan_oauth", idOf(subA), Some(k.clientId)))
      ka.authorizedEntities mustBe Seq(ApiIdentifier(apiA.id))
      // an apikey subscription of the same owner shares the same credential
      val (subB, statusB) = subscribe(apiB, "plan_b", owner, Seq(ownerShared))
      withClue(subB)(statusB mustBe 201)
      sharedOf(subB).clientId mustBe k.clientId
      val kb              = ownKeyOf(subscription(idOf(subB)))
      ready(k, apiA, subA, ka)
      ready(k, apiB, subB, kb)

      // the credential is exchanged for a token, that names it
      val (tokenStatus, maybeToken) = token(k.clientId, k.clientSecret)
      tokenStatus mustBe 200
      val accessToken               = maybeToken.get
      JWT.decode(accessToken).getClaim("cid").asString() mustBe k.clientId
      // a wrong secret gets none
      token(k.clientId, "wrong-secret") mustBe (401, None)

      // the token calls both apis, each on the counter of its own plan, the one the secret of the credential counts on
      callWith(apiA, accessToken) mustBe 200
      call(apiA, k.clientId, k.clientSecret) mustBe 200
      callWith(apiA, accessToken) mustBe 429
      callWith(apiB, accessToken) mustBe 200
      callWith(apiB, accessToken) mustBe 200

      // an alias has no secret of its own: it is exchanged for nothing
      Seq(ka, kb).foreach(alias => token(alias.clientId, alias.clientSecret) mustBe (401, None))

      // suspended, the oauth2-local subscription is not called anymore, the other one still is
      update(idOf(subA), Json.obj("status" -> "disabled"))._2 mustBe 200
      synced(ka.clientId)(_.exists(!_.enabled))
      callWith(apiA, accessToken) mustBe 401
      callWith(apiB, accessToken) mustBe 200

      // a disabled credential is exchanged for nothing, and the tokens it was given call nothing
      k.copy(enabled = false).save().futureValue mustBe true
      synced(k.clientId)(_.exists(!_.enabled))
      token(k.clientId, k.clientSecret) mustBe (401, None)
      callWith(apiB, accessToken) mustBe 401

      Seq(subB, subA).foreach(sub => unsubscribe(idOf(sub)) mustBe 200)
      dropKeys(k.clientId)
    } finally {
      undeploy(apiA, apiB)
      deleteOtoroshiRoute(tokenEndpoint).futureValue
    }
  }

  // the subscriptions of an owner created at once share one credential: the first one to reserve it creates it
  def subscriptionsCreatedAtOnce(): Unit = {
    val owner = newOwner()
    val apis  = (1 to 5).map(_ => apiWith(plan("plan_x")))
    deploy(apis*)
    try {
      // started together, then awaited
      val calls   = apis.map(api =>
        otoroshiApiCall("POST", subscriptionsPath, Some(subscriptionBody(api, "plan_x", owner, Seq(ownerShared))))
      )
      val results = calls.map(_.futureValue)
      results.foreach { case (body, status) => withClue(body)(status mustBe 201) }
      val subs    = results.map(_._1)
      subs.map(sub => sharedOf(sub).clientId).distinct must have size 1
      val k       = sharedOf(subs.head)
      ApiSubscription
        .ownerCredentials(env.datastores.apiKeyDataStore.findAll(force = true).futureValue, stored(subs.head))
        .map(_.clientId) mustBe Seq(k.clientId)
      // each of them calls its api with that credential
      apis.zip(subs).foreach { case (api, sub) =>
        ready(k, api, sub, ownKeyOf(subscription(idOf(sub))))
        call(api, k.clientId, k.clientSecret) mustBe 200
      }
      subs.foreach(sub => unsubscribe(idOf(sub)) mustBe 200)
      dropKeys(k.clientId)
    } finally {
      undeploy(apis*)
    }
  }

  // the owner_ref is free text: a shared credential written by hand for an owner, by someone who knows its secret, is
  // not the one the subscriptions of that owner share without naming it. Only the one a subscription minted is, and
  // nobody writes a minted one by hand
  def plantedCredential(): Unit = {
    val owner = newOwner()
    val apiA  = apiWith(plan("plan_a"))
    val apiB  = apiWith(plan("plan_b"))
    deploy(apiA, apiB)
    try {
      // a user writes, where it can, a shared credential for an owner
      val planted = ApiKey(
        clientId = IdGenerator.lowerCaseToken(16),
        clientSecret = IdGenerator.lowerCaseToken(64),
        clientName = s"planted credential of $owner",
        authorizedEntities = Seq.empty,
        sharedCredential = Some(SharedCredential(owner)),
        location = EntityLocation(tenant, Seq(team))
      )
      val (created, createdStatus) = asUser(scoped, "POST", apikeysPath, planted.toJson.as[JsObject] ++ located)
      withClue(created)(createdStatus mustBe 201)
      synced(planted.clientId)(_.isDefined)

      // but not as the one a subscription minted, nor does its own become one
      val forged = planted.copy(
        clientId = IdGenerator.lowerCaseToken(16),
        sharedCredential = Some(SharedCredential(owner, minted = true))
      )
      refusedWith(
        asUser(scoped, "POST", apikeysPath, forged.toJson.as[JsObject] ++ located),
        400,
        "you can't grant rights you don't have yourself"
      )
      key(forged.clientId) mustBe None
      asUser(
        scoped,
        "PUT",
        s"$apikeysPath/${planted.clientId}",
        forged.copy(clientId = planted.clientId).toJson.as[JsObject] ++ located
      )._2 mustBe 400
      key(planted.clientId).get.sharedCredential mustBe Some(SharedCredential(owner))

      // the first subscription of the owner, whoever writes it, does not take the planted credential: it mints its own
      val (subA, statusA) = subscribe(apiA, "plan_a", owner, Seq(ownerShared))
      withClue(subA)(statusA mustBe 201)
      val k               = sharedOf(subA)
      k.clientId must not be planted.clientId
      k.sharedCredential mustBe Some(SharedCredential(owner, minted = true))
      // and the next ones share that one
      val (subB, statusB) = subscribe(apiB, "plan_b", owner, Seq(ownerShared))
      withClue(subB)(statusB mustBe 201)
      sharedOf(subB).clientId mustBe k.clientId
      aliasesOf(planted.clientId) mustBe empty
      ready(k, apiA, subA, ownKeyOf(subscription(idOf(subA))))
      ready(k, apiB, subB, ownKeyOf(subscription(idOf(subB))))
      Seq(apiA, apiB).foreach { api =>
        call(api, k.clientId, k.clientSecret) mustBe 200
        call(api, planted.clientId, planted.clientSecret) must not be 200
      }

      Seq(subB, subA).foreach(sub => unsubscribe(idOf(sub)) mustBe 200)
      dropKeys(k.clientId, planted.clientId)
    } finally undeploy(apiA, apiB)
  }

  // a credential written a moment ago, that the proxy state does not have yet, is shared by the ones who can write it:
  // its rights are read from the datastore
  def credentialJustCreated(): Unit = {
    val owner = newOwner()
    val apiA  = apiWith(plan("plan_a"))
    deploy(apiA)
    try {
      def credential(location: EntityLocation) = ApiKey(
        clientId = IdGenerator.lowerCaseToken(16),
        clientSecret = IdGenerator.lowerCaseToken(64),
        clientName = s"credential of $owner",
        authorizedEntities = Seq.empty,
        sharedCredential = Some(SharedCredential(owner)),
        location = location
      )
      // one the user cannot write, saved a moment ago: still refused
      val other = credential(EntityLocation(tenant, Seq(TeamId("alias-other-team"))))
      other.save().futureValue mustBe true
      refusedWith(
        asUser(scoped, "POST", subscriptionsPath, subscriptionBody(apiA, "plan_a", owner, Seq(shared(other.clientId))) ++ located),
        400,
        "you can't grant rights you don't have yourself"
      )
      // its own, saved a moment ago: shared at once
      val own   = credential(EntityLocation(tenant, Seq(team)))
      own.save().futureValue mustBe true
      val (sub, status) =
        asUser(scoped, "POST", subscriptionsPath, subscriptionBody(apiA, "plan_a", owner, Seq(shared(own.clientId))) ++ located)
      withClue(sub)(status mustBe 201)
      sharedOf(sub).clientId mustBe own.clientId

      unsubscribe(idOf(sub)) mustBe 200
      dropKeys(own.clientId, other.clientId)
    } finally undeploy(apiA)
  }

  // a credential deleted by hand leaves the subscriptions that shared it: the ones who could write them still delete
  // them, the credential they cannot be checked against is gone
  def subscriptionOfDeletedCredential(): Unit = {
    val owner = newOwner()
    val apiA  = apiWith(plan("plan_a"))
    deploy(apiA)
    try {
      val own = ApiKey(
        clientId = IdGenerator.lowerCaseToken(16),
        clientSecret = IdGenerator.lowerCaseToken(64),
        clientName = s"credential of $owner",
        authorizedEntities = Seq.empty,
        sharedCredential = Some(SharedCredential(owner)),
        location = EntityLocation(tenant, Seq(team))
      )
      own.save().futureValue mustBe true
      val (sub, status) =
        asUser(scoped, "POST", subscriptionsPath, subscriptionBody(apiA, "plan_a", owner, Seq(shared(own.clientId))) ++ located)
      withClue(sub)(status mustBe 201)
      val path = s"$subscriptionsPath/${idOf(sub)}"
      // while the credential is there, its author rewrites the subscription
      waitFor("the credential in the proxy state")(env.proxyState.apikey(own.clientId).isDefined)
      val (rewritten, rewrittenStatus) = asUser(scoped, "PUT", path, subscription(idOf(sub)))
      withClue(rewritten)(rewrittenStatus mustBe 200)

      dropKeys(own.clientId)
      waitFor("the credential out of the proxy state")(env.proxyState.apikey(own.clientId).isEmpty)
      aliasesOf(own.clientId) mustBe empty

      // its alias went with the credential: there is no key left to rebuild, and its author still rewrites the
      // subscription, which mints no other alias
      val (again, againStatus) = asUser(scoped, "PUT", path, subscription(idOf(sub)) ++ Json.obj("name" -> "renamed"))
      withClue(again)(againStatus mustBe 200)
      subscription(idOf(sub)).select("name").asString mustBe "renamed"
      aliasesOf(own.clientId) mustBe empty
      val (deleted, deletedStatus) = asUser(scoped, "DELETE", path, JsNull)
      withClue(deleted)(deletedStatus mustBe 200)
    } finally undeploy(apiA)
  }

  // an alias key is only called through its credential: the extractors reading the datastore, which do not resolve the
  // credential (the legacy ones: secret, jwt signed with the secret, client id alone), find no key for it
  def aliasesAndLegacyExtractors(): Unit = {
    val owner = newOwner()
    val apiA  = apiWith(plan("plan_a"))
    val apiB  = apiWith(plan("plan_b"))
    deploy(apiA, apiB)
    try {
      val k          = credentialOf(owner, minted = true)
      val (subA, sa) = subscribe(apiA, "plan_a", owner, Seq(shared(k.clientId)))
      withClue(subA)(sa mustBe 201)
      val (subB, sb) = subscribe(apiB, "plan_b", owner)
      withClue(subB)(sb mustBe 201)
      val alias      = ownKeyOf(subscription(idOf(subA)))
      val plain      = ownKeyOf(subscription(idOf(subB)))
      alias.isAlias mustBe true
      env.datastores.apiKeyDataStore.findAuthorizeKeyFor(alias.clientId, apiA.id).futureValue mustBe None
      env.datastores.apiKeyDataStore.findAuthorizeKeyFor(plain.clientId, apiB.id).futureValue.map(_.clientId) mustBe Some(
        plain.clientId
      )
      Seq(subA, subB).foreach(sub => unsubscribe(idOf(sub)) mustBe 200)
      dropKeys(k.clientId)
    } finally undeploy(apiA, apiB)
  }

  // two subscriptions sharing a credential on one api, created at once, both find no alias there: one of them only
  // mints its alias, and the api is subscribed again as soon as that subscription is deleted
  def sameApiAtOnce(): Unit = {
    val owner = newOwner()
    val apiA  = apiWith(plan("plan_a"), plan("plan_b"))
    deploy(apiA)
    try {
      val k       = credentialOf(owner, minted = true)
      // started together, then awaited
      val calls   = Seq("plan_a", "plan_b", "plan_a", "plan_b").map(planId =>
        otoroshiApiCall("POST", subscriptionsPath, Some(subscriptionBody(apiA, planId, owner, Seq(shared(k.clientId)))))
      )
      val results = calls.map(_.futureValue)
      val created = results.filter(_._2 == 201)
      withClue(results)(created must have size 1)
      results.filterNot(_._2 == 201).foreach { case (body, status) =>
        withClue(body)(status mustBe 400)
        body.select("error").asString must (include("already shared") or include("is being shared"))
      }
      aliasesOf(k.clientId).map(_.clientId) mustBe Seq(ownKeyOf(subscription(idOf(created.head._1))).clientId)

      unsubscribe(idOf(created.head._1)) mustBe 200
      val (again, againStatus) = subscribe(apiA, "plan_b", owner, Seq(shared(k.clientId)))
      withClue(again)(againStatus mustBe 201)
      unsubscribe(idOf(again)) mustBe 200
      dropKeys(k.clientId)
    } finally undeploy(apiA)
  }
}
