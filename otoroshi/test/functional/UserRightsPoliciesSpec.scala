package functional

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.typesafe.config.ConfigFactory
import otoroshi.models.*
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.{JsArray, JsNull, JsObject, JsString, JsValue, Json}
import play.api.libs.crypto.CookieSignerProvider
import play.api.libs.ws.{WSAuthScheme, WSResponse, writeableOf_String}
import play.api.mvc.{DefaultSessionCookieBaker, Session}

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.concurrent.duration.*
import scala.util.Try

// the rights limited to some kinds of entities, the rights policies of the backoffice users and their personal admin
// apikeys (issue #2927)
class UserRightsPoliciesSpec(name: String, configurationSpec: => Configuration) extends OtoroshiSpec {

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

  // the auth module the users of the tests log in with, the one of their rights policies
  val testModule = "test-module"

  def user(email: String, rights: UserRights, authConfigId: String = testModule): BackOfficeUser = BackOfficeUser(
    randomId = s"$authConfigId-$email",
    name = email,
    email = email,
    profile = Json.obj(),
    authConfigId = authConfigId,
    simpleLogin = false,
    tags = Seq.empty,
    metadata = Map.empty,
    rights = rights,
    adminEntityValidators = Map()
  )

  val superAdmin  = user("admin@otoroshi.io", UserRights.superAdmin)
  // tenant admin of the default tenant
  val tenantAdmin = user(
    "tenantadmin@otoroshi.io",
    UserRights(Seq(UserRight(TenantAccess("default:rw"), Seq(TeamAccess("*:rw")))))
  )
  // reads everything of the default team, writes only its backends
  val backendsOnly = user(
    "backends@otoroshi.io",
    UserRights(
      Seq(
        UserRight(
          TenantAccess("default:rw"),
          Seq(TeamAccess("default:rw")),
          EntityKindsAccess(Seq("*"), Seq("backends"))
        )
      )
    )
  )
  // every tenant and every team, but only the backends: not a super admin
  val everywhereBackendsOnly = user(
    "everywhere@otoroshi.io",
    UserRights(Seq(UserRight(TenantAccess("*:rw"), Seq(TeamAccess("*:rw")), EntityKindsAccess(Seq("*"), Seq("backends")))))
  )
  // reads the routes only
  val routesReader = user(
    "routes@otoroshi.io",
    UserRights(
      Seq(UserRight(TenantAccess("default:rw"), Seq(TeamAccess("default:rw")), EntityKindsAccess(Seq("routes"), Seq.empty)))
    )
  )

  startOtoroshi()

  def otoEnv = otoroshiComponents.env

  def backOfficeUserToken(user: BackOfficeUser): String =
    JWT
      .create()
      .withClaim("user", Json.stringify(user.toJson))
      .withExpiresAt(java.time.Instant.now().plusSeconds(30L))
      .sign(Algorithm.HMAC512(otoroshiComponents.env.otoroshiSecret))

