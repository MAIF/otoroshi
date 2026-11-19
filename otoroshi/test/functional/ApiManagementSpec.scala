package functional

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{Actor, Props}
import otoroshi.env.Env
import otoroshi.events.ApiDeploymentEvent
import otoroshi.models.{
  ApiIdentifier,
  ApiKey,
  EntityLocation,
  GlobalJwtVerifier,
  HSAlgoSettings,
  InHeader,
  PassThrough,
  VerificationSettings
}
import otoroshi.next.models.*
import otoroshi.next.plugins.{AllowedQuota, ApikeyFromPlan, FixedWindowStrategyConfig}
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.*
import play.api.libs.ws.DefaultBodyWritables.*
import play.api.libs.ws.{readableAsString, WSAuthScheme, WSResponse}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

// the operations of the management of the apis: the api keys of an api, its plans and subscriptions, its routes
class ApiManagementSpec extends OtoroshiSpec {

  // the routes of an api and its api keys reach the router through the proxy state
  override def getTestConfiguration(configuration: Configuration): Configuration =
    Configuration(
      ConfigFactory
        .parseString(s"""
          |otoroshi.next.state-sync-interval = 5
          |otoroshi.apis.deployments-history-size = 10
          |""".stripMargin)
        .resolve()
    ).withFallback(configuration)

  private val apis          = "/apis/apis.otoroshi.io/v1/apis"
  private val subscriptions = "/apis/apis.otoroshi.io/v1/apisubscriptions"

  private def call(method: String, path: String, body: Option[JsValue] = None): WSResponse = {
    val req = ws
      .url(s"http://127.0.0.1:$port$path")
      .withHttpHeaders("Host" -> "otoroshi-api.oto.tools", "Accept" -> "application/json")
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .withMethod(method)
    body
      .map(b => req.addHttpHeaders("Content-Type" -> "application/json").withBody(b.stringify))
      .getOrElse(req)
      .execute()
      .futureValue
  }

  private def ok(resp: WSResponse, status: Int = 200): JsValue = {
    withClue(resp.body[String])(resp.status mustBe status)
    if (resp.body[String].trim.isEmpty) JsNull else resp.json
  }

  // an apikey plan, with the same id in every api
  private val plan = Json.obj(
    "id"                             -> "plan-x",
    "name"                           -> "plan x",
    "status"                         -> "published",
    "access_mode_configuration_type" -> "apikey",
    "access_mode_configuration"      -> Json.obj("clientNamePattern" -> "${api.name} - ${plan.name}", "enabled" -> true)
  )

  private def createApi(id: String, state: String): Unit = {
    val template = ok(call("GET", s"$apis/_template")).as[JsObject]
    ok(call("POST", apis, Some(template ++ Json.obj("id" -> id, "name" -> id, "state" -> state, "plans" -> Json.arr(plan)))), 201)
  }

  // a subscription without status
  private def subscribe(api: String, sub: String): Unit = {
    val template = ok(call("GET", "/apis/apis.otoroshi.io/v1/apisubscriptions/_template")).as[JsObject] - "status"
    val body     =
      template ++ Json.obj("id" -> sub, "name" -> sub, "api_ref" -> api, "plan_ref" -> "plan-x", "subscription_kind" -> "apikey")
    ok(call("POST", s"$apis/$api/plans/plan-x/subscribe", Some(body)))
  }

  private def keyOf(sub: String): String =
    (ok(call("GET", s"/apis/apis.otoroshi.io/v1/apisubscriptions/$sub")) \ "token_refs" \ 0 \ "apikey").as[String]

  // a published api with a backend, so that it can be called, holding the apikey plan and an oauth2-local plan
  private val callable           = "api-mgmt-callable"
  private val callableDomain     = "api-mgmt-callable.oto.tools"
  private lazy val backendServer = TargetService(None, "/", "application/json", _ => """{"ok":true}""")

  private val oauth2LocalPlan = Json.obj(
    "id"                             -> "plan-oauth2",
    "name"                           -> "plan oauth2",
    "status"                         -> "published",
    "access_mode_configuration_type" -> "oauth2-local",
    "access_mode_configuration"      -> Json.obj("default_key_pair" -> "otoroshi-jwt-signing", "expiration" -> 3600)
  )

  private def waitUntil(what: String)(cond: => Boolean): Unit = {
    val deadline = System.currentTimeMillis() + 15000
    while (!cond && System.currentTimeMillis() < deadline) await(100.millis)
    withClue(what)(cond mustBe true)
  }

  private def subscriptionTemplate: JsObject = ok(call("GET", s"$subscriptions/_template")).as[JsObject]

  private def subscriptionBody(sub: String, status: String, plan: String, kind: String, api: String): JsObject =
    subscriptionTemplate ++ Json.obj(
      "id"                -> sub,
      "name"              -> sub,
      "api_ref"           -> api,
      "plan_ref"          -> plan,
      "owner_ref"         -> "client-1",
      "subscription_kind" -> kind,
      "status"            -> status
    )

  // a subscription to a plan of the callable api, created through the admin api
  private def createSubscription(
      sub: String,
      status: String,
      plan: String = "plan-x",
      kind: String = "apikey",
      api: String = callable
  ): Unit =
    ok(call("POST", subscriptions, Some(subscriptionBody(sub, status, plan, kind, api))), 201)

  private def statusOf(sub: String): String = (ok(call("GET", s"$subscriptions/$sub")) \ "status").as[String]

  private def setStatus(sub: String, status: String): Unit = {
    val current = ok(call("GET", s"$subscriptions/$sub")).as[JsObject]
    ok(call("PUT", s"$subscriptions/$sub", Some(current ++ Json.obj("status" -> status))))
  }

  private def keyEnabled(sub: String): Boolean =
    (ok(call("GET", s"/apis/apim.otoroshi.io/v1/apikeys/${keyOf(sub)}")) \ "enabled").as[Boolean]

  private def storedKeyOf(sub: String): ApiKey = {
    given env: Env = otoroshiComponents.env
    env.datastores.apiKeyDataStore.findById(keyOf(sub)).futureValue.get
  }

