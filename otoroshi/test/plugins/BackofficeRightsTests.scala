package plugins

import functional.PluginsTestSpecBase
import otoroshi.models.*
import play.api.http.Status
import play.api.libs.crypto.CookieSignerProvider
import play.api.libs.json.*
import play.api.libs.ws.WSBodyWritables.writeableOf_String
import play.api.libs.ws.WSResponse
import play.api.mvc.{DefaultSessionCookieBaker, Session}

import scala.concurrent.duration.DurationInt

// rights of a backoffice user restricted to one team: the backoffice endpoints must not let it act outside of it
class BackofficeRightsTests(parent: PluginsTestSpecBase) {
  import parent.*

  val user = BackOfficeUser(
    randomId = "backoffice-rights-user",
    name = "backoffice-rights-user",
    email = "backoffice-rights-user@otoroshi.io",
    profile = Json.obj(),
    authConfigId = "basic",
    simpleLogin = true,
    tags = Seq.empty,
    metadata = Map.empty,
    rights = UserRights(Seq(UserRight(TenantAccess("default"), Seq(TeamAccess("visible-team"))))),
    adminEntityValidators = Map()
  )
  user.save(5.minutes)(using env.otoroshiExecutionContext, env).futureValue

  val (created, createStatus) = otoroshiApiCall(
    "POST",
    "/api/routes",
    Some(
      Json.obj(
        "id"       -> "backoffice-rights-hidden-route",
        "name"     -> "backoffice-rights-hidden-route",
        "frontend" -> Json.obj("domains" -> Json.arr("backoffice-rights-hidden-route.oto.tools")),
        "backend"  -> Json.obj("targets" -> Json.arr(Json.obj("hostname" -> "127.0.0.1", "port" -> 9999, "tls" -> false))),
        "_loc"     -> Json.obj("tenant" -> "default", "teams" -> Json.arr("hidden-team"))
      )
    )
  ).futureValue
  withClue(s"create route: $created ") {
    createStatus mustBe 201
  }
  await(2.seconds) // the endpoints read the proxy state

  val sessionCookieBaker = new DefaultSessionCookieBaker(
    env.httpConfiguration.session,
    env.httpConfiguration.secret,
    new CookieSignerProvider(env.httpConfiguration.secret).get
  )

  def call(method: String, path: String, body: JsValue = Json.obj()): WSResponse = {
    val cookie = sessionCookieBaker.encodeAsCookie(Session(Map("bousr" -> user.randomId)))
    ws.url(s"http://127.0.0.1:$port$path")
      .withFollowRedirects(false)
      .withHttpHeaders(
        "Host"         -> env.backOfficeHost,
        "Content-Type" -> "application/json",
        "Cookie"       -> s"${cookie.name}=${cookie.value}"
      )
      .withMethod(method)
      .withBody(Json.stringify(body))
      .execute()
      .futureValue
  }

  def statusOf(label: String, response: WSResponse): Int = {
    withClue(s"$label: body=${response.body} ") {
      response.status
    }
  }

  // super admin only endpoints: the guarded block used to run before the rights were checked
  statusOf("panic mode", call("POST", "/bo/api/panic")) mustBe Status.FORBIDDEN
  withClue("panic mode must not have been applied ") {
    env.datastores.globalConfigDataStore.latest()(using env.otoroshiExecutionContext, env).apiReadOnly mustBe false
  }
  statusOf("anonymous reporting", call("POST", "/bo/api/_anonymous_reporting", Json.obj("enabled" -> true))) mustBe Status.FORBIDDEN

  // entities of a team the user cannot see
  statusOf("route entries", call("GET", "/bo/api/routes/backoffice-rights-hidden-route/entries")) mustBe Status.FORBIDDEN
  statusOf(
    "circuit breakers",
    call("DELETE", "/bo/api/services/backoffice-rights-hidden-route/circuitbreakers")
  ) mustBe Status.FORBIDDEN
  val tryIt = call("POST", "/bo/api/tryit", Json.obj("route_id" -> "backoffice-rights-hidden-route", "path" -> "/"))
  withClue(s"try it: status=${tryIt.status} body=${tryIt.body} ") {
    tryIt.status must not be Status.OK
    tryIt.body.contains("backoffice-rights-hidden-route.oto.tools") mustBe false
  }

  user.delete()(using env.otoroshiExecutionContext, env).futureValue
  otoroshiApiCall("DELETE", "/api/routes/backoffice-rights-hidden-route").futureValue
}