  def call(method: String, path: String, user: BackOfficeUser, payload: Option[JsValue] = None): (JsValue, Int) = {
    val base     = ws
      .url(s"http://localhost:${port}${path}")
      .withHttpHeaders(
        "Host"                     -> "otoroshi-api.oto.tools",
        "Accept"                   -> "application/json",
        "Otoroshi-Admin-Profile"   -> Base64.getUrlEncoder.encodeToString(
          Json.stringify(user.profile).getBytes(StandardCharsets.UTF_8)
        ),
        "Otoroshi-Tenant"          -> "default",
        "Otoroshi-BackOffice-User" -> backOfficeUserToken(user)
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .withFollowRedirects(false)
      .withMethod(method)
    val request  = payload match {
      case None    => base
      case Some(p) => base.addHttpHeaders("Content-Type" -> "application/json").withBody(Json.stringify(p))
    }
    val response = request.execute().futureValue
    (Try(response.json).getOrElse(JsNull), response.status)
  }

  def apikeyCall(method: String, path: String, apikey: ApiKey, payload: Option[JsValue] = None): (JsValue, Int) = {
    val base     = ws
      .url(s"http://localhost:${port}${path}")
      .withHttpHeaders(
        "Host"            -> "otoroshi-api.oto.tools",
        "Accept"          -> "application/json",
        "Otoroshi-Tenant" -> "default"
      )
      .withAuth(apikey.clientId, apikey.clientSecret, WSAuthScheme.BASIC)
      .withFollowRedirects(false)
      .withMethod(method)
    val request  = payload match {
      case None    => base
      case Some(p) => base.addHttpHeaders("Content-Type" -> "application/json").withBody(Json.stringify(p))
    }
    val response = request.execute().futureValue
    (Try(response.json).getOrElse(JsNull), response.status)
  }

  val forbidden = Seq(401, 403)

  def template(path: String): JsObject = {
    val (body, status) = otoroshiApiCall("GET", s"$path/_template").futureValue
    status mustBe 200
    body.as[JsObject]
  }

  def backend(id: String): JsObject = template("/apis/proxy.otoroshi.io/v1/backends") ++ Json.obj("id" -> id)
  def route(id: String): JsObject   = template("/apis/proxy.otoroshi.io/v1/routes") ++ Json.obj(
    "id"       -> id,
    "frontend" -> Json.obj("domains" -> Json.arr(s"$id.oto.tools"))
  )

  def policyJson(email: String, mode: String, rights: UserRights, authModule: Option[String] = testModule.some): JsObject =
    Json.obj(
      "name"            -> email,
      "email"           -> email,
      "mode"            -> mode,
      "auth_module_ref" -> authModule.map(JsString.apply).getOrElse(JsNull).asValue,
      "rights"          -> rights.json
    )

  val policies = "/apis/security.otoroshi.io/v1/user-rights-policies"

  def effective(u: BackOfficeUser): UserRights =
    u.withPolicyRights()(using otoEnv, otoEnv.otoroshiExecutionContext).futureValue.rights

  s"[$name] Rights limited to some kinds of entities" should {

    "warm up" in {
      getOtoroshiServices().futureValue
    }

    "let a user read everything but write only the kinds it is given" in {
      call("GET", "/apis/proxy.otoroshi.io/v1/routes", backendsOnly)._2 mustBe 200
      call("GET", "/apis/pki.otoroshi.io/v1/certificates", backendsOnly)._2 mustBe 200
      val (created, createdStatus) =
        call("POST", "/apis/proxy.otoroshi.io/v1/backends", backendsOnly, backend("kinds-backend").some)
      withClue(s"${Json.stringify(created)} ") {
        createdStatus mustBe 201
      }
      forbidden must contain(
        call("POST", "/apis/proxy.otoroshi.io/v1/routes", backendsOnly, route("kinds-route").some)._2
      )
      otoroshiApiCall("GET", "/apis/proxy.otoroshi.io/v1/routes/kinds-route").futureValue._2 mustBe 404
      // the typed api of the routes too
      forbidden must contain(call("POST", "/api/routes", backendsOnly, route("kinds-route-2").some)._2)
      otoroshiApiCall("GET", "/apis/proxy.otoroshi.io/v1/routes/kinds-route-2").futureValue._2 mustBe 404
    }

    "not let a user delete an entity of a kind it cannot write" in {
      otoroshiApiCall("POST", "/apis/proxy.otoroshi.io/v1/routes", route("kinds-route-3").some).futureValue._2 mustBe 201
      forbidden must contain(call("DELETE", "/apis/proxy.otoroshi.io/v1/routes/kinds-route-3", backendsOnly)._2)
      otoroshiApiCall("GET", "/apis/proxy.otoroshi.io/v1/routes/kinds-route-3").futureValue._2 mustBe 200
      call("DELETE", "/apis/proxy.otoroshi.io/v1/backends/kinds-backend", backendsOnly)._2 mustBe 200
    }

    "hide the kinds a user cannot read" in {
      val (routes, routesStatus) = call("GET", "/apis/proxy.otoroshi.io/v1/routes", routesReader)
      routesStatus mustBe 200
      routes.as[JsArray].value.map(r => (r \ "id").as[String]) must contain("kinds-route-3")
      val (backends, backendsStatus) = call("GET", "/apis/proxy.otoroshi.io/v1/backends", routesReader)
      backendsStatus mustBe 200
      backends.as[JsArray].value mustBe empty
      forbidden must contain(call("POST", "/apis/proxy.otoroshi.io/v1/routes", routesReader, route("kinds-route-4").some)._2)
    }

    "not make a super admin nor a tenant admin of a right limited to some kinds" in {
      call("GET", "/apis/config.otoroshi.io/v1/global-configs", superAdmin)._2 mustBe 200
      call("GET", "/apis/config.otoroshi.io/v1/global-configs", everywhereBackendsOnly)._2 mustBe 403
      call("GET", "/apis/security.otoroshi.io/v1/admins", everywhereBackendsOnly)._2 mustBe 403
    }

    "not let a user give an apikey kinds it does not have" in {
      val apikeysWriter = user(
        "apikeys@otoroshi.io",
        UserRights(
          Seq(UserRight(TenantAccess("default:rw"), Seq(TeamAccess("default:rw")), EntityKindsAccess(Seq("*"), Seq("apikeys"))))
        )
      )
      def adminApikey(id: String, rights: UserRights): JsObject = Json.obj(
        "clientId"           -> id,
        "clientSecret"       -> s"$id-secret",
        "clientName"         -> id,
        "authorizedEntities" -> Json.arr("group_admin-api-group"),
        "metadata"           -> Json.obj(ApiKey.accessRightsMetadataKey -> rights.json.stringify),
        "_loc"               -> Json.obj("tenant" -> "default", "teams" -> Json.arr("default"))
      )
      val everyKind = UserRights(Seq(UserRight(TenantAccess("default:rw"), Seq(TeamAccess("default:rw")))))
      val (body, status) =
        call("POST", "/apis/apim.otoroshi.io/v1/apikeys", apikeysWriter, adminApikey("kinds-apikey-1", everyKind).some)
      withClue(s"${Json.stringify(body)} ") {
        forbidden ++ Seq(400) must contain(status)
      }
      otoroshiApiCall("GET", "/apis/apim.otoroshi.io/v1/apikeys/kinds-apikey-1").futureValue._2 mustBe 404
      call(
        "POST",
        "/apis/apim.otoroshi.io/v1/apikeys",
        apikeysWriter,
        adminApikey("kinds-apikey-2", apikeysWriter.rights).some
      )._2 mustBe 201
    }
  }

  s"[$name] User rights policies" should {

    "only be written by a super admin" in {
      val policy = policyJson("someone@oto.tools", "replace", UserRights.empty)
      call("POST", policies, tenantAdmin, policy.some)._2 mustBe 403
      call("GET", policies, tenantAdmin)._2 mustBe 403
      call("POST", policies, superAdmin, policy.some)._2 mustBe 201
    }

    "be identified by the email of its user, whatever its case" in {
      call("GET", s"$policies/someone@oto.tools", superAdmin)._2 mustBe 200
      val (body, status) = call(
        "POST",
        policies,
        superAdmin,
        policyJson("SomeOne@oto.tools", "replace", UserRights.empty).some
      )
      // the id of a policy is the email of its user in lower case: this one is already used
      withClue(s"${Json.stringify(body)} ") {
        status mustBe 409
      }
      call("DELETE", s"$policies/someone@oto.tools", superAdmin)._2 mustBe 200
    }

    "replace or restrict the rights of the session of a user, on every call" in {
      val bob = user("bob@oto.tools", UserRights.superAdmin)
      effective(bob) mustBe UserRights.superAdmin
      val readDefault = UserRights(Seq(UserRight(TenantAccess("default:r"), Seq(TeamAccess("default:r")))))
      call("POST", policies, superAdmin, policyJson("Bob@oto.tools", "replace", readDefault).some)._2 mustBe 201
      effective(bob) mustBe readDefault
      val module = UserRights(Seq(UserRight(TenantAccess("default:rw"), Seq(TeamAccess("*:rw")))))
      call("PUT", s"$policies/bob@oto.tools", superAdmin, policyJson("bob@oto.tools", "restrict", readDefault).some)._2 mustBe 200
      effective(user("bob@oto.tools", module)) mustBe readDefault
      // the auth module cannot give what the policy keeps away
      val other = UserRights(Seq(UserRight(TenantAccess("other:rw"), Seq(TeamAccess("*:rw")))))
      effective(user("bob@oto.tools", other)) mustBe UserRights.empty
      call("DELETE", s"$policies/bob@oto.tools", superAdmin)._2 mustBe 200
      effective(user("bob@oto.tools", other)) mustBe other
    }
  }

  lazy val sessionCookieBaker = new DefaultSessionCookieBaker(
    otoEnv.httpConfiguration.session,
    otoEnv.httpConfiguration.secret,
    new CookieSignerProvider(otoEnv.httpConfiguration.secret).get
  )

  def session(u: BackOfficeUser): BackOfficeUser = {
    u.save(5.minutes)(using otoEnv.otoroshiExecutionContext, otoEnv).futureValue
  }

  def bo(method: String, path: String, u: BackOfficeUser): WSResponse = {
    val cookie = sessionCookieBaker.encodeAsCookie(Session(Map("bousr" -> u.randomId)))
    ws.url(s"http://127.0.0.1:$port$path")
      .withFollowRedirects(false)
      .withHttpHeaders("Host" -> otoEnv.backOfficeHost, "Cookie" -> s"${cookie.name}=${cookie.value}")
      .withMethod(method)
      .execute()
      .futureValue
  }

  s"[$name] Backoffice sessions" should {

    "get the rights of the policy of their user on every call" in {
      val carol = session(user("carol@oto.tools", UserRights.superAdmin))
      bo("GET", "/bo/api/proxy/apis/config.otoroshi.io/v1/global-configs", carol).status mustBe 200
      val readDefault = UserRights(Seq(UserRight(TenantAccess("default:r"), Seq(TeamAccess("default:r")))))
      call("POST", policies, superAdmin, policyJson("carol@oto.tools", "replace", readDefault).some)._2 mustBe 201
      bo("GET", "/bo/api/proxy/apis/config.otoroshi.io/v1/global-configs", carol).status mustBe 403
      call("DELETE", s"$policies/carol@oto.tools", superAdmin)._2 mustBe 200
      bo("GET", "/bo/api/proxy/apis/config.otoroshi.io/v1/global-configs", carol).status mustBe 200
    }

    "only use the url tools of the kinds of entities they write" in {
      def writer(email: String, kinds: Seq[String]) = session(
        user(
          email,
          UserRights(Seq(UserRight(TenantAccess("default:rw"), Seq(TeamAccess("default:rw")), EntityKindsAccess(Seq("*"), kinds))))
        )
      )
      // the graphql composer of the routes
      val url = "/bo/api/graphqlproxy?url=http://127.0.0.1:1/schema"
      bo("GET", url, writer("tools-backends@oto.tools", Seq("backends"))).status mustBe 403
      bo("GET", url, writer("tools-nothing@oto.tools", Seq.empty)).status mustBe 403
      bo("GET", url, writer("tools-routes@oto.tools", Seq("routes"))).status must not be 403
    }

    "create, rotate and delete the personal admin apikey of their user" in {
      val daveRights = UserRights(
        Seq(UserRight(TenantAccess("default:rw"), Seq(TeamAccess("default:rw")), EntityKindsAccess(Seq("*"), Seq("backends"))))
      )
      val dave       = session(user("dave@oto.tools", UserRights.superAdmin))
      (bo("GET", "/bo/api/me/admin-apikey", dave).json \ "policy").asOpt[JsObject] mustBe None
      bo("POST", "/bo/api/me/admin-apikey", dave).status mustBe 403
      call("POST", policies, superAdmin, policyJson("dave@oto.tools", "replace", daveRights).some)._2 mustBe 201
      val created = bo("POST", "/bo/api/me/admin-apikey", dave)
      created.status mustBe 200
      val first   = (created.json \ "apikey").as[JsObject]
      // created once
      (bo("POST", "/bo/api/me/admin-apikey", dave).json \ "apikey" \ "clientId").as[String] mustBe (first \ "clientId")
        .as[String]
      val key     = (c: JsObject) =>
        ApiKey(
          clientId = (c \ "clientId").as[String],
          clientSecret = (c \ "clientSecret").as[String],
          clientName = "dave",
          authorizedEntities = Seq.empty
        )
      await(1.second)
      apikeyCall("GET", "/apis/proxy.otoroshi.io/v1/routes", key(first))._2 mustBe 200
      val rotated = (bo("POST", "/bo/api/me/admin-apikey/_rotate", dave).json \ "apikey").as[JsObject]
      (rotated \ "clientId").as[String] mustBe (first \ "clientId").as[String]
      (rotated \ "clientSecret").as[String] must not be (first \ "clientSecret").as[String]
      await(1.second)
      apikeyCall("GET", "/apis/proxy.otoroshi.io/v1/routes", key(first))._2 mustBe 401
      apikeyCall("GET", "/apis/proxy.otoroshi.io/v1/routes", key(rotated))._2 mustBe 200
      // a policy in restrict mode gives no personal apikey
      call("PUT", s"$policies/dave@oto.tools", superAdmin, policyJson("dave@oto.tools", "restrict", daveRights).some)._2 mustBe 200
      (bo("GET", "/bo/api/me/admin-apikey", dave).json \ "allowed").as[Boolean] mustBe false
      bo("POST", "/bo/api/me/admin-apikey", dave).status mustBe 403
      bo("DELETE", "/bo/api/me/admin-apikey", dave).status mustBe 200
      call("DELETE", s"$policies/dave@oto.tools", superAdmin)._2 mustBe 200
    }
  }

  def localLogin(username: String, password: String): String = {
    val response = ws
      .url(s"http://127.0.0.1:$port/bo/simple/login")
      .withFollowRedirects(false)
      .withHttpHeaders("Host" -> otoEnv.backOfficeHost, "Content-Type" -> "application/json")
      .post(Json.stringify(Json.obj("username" -> username, "password" -> password)))
      .futureValue
    withClue(s"login of $username: ${response.body} ") {
      response.status mustBe 200
    }
    val cookie = response.cookie(otoEnv.httpConfiguration.session.cookieName).get
    s"${cookie.name}=${cookie.value}"
  }

  def boCookie(method: String, path: String, cookie: String): WSResponse = {
    ws.url(s"http://127.0.0.1:$port$path")
      .withFollowRedirects(false)
      .withHttpHeaders("Host" -> otoEnv.backOfficeHost, "Cookie" -> cookie)
      .withMethod(method)
      .execute()
      .futureValue
  }

  def apikeyOf(json: JsValue, name: String): ApiKey = ApiKey(
    clientId = (json \ "apikey" \ "clientId").as[String],
    clientSecret = (json \ "apikey" \ "clientSecret").as[String],
    clientName = name,
    authorizedEntities = Seq.empty
  )

  val admins = "/apis/security.otoroshi.io/v1/admins"

  def localAdminJson(username: String, rights: UserRights): JsObject = Json.obj(
    "username" -> username,
    "password" -> "local-admin-password",
    "label"    -> username,
    "type"     -> "SIMPLE",
    "rights"   -> rights.json,
    "_loc"     -> Json.obj("tenant" -> "default", "teams" -> Json.arr("*"))
  )

  s"[$name] Rights policies and the other users with the same email" should {

    val readDefault = UserRights(Seq(UserRight(TenantAccess("default:r"), Seq(TeamAccess("default:r")))))
    val otherTenant = UserRights(Seq(UserRight(TenantAccess("other:rw"), Seq(TeamAccess("*:rw")))))

    "only give their rights to the users of their auth module, and cap the rights of the others" in {
      call("POST", policies, superAdmin, policyJson("erin@oto.tools", "replace", readDefault).some)._2 mustBe 201
      effective(user("erin@oto.tools", otherTenant)) mustBe readDefault
      effective(user("erin@oto.tools", otherTenant, "another-module")) mustBe UserRights.empty
      effective(user("erin@oto.tools", UserRights.superAdmin, "another-module")) mustBe readDefault
      // a local admin, simple or webauthn
      effective(user("erin@oto.tools", otherTenant, BackOfficeUser.localAdminsAuthConfigId)) mustBe UserRights.empty
      effective(user("erin@oto.tools", UserRights.superAdmin, BackOfficeUser.localAdminsAuthConfigId)) mustBe readDefault
      call("DELETE", s"$policies/erin@oto.tools", superAdmin)._2 mustBe 200
    }

    "give their rights to the users of the backoffice auth module without auth module" in {
      val ec: scala.concurrent.ExecutionContext = otoEnv.otoroshiExecutionContext
      val config                                = otoEnv.datastores.globalConfigDataStore.latest()(using ec, otoEnv)
      def setBackOfficeModule(module: Option[String]): Unit = {
        otoEnv.datastores.globalConfigDataStore
          .set(config.copy(backOfficeAuthRef = module), None)(using ec, otoEnv)
          .futureValue
        await(1.second)
      }
      call("POST", policies, superAdmin, policyJson("frank@oto.tools", "replace", readDefault, None).some)._2 mustBe 201
      try {
        // without a backoffice auth module, the policy only restricts
        setBackOfficeModule(None)
        effective(user("frank@oto.tools", otherTenant, "bo-module")) mustBe UserRights.empty
        setBackOfficeModule("bo-module".some)
        effective(user("frank@oto.tools", otherTenant, "bo-module")) mustBe readDefault
        effective(user("frank@oto.tools", otherTenant, testModule)) mustBe UserRights.empty
        effective(user("frank@oto.tools", otherTenant, BackOfficeUser.localAdminsAuthConfigId)) mustBe UserRights.empty
      } finally {
        setBackOfficeModule(config.backOfficeAuthRef)
        call("DELETE", s"$policies/frank@oto.tools", superAdmin)._2 mustBe 200
      }
    }

    "not give the rights of a policy to a local admin named after its email by a tenant admin" in {
      // the policy of a super admin, for the users of its auth module
      call("POST", policies, superAdmin, policyJson("victim@oto.tools", "replace", UserRights.superAdmin).some)._2 mustBe 201
      // the super admin gets its personal apikey
      val victim       = session(user("victim@oto.tools", UserRights.empty))
      val victimKeyRes = bo("POST", "/bo/api/me/admin-apikey", victim)
      victimKeyRes.status mustBe 200
      val victimKey    = apikeyOf(victimKeyRes.json, "victim")
      await(1.second)
      apikeyCall("GET", "/apis/config.otoroshi.io/v1/global-configs", victimKey)._2 mustBe 200
      // a tenant admin creates a local admin with the same email, and logs in with it
      val (created, createdStatus) = call(
        "POST",
        "/api/admins/simple",
        tenantAdmin,
        Json.obj("username" -> "victim@oto.tools", "password" -> "chosen-by-the-tenant-admin", "label" -> "victim").some
      )
      withClue(s"${Json.stringify(created)} ") {
        createdStatus mustBe 200
      }
      val local = localLogin("victim@oto.tools", "chosen-by-the-tenant-admin")
      // the local admin keeps its own rights, the ones of a tenant admin
      boCookie("GET", "/bo/api/proxy/apis/config.otoroshi.io/v1/global-configs", local).status mustBe 403
      boCookie("GET", "/bo/api/proxy/apis/security.otoroshi.io/v1/user-rights-policies", local).status mustBe 403
      boCookie("GET", "/bo/api/proxy/apis/proxy.otoroshi.io/v1/routes", local).status mustBe 200
      // it does not see the personal apikey of the super admin, not even its secret
      val localPayload = boCookie("GET", "/bo/api/me/admin-apikey", local)
      localPayload.status mustBe 200
      (localPayload.json \ "source").as[String] mustBe "local-admin"
      (localPayload.json \ "apikey").asOpt[JsObject] mustBe None
      Json.stringify(localPayload.json) must not include victimKey.clientSecret
      forbidden ++ Seq(404) must contain(
        boCookie("GET", s"/bo/api/proxy/apis/apim.otoroshi.io/v1/apikeys/${victimKey.clientId}", local).status
      )
      // its own personal apikey has its own rights
      val localKeyRes = boCookie("POST", "/bo/api/me/admin-apikey", local)
      localKeyRes.status mustBe 200
      val localKey    = apikeyOf(localKeyRes.json, "local victim")
      localKey.clientId must not be victimKey.clientId
      await(1.second)
      apikeyCall("GET", "/apis/proxy.otoroshi.io/v1/routes", localKey)._2 mustBe 200
      forbidden must contain(apikeyCall("GET", "/apis/config.otoroshi.io/v1/global-configs", localKey)._2)
      // deleting it does not delete the one of the super admin
      boCookie("DELETE", "/bo/api/me/admin-apikey", local).status mustBe 200
      (bo("GET", "/bo/api/me/admin-apikey", victim).json \ "apikey" \ "clientId").as[String] mustBe victimKey.clientId
      apikeyCall("GET", "/apis/config.otoroshi.io/v1/global-configs", victimKey)._2 mustBe 200
      bo("DELETE", "/bo/api/me/admin-apikey", victim).status mustBe 200
      otoroshiApiCall("DELETE", "/api/admins/simple/victim@oto.tools").futureValue._2 mustBe 200
      call("DELETE", s"$policies/victim@oto.tools", superAdmin)._2 mustBe 200
    }

    "not give a personal apikey to a user of another auth module than the one of its policy" in {
      call("POST", policies, superAdmin, policyJson("grace@oto.tools", "replace", readDefault).some)._2 mustBe 201
      val grace   = session(user("grace@oto.tools", UserRights.superAdmin, "another-module"))
      val payload = bo("GET", "/bo/api/me/admin-apikey", grace)
      (payload.json \ "allowed").as[Boolean] mustBe false
      (payload.json \ "reason").as[String] mustBe PersonalAdminApikey.otherAuthModuleError
      bo("POST", "/bo/api/me/admin-apikey", grace).status mustBe 403
      call("DELETE", s"$policies/grace@oto.tools", superAdmin)._2 mustBe 200
    }
  }

  s"[$name] Personal admin apikeys of the local admins" should {

    val backendsWriter = UserRights(
      Seq(UserRight(TenantAccess("default:rw"), Seq(TeamAccess("*:rw")), EntityKindsAccess(Seq("*"), Seq("backends"))))
    )
    var key: ApiKey = null

    "have the rights of their local admin, without rights policy" in {
      otoroshiApiCall("POST", admins, localAdminJson("henry@oto.tools", backendsWriter).some).futureValue._2 mustBe 201
      val henry   = localLogin("henry@oto.tools", "local-admin-password")
      val payload = boCookie("GET", "/bo/api/me/admin-apikey", henry)
      (payload.json \ "allowed").as[Boolean] mustBe true
      (payload.json \ "policy").asOpt[JsObject] mustBe None
      val created = boCookie("POST", "/bo/api/me/admin-apikey", henry)
      created.status mustBe 200
      key = apikeyOf(created.json, "henry")
      await(1.second)
      apikeyCall("POST", "/apis/proxy.otoroshi.io/v1/backends", key, backend("henry-backend").some)._2 mustBe 201
      forbidden must contain(apikeyCall("POST", "/apis/proxy.otoroshi.io/v1/routes", key, route("henry-route").some)._2)
      forbidden must contain(apikeyCall("GET", "/apis/config.otoroshi.io/v1/global-configs", key)._2)
    }

    "follow the rights of their local admin on the next call" in {
      val (current, _) = otoroshiApiCall("GET", "/api/admins/simple/henry@oto.tools").futureValue
      val readOnly     = UserRights(
        Seq(UserRight(TenantAccess("default:rw"), Seq(TeamAccess("*:rw")), EntityKindsAccess(Seq("*"), Seq.empty)))
      )
      otoroshiApiCall(
        "PUT",
        "/api/admins/simple/henry@oto.tools",
        (current.as[JsObject] ++ Json.obj("rights" -> readOnly.json)).some
      ).futureValue._2 mustBe 200
      forbidden must contain(
        apikeyCall("POST", "/apis/proxy.otoroshi.io/v1/backends", key, backend("henry-backend-2").some)._2
      )
      apikeyCall("GET", "/apis/proxy.otoroshi.io/v1/routes", key)._2 mustBe 200
    }

    "be capped by the rights policy of their local admin, whatever its mode" in {
      call("POST", policies, superAdmin, policyJson("henry@oto.tools", "replace", UserRights.superAdmin).some)._2 mustBe 201
      forbidden must contain(apikeyCall("GET", "/apis/config.otoroshi.io/v1/global-configs", key)._2)
      call("PUT", s"$policies/henry@oto.tools", superAdmin, policyJson("henry@oto.tools", "restrict", UserRights.empty).some)._2 mustBe 200
      val (routes, status) = apikeyCall("GET", "/apis/proxy.otoroshi.io/v1/routes", key)
      status mustBe 200
      routes.as[JsArray].value mustBe empty
      call("DELETE", s"$policies/henry@oto.tools", superAdmin)._2 mustBe 200
    }

    "have no rights once their local admin is deleted, even when an admin is created again with the same username" in {
      otoroshiApiCall("DELETE", s"$admins/henry@oto.tools").futureValue._2 mustBe 200
      apikeyCall("GET", "/apis/proxy.otoroshi.io/v1/routes", key)._2 mustBe 401
      otoroshiApiCall(
        "POST",
        admins,
        (localAdminJson("henry@oto.tools", UserRights.superAdmin) ++ Json.obj(
          "createdAt" -> (System.currentTimeMillis() + 60000L)
        )).some
      ).futureValue._2 mustBe 201
      apikeyCall("GET", "/apis/proxy.otoroshi.io/v1/routes", key)._2 mustBe 401
      // the new admin does not see the apikey of the old one
      val henry = localLogin("henry@oto.tools", "local-admin-password")
      (boCookie("GET", "/bo/api/me/admin-apikey", henry).json \ "apikey").asOpt[JsObject] mustBe None
      otoroshiApiCall("DELETE", s"$admins/henry@oto.tools").futureValue._2 mustBe 200
      otoroshiApiCall("DELETE", s"/apis/apim.otoroshi.io/v1/apikeys/${key.clientId}").futureValue._2 mustBe 200
      otoroshiApiCall("DELETE", "/apis/proxy.otoroshi.io/v1/backends/henry-backend").futureValue
    }
  }

  s"[$name] Personal admin apikeys" should {

    val aliceRights = UserRights(
      Seq(UserRight(TenantAccess("default:rw"), Seq(TeamAccess("default:rw")), EntityKindsAccess(Seq("*"), Seq("backends"))))
    )
    var alice: ApiKey = null

    "get the rights of the policy of their owner" in {
      call("POST", policies, superAdmin, policyJson("alice@oto.tools", "replace", aliceRights).some)._2 mustBe 201
      val policy = UserRightsPolicy.of("alice@oto.tools")(using otoEnv).get
      alice = PersonalAdminApikey
        .create(PersonalAdminApikeyOwner.AuthModuleUser("alice@oto.tools", testModule), policy.location)(using
          otoEnv,
          otoEnv.otoroshiExecutionContext
        )
        .futureValue
      await(1.second)
      apikeyCall("GET", "/apis/proxy.otoroshi.io/v1/routes", alice)._2 mustBe 200
      apikeyCall("POST", "/apis/proxy.otoroshi.io/v1/backends", alice, backend("alice-backend").some)._2 mustBe 201
      forbidden must contain(apikeyCall("POST", "/apis/proxy.otoroshi.io/v1/routes", alice, route("alice-route").some)._2)
      forbidden must contain(apikeyCall("GET", "/apis/config.otoroshi.io/v1/global-configs", alice)._2)
    }

    "follow the policy of their owner on the next call" in {
      val readOnly = UserRights(
        Seq(UserRight(TenantAccess("default:rw"), Seq(TeamAccess("default:rw")), EntityKindsAccess(Seq("*"), Seq.empty)))
      )
      call("PUT", s"$policies/alice@oto.tools", superAdmin, policyJson("alice@oto.tools", "replace", readOnly).some)._2 mustBe 200
      forbidden must contain(
        apikeyCall("POST", "/apis/proxy.otoroshi.io/v1/backends", alice, backend("alice-backend-2").some)._2
      )
      apikeyCall("GET", "/apis/proxy.otoroshi.io/v1/routes", alice)._2 mustBe 200
    }

    "be kept away from the other users" in {
      val (keys, keysStatus) = call("GET", "/apis/apim.otoroshi.io/v1/apikeys", tenantAdmin)
      keysStatus mustBe 200
      keys.as[JsArray].value.map(k => (k \ "clientId").as[String]) must not contain alice.clientId
      forbidden ++ Seq(404) must contain(call("GET", s"/apis/apim.otoroshi.io/v1/apikeys/${alice.clientId}", tenantAdmin)._2)
      forbidden must contain(call("GET", s"/api/apikeys/${alice.clientId}", tenantAdmin)._2)
      call("GET", s"/apis/apim.otoroshi.io/v1/apikeys/${alice.clientId}", superAdmin)._2 mustBe 200
      // its owner can read it
      call("GET", s"/apis/apim.otoroshi.io/v1/apikeys/${alice.clientId}", user("alice@oto.tools", tenantAdmin.rights))._2 mustBe 200
      // only a super admin writes one
      val updated = alice.json.as[JsObject] ++ Json.obj("description" -> "hijacked")
      forbidden must contain(call("PUT", s"/apis/apim.otoroshi.io/v1/apikeys/${alice.clientId}", tenantAdmin, updated.some)._2)
    }

    "not let a user mark an apikey as the one of someone else" in {
      val json = Json.obj(
        "clientId"           -> "marked-apikey",
        "clientSecret"       -> "marked-apikey-secret",
        "clientName"         -> "marked-apikey",
        "authorizedEntities" -> Json.arr("group_admin-api-group"),
        "metadata"           -> Json.obj(PersonalAdminApikey.ownerMetadataKey -> "alice@oto.tools"),
        "_loc"               -> Json.obj("tenant" -> "default", "teams" -> Json.arr("default"))
      )
      forbidden ++ Seq(400) must contain(call("POST", "/apis/apim.otoroshi.io/v1/apikeys", tenantAdmin, json.some)._2)
      otoroshiApiCall("GET", "/apis/apim.otoroshi.io/v1/apikeys/marked-apikey").futureValue._2 mustBe 404
    }

    "have no rights while the policy of their owner is in restrict mode" in {
      call("PUT", s"$policies/alice@oto.tools", superAdmin, policyJson("alice@oto.tools", "restrict", aliceRights).some)._2 mustBe 200
      val (body, status) = apikeyCall("GET", "/apis/proxy.otoroshi.io/v1/routes", alice)
      withClue(s"${Json.stringify(body)} ") {
        status mustBe 401
      }
      call("PUT", s"$policies/alice@oto.tools", superAdmin, policyJson("alice@oto.tools", "replace", aliceRights).some)._2 mustBe 200
      apikeyCall("GET", "/apis/proxy.otoroshi.io/v1/routes", alice)._2 mustBe 200
    }

    "have no rights without the metadata of their owner" in {
      // a super admin removing the owner by hand must not turn the apikey into an admin apikey without restriction
      val orphan = alice.copy(clientId = "orphan-apikey", metadata = alice.metadata - PersonalAdminApikey.ownerMetadataKey)
      orphan.save()(using otoEnv.otoroshiExecutionContext, otoEnv).futureValue
      await(1.second)
      val (routes, status) = apikeyCall("GET", "/apis/proxy.otoroshi.io/v1/routes", orphan)
      status mustBe 200
      routes.as[JsArray].value mustBe empty
      forbidden must contain(apikeyCall("POST", "/apis/proxy.otoroshi.io/v1/backends", orphan, backend("orphan-backend").some)._2)
    }

    "have no rights once the policy of their owner is deleted" in {
      call("DELETE", s"$policies/alice@oto.tools", superAdmin)._2 mustBe 200
      forbidden must contain(apikeyCall("GET", "/apis/proxy.otoroshi.io/v1/routes", alice)._2)
      forbidden must contain(
        apikeyCall("POST", "/apis/proxy.otoroshi.io/v1/backends", alice, backend("alice-backend-3").some)._2
      )
    }

    "shutdown" in {
      stopAll()
    }
  }
}
