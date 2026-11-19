package functional

import com.typesafe.config.ConfigFactory
import otoroshi.env.Env
import otoroshi.models.{ApiIdentifier, ApiKey, EntityLocation}
import otoroshi.next.models.*
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.*
import play.api.libs.ws.DefaultBodyWritables.*
import play.api.libs.ws.{readableAsString, WSAuthScheme, WSResponse}

import scala.concurrent.duration.*

// the operations of the management of the apis: the api keys of an api, its plans and subscriptions, its routes
class ApiManagementSpec extends OtoroshiSpec {

  // the routes of an api and its api keys reach the router through the proxy state
  override def getTestConfiguration(configuration: Configuration): Configuration =
    Configuration(
      ConfigFactory
        .parseString(s"""
          |otoroshi.next.state-sync-interval = 5
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

  // a subscription to a plan of the callable api, created through the admin api
  private def createSubscription(sub: String, status: String, plan: String = "plan-x", kind: String = "apikey"): Unit = {
    val body = subscriptionTemplate ++ Json.obj(
      "id"                -> sub,
      "name"              -> sub,
      "api_ref"           -> callable,
      "plan_ref"          -> plan,
      "owner_ref"         -> "client-1",
      "subscription_kind" -> kind,
      "status"            -> status
    )
    ok(call("POST", subscriptions, Some(body)), 201)
  }

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
    "shutdown" in {
      backendServer.stop()
      stopAll()
    }
  }
}
