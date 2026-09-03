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

  startOtoroshi()

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
        "Otoroshi-BackOffice-User" -> JWT
          .create()
          .withClaim("user", Json.stringify(user.toJson))
          .sign(Algorithm.HMAC512("admin-api-apikey-secret"))
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

    "shutdown" in {
      stopAll()
    }
  }
}