  private def callWith(key: ApiKey): Int =
    ws.url(s"http://127.0.0.1:$port/")
      .withHttpHeaders(
        "Host"                   -> callableDomain,
        "Otoroshi-Client-Id"     -> key.clientId,
        "Otoroshi-Client-Secret" -> key.clientSecret
      )
      .get()
      .futureValue
      .status

  // a call to the callable api with the api key of the subscription, once the router sees the key as it is stored:
  // a key the router does not know yet would be refused too
  private def callWithKeyOf(sub: String): Int = {
    given env: Env = otoroshiComponents.env
    val stored     = storedKeyOf(sub)
    waitUntil(s"the router sees the api key ${stored.clientId} enabled = ${stored.enabled}") {
      env.proxyState.apikey(stored.clientId).map(_.enabled).contains(stored.enabled)
    }
    callWith(stored)
  }

  // a call with the credentials of a deleted api key, once the router does not see it anymore
  private def callWithDeleted(key: ApiKey): Int = {
    given env: Env = otoroshiComponents.env
    waitUntil(s"the router does not see the api key ${key.clientId} anymore") {
      env.proxyState.apikey(key.clientId).isEmpty
    }
    callWith(key)
  }

  private def apikeyStatus(clientId: String): Int = call("GET", s"/apis/apim.otoroshi.io/v1/apikeys/$clientId").status

  // a published api with a jwt plan reading the client_id claim of the tokens of one verifier
  private val jwtApi       = "api-mgmt-jwt"
  private val jwtDomain    = "api-mgmt-jwt.oto.tools"
  private val jwtSecret    = "api-mgmt-jwt-secret"
  private val jwtVerifier  = "api-mgmt-jwt-verifier"
  private val jwtKeyPrefix = "jwt_api-mgmt-jwt_plan-jwt_"

  private def jwtStrategy(quota: Long): FixedWindowStrategyConfig =
    FixedWindowStrategyConfig(windowDurationMs = 60000L, quota = AllowedQuota(quota, quota * 10, quota * 100))

  private def jwtPlan(quota: Long): JsObject = Json.obj(
    "id"                             -> "plan-jwt",
    "name"                           -> "plan jwt",
    "status"                         -> "published",
    "access_mode_configuration_type" -> "jwt",
    "access_mode_configuration"      -> Json.obj(
      "verifier"          -> jwtVerifier,
      "client_id_path"    -> "client_id",
      "create_if_missing" -> true
    ),
    "rateLimiting"                   -> Json.obj("strategy" -> jwtStrategy(quota).json)
  )

  private def callJwtApi(clientId: String): Int =
    ws.url(s"http://127.0.0.1:$port/")
      .withHttpHeaders(
        "Host"          -> jwtDomain,
        "Authorization" -> s"Bearer ${JWT.create().withClaim("client_id", clientId).sign(Algorithm.HMAC512(jwtSecret))}"
      )
      .get()
      .futureValue
      .status

  private def storedApikey(clientId: String): Option[ApiKey] = {
    given env: Env = otoroshiComponents.env
    env.datastores.apiKeyDataStore.findById(clientId).futureValue
  }

  // a call with the token of a client id, once the router sees its api key as it is stored
  private def callJwtApiAsStored(clientId: String): Int = {
    given env: Env = otoroshiComponents.env
    val stored     = storedApikey(s"$jwtKeyPrefix$clientId")
    waitUntil(s"the router sees the api key of $clientId as it is stored") {
      env.proxyState.apikey(s"$jwtKeyPrefix$clientId").map(_.enabled) == stored.map(_.enabled)
    }
    callJwtApi(clientId)
  }

  private def jwtSubscription(sub: String, status: String, clientId: Option[String]): JsObject =
    subscriptionBody(sub, status, "plan-jwt", "jwt", jwtApi) ++
    clientId.map(id => Json.obj("client_id" -> id)).getOrElse(Json.obj())

  private def consumerMetadataOf(key: ApiKey): Map[String, String] =
    key.metadata.view.filterKeys(Set("api_id", "plan_id", "access_mode", "plan_consumer")).toMap

  private val jwtConsumerMetadata =
    Map("api_id" -> jwtApi, "plan_id" -> "plan-jwt", "access_mode" -> "jwt", "plan_consumer" -> "true")

  // two published apis with plans of the same ids: a jwt plan on the verifier of the jwt api, and a keyless plan
  private val partnerA = "api-mgmt-partner-a"
  private val partnerB = "api-mgmt-partner-b"

  private def partnerDomain(api: String): String = s"$api.oto.tools"

