package functional

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.typesafe.config.ConfigFactory
import otoroshi.models.*
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.{JsNull, JsValue, Json}
import play.api.libs.ws.{WSAuthScheme, writeableOf_String}

import java.nio.charset.StandardCharsets
import java.util.Base64
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
    rights = UserRights(Seq(UserRight(TenantAccess(tenant.value), Seq(TeamAccess("scoped-team"))))),
    adminEntityValidators = Map()
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
      val before      = getOtoroshiConfig().futureValue
      val (_, status) = call(
        "POST",
        "/api/globalconfig/_template",
        scopedUser,
        Json.obj("maxConcurrentRequests" -> 42).some
      )
      status mustBe 403
      val after = getOtoroshiConfig().futureValue
      after.maxConcurrentRequests mustBe before.maxConcurrentRequests
    }

    "shutdown" in {
      stopAll()
    }
  }
}
