package plugins

import functional.PluginsTestSpecBase
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

// removing a plan from an api deletes the subscriptions of that plan, and only them: the other plans of the
// api keep theirs, and so does another api giving the same id to one of its plans
class ApiPlanRemovalTests(parent: PluginsTestSpecBase) {
  import parent.*

  private val apisPath          = "/apis/apis.otoroshi.io/v1/apis"
  private val subscriptionsPath = "/apis/apis.otoroshi.io/v1/apisubscriptions"

  private def plan(id: String): ApiPlan = ApiPlan(
    Json.obj(
      "id"                             -> id,
      "name"                           -> id,
      "status"                         -> "published",
      "access_mode_configuration_type" -> "apikey",
      "access_mode_configuration"      -> Json.obj()
    )
  )

  private def api(plans: Seq[ApiPlan]): Api = {
    val id = s"api_${IdGenerator.uuid}"
    Api(
      location = EntityLocation.default,
      id = id,
      name = id,
      description = "",
      domain = s"$id.oto.tools",
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
  }

  private val template: JsObject =
    otoroshiApiCall("GET", s"$subscriptionsPath/_template").futureValue._1.as[JsObject]

  private def subscribe(api: Api, planId: String): String = {
    val id = s"api-subscription_${IdGenerator.uuid}"
    otoroshiApiCall(
      "POST",
      subscriptionsPath,
      Some(
        template ++ Json.obj(
          "id"                -> id,
          "api_ref"           -> api.id,
          "plan_ref"          -> planId,
          "subscription_kind" -> "apikey"
        )
      )
    ).futureValue._2 mustBe 201
    id
  }

  private def exists(subscriptionId: String): Boolean =
    otoroshiApiCall("GET", s"$subscriptionsPath/$subscriptionId").futureValue._2 == 200

  private val apiA = api(Seq(plan("gold"), plan("silver")))
  private val apiB = api(Seq(plan("gold")))
  Seq(apiA, apiB).foreach(a => otoroshiApiCall("POST", apisPath, Some(a.json)).futureValue._2 mustBe 201)

  private val goldOfA   = subscribe(apiA, "gold")
  private val silverOfA = subscribe(apiA, "silver")
  private val goldOfB   = subscribe(apiB, "gold")

  otoroshiApiCall("PUT", s"$apisPath/${apiA.id}", Some(apiA.copy(plans = Seq(plan("silver"))).json))
    .futureValue
    ._2 mustBe 200
  exists(goldOfA) mustBe false
  exists(silverOfA) mustBe true
  exists(goldOfB) mustBe true

  env.datastores.apiKeyDataStore
    .findAll()
    .futureValue
    .filter(_.apiRef.exists(ref => Seq(goldOfA, silverOfA, goldOfB).contains(ref.subscription)))
    .foreach(key => otoroshiApiCall("DELETE", s"/api/apikeys/${key.clientId}").futureValue)
  Seq(silverOfA, goldOfB).foreach(id => otoroshiApiCall("DELETE", s"$subscriptionsPath/$id").futureValue)
  Seq(apiA, apiB).foreach(a => otoroshiApiCall("DELETE", s"$apisPath/${a.id}").futureValue)
}
