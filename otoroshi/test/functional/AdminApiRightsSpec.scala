package functional

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.typesafe.config.ConfigFactory
import otoroshi.models.*
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.{JsArray, JsNull, JsObject, JsString, JsValue, Json}
import play.api.libs.ws.{WSAuthScheme, writeableOf_String}

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.concurrent.duration.*
import scala.util.Try

// rights enforcement on the admin api. every test here comes from a security review: it describes an
// endpoint that used to let a scoped caller act outside of its own tenant/teams.
class AdminApiRightsSpec(name: String, configurationSpec: => Configuration) extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString(s"""
                      |{
                      |  otoroshi.cache.enabled = false
                      |  otoroshi.cache.ttl = 1
                      |}
       """.stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  val tenant = TenantId("scoped-tenant")
  val team   = TeamId("scoped-team")

  val ownRights        = s"""[{"tenant":"${tenant.value}:rw","teams":["${team.value}:rw"]}]"""
  val superAdminRights = """[{"tenant":"*:rw","teams":["*:rw"]}]"""
  // not a json array: the admin api reads that as "no restriction at all", which is an escalation too
  val unreadableRights = """{}"""

  // neither super admin (no "*" tenant) nor tenant admin (no "*" team): the most restricted shape of
  // an admin api caller
  val scopedUser = BackOfficeUser(
    randomId = "scoped@otoroshi.io",
    name = "scoped@otoroshi.io",
    email = "scoped@otoroshi.io",
    profile = Json.obj(),
    authConfigId = "basic",
    simpleLogin = true,
    tags = Seq.empty,
    metadata = Map.empty,
    rights = UserRights(Seq(UserRight(TenantAccess(tenant.value), Seq(TeamAccess(team.value))))),
    adminEntityValidators = Map()
  )

  val adminUser = scopedUser.copy(
    randomId = "admin@otoroshi.io",
    name = "admin@otoroshi.io",
    email = "admin@otoroshi.io",
    rights = UserRights(Seq(UserRight(TenantAccess("*"), Seq(TeamAccess("*")))))
  )

  // tenant admin on its own tenant, plus a narrow team on every other one. that "*" in the tenant list
  // is what used to switch checkNewUserRights to its dead team check
  val mixedUser = scopedUser.copy(
    randomId = "mixed@otoroshi.io",
    name = "mixed@otoroshi.io",
    email = "mixed@otoroshi.io",
    rights = UserRights(
      Seq(
        UserRight(TenantAccess("*"), Seq(TeamAccess(team.value))),
        UserRight(TenantAccess(tenant.value), Seq(TeamAccess("*")))
      )
    )
  )

  val tenantAdminUser = scopedUser.copy(
    randomId = "tenantadmin@otoroshi.io",
    name = "tenantadmin@otoroshi.io",
    email = "tenantadmin@otoroshi.io",
    rights = UserRights(Seq(UserRight(TenantAccess(tenant.value), Seq(TeamAccess("*")))))
  )

  // reads its own tenant and team, writes nothing
  val readOnlyUser = scopedUser.copy(
    randomId = "readonly@otoroshi.io",
    name = "readonly@otoroshi.io",
    email = "readonly@otoroshi.io",
    rights = UserRights(Seq(UserRight(TenantAccess(s"${tenant.value}:r"), Seq(TeamAccess(s"${team.value}:r")))))
  )

  startOtoroshi()

  def backOfficeUserToken(user: BackOfficeUser, expiresInSeconds: Option[Long] = Some(30L)): String = {
    val builder = JWT.create().withClaim("user", Json.stringify(user.toJson))
    expiresInSeconds
      .map(seconds => builder.withExpiresAt(java.time.Instant.now().plusSeconds(seconds)))
      .getOrElse(builder)
      .sign(Algorithm.HMAC512(otoroshiComponents.env.otoroshiSecret))
  }

  def call(
      method: String,
      path: String,
      user: BackOfficeUser,
      payload: Option[JsValue] = None,
      currentTenant: TenantId = tenant
  ): (JsValue, Int) = {
    val base    = ws
      .url(s"http://localhost:${port}${path}")
      .withHttpHeaders(
        "Host"                     -> "otoroshi-api.oto.tools",
        "Accept"                   -> "application/json",
        "Otoroshi-Admin-Profile"   -> Base64.getUrlEncoder.encodeToString(
          Json.stringify(user.profile).getBytes(StandardCharsets.UTF_8)
        ),
        "Otoroshi-Tenant"          -> currentTenant.value,
        "Otoroshi-BackOffice-User" -> backOfficeUserToken(user)
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .withFollowRedirects(false)
      .withMethod(method)
    val request = payload match {
      case None    => base
      case Some(p) => base.addHttpHeaders("Content-Type" -> "application/json").withBody(Json.stringify(p))
    }
    val response = request.execute().futureValue
    (Try(response.json).getOrElse(JsNull), response.status)
  }

  // an apikey carries its admin api rights in its own metadata, so it is the only way to exercise that
  // code path: a BackOfficeUser header would shortcut the metadata entirely
  def apikeyCall(
      method: String,
      path: String,
      clientId: String,
      payload: Option[JsValue] = None,
      currentTenant: TenantId = tenant
  ): (JsValue, Int) = {
    val base    = ws
      .url(s"http://localhost:${port}${path}")
      .withHttpHeaders(
        "Host"            -> "otoroshi-api.oto.tools",
        "Accept"          -> "application/json",
        "Otoroshi-Tenant" -> currentTenant.value
      )
      .withAuth(clientId, s"$clientId-secret", WSAuthScheme.BASIC)
      .withFollowRedirects(false)
      .withMethod(method)
    val request = payload match {
      case None    => base
      case Some(p) => base.addHttpHeaders("Content-Type" -> "application/json").withBody(Json.stringify(p))
    }
    val response = request.execute().futureValue
    (Try(response.json).getOrElse(JsNull), response.status)
  }

  def scopedApiKeyJson(
      clientId: String,
      accessRights: String,
      extraAuthorizedEntities: Seq[String] = Seq.empty
  ): JsValue = Json.obj(
    "clientId"           -> clientId,
    "clientSecret"       -> s"$clientId-secret",
    "clientName"         -> clientId,
    "authorizedEntities" -> JsArray(("group_admin-api-group" +: extraAuthorizedEntities).map(JsString.apply)),
    "metadata"           -> Json.obj("otoroshi-access-rights" -> accessRights),
    "_loc"               -> Json.obj("tenant" -> tenant.value, "teams" -> Json.arr(team.value))
  )

  def createSimpleAdmin(username: String, user: BackOfficeUser): Unit = {
    val (body, status) = call(
      "POST",
      "/api/admins/simple",
      user,
      Json.obj("username" -> username, "password" -> "password", "label" -> username).some
    )
    withClue(s"create admin $username: ${Json.stringify(body)} ") {
      status mustBe 200
    }
  }

  def simpleAdmin(username: String): JsValue = {
    val (body, status) = otoroshiApiCall("GET", s"/api/admins/simple/$username").futureValue
    status mustBe 200
    body
  }

  // the bulk endpoints speak ndjson in and out, so neither the body nor the response is a single json
  def ndjsonCall(method: String, path: String, user: BackOfficeUser, body: String): Int = {
    ws.url(s"http://localhost:${port}${path}")
      .withHttpHeaders(
        "Host"                     -> "otoroshi-api.oto.tools",
        "Accept"                   -> "application/x-ndjson",
        "Content-Type"             -> "application/x-ndjson",
        "Otoroshi-Tenant"          -> tenant.value,
        "Otoroshi-BackOffice-User" -> backOfficeUserToken(user)
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .withFollowRedirects(false)
      .withMethod(method)
      .withBody(body)
      .execute()
      .futureValue
      .status
  }

  def createRoute(id: String, tenantId: String, teamId: String): Unit = {
    val (body, status) = otoroshiApiCall(
      "POST",
      "/api/routes",
      Json
        .obj(
          "id"       -> id,
          "name"     -> id,
          "frontend" -> Json.obj("domains" -> Json.arr(s"$id.oto.tools")),
          "backend"  -> Json.obj(
            "targets" -> Json.arr(Json.obj("hostname" -> "127.0.0.1", "port" -> 9999, "tls" -> false))
          ),
          "_loc"     -> Json.obj("tenant" -> tenantId, "teams" -> Json.arr(teamId))
        )
        .some
    ).futureValue
    withClue(s"create route $id: ${Json.stringify(body)} ") {
      status mustBe 201
    }
  }

  def route(id: String): JsValue = {
    val (body, status) = otoroshiApiCall("GET", s"/api/routes/$id").futureValue
    status mustBe 200
    body
  }

  def routeTenant(id: String): String = (route(id) \ "_loc" \ "tenant").as[String]

  def maxConcurrentRequests(): Long = {
    val (body, status) = otoroshiApiCall("GET", "/api/globalconfig").futureValue
    status mustBe 200
    (body \ "maxConcurrentRequests").as[Long]
  }

  def createScopedApiKey(clientId: String, extraAuthorizedEntities: Seq[String] = Seq.empty): Unit = {
    val json           = scopedApiKeyJson(clientId, ownRights, extraAuthorizedEntities)
    val (body, status) = otoroshiApiCall("POST", "/api/apikeys", json.some).futureValue
    withClue(s"create $clientId: ${Json.stringify(body)} ") {
      status mustBe 201
    }
    // the apikey is about to authenticate through the gateway, which reads it from the proxy state
    await(1.second)
  }

  def createScopedGroup(id: String): Unit = {
    val (body, status) = otoroshiApiCall(
      "POST",
      "/api/groups",
      Json
        .obj(
          "id"          -> id,
          "name"        -> id,
          "description" -> id,
          "_loc"        -> Json.obj("tenant" -> tenant.value, "teams" -> Json.arr(team.value))
        )
        .some
    ).futureValue
    withClue(s"create group $id: ${Json.stringify(body)} ") {
      status mustBe 201
    }
  }

  def scopedLocation(tenantId: String = tenant.value): JsObject =
    Json.obj("tenant" -> tenantId, "teams" -> Json.arr(team.value))

  def createScopedApi(id: String): Unit = {
    val (template, _)  = otoroshiApiCall("GET", "/apis/apis.otoroshi.io/v1/apis/_template").futureValue
    val (body, status) = otoroshiApiCall(
      "POST",
      "/apis/apis.otoroshi.io/v1/apis",
      (template.as[JsObject] ++ Json.obj("id" -> id, "_loc" -> scopedLocation())).some
    ).futureValue
    withClue(s"create api $id: ${Json.stringify(body)} ") {
      status mustBe 201
    }
  }

  def createDraft(id: String, tenantId: String): Unit = {
    val (body, status) = otoroshiApiCall(
      "POST",
      "/apis/proxy.otoroshi.io/v1/drafts",
      Json
        .obj(
          "id"          -> id,
          "kind"        -> "apis",
          "name"        -> id,
          "description" -> id,
          "content"     -> Json.obj("id" -> id, "_loc" -> scopedLocation(tenantId))
        )
        .some
    ).futureValue
    withClue(s"create draft $id: ${Json.stringify(body)} ") {
      status mustBe 201
    }
  }

  s"[$name] Otoroshi admin API rights" should {

    "warm up" in {
      getOtoroshiServices().futureValue
    }

    "not let a scoped user create an entity outside of its scope through POST /api/:entity/_template" in {
      val (_, status) = call(
        "POST",
        "/api/apikeys/_template",
        scopedUser,
        Json
          .obj(
            "clientId"   -> "escaped-apikey",
            "clientName" -> "escaped apikey",
            "_loc"       -> Json.obj("tenant" -> "default", "teams" -> Json.arr("default"))
          )
          .some
      )
      status mustBe 403
      val (_, getStatus) = otoroshiApiCall("GET", "/api/apikeys/escaped-apikey").futureValue
      getStatus mustBe 404
    }

    "still let a scoped user create an entity inside of its scope through POST /api/:entity/_template" in {
      val (_, status) = call(
        "POST",
        "/api/apikeys/_template",
        scopedUser,
        Json
          .obj(
            "clientId"   -> "in-scope-apikey",
            "clientName" -> "in scope apikey",
            "_loc"       -> Json.obj("tenant" -> tenant.value, "teams" -> Json.arr("scoped-team"))
          )
          .some
      )
      status mustBe 201
      val (_, getStatus) = otoroshiApiCall("GET", "/api/apikeys/in-scope-apikey").futureValue
      getStatus mustBe 200
    }

    "not let a scoped user overwrite the global config through POST /api/globalconfig/_template" in {
      val before      = maxConcurrentRequests()
      val (_, status) = call(
        "POST",
        "/api/globalconfig/_template",
        scopedUser,
        Json.obj("maxConcurrentRequests" -> 42).some
      )
      status mustBe 403
      maxConcurrentRequests() mustBe before
    }

    // checkRights used to take its body by value, so a forbidden caller still ran the whole guarded
    // block and only the response was replaced by a 403
    "not let a scoped user overwrite the global config through PUT /api/globalconfig" in {
      val before         = maxConcurrentRequests()
      val (current, 200) = otoroshiApiCall("GET", "/api/globalconfig").futureValue
      val (_, status)    = call(
        "PUT",
        "/api/globalconfig",
        scopedUser,
        (current.as[JsObject] ++ Json.obj("maxConcurrentRequests" -> 43)).some
      )
      status mustBe 403
      maxConcurrentRequests() mustBe before
    }

    "give a tenant scoped apikey no access to super admin only endpoints" in {
      createScopedApiKey("scoped-apikey")
      apikeyCall("GET", "/api/apikeys/scoped-apikey", "scoped-apikey")._2 mustBe 200
      apikeyCall("GET", "/api/globalconfig", "scoped-apikey")._2 mustBe 403
    }

    // the Otoroshi-BackOffice-User header used to be signed with the caller's own clientSecret, so the
    // holder of any apikey could mint whatever rights it wanted
    "not let an apikey forge a backoffice user with its own client secret" in {
      createScopedApiKey("forging-apikey")
      val forged      = JWT
        .create()
        .withClaim("user", Json.stringify(adminUser.toJson))
        .sign(Algorithm.HMAC512("forging-apikey-secret"))
      val (_, status) = ws
        .url(s"http://localhost:${port}/api/globalconfig")
        .withHttpHeaders(
          "Host"                     -> "otoroshi-api.oto.tools",
          "Accept"                   -> "application/json",
          "Otoroshi-Tenant"          -> tenant.value,
          "Otoroshi-BackOffice-User" -> forged
        )
        .withAuth("forging-apikey", "forging-apikey-secret", WSAuthScheme.BASIC)
        .get()
        .futureValue
        .applyOn(response => (response.status, response.status))
      status mustBe 403
    }

    def globalConfigWithToken(token: String): Int = ws
      .url(s"http://localhost:${port}/api/globalconfig")
      .withHttpHeaders(
        "Host"                     -> "otoroshi-api.oto.tools",
        "Accept"                   -> "application/json",
        "Otoroshi-Tenant"          -> TenantId("default").value,
        "Otoroshi-BackOffice-User" -> token
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .get()
      .futureValue
      .status

    // a token minted before the expiry was introduced carries none, and would otherwise stay valid
    // for as long as the signing secret does
    "not let a backoffice user token without an expiry through" in {
      globalConfigWithToken(backOfficeUserToken(adminUser, None)) mustBe 403
    }

    "not let an expired backoffice user token through" in {
      globalConfigWithToken(backOfficeUserToken(adminUser, Some(-60L))) mustBe 403
    }

    "still let a fresh backoffice user token through" in {
      globalConfigWithToken(backOfficeUserToken(adminUser)) mustBe 200
    }

    "not let an apikey grant itself super admin rights through its own metadata" in {
      createScopedApiKey("escalating-apikey")
      val (_, writeStatus) = apikeyCall(
        "PUT",
        "/api/apikeys/escalating-apikey",
        "escalating-apikey",
        scopedApiKeyJson("escalating-apikey", superAdminRights).some
      )
      await(2.seconds)
      val (_, escalated) = apikeyCall("GET", "/api/globalconfig", "escalating-apikey")
      withClue(s"write=$writeStatus escalated=$escalated ") {
        writeStatus mustBe 403
        escalated mustBe 403
      }
    }

    "not let an apikey drop its own rights metadata to become unrestricted" in {
      createScopedApiKey("unreadable-apikey")
      val (_, writeStatus) = apikeyCall(
        "PUT",
        "/api/apikeys/unreadable-apikey",
        "unreadable-apikey",
        scopedApiKeyJson("unreadable-apikey", unreadableRights).some
      )
      await(2.seconds)
      val (_, escalated) = apikeyCall("GET", "/api/globalconfig", "unreadable-apikey")
      withClue(s"write=$writeStatus escalated=$escalated ") {
        writeStatus mustBe 403
        escalated mustBe 403
      }
    }

    "not let an apikey patch super admin rights into its own metadata" in {
      createScopedApiKey("patching-apikey")
      val (_, writeStatus) = apikeyCall(
        "PATCH",
        "/api/apikeys/patching-apikey",
        "patching-apikey",
        Json
          .arr(
            Json.obj(
              "op"    -> "replace",
              "path"  -> "/metadata/otoroshi-access-rights",
              "value" -> superAdminRights
            )
          )
          .some
      )
      await(2.seconds)
      val (_, escalated) = apikeyCall("GET", "/api/globalconfig", "patching-apikey")
      withClue(s"write=$writeStatus escalated=$escalated ") {
        writeStatus mustBe 403
        escalated mustBe 403
      }
    }

    "not let an apikey mint a super admin apikey inside its own scope" in {
      createScopedApiKey("minting-apikey")
      val (_, status) = apikeyCall(
        "POST",
        "/api/apikeys",
        "minting-apikey",
        scopedApiKeyJson("minted-apikey", superAdminRights).some
      )
      status mustBe 403
      val (_, getStatus) = otoroshiApiCall("GET", "/api/apikeys/minted-apikey").futureValue
      getStatus mustBe 404
    }

    // the per group and per route apikey endpoints check the rights of the stored apikey but never of
    // the one being written, so they are a second way in
    "not let an apikey grant itself super admin rights through the per group apikey endpoint" in {
      createScopedGroup("scoped-group")
      createScopedApiKey("group-escalating-apikey", Seq("group_scoped-group"))
      val (_, writeStatus) = apikeyCall(
        "PUT",
        "/api/groups/scoped-group/apikeys/group-escalating-apikey",
        "group-escalating-apikey",
        scopedApiKeyJson("group-escalating-apikey", superAdminRights, Seq("group_scoped-group")).some
      )
      await(2.seconds)
      val (_, escalated) = apikeyCall("GET", "/api/globalconfig", "group-escalating-apikey")
      withClue(s"write=$writeStatus escalated=$escalated ") {
        writeStatus mustBe 403
        escalated mustBe 403
      }
    }

    "not let an apikey patch itself super admin rights through the per group apikey endpoint" in {
      createScopedApiKey("group-patching-apikey", Seq("group_scoped-group"))
      val (_, writeStatus) = apikeyCall(
        "PATCH",
        "/api/groups/scoped-group/apikeys/group-patching-apikey",
        "group-patching-apikey",
        Json
          .arr(
            Json.obj(
              "op"    -> "replace",
              "path"  -> "/metadata/otoroshi-access-rights",
              "value" -> superAdminRights
            )
          )
          .some
      )
      await(2.seconds)
      val (_, escalated) = apikeyCall("GET", "/api/globalconfig", "group-patching-apikey")
      withClue(s"write=$writeStatus escalated=$escalated ") {
        writeStatus mustBe 403
        escalated mustBe 403
      }
    }

    "not let an apikey grant itself super admin rights through the per route apikey endpoint" in {
      val (routeBody, routeStatus) = otoroshiApiCall(
        "POST",
        "/api/routes",
        Json
          .obj(
            "id"       -> "scoped-route",
            "name"     -> "scoped route",
            "frontend" -> Json.obj("domains" -> Json.arr("scoped-route.oto.tools")),
            "backend"  -> Json.obj(
              "targets" -> Json.arr(Json.obj("hostname" -> "127.0.0.1", "port" -> 9999, "tls" -> false))
            ),
            "groups"   -> Json.arr("scoped-group"),
            "_loc"     -> Json.obj("tenant" -> tenant.value, "teams" -> Json.arr(team.value))
          )
          .some
      ).futureValue
      withClue(s"create route: ${Json.stringify(routeBody)} ") {
        routeStatus mustBe 201
      }
      createScopedApiKey("route-escalating-apikey", Seq("route_scoped-route"))
      val (_, writeStatus) = apikeyCall(
        "PUT",
        "/api/routes/scoped-route/apikeys/route-escalating-apikey",
        "route-escalating-apikey",
        scopedApiKeyJson("route-escalating-apikey", superAdminRights, Seq("route_scoped-route")).some
      )
      await(2.seconds)
      val (_, escalated) = apikeyCall("GET", "/api/globalconfig", "route-escalating-apikey")
      withClue(s"write=$writeStatus escalated=$escalated ") {
        writeStatus mustBe 403
        escalated mustBe 403
      }
    }

    "still let a super admin write an apikey carrying super admin rights" in {
      val (_, status) = call(
        "POST",
        "/api/apikeys",
        adminUser,
        Json
          .obj(
            "clientId"           -> "admin-minted-apikey",
            "clientSecret"       -> "admin-minted-apikey-secret",
            "clientName"         -> "admin minted apikey",
            "authorizedEntities" -> Json.arr("group_admin-api-group"),
            "metadata"           -> Json.obj("otoroshi-access-rights" -> superAdminRights),
            "_loc"               -> Json.obj("tenant" -> "default", "teams" -> Json.arr("default"))
          )
          .some,
        currentTenant = TenantId("default")
      )
      status mustBe 201
    }

    "still let an apikey write apikeys that stay inside its own rights" in {
      createScopedApiKey("legit-apikey")
      val (_, status) = apikeyCall(
        "POST",
        "/api/apikeys",
        "legit-apikey",
        scopedApiKeyJson("legit-child-apikey", ownRights).some
      )
      status mustBe 201
    }

    // checkNewUserRights computed its team containment over the caller's own rights instead of the
    // requested ones, so the check was always true as soon as the caller had a "*" tenant access
    "not let an admin grant another admin rights it does not have itself" in {
      createSimpleAdmin("victim-admin", mixedUser)
      val before      = simpleAdmin("victim-admin") \ "rights"
      val (_, status) = call(
        "PUT",
        "/api/admins/simple/victim-admin",
        mixedUser,
        (simpleAdmin("victim-admin").as[JsObject] ++ Json.obj(
          "rights" -> Json.arr(Json.obj("tenant" -> "*:rw", "teams" -> Json.arr("*:r")))
        )).some
      )
      val after = simpleAdmin("victim-admin") \ "rights"
      withClue(s"write=$status rights=${Json.stringify(after.as[JsValue])} ") {
        status mustBe 403
        after.as[JsValue] mustBe before.as[JsValue]
      }
    }

    "not let a tenant admin move an admin into another tenant" in {
      createSimpleAdmin("moved-admin", tenantAdminUser)
      val (_, status) = call(
        "PUT",
        "/api/admins/simple/moved-admin",
        tenantAdminUser,
        (simpleAdmin("moved-admin").as[JsObject] ++ Json.obj(
          "_loc" -> Json.obj("tenant" -> "default", "teams" -> Json.arr("default"))
        )).some
      )
      val after = (simpleAdmin("moved-admin") \ "_loc" \ "tenant").as[String]
      withClue(s"write=$status tenant=$after ") {
        status mustBe 403
        after mustBe tenant.value
      }
    }

    "still let a tenant admin update an admin inside its own rights" in {
      createSimpleAdmin("legit-admin", tenantAdminUser)
      val (_, status) = call(
        "PUT",
        "/api/admins/simple/legit-admin",
        tenantAdminUser,
        (simpleAdmin("legit-admin").as[JsObject] ++ Json.obj("label" -> "renamed")).some
      )
      status mustBe 200
      (simpleAdmin("legit-admin") \ "label").as[String] mustBe "renamed"
    }

    "still let a super admin grant super admin rights to an admin" in {
      createSimpleAdmin("promoted-admin", adminUser)
      val (_, status) = call(
        "PUT",
        "/api/admins/simple/promoted-admin",
        adminUser,
        (simpleAdmin("promoted-admin").as[JsObject] ++ Json.obj(
          "rights" -> Json.arr(Json.obj("tenant" -> "*:rw", "teams" -> Json.arr("*:rw")))
        )).some,
        currentTenant = TenantId("default")
      )
      status mustBe 200
    }

    // the generic api upsert only validated the location the caller sent, never the one of the entity
    // it was about to overwrite
    "not let a scoped user hijack an entity of another tenant through POST /apis/:group/:version/:entity/:id" in {
      createRoute("victim-route", "default", "default")
      val hijacked    = route("victim-route").as[JsObject] ++ Json.obj(
        "_loc" -> Json.obj("tenant" -> tenant.value, "teams" -> Json.arr(team.value))
      )
      val (_, status) =
        call("POST", "/apis/proxy.otoroshi.io/v1/routes/victim-route", scopedUser, hijacked.some)
      val after = routeTenant("victim-route")
      withClue(s"write=$status tenant=$after ") {
        status mustBe 401
        after mustBe "default"
      }
    }

    // patch checked the stored entity but never the patched one, so the location could be rewritten
    "not let a scoped user move an entity out of its scope through PATCH /apis/:group/:version/:entity/:id" in {
      createRoute("escaping-route", tenant.value, team.value)
      val (_, status) = call(
        "PATCH",
        "/apis/proxy.otoroshi.io/v1/routes/escaping-route",
        scopedUser,
        Json.arr(Json.obj("op" -> "replace", "path" -> "/_loc/tenant", "value" -> "default")).some
      )
      val after = routeTenant("escaping-route")
      withClue(s"write=$status tenant=$after ") {
        status mustBe 401
        after mustBe tenant.value
      }
    }

    "still let a scoped user upsert an entity inside its own scope" in {
      createRoute("owned-route", tenant.value, team.value)
      val body        = route("owned-route").as[JsObject] ++ Json.obj("name" -> "renamed")
      val (_, status) = call("POST", "/apis/proxy.otoroshi.io/v1/routes/owned-route", scopedUser, body.some)
      status mustBe 200
      (route("owned-route") \ "name").as[String] mustBe "renamed"
    }

    // upsert on an unknown id has to keep creating, so the check must only apply when the entity exists
    "still let a scoped user create an entity through POST /apis/:group/:version/:entity/:id" in {
      createRoute("model-route", tenant.value, team.value)
      val body        = route("model-route").as[JsObject] ++ Json.obj(
        "id"       -> "created-route",
        "name"     -> "created route",
        "frontend" -> Json.obj("domains" -> Json.arr("created-route.oto.tools"))
      )
      val (_, status) = call("POST", "/apis/proxy.otoroshi.io/v1/routes/created-route", scopedUser, body.some)
      status mustBe 201
      routeTenant("created-route") mustBe tenant.value
    }

    "not let a scoped user move an entity out of its scope through PATCH /apis/:group/:version/:entity/_bulk" in {
      createRoute("bulk-escaping-route", tenant.value, team.value)
      val status = ndjsonCall(
        "PATCH",
        "/apis/proxy.otoroshi.io/v1/routes/_bulk",
        scopedUser,
        Json
          .obj(
            "id"    -> "bulk-escaping-route",
            "patch" -> Json.arr(Json.obj("op" -> "replace", "path" -> "/_loc/tenant", "value" -> "default"))
          )
          .stringify
      )
      val after = routeTenant("bulk-escaping-route")
      withClue(s"write=$status tenant=$after ") {
        after mustBe tenant.value
      }
    }

    "still let a scoped user bulk patch an entity inside its own scope" in {
      createRoute("bulk-owned-route", tenant.value, team.value)
      ndjsonCall(
        "PATCH",
        "/apis/proxy.otoroshi.io/v1/routes/_bulk",
        scopedUser,
        Json
          .obj(
            "id"    -> "bulk-owned-route",
            "patch" -> Json.arr(Json.obj("op" -> "replace", "path" -> "/name", "value" -> "bulk renamed"))
          )
          .stringify
      )
      (route("bulk-owned-route") \ "name").as[String] mustBe "bulk renamed"
    }

    "not let a read only user start, stop, duplicate or deploy an api" in {
      createScopedApi("read-only-scoped-api")
      val base = "/apis/apis.otoroshi.io/v1/apis/read-only-scoped-api"
      call("POST", s"$base/_stop", readOnlyUser)._2 mustBe 403
      call("POST", s"$base/_start", readOnlyUser)._2 mustBe 403
      call("POST", s"$base/duplicate", readOnlyUser, Json.obj().some)._2 mustBe 403
      call("POST", s"$base/deployments", readOnlyUser, Json.obj().some)._2 mustBe 403
      call("POST", s"$base/_stop", scopedUser)._2 mustBe 200
      // state changing, so never on a GET: a GET goes through the backoffice proxy without any origin check
      call("GET", s"$base/_stop", scopedUser)._2 must not be 200
    }

    "not let a caller have an openapi document fetched for an entity it cannot create" in {
      val hits    = new java.util.concurrent.atomic.AtomicInteger(0)
      val doc     = Json.stringify(Json.obj("openapi" -> "3.0.0", "info" -> Json.obj("title" -> "t", "version" -> "1")))
      val openapi = TargetService
        .full(None, "/openapi.json", "application/json", _ => { hits.incrementAndGet(); (200, doc, List.empty) })
        .await()
      val url     = s"http://127.0.0.1:${openapi.port}/openapi.json"
      val apiBody = Json.obj(
        "domain"          -> "openapi.oto.tools",
        "openapi"         -> url,
        "contextPath"     -> "/openapi",
        "backendHostname" -> "127.0.0.1",
        "backendPath"     -> "/",
        "_loc"            -> scopedLocation()
      )
      call("POST", "/apis/apis.otoroshi.io/v1/apis/_openapi", readOnlyUser, apiBody.some)._2 mustBe 403
      call(
        "POST",
        "/api/route-compositions/_openapi",
        readOnlyUser,
        Json.obj("domain" -> "openapi.oto.tools", "openapi" -> url, "_loc" -> scopedLocation()).some
      )._2 mustBe 403
      hits.get() mustBe 0
      call("POST", "/apis/apis.otoroshi.io/v1/apis/_openapi", scopedUser, apiBody.some)
      hits.get() mustBe 1
      openapi.stop()
    }

    "not let a tenant admin discard the private apps sessions of another tenant's auth module" in {
      val otoEnv = otoroshiComponents.env
      def authModuleWithSession(id: String, tenantId: String): Unit = {
        val location = EntityLocation(TenantId(tenantId), Seq(team))
        val module   = otoroshi.auth.BasicAuthModuleConfig(
          id = id,
          name = id,
          desc = id,
          clientSideSessionEnabled = true,
          tags = Seq.empty,
          metadata = Map.empty,
          sessionCookieValues = otoroshi.auth.SessionCookieValues(),
          location = location
        )
        val (body, status) = otoroshiApiCall("POST", "/api/auths", module.asJson.some).futureValue
        withClue(s"create auth module $id: ${Json.stringify(body)} ") {
          status mustBe 201
        }
        PrivateAppsUser(
          randomId = s"$id-session",
          name = id,
          email = s"$id@oto.tools",
          profile = Json.obj(),
          realm = id,
          authConfigId = id,
          otoroshiData = None,
          tags = Seq.empty,
          metadata = Map.empty,
          location = location
        ).save(5.minutes)(using otoEnv.otoroshiExecutionContext, otoEnv).futureValue
      }
      def sessionExists(id: String): Boolean =
        otoEnv.datastores.privateAppsUserDataStore.findById(s"$id-session")(using otoEnv.otoroshiExecutionContext, otoEnv).futureValue.isDefined

      authModuleWithSession("other-tenant-auth-module", "default")
      authModuleWithSession("own-tenant-auth-module", tenant.value)

      call("DELETE", "/api/apps-sessions-module/other-tenant-auth-module", tenantAdminUser)._2 mustBe 403
      call("DELETE", "/api/apps-sessions-module/own-tenant-auth-module", tenantAdminUser)._2 mustBe 200
      await(1.second)
      sessionExists("other-tenant-auth-module") mustBe true
      sessionExists("own-tenant-auth-module") mustBe false
    }

    "not let a caller send profile links for an auth module it cannot write" in {
      val module = otoroshi.auth.BasicAuthModuleConfig(
        id = "profile-link-other-module",
        name = "profile-link-other-module",
        desc = "profile-link-other-module",
        clientSideSessionEnabled = true,
        webauthn = true,
        tags = Seq.empty,
        metadata = Map.empty,
        sessionCookieValues = otoroshi.auth.SessionCookieValues(),
        location = EntityLocation(TenantId("default"), Seq(TeamId("default")))
      )
      otoroshiApiCall("POST", "/api/auths", module.asJson.some).futureValue._2 mustBe 201
      call("POST", s"/api/privateapps/sessions/send/${module.id}/someone@oto.tools", scopedUser)._2 mustBe 403
      call("POST", s"/api/privateapps/sessions/${module.id}/someone@oto.tools", scopedUser)._2 mustBe 403
    }

    "require the role of the typed controllers on the privileged generic resources" in {
      def admin(username: String, rights: Option[JsArray]): JsValue = Json.obj(
        "username" -> username,
        "password" -> "$2a$10$RtYWagxgvorxpxNIYTi4Be2tU.n8294eHpwle1ad0Tmh7.NiVXOEq",
        "label"    -> username,
        "_loc"     -> Json.obj("tenant" -> tenant.value, "teams" -> Json.arr(team.value))
      ) ++ rights.map(r => Json.obj("rights" -> r)).getOrElse(Json.obj())
      val admins = "/apis/security.otoroshi.io/v1/admins"
      // not a tenant admin
      call("POST", admins, scopedUser, admin("generic-scoped@otoroshi.io", None).some)._2 mustBe 403
      call("GET", "/apis/security.otoroshi.io/v1/admin-sessions", scopedUser)._2 mustBe 403
      call("GET", "/apis/security.otoroshi.io/v1/auth-module-users", scopedUser)._2 mustBe 403
      call("GET", "/apis/config.otoroshi.io/v1/global-configs", scopedUser)._2 mustBe 403
      // a tenant admin, but no rights field means super admin rights
      val (_, superStatus) = call("POST", admins, tenantAdminUser, admin("generic-super@otoroshi.io", None).some)
      withClue(s"super admin created by a tenant admin: $superStatus ") {
        superStatus must (be >= 400 and be < 500)
      }
      otoroshiApiCall("GET", s"$admins/generic-super@otoroshi.io").futureValue._2 mustBe 404
      // a tenant admin granting rights inside its own tenant
      val (body, ownStatus) = call(
        "POST",
        admins,
        tenantAdminUser,
        admin(
          "generic-own@otoroshi.io",
          Json.arr(Json.obj("tenant" -> s"${tenant.value}:rw", "teams" -> Json.arr(s"${team.value}:rw"))).some
        ).some
      )
      withClue(s"admin created by a tenant admin inside its tenant: $body ") {
        ownStatus must (be >= 200 and be < 300)
      }
    }

    "only let a caller with rights on the workflow manage its paused sessions" in {
      val otoEnv   = otoroshiComponents.env
      val wf       = otoroshi.next.workflow.Workflow
        .template()
        .copy(id = "paused-sessions-workflow", location = EntityLocation(tenant, Seq(team)))
      createOtoroshiWorkflow(wf).futureValue
      val wfJson   = Json.obj("kind" -> "value", "value" -> "resumed")
      def session(id: String): JsValue = otoroshi.next.workflow
        .PausedWorkflowSession(
          id = id,
          workflowRef = wf.id,
          workflow = wfJson,
          functions = Map.empty,
          wfr = otoroshi.next.workflow.WorkflowRun(
            id = id,
            attrs = otoroshi.utils.TypedMap.empty,
            env = otoEnv,
            workflow_ref = wf.id,
            workflow = wfJson
          ),
          from = Seq.empty,
          createdAt = org.joda.time.DateTime.now(),
          validUntil = None
        )
        .json
      // rights on the default tenant only, nothing on the tenant of the workflow
      val outsider = scopedUser.copy(
        randomId = "outsider@otoroshi.io",
        name = "outsider@otoroshi.io",
        email = "outsider@otoroshi.io",
        rights = UserRights(Seq(UserRight(TenantAccess("default"), Seq(TeamAccess("default")))))
      )
      val base     = "/apis/extensions/otoroshi.extensions.workflows/sessions"
      call("POST", base, readOnlyUser, session("read-only-session").some)._2 mustBe 403
      call("POST", base, scopedUser, session("scoped-session").some)._2 mustBe 200
      call("GET", s"$base/${wf.id}/scoped-session", readOnlyUser)._2 mustBe 200
      call("POST", s"$base/${wf.id}/scoped-session/_resume", readOnlyUser, Json.obj().some)._2 mustBe 403
      call("DELETE", s"$base/${wf.id}/scoped-session", readOnlyUser)._2 mustBe 403
      call("GET", s"$base/${wf.id}/scoped-session", outsider, currentTenant = TenantId("default"))._2 mustBe 403
      call("GET", s"$base/${wf.id}", outsider, currentTenant = TenantId("default"))._2 mustBe 403
      val (all, allStatus) = call("GET", base, outsider, currentTenant = TenantId("default"))
      allStatus mustBe 200
      all.as[Seq[JsObject]].map(s => (s \ "id").as[String]) must not contain "scoped-session"
      call("DELETE", s"$base/${wf.id}/scoped-session", scopedUser)._2 mustBe 200
    }

    "not let a caller renew a certificate it cannot write" in {
      val otoEnv = otoroshiComponents.env
      def certificate(id: String, tenantId: String): Unit = {
        val cert = otoEnv.pki
          .genSelfSignedCert(otoroshi.ssl.pki.models.GenCsrQuery(hosts = Seq(s"$id.oto.tools"), subject = Some(s"CN=$id")))(
            using otoEnv.otoroshiExecutionContext
          )
          .futureValue
          .toOption
          .get
          .toCert
          .copy(id = id, name = id, location = EntityLocation(TenantId(tenantId), Seq(team)))
        cert.save()(using otoEnv.otoroshiExecutionContext, otoEnv).futureValue
      }
      certificate("renew-own-cert", tenant.value)
      certificate("renew-other-cert", "default")
      call("POST", "/api/certificates/renew-own-cert/_renew", readOnlyUser)._2 mustBe 403
      call("POST", "/api/certificates/renew-other-cert/_renew", scopedUser)._2 mustBe 403
      call("POST", "/api/certificates/renew-own-cert/_renew", scopedUser)._2 mustBe 200
    }

    "only list the drafts of the caller's own scope" in {
      createDraft("rights-draft-own", tenant.value)
      createDraft("rights-draft-other", "other-tenant")
      val (body, status) = call("GET", "/apis/proxy.otoroshi.io/v1/drafts/rights-draft", scopedUser)
      status mustBe 200
      val ids = body.as[Seq[JsObject]].map(d => (d \ "id").as[String])
      withClue(s"drafts: $ids ") {
        ids must contain("rights-draft-own")
        ids must not contain "rights-draft-other"
      }
    }

    "shutdown" in {
      stopAll()
    }
  }
}
