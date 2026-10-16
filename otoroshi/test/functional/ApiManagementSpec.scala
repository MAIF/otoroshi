package functional

import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.*
import play.api.libs.ws.DefaultBodyWritables.*
import play.api.libs.ws.{readableAsString, WSAuthScheme, WSResponse}

// the operations of the management of the apis: the api keys of an api, its plans and subscriptions, its routes
class ApiManagementSpec extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration = configuration

  private val apis = "/apis/apis.otoroshi.io/v1/apis"

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
    "shutdown" in {
      stopAll()
    }
  }
}
