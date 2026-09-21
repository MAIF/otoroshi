package plugins

import functional.PluginsTestSpecBase
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

// an api subscription only gets what its plan hands over: its kind has to be the kind of its plan,
// and a subscription to an apikey or an oauth2-local plan is given an apikey, built from the plan
// whatever the kind of the plan is now.
class ApiSubscriptionKindTests(parent: PluginsTestSpecBase) {
  import parent.*

  private val apisPath          = "/apis/apis.otoroshi.io/v1/apis"
  private val subscriptionsPath = "/apis/apis.otoroshi.io/v1/apisubscriptions"

  private def plan(id: String, kind: String, conf: JsObject, extra: JsObject = Json.obj()): ApiPlan = ApiPlan(
    Json.obj(
      "id"                             -> id,
      "name"                           -> id,
      "status"                         -> "published",
      "access_mode_configuration_type" -> kind,
      "access_mode_configuration"      -> conf
    ) ++ extra
  )

  private val keylessPlan     = plan("keyless", "keyless", Json.obj("expr" -> "${req.ip}"))
  private val jwtPlan         = plan("jwt", "jwt", Json.obj("verifier" -> "none"))
  private val apikeyPlan      = plan("apikey", "apikey", Json.obj("description" -> "minted by the apikey plan"))
  private val oauth2LocalPlan = plan(
    "oauth2-local",
    "oauth2-local",
    Json.obj("default_key_pair" -> "otoroshi-jwt-signing", "expiration" -> 3600),
    Json.obj("tags" -> Json.arr("oauth2-local-plan"))
  )

  private val api = Api(
    location = EntityLocation.default,
    id = s"api_${IdGenerator.uuid}",
    name = "subscription kinds",
    description = "",
    domain = "subscription-kinds.oto.tools",
    contextPath = "/v1",
    version = "0.0.1",
    debugFlow = false,
    capture = false,
    exportReporting = false,
    groups = Seq.empty,
    state = ApiStaging,
    blueprint = ApiBlueprint.REST,
    testing = ApiTesting(),
    plans = Seq(keylessPlan, jwtPlan, apikeyPlan, oauth2LocalPlan)
  )

  private val template: JsObject =
    otoroshiApiCall("GET", s"$subscriptionsPath/_template").futureValue._1.as[JsObject]

  private def subscribe(plan: ApiPlan, kind: String): (JsValue, Int) =
    otoroshiApiCall(
      "POST",
      subscriptionsPath,
      Some(
        template ++ Json.obj(
          "id"                -> s"api-subscription_${IdGenerator.uuid}",
          "api_ref"           -> api.id,
          "plan_ref"          -> plan.id,
          "subscription_kind" -> kind
        )
      )
    ).futureValue

  private def subscription(id: String): JsValue =
    otoroshiApiCall("GET", s"$subscriptionsPath/$id").futureValue._1

  private def apikeyOf(subscription: JsValue): otoroshi.models.ApiKey = {
    val clientIds = subscription.select("token_refs").as[Seq[JsValue]].flatMap(_.select("apikey").asOpt[String])
    clientIds must have size 1
    env.datastores.apiKeyDataStore.findById(clientIds.head).futureValue.get
  }

  otoroshiApiCall("POST", apisPath, Some(api.json)).futureValue._2 mustBe 201

  // a subscription whose kind is not the kind of its plan is refused before anything is minted
  Seq(keylessPlan, jwtPlan, oauth2LocalPlan).foreach { p =>
    val (body, status) = subscribe(p, "apikey")
    status mustBe 400
    body.select("error").asString must include("does not match")
  }

  // a keyless subscription has nothing to mint: its consumers are built at call time
  val (keyless, keylessStatus) = subscribe(keylessPlan, "keyless")
  keylessStatus mustBe 201
  subscription(keyless.select("id").asString).select("token_refs").as[Seq[JsValue]] mustBe empty

  // an apikey subscription gets an apikey carrying the settings of the plan
  val (apikeySub, apikeyStatus) = subscribe(apikeyPlan, "apikey")
  apikeyStatus mustBe 201
  val apikeySubId               = apikeySub.select("id").asString
  val minted                    = apikeyOf(subscription(apikeySubId))
  minted.description mustBe "minted by the apikey plan"
  minted.apiRef.map(_.plan) mustBe Some(apikeyPlan.id)

  // the consumers of an oauth2-local plan call with an apikey too, so its subscriptions get one
  val (oauth2Sub, oauth2Status) = subscribe(oauth2LocalPlan, "oauth2-local")
  oauth2Status mustBe 201
  val oauth2Apikey              = apikeyOf(subscription(oauth2Sub.select("id").asString))
  oauth2Apikey.apiRef.map(_.plan) mustBe Some(oauth2LocalPlan.id)
  oauth2Apikey.tags must contain("oauth2-local-plan")

  // the apikey plan becomes a keyless one: its apikey subscription still has to be manageable
  val switched = api.copy(plans = Seq(keylessPlan, jwtPlan, plan("apikey", "keyless", Json.obj()), oauth2LocalPlan))
  otoroshiApiCall("PUT", s"$apisPath/${api.id}", Some(switched.json)).futureValue._2 mustBe 200
  otoroshiApiCall(
    "PUT",
    s"$subscriptionsPath/$apikeySubId",
    Some(subscription(apikeySubId).as[JsObject] ++ Json.obj("name" -> "renamed"))
  ).futureValue._2 mustBe 200
  subscription(apikeySubId).select("name").asString mustBe "renamed"
  apikeyOf(subscription(apikeySubId)).clientId mustBe minted.clientId

  Seq(minted.clientId, oauth2Apikey.clientId).foreach(id => otoroshiApiCall("DELETE", s"/api/apikeys/$id").futureValue)
  Seq(keyless, apikeySub, oauth2Sub).foreach { sub =>
    otoroshiApiCall("DELETE", s"$subscriptionsPath/${sub.select("id").asString}").futureValue
  }
  otoroshiApiCall("DELETE", s"$apisPath/${api.id}").futureValue
}