  private def partnerApi(id: String, quota: Long): Api = {
    given env: Env = otoroshiComponents.env
    val apiBackend = ApiBackend(
      id = s"backend-$id",
      name = "backend",
      backend = NgBackend.empty.copy(
        targets = Seq(NgTarget(id = "target_1", hostname = "127.0.0.1", port = backendServer.port, tls = false))
      ),
      client = "default_backend_client"
    )
    val flow       = ApiFlows.empty
    val strategy   = Json.obj("strategy" -> jwtStrategy(quota).json)
    Api(
      location = EntityLocation.default,
      id = id,
      name = id,
      description = "",
      domain = partnerDomain(id),
      contextPath = "",
      version = "0.0.1",
      debugFlow = false,
      capture = false,
      exportReporting = false,
      groups = Seq.empty,
      state = ApiPublished,
      blueprint = ApiBlueprint.REST,
      testing = ApiTesting(),
      backends = Seq(apiBackend),
      flows = Seq(flow),
      plans = Seq(
        ApiPlan(jwtPlan(quota) ++ Json.obj("id" -> "plan_partner", "name" -> "partner")),
        ApiPlan(
          Json.obj(
            "id"                             -> "plan_free",
            "name"                           -> "free",
            "status"                         -> "published",
            "access_mode_configuration_type" -> "keyless",
            "access_mode_configuration"      -> Json.obj("expr" -> "${req.ip}", "create_if_missing" -> true),
            "rateLimiting"                   -> strategy
          )
        )
      ),
      routes = Seq(
        ApiRoute(
          id = s"route-$id",
          frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(partnerDomain(id)))),
          flowRef = flow.id,
          backend = apiBackend.id
        )
      )
    )
  }

  // a call to a partner api, with the token of a client id or without any credential
  private def callPartner(api: String, clientId: Option[String]): Int =
    ws.url(s"http://127.0.0.1:$port/")
      .withHttpHeaders(
        Seq("Host" -> partnerDomain(api)) ++ clientId.map(id =>
          "Authorization" -> s"Bearer ${JWT.create().withClaim("client_id", id).sign(Algorithm.HMAC512(jwtSecret))}"
        )*
      )
      .get()
      .futureValue
      .status

  "The management of the apis" should {
    "warm up" in {
      startOtoroshi()
      // a new api is created in staging, which subscriptions accept
      createApi("api-mgmt-a", "staging")
      createApi("api-mgmt-b", "staging")
      subscribe("api-mgmt-a", "sub-a")
      subscribe("api-mgmt-b", "sub-b")
    }
    "list the api keys of an api" in {
      val keys = ok(call("GET", s"$apis/api-mgmt-a/apikeys")).as[Seq[JsObject]].map(k => (k \ "clientId").as[String])
      keys must contain(keyOf("sub-a"))
      keys must not contain keyOf("sub-b")
    }
    "read the quotas of the api keys of an api" in {
      ok(call("GET", s"$apis/api-mgmt-a/apikeys/${keyOf("sub-a")}/quotas"))
    }
    "only preview the subscriptions of the plan of the path in the api of the path" in {
      val subs = ok(call("POST", s"$apis/api-mgmt-a/plans/plan-x/subscriptions/preview-update", Some(Json.obj())))
      subs.as[Seq[JsObject]].map(s => (s \ "id").as[String]) mustBe Seq("sub-a")
    }
    "enable the api key of a subscription it confirms" in {
      val key = keyOf("sub-a")
      (ok(call("GET", s"/apis/apim.otoroshi.io/v1/apikeys/$key")) \ "enabled").as[Boolean] mustBe false
      ok(call("POST", s"$apis/api-mgmt-a/subscriptions/sub-a/confirm", Some(Json.obj())), 204)
      (ok(call("GET", s"/apis/apis.otoroshi.io/v1/apisubscriptions/sub-a")) \ "status").as[String] mustBe "enabled"
      (ok(call("GET", s"/apis/apim.otoroshi.io/v1/apikeys/$key")) \ "enabled").as[Boolean] mustBe true
    }
    "close a plan, deleting its subscriptions and their api keys, in its api only" in {
      val key = keyOf("sub-a")
      ok(call("POST", s"$apis/api-mgmt-a/plans/plan-x/close", Some(Json.obj())))
      val plans = (ok(call("GET", s"$apis/api-mgmt-a")) \ "plans").as[Seq[JsObject]]
      plans.map(p => ((p \ "id").as[String], (p \ "status").as[String])) mustBe Seq(("plan-x", "closed"))
      call("GET", s"/apis/apis.otoroshi.io/v1/apisubscriptions/sub-a").status mustBe 404
      call("GET", s"/apis/apim.otoroshi.io/v1/apikeys/$key").status mustBe 404
      ok(call("GET", s"/apis/apis.otoroshi.io/v1/apisubscriptions/sub-b"))
    }
    "keep the state of an api it stops" in {
      createApi("api-mgmt-staging", "staging")
      ok(call("POST", s"$apis/api-mgmt-staging/_stop"))
      (ok(call("GET", s"$apis/api-mgmt-staging")) \ "state").as[String] mustBe "staging"
    }
    "answer not found for a route an api does not hold" in {
      call("GET", s"$apis/api-mgmt-a/routes/route-that-does-not-exist").status mustBe 404
    }
    "answer not found, with a body, for an api that does not exist" in {
      val resp = call("POST", s"$apis/api-that-does-not-exist/_start")
      withClue(resp.body[String]) {
        resp.status mustBe 404
        (resp.json \ "error").asOpt[String] must not be empty
      }
    }
    "check the access to the api in the template of its http client" in {
      call("GET", s"$apis/api-that-does-not-exist/http-client-settings/_template").status mustBe 404
      ok(call("GET", s"$apis/api-mgmt-a/http-client-settings/_template"))
    }
    "set up a published api that can be called" in {
      given env: Env = otoroshiComponents.env
      backendServer.await()
      val apiBackend = ApiBackend(
        id = "backend-callable",
        name = "backend",
        backend = NgBackend.empty.copy(
          targets = Seq(NgTarget(id = "target_1", hostname = "127.0.0.1", port = backendServer.port, tls = false))
        ),
        client = "default_backend_client"
      )
      val flow       = ApiFlows.empty
      val api        = Api(
        location = EntityLocation.default,
        id = callable,
        name = callable,
        description = "",
        domain = callableDomain,
        contextPath = "",
        version = "0.0.1",
        debugFlow = false,
        capture = false,
        exportReporting = false,
        groups = Seq.empty,
        state = ApiPublished,
        blueprint = ApiBlueprint.REST,
        testing = ApiTesting(),
        backends = Seq(apiBackend),
        flows = Seq(flow),
        plans = Seq(ApiPlan(plan), ApiPlan(oauth2LocalPlan)),
        routes = Seq(
          ApiRoute(
            id = "route-callable",
            frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(callableDomain))),
            flowRef = flow.id,
            backend = apiBackend.id
          )
        )
      )
      env.datastores.apiDataStore.set(api).futureValue mustBe true
      waitUntil("the route of the api reaches the router") {
        env.proxyState.allRoutes().exists(_.apiRef.exists(_.id == callable))
      }
    }
    "disable the api key of a subscription that is pending, deprecated or in a custom status" in {
      Seq("pending", "under_review", "deprecated").foreach { status =>
        val sub = s"sub-created-$status"
        createSubscription(sub, status)
        withClue(status) {
          keyEnabled(sub) mustBe false
          callWithKeyOf(sub) mustBe 401
        }
      }
    }
    "enable the api key of a subscription created enabled" in {
      createSubscription("sub-created-enabled", "enabled")
      keyEnabled("sub-created-enabled") mustBe true
      callWithKeyOf("sub-created-enabled") mustBe 200
    }
    "make the api key of a subscription follow its status" in {
      createSubscription("sub-follow", "pending")
      keyEnabled("sub-follow") mustBe false
      callWithKeyOf("sub-follow") mustBe 401
      setStatus("sub-follow", "enabled")
      keyEnabled("sub-follow") mustBe true
      callWithKeyOf("sub-follow") mustBe 200
      setStatus("sub-follow", "under_review")
      keyEnabled("sub-follow") mustBe false
      callWithKeyOf("sub-follow") mustBe 401
      setStatus("sub-follow", "disabled")
      keyEnabled("sub-follow") mustBe false
      callWithKeyOf("sub-follow") mustBe 401
    }
    "subscribe pending, with a disabled api key whatever the body says, until the subscription is confirmed" in {
      val body = subscriptionTemplate ++ Json.obj(
        "id"                -> "sub-subscribed-enabled",
        "name"              -> "sub-subscribed-enabled",
        "api_ref"           -> callable,
        "plan_ref"          -> "plan-x",
        "owner_ref"         -> "client-1",
        "subscription_kind" -> "apikey",
        "status"            -> "enabled"
      )
      ok(call("POST", s"$apis/$callable/plans/plan-x/subscribe", Some(body)))
      statusOf("sub-subscribed-enabled") mustBe "pending"
      keyEnabled("sub-subscribed-enabled") mustBe false
      callWithKeyOf("sub-subscribed-enabled") mustBe 401
      ok(call("POST", s"$apis/$callable/subscriptions/sub-subscribed-enabled/confirm", Some(Json.obj())), 204)
      statusOf("sub-subscribed-enabled") mustBe "enabled"
      keyEnabled("sub-subscribed-enabled") mustBe true
      callWithKeyOf("sub-subscribed-enabled") mustBe 200
    }
    "disable the api key of a pending subscription to an oauth2-local plan" in {
      createSubscription("sub-oauth2-pending", "pending", plan = "plan-oauth2", kind = "oauth2-local")
      keyEnabled("sub-oauth2-pending") mustBe false
    }
    "disable once the enabled api keys of the stored subscriptions that are not enabled" in {
      given env: Env = otoroshiComponents.env
      // what an older version left: subscriptions holding an enabled api key whatever their status
      def stored(sub: String, status: String): (ApiSubscription, ApiKey) = {
        val key          = ApiKey(
          clientId = s"$sub-key",
          clientSecret = "secret",
          clientName = s"$sub-key",
          authorizedEntities = Seq(ApiIdentifier(callable)),
          enabled = true
        )
        val subscription = ApiSubscription.format
          .reads(
            subscriptionTemplate ++ Json.obj(
              "id"                -> sub,
              "name"              -> sub,
              "api_ref"           -> callable,
              "plan_ref"          -> "plan-x",
              "owner_ref"         -> "client-1",
              "subscription_kind" -> "apikey",
              "status"            -> status,
              "token_refs"        -> Json.arr(Json.obj("apikey" -> key.clientId))
            )
          )
          .get
        key.save().futureValue mustBe true
        subscription.save().futureValue mustBe true
        (subscription, env.datastores.apiKeyDataStore.findById(key.clientId).futureValue.get)
      }
      def keyOfStore(key: ApiKey): ApiKey = env.datastores.apiKeyDataStore.findById(key.clientId).futureValue.get
      def subOfStore(sub: ApiSubscription): JsValue =
        env.datastores.apiSubscriptionDataStore.findById(sub.id).futureValue.get.json

      val (pendingSub, pendingKey) = stored("sub-migrated-pending", "pending")
      val (enabledSub, enabledKey) = stored("sub-migrated-enabled", "enabled")
      val store                    = env.datastores.apiSubscriptionDataStore
      // the migration runs at startup, before these subscriptions were stored
      waitUntil("the migration ran at startup") {
        env.datastores.rawDataStore.get(store.keysFollowStatusMigrationKey).futureValue.nonEmpty
      }
      env.datastores.rawDataStore.del(Seq(store.keysFollowStatusMigrationKey)).futureValue

      store.migrate().futureValue
      keyOfStore(pendingKey).json mustBe pendingKey.copy(enabled = false).json
      keyOfStore(enabledKey).json mustBe enabledKey.json
      subOfStore(pendingSub) mustBe pendingSub.json
      subOfStore(enabledSub) mustBe enabledSub.json
      env.datastores.rawDataStore.get(store.keysFollowStatusMigrationKey).futureValue must not be empty

      // recorded: it does not run again, a key enabled since then stays enabled
      pendingKey.save().futureValue mustBe true
      store.migrate().futureValue
      keyOfStore(pendingKey).json mustBe pendingKey.json
      subOfStore(pendingSub) mustBe pendingSub.json

      Seq(pendingKey, enabledKey).foreach(key => env.datastores.apiKeyDataStore.delete(key.clientId).futureValue)
      Seq(pendingSub, enabledSub).foreach(sub => store.delete(sub.id).futureValue)
    }
    "delete the api key of a subscription deleted by id" in {
      createSubscription("sub-deleted", "enabled")
      callWithKeyOf("sub-deleted") mustBe 200
      val key = storedKeyOf("sub-deleted")
      ok(call("DELETE", s"$subscriptions/sub-deleted"))
      apikeyStatus(key.clientId) mustBe 404
      callWithDeleted(key) mustBe 401
    }
    "delete the api keys of the subscriptions deleted in bulk" in {
      createSubscription("sub-bulk-1", "enabled")
      createSubscription("sub-bulk-2", "enabled")
      val keys = Seq(storedKeyOf("sub-bulk-1"), storedKeyOf("sub-bulk-2"))
      callWithKeyOf("sub-bulk-1") mustBe 200
      val resp = ws
        .url(s"http://127.0.0.1:$port$subscriptions/_bulk")
        .withHttpHeaders(
          "Host"         -> "otoroshi-api.oto.tools",
          "Accept"       -> "application/x-ndjson",
          "Content-Type" -> "application/x-ndjson"
        )
        .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
        .withMethod("DELETE")
        .withBody(Seq("sub-bulk-1", "sub-bulk-2").map(id => Json.obj("id" -> id).stringify).mkString("\n"))
        .execute()
        .futureValue
      withClue(resp.body[String]) {
        resp.status mustBe 200
        resp.body[String].split("\n").filter(_.trim.nonEmpty).map(l => (Json.parse(l) \ "deleted").as[Boolean]).toSeq mustBe
        Seq(true, true)
      }
      call("GET", s"$subscriptions/sub-bulk-1").status mustBe 404
      keys.foreach(key => apikeyStatus(key.clientId) mustBe 404)
      callWithDeleted(keys.head) mustBe 401
    }
    "delete the api key of a deleted subscription to an oauth2-local plan" in {
      createSubscription("sub-oauth2-deleted", "enabled", plan = "plan-oauth2", kind = "oauth2-local")
      val key = keyOf("sub-oauth2-deleted")
      apikeyStatus(key) mustBe 200
      ok(call("DELETE", s"$subscriptions/sub-oauth2-deleted"))
      apikeyStatus(key) mustBe 404
    }
    "keep the api key another subscription owns when a subscription pointing at it is deleted" in {
      given env: Env = otoroshiComponents.env
      createSubscription("sub-owner", "enabled")
      val owned = keyOf("sub-owner")
      // a stale reference, written straight to the datastore: an update through the admin api would rewrite the key
      val stale = ApiSubscription.format
        .reads(
          subscriptionTemplate ++ Json.obj(
            "id"                -> "sub-stale",
            "name"              -> "sub-stale",
            "api_ref"           -> callable,
            "plan_ref"          -> "plan-x",
            "owner_ref"         -> "client-1",
            "subscription_kind" -> "apikey",
            "status"            -> "enabled",
            "token_refs"        -> Json.arr(Json.obj("apikey" -> owned))
          )
        )
        .get
      stale.save().futureValue mustBe true
      ok(call("DELETE", s"$subscriptions/sub-stale"))
      call("GET", s"$subscriptions/sub-stale").status mustBe 404
      val kept = ok(call("GET", s"/apis/apim.otoroshi.io/v1/apikeys/$owned"))
      (kept \ "enabled").as[Boolean] mustBe true
      (kept \ "apiRef" \ "sub").as[String] mustBe "sub-owner"
      callWithKeyOf("sub-owner") mustBe 200
    }
    "delete the subscriptions of a plan removed from an api, and their api keys, and only them" in {
      val template = ok(call("GET", s"$apis/_template")).as[JsObject]
      val planY    = plan ++ Json.obj("id" -> "plan-y", "name" -> "plan y")
      ok(
        call(
          "POST",
          apis,
          Some(
            template ++ Json.obj(
              "id"    -> "api-mgmt-plans",
              "name"  -> "api-mgmt-plans",
              "state" -> "staging",
              "plans" -> Json.arr(plan, planY)
            )
          )
        ),
        201
      )
      def subscribeTo(planId: String, sub: String): String = {
        val body = subscriptionTemplate ++ Json.obj(
          "id"                -> sub,
          "name"              -> sub,
          "api_ref"           -> "api-mgmt-plans",
          "plan_ref"          -> planId,
          "owner_ref"         -> "client-1",
          "subscription_kind" -> "apikey",
          "status"            -> "enabled"
        )
        ok(call("POST", subscriptions, Some(body)), 201)
        keyOf(sub)
      }
      val keptKey    = subscribeTo("plan-x", "sub-plans-x")
      val removedKey = subscribeTo("plan-y", "sub-plans-y")
      val api        = ok(call("GET", s"$apis/api-mgmt-plans")).as[JsObject]
      ok(call("PUT", s"$apis/api-mgmt-plans", Some(api ++ Json.obj("plans" -> Json.arr(plan)))))
      call("GET", s"$subscriptions/sub-plans-y").status mustBe 404
      apikeyStatus(removedKey) mustBe 404
      ok(call("GET", s"$subscriptions/sub-plans-x"))
      (ok(call("GET", s"/apis/apim.otoroshi.io/v1/apikeys/$keptKey")) \ "enabled").as[Boolean] mustBe true
    }
    "refuse new subscriptions to a deprecated plan, and keep managing the existing ones" in {
      createApi("api-mgmt-deprecated", "staging")
      createSubscription("sub-before-deprecation", "enabled", api = "api-mgmt-deprecated")
      val api   = ok(call("GET", s"$apis/api-mgmt-deprecated")).as[JsObject]
      val plans = (api \ "plans").as[Seq[JsObject]].map { p =>
        if ((p \ "id").as[String] == "plan-x") p ++ Json.obj("status" -> "deprecated") else p
      }
      ok(call("PUT", s"$apis/api-mgmt-deprecated", Some(api ++ Json.obj("plans" -> plans))))

      val created = call(
        "POST",
        subscriptions,
        Some(subscriptionBody("sub-after-deprecation", "enabled", "plan-x", "apikey", "api-mgmt-deprecated"))
      )
      withClue(created.body[String]) {
        created.status mustBe 400
        (created.json \ "error").as[String] mustBe "wrong status plan"
      }
      call("GET", s"$subscriptions/sub-after-deprecation").status mustBe 404

      val subscribed = call(
        "POST",
        s"$apis/api-mgmt-deprecated/plans/plan-x/subscribe",
        Some(subscriptionBody("sub-subscribed-deprecated", "enabled", "plan-x", "apikey", "api-mgmt-deprecated"))
      )
      withClue(subscribed.body[String]) {
        subscribed.status mustBe 400
        (subscribed.json \ "error").as[String] mustBe "plan is not published"
      }
      call("GET", s"$subscriptions/sub-subscribed-deprecated").status mustBe 404

      // the subscriptions made before keep being managed, their api keys with them
      val current = ok(call("GET", s"$subscriptions/sub-before-deprecation")).as[JsObject]
      ok(
        call(
          "PUT",
          s"$subscriptions/sub-before-deprecation",
          Some(current ++ Json.obj("metadata" -> Json.obj("contract" -> "c-42")))
        )
      )
      val key = ok(call("GET", s"/apis/apim.otoroshi.io/v1/apikeys/${keyOf("sub-before-deprecation")}"))
      (key \ "metadata" \ "contract").as[String] mustBe "c-42"
      (key \ "enabled").as[Boolean] mustBe true
    }
    "set up a published api with a jwt plan" in {
      given env: Env = otoroshiComponents.env
      val verifier   = GlobalJwtVerifier(
        id = jwtVerifier,
        name = jwtVerifier,
        desc = "the verifier of the jwt plan",
        strict = true,
        source = InHeader(name = "Authorization", remove = "Bearer "),
        algoSettings = HSAlgoSettings(512, jwtSecret),
        strategy = PassThrough(verificationSettings = VerificationSettings())
      )
      env.datastores.globalJwtVerifierDataStore.set(verifier).futureValue mustBe true
      val apiBackend = ApiBackend(
        id = "backend-jwt",
        name = "backend",
        backend = NgBackend.empty.copy(
          targets = Seq(NgTarget(id = "target_1", hostname = "127.0.0.1", port = backendServer.port, tls = false))
        ),
        client = "default_backend_client"
      )
      val flow       = ApiFlows.empty
      val api        = Api(
        location = EntityLocation.default,
        id = jwtApi,
        name = jwtApi,
        description = "",
        domain = jwtDomain,
        contextPath = "",
        version = "0.0.1",
        debugFlow = false,
        capture = false,
        exportReporting = false,
        groups = Seq.empty,
        state = ApiPublished,
        blueprint = ApiBlueprint.REST,
        testing = ApiTesting(),
        backends = Seq(apiBackend),
        flows = Seq(flow),
        plans = Seq(ApiPlan(jwtPlan(100))),
        routes = Seq(
          ApiRoute(
            id = "route-jwt",
            frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(jwtDomain))),
            flowRef = flow.id,
            backend = apiBackend.id
          )
        )
      )
      env.datastores.apiDataStore.set(api).futureValue mustBe true
      waitUntil("the route of the jwt api and its verifier reach the router") {
        env.proxyState.allRoutes().exists(_.apiRef.exists(_.id == jwtApi)) &&
        env.proxyState.jwtVerifier(jwtVerifier).isDefined
      }
    }
    "provision the api key of the client_id of a subscription to a jwt plan" in {
      ok(call("POST", subscriptions, Some(jwtSubscription("sub-jwt-c1", "enabled", Some("c1")))), 201)
      val sub = ok(call("GET", s"$subscriptions/sub-jwt-c1"))
      (sub \ "client_id").as[String] mustBe "c1"
      (sub \ "token_refs").as[Seq[JsObject]] mustBe Seq(Json.obj("apikey" -> s"${jwtKeyPrefix}c1"))
      val key = storedApikey(s"${jwtKeyPrefix}c1").get
      key.enabled mustBe true
      key.apiRef mustBe Some(ApiRef(jwtApi, "plan-jwt", "sub-jwt-c1"))
      consumerMetadataOf(key) mustBe jwtConsumerMetadata
      callJwtApiAsStored("c1") mustBe 200
    }
    "cut the consumer of a jwt plan off while its subscription is not enabled" in {
      setStatus("sub-jwt-c1", "disabled")
      storedApikey(s"${jwtKeyPrefix}c1").map(_.enabled) mustBe Some(false)
      callJwtApiAsStored("c1") mustBe 401
      setStatus("sub-jwt-c1", "enabled")
      storedApikey(s"${jwtKeyPrefix}c1").map(_.enabled) mustBe Some(true)
      callJwtApiAsStored("c1") mustBe 200
    }
    "take over the api key a jwt plan minted on the fly before the subscription" in {
      // no subscription yet: the plan mints the key of the consumer at its first call
      callJwtApi("c2") mustBe 200
      waitUntil("the key minted on the fly is stored")(storedApikey(s"${jwtKeyPrefix}c2").isDefined)
      val minted = storedApikey(s"${jwtKeyPrefix}c2").get
      consumerMetadataOf(minted) mustBe jwtConsumerMetadata
      minted.metadata.get("created_by") mustBe Some("apikey-extractor")
      minted.apiRef mustBe Some(ApiRef(jwtApi, "plan-jwt", ""))

      ok(call("POST", subscriptions, Some(jwtSubscription("sub-jwt-c2", "enabled", Some("c2")))), 201)
      val taken = storedApikey(s"${jwtKeyPrefix}c2").get
      taken.clientId mustBe minted.clientId
      taken.clientSecret mustBe minted.clientSecret
      taken.apiRef mustBe Some(ApiRef(jwtApi, "plan-jwt", "sub-jwt-c2"))
      taken.enabled mustBe true
      consumerMetadataOf(taken) mustBe jwtConsumerMetadata
      callJwtApiAsStored("c2") mustBe 200
    }
    "refuse a second subscription of a jwt plan with the same client_id, and a change of client_id" in {
      val duplicate = call("POST", subscriptions, Some(jwtSubscription("sub-jwt-dup", "enabled", Some("c1"))))
      withClue(duplicate.body[String]) {
        duplicate.status mustBe 400
        (duplicate.json \ "error").as[String] mustBe "client_id 'c1' already subscribed to plan 'plan-jwt'"
      }
      call("GET", s"$subscriptions/sub-jwt-dup").status mustBe 404
      val current = ok(call("GET", s"$subscriptions/sub-jwt-c1")).as[JsObject]
      val changed = call("PUT", s"$subscriptions/sub-jwt-c1", Some(current ++ Json.obj("client_id" -> "c9")))
      withClue(changed.body[String]) {
        changed.status mustBe 400
        (changed.json \ "error").as[String] mustBe "client_id cannot change: delete the subscription and create another one"
      }
      (ok(call("GET", s"$subscriptions/sub-jwt-c1")) \ "client_id").as[String] mustBe "c1"
    }
    "reach the api key of a subscription to a jwt plan with a change of the quotas of the plan" in {
      val api = ok(call("GET", s"$apis/$jwtApi")).as[JsObject]
      ok(call("PUT", s"$apis/$jwtApi", Some(api ++ Json.obj("plans" -> Json.arr(jwtPlan(7))))))
      storedApikey(s"${jwtKeyPrefix}c1").flatMap(_.throttlingStrategy) mustBe Some(jwtStrategy(7))
      storedApikey(s"${jwtKeyPrefix}c1").map(_.enabled) mustBe Some(true)
    }
    "disable, not delete, the api key of a deleted subscription to a jwt plan" in {
      ok(call("DELETE", s"$subscriptions/sub-jwt-c1"))
      call("GET", s"$subscriptions/sub-jwt-c1").status mustBe 404
      val key = storedApikey(s"${jwtKeyPrefix}c1")
      key.map(_.enabled) mustBe Some(false)
      callJwtApiAsStored("c1") mustBe 401
    }
    "provision nothing for a subscription to a jwt plan without client_id" in {
      ok(call("POST", subscriptions, Some(jwtSubscription("sub-jwt-none", "enabled", None))), 201)
      (ok(call("GET", s"$subscriptions/sub-jwt-none")) \ "token_refs").as[Seq[JsValue]] mustBe empty
    }
    "set up two apis with plans of the same ids" in {
      given env: Env = otoroshiComponents.env
      env.datastores.apiDataStore.set(partnerApi(partnerA, 2)).futureValue mustBe true
      env.datastores.apiDataStore.set(partnerApi(partnerB, 100)).futureValue mustBe true
      waitUntil("the routes of the partner apis reach the router") {
        Seq(partnerA, partnerB).forall(id => env.proxyState.allRoutes().exists(_.apiRef.exists(_.id == id)))
      }
    }
    "not share the consumers of jwt plans of the same id between two apis" in {
      Seq(callPartner(partnerA, Some("c1")), callPartner(partnerA, Some("c1"))) mustBe Seq(200, 200)
      callPartner(partnerA, Some("c1")) mustBe 429
      callPartner(partnerB, Some("c1")) mustBe 200
      waitUntil("the keys minted for c1 are stored") {
        storedApikey(s"jwt_${partnerA}_plan_partner_c1").isDefined && storedApikey(s"jwt_${partnerB}_plan_partner_c1").isDefined
      }
      storedApikey(s"jwt_${partnerA}_plan_partner_c1").flatMap(_.apiRef).map(_.api) mustBe Some(partnerA)
      storedApikey(s"jwt_${partnerB}_plan_partner_c1").flatMap(_.apiRef).map(_.api) mustBe Some(partnerB)
      storedApikey("jwt_plan_partner_c1") mustBe None
    }
    "not share the consumers of keyless plans of the same id between two apis" in {
      Seq(callPartner(partnerA, None), callPartner(partnerA, None)) mustBe Seq(200, 200)
      callPartner(partnerA, None) mustBe 429
      callPartner(partnerB, None) mustBe 200
      waitUntil("the keys minted for the ip of the caller are stored") {
        storedApikey(s"keyless_${partnerA}_plan_free_127.0.0.1").isDefined &&
        storedApikey(s"keyless_${partnerB}_plan_free_127.0.0.1").isDefined
      }
    }
    // on the jwt api, whose only plan is the jwt one: on an api with a keyless plan, a call the jwt plan does not
    // identify is identified by the keyless plan, as it would be without any token
    "refuse a key of another api saved under the client id of a consumer" in {
      given env: Env = otoroshiComponents.env
      val elsewhere  = ApiKey(
        clientId = s"${jwtKeyPrefix}c7",
        clientSecret = "elsewhere-secret",
        clientName = "elsewhere",
        authorizedEntities = Seq(ApiIdentifier("api-mgmt-elsewhere")),
        apiRef = Some(ApiRef("api-mgmt-elsewhere", "plan-jwt", "")),
        enabled = true
      )
      elsewhere.save().futureValue mustBe true
      waitUntil("the router sees the key saved by hand")(env.proxyState.apikey(elsewhere.clientId).isDefined)
      val before     = storedApikey(elsewhere.clientId).map(_.json)
      callJwtApi("c7") mustBe 401
      storedApikey(elsewhere.clientId).map(_.json) mustBe before
      env.datastores.apiKeyDataStore.delete(elsewhere.clientId).futureValue
    }
    "rename once the keys minted before the api id was in their client id" in {
      given env: Env = otoroshiComponents.env
      val marker     = ApikeyFromPlan.consumerKeysMigrationKey
      waitUntil("the migration ran at startup")(env.datastores.rawDataStore.get(marker).futureValue.nonEmpty)
      // what an older version minted on the fly, disabled by hand since
      val minted     = ApiKey(
        clientId = "jwt_plan_partner_c3",
        clientSecret = "c3-secret",
        clientName = "c3",
        authorizedEntities = Seq(ApiIdentifier(partnerA)),
        apiRef = Some(ApiRef(partnerA, "plan_partner", "")),
        metadata = Map("created_by" -> "apikey-extractor"),
        enabled = false
      )
      // and what a subscription of an older version provisioned
      val provisioned = ApiKey(
        clientId = "jwt_plan_partner_c5",
        clientSecret = "c5-secret",
        clientName = "c5",
        authorizedEntities = Seq(ApiIdentifier(partnerA)),
        apiRef = Some(ApiRef(partnerA, "plan_partner", "sub-migrated-consumer")),
        metadata = Map("plan_consumer" -> "true"),
        enabled = true
      )
      val subscription = ApiSubscription.format
        .reads(
          jwtSubscription("sub-migrated-consumer", "enabled", Some("c5")) ++ Json.obj(
            "api_ref"    -> partnerA,
            "plan_ref"   -> "plan_partner",
            "token_refs" -> Json.arr(Json.obj("apikey" -> provisioned.clientId))
          )
        )
        .get
      // a key without apiRef is not a consumer of a plan
      val unrelated  = ApiKey(
        clientId = "jwt_plan_partner_c6",
        clientSecret = "c6-secret",
        clientName = "c6",
        authorizedEntities = Seq(ApiIdentifier(partnerA)),
        metadata = Map("created_by" -> "apikey-extractor")
      )
      Seq(minted, provisioned, unrelated).foreach(key => key.save().futureValue mustBe true)
      subscription.save().futureValue mustBe true
      val mintedBefore    = storedApikey(minted.clientId).get
      val unrelatedBefore = storedApikey(unrelated.clientId).get
      env.datastores.rawDataStore.del(Seq(marker)).futureValue

      ApikeyFromPlan.migrateConsumerKeys().futureValue
      storedApikey("jwt_plan_partner_c3") mustBe None
      storedApikey(s"jwt_${partnerA}_plan_partner_c3").map(_.json) mustBe Some(
        mintedBefore.copy(clientId = s"jwt_${partnerA}_plan_partner_c3").json
      )
      storedApikey(s"jwt_${partnerA}_plan_partner_c3").map(_.enabled) mustBe Some(false)
      storedApikey("jwt_plan_partner_c5") mustBe None
      storedApikey(s"jwt_${partnerA}_plan_partner_c5").map(_.clientSecret) mustBe Some("c5-secret")
      env.datastores.apiSubscriptionDataStore.findById("sub-migrated-consumer").futureValue.map(_.tokenRefs) mustBe Some(
        Seq(Json.obj("apikey" -> s"jwt_${partnerA}_plan_partner_c5"))
      )
      storedApikey("jwt_plan_partner_c6").map(_.json) mustBe Some(unrelatedBefore.json)
      env.datastores.rawDataStore.get(marker).futureValue must not be empty

      // recorded: a key of the old shape stored since is left as it is
      minted.save().futureValue mustBe true
      ApikeyFromPlan.migrateConsumerKeys().futureValue
      storedApikey("jwt_plan_partner_c3").isDefined mustBe true
      Seq(minted, unrelated).foreach(key => env.datastores.apiKeyDataStore.delete(key.clientId).futureValue)
    }
    "delete what was minted for an api with it, and only for it" in {
      ok(
        call(
          "POST",
          subscriptions,
          Some(
            jwtSubscription("sub-partner-c4", "enabled", Some("c4")) ++
            Json.obj("api_ref" -> partnerA, "plan_ref" -> "plan_partner")
          )
        ),
        201
      )
      storedApikey(s"jwt_${partnerA}_plan_partner_c4").map(_.enabled) mustBe Some(true)

      ok(call("DELETE", s"$apis/$partnerA"))
      call("GET", s"$apis/$partnerA").status mustBe 404
      Seq(
        s"jwt_${partnerA}_plan_partner_c1",
        s"jwt_${partnerA}_plan_partner_c3",
        s"jwt_${partnerA}_plan_partner_c4",
        s"jwt_${partnerA}_plan_partner_c5",
        s"keyless_${partnerA}_plan_free_127.0.0.1"
      ).foreach(id => withClue(id)(storedApikey(id) mustBe None))
      Seq("sub-partner-c4", "sub-migrated-consumer").foreach { id =>
        withClue(id)(call("GET", s"$subscriptions/$id").status mustBe 404)
      }
      // the keys of the other api, and a key that is not a consumer of the api, are untouched
      storedApikey(s"jwt_${partnerB}_plan_partner_c1").isDefined mustBe true
      storedApikey(s"keyless_${partnerB}_plan_free_127.0.0.1").isDefined mustBe true
    }
    "record the ref of the version deployed and the plans of the draft in a deployment" in {
      given env: Env = otoroshiComponents.env
      createApi("api-mgmt-deploy", "staging")
      val draft      = ok(call("GET", s"$apis/api-mgmt-deploy")).as[JsObject]
      ok(
        call(
          "POST",
          "/apis/proxy.otoroshi.io/v1/drafts",
          Some(
            Json.obj(
              "id"          -> "api-mgmt-deploy",
              "kind"        -> "apis",
              "name"        -> "api-mgmt-deploy",
              "description" -> "",
              "content"     -> draft
            )
          )
        ),
        201
      )
      val events    = new ConcurrentLinkedQueue[ApiDeploymentEvent]()
      val collector = env.analyticsActorSystem.actorOf(Props(new ApiManagementSpec.DeploymentEvents(events)))
      env.analyticsActorSystem.eventStream.subscribe(collector, classOf[ApiDeploymentEvent])
      def deploy(ref: Option[String]) = call(
        "POST",
        s"$apis/api-mgmt-deploy/deployments",
        Some(
          Json.obj("apiRef" -> "api-mgmt-deploy", "owner" -> "my-gitops-tool", "apiDefinition" -> Json.obj()) ++
          ref.map(r => Json.obj("ref" -> r)).getOrElse(Json.obj())
        )
      )
      try {
        val deployed = ok(deploy(Some("release-42")), 201)
        val head     = (deployed \ "deployments" \ 0).as[JsObject]
        (head \ "ref").as[String] mustBe "release-42"
        (head \ "apiDefinition" \ "plans").as[Seq[JsObject]].map(plan => (plan \ "id").as[String]) mustBe Seq("plan-x")
        (ok(call("GET", s"$apis/api-mgmt-deploy")) \ "deployments" \ 0 \ "ref").as[String] mustBe "release-42"
        waitUntil("the event of the deployment")(events.asScala.exists(_.ref.contains("release-42")))
        events.asScala.find(_.ref.contains("release-42")).map(_.deploymentId) mustBe Some((head \ "id").as[String])

        val withoutRef = ok(deploy(None), 201)
        (withoutRef \ "deployments" \ 0 \ "ref").toOption mustBe Some(JsNull)
        (withoutRef \ "deployments" \ 1 \ "ref").as[String] mustBe "release-42"

        val tooLong = deploy(Some("r" * 257))
        withClue(tooLong.body[String]) {
          tooLong.status mustBe 400
          (tooLong.json \ "error").as[String] mustBe "bad_request"
        }
      } finally {
        env.analyticsActorSystem.eventStream.unsubscribe(collector)
        env.analyticsActorSystem.stop(collector)
      }
    }
    "keep the last deployments-history-size deployments of an api" in {
      (1 to 10).foreach { i =>
        ok(
          call(
            "POST",
            s"$apis/api-mgmt-deploy/deployments",
            Some(
              Json.obj("apiRef" -> "api-mgmt-deploy", "owner" -> "me", "apiDefinition" -> Json.obj(), "ref" -> s"r$i")
            )
          ),
          201
        )
      }
      // 12 deployments, the setting of this spec keeps 10
      val deployments = (ok(call("GET", s"$apis/api-mgmt-deploy")) \ "deployments").as[Seq[JsObject]]
      deployments.size mustBe 10
      deployments.map(d => (d \ "ref").asOpt[String]) mustBe (10 to 1 by -1).map(i => Some(s"r$i"))
    }
    "shutdown" in {
      backendServer.stop()
      stopAll()
    }
  }
}

object ApiManagementSpec {
  // the deployment events sent by the deploys of the spec
  class DeploymentEvents(events: ConcurrentLinkedQueue[ApiDeploymentEvent]) extends Actor {
    override def receive: Receive = {
      case event: ApiDeploymentEvent => events.add(event)
      case _                         => ()
    }
  }
}
