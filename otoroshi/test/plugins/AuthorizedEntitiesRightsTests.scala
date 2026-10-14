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

// the backoffice names the authorized entities of an apikey, but the ids come from the request body: it named any
// route, api or group, and listed every route of any group, whatever the rights of the user on them
class AuthorizedEntitiesRightsTests(parent: PluginsTestSpecBase) {
  import parent.*

  def location(team: String): JsObject = Json.obj("tenant" -> "default", "teams" -> Json.arr(team))

  def create(path: String, json: JsObject): Unit = {
    val (body, status) = otoroshiApiCall("POST", path, Some(json)).futureValue
    withClue(s"create on $path: $body ") {
      status mustBe 201
    }
  }

  val user = BackOfficeUser(
    randomId = "authorized-entities-user",
    name = "authorized-entities-user",
    email = "authorized-entities-user@otoroshi.io",
    profile = Json.obj(),
    authConfigId = "basic",
    simpleLogin = true,
    tags = Seq.empty,
    metadata = Map.empty,
    rights = UserRights(Seq(UserRight(TenantAccess("default"), Seq(TeamAccess("visible-team"))))),
    adminEntityValidators = Map()
  )
  user.save(5.minutes)(using env.otoroshiExecutionContext, env).futureValue

  def route(id: String, name: String, team: String): JsObject = Json.obj(
    "id"       -> id,
    "name"     -> name,
    "groups"   -> Json.arr("authorized-entities-hidden-group"),
    "frontend" -> Json.obj("domains" -> Json.arr(s"$id.oto.tools")),
    "backend"  -> Json.obj("targets" -> Json.arr(Json.obj("hostname" -> "127.0.0.1", "port" -> 9999, "tls" -> false))),
    "_loc"     -> location(team)
  )

  create(
    "/api/groups",
    Json.obj(
      "id"          -> "authorized-entities-hidden-group",
      "name"        -> "hidden-group-name",
      "description" -> "hidden",
      "_loc"        -> location("hidden-team")
    )
  )
  create("/api/routes", route("authorized-entities-visible-route", "visible-route-name", "visible-team"))
  create("/api/routes", route("authorized-entities-hidden-route", "hidden-route-name", "hidden-team"))
  val (apiTemplate, _) = otoroshiApiCall("GET", "/apis/apis.otoroshi.io/v1/apis/_template").futureValue
  create(
    "/apis/apis.otoroshi.io/v1/apis",
    apiTemplate.as[JsObject] ++ Json.obj(
      "id"   -> "authorized-entities-hidden-api",
      "name" -> "hidden-api-name",
      "_loc" -> location("hidden-team")
    )
  )
  create(
    "/api/apikeys",
    Json.obj(
      "clientId"           -> "authorized-entities-apikey",
      "clientSecret"       -> "authorized-entities-secret",
      "clientName"         -> "authorized-entities-apikey",
      "authorizedEntities" -> Json.arr(),
      "_loc"               -> location("visible-team")
    )
  )
  await(3.seconds) // the endpoint reads the proxy state

  val sessionCookieBaker = new DefaultSessionCookieBaker(
    env.httpConfiguration.session,
    env.httpConfiguration.secret,
    new CookieSignerProvider(env.httpConfiguration.secret).get
  )

  def names(kind: String, entities: JsValue*): WSResponse = {
    val cookie = sessionCookieBaker.encodeAsCookie(Session(Map("bousr" -> user.randomId)))
    ws.url(s"http://127.0.0.1:$port/bo/api/apikeys/authorized-entities-apikey/authorized_entities?kind=$kind")
      .withFollowRedirects(false)
      .withHttpHeaders(
        "Host"         -> env.backOfficeHost,
        "Content-Type" -> "application/json",
        "Cookie"       -> s"${cookie.name}=${cookie.value}"
      )
      .post(Json.stringify(Json.obj("authorized_entities" -> JsArray(entities))))
      .futureValue
  }

  def entity(kind: String, id: String): JsObject = Json.obj("kind" -> kind, "id" -> id)

  val routes = names(
    "route",
    entity("route", "authorized-entities-visible-route"),
    entity("route", "authorized-entities-hidden-route"),
    entity("group", "authorized-entities-hidden-group")
  )
  val apis   = names("api", entity("api", "authorized-entities-hidden-api"))
  val groups = names("group", entity("group", "authorized-entities-hidden-group"))

  Seq(routes, apis, groups).foreach { response =>
    withClue(s"status=${response.status} body=${response.body} ") {
      response.status mustBe Status.OK
      response.body.contains("hidden-route-name") mustBe false
      response.body.contains("hidden-api-name") mustBe false
      response.body.contains("hidden-group-name") mustBe false
    }
  }
  withClue(s"routes: ${routes.body} ") {
    routes.body.contains("visible-route-name") mustBe true
  }

  user.delete()(using env.otoroshiExecutionContext, env).futureValue
}
