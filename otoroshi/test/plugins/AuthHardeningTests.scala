package plugins

import functional.{PluginsTestSpecBase, TargetService}
import otoroshi.auth.WebAuthnSupport
import otoroshi.models.*
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgTarget}
import otoroshi.next.plugins.{SimpleBasicAuth, SimpleBasicAuthConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import play.api.http.Status
import play.api.libs.crypto.CookieSignerProvider
import play.api.libs.json.*
import play.api.libs.ws.WSBodyWritables.writeableOf_String
import play.api.libs.ws.WSResponse
import play.api.mvc.{DefaultSessionCookieBaker, Session}

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.concurrent.duration.DurationInt

// auth modules, backoffice logins and backoffice tooling
class AuthHardeningTests(parent: PluginsTestSpecBase) {
  import parent.*

  val backend = TargetService
    .full(None, "/", "application/json", _ => (200, """{"ok":true}""", List.empty))
    .await()

  private val sessionCookieBaker = new DefaultSessionCookieBaker(
    env.httpConfiguration.session,
    env.httpConfiguration.secret,
    new CookieSignerProvider(env.httpConfiguration.secret).get
  )

  private def backofficeUser(id: String, rights: UserRights): BackOfficeUser = {
    val user = BackOfficeUser(
      randomId = id,
      name = id,
      email = s"$id@otoroshi.io",
      profile = Json.obj(),
      authConfigId = "basic",
      simpleLogin = true,
      tags = Seq.empty,
      metadata = Map.empty,
      rights = rights,
      adminEntityValidators = Map()
    )
    user.save(5.minutes)(using env.otoroshiExecutionContext, env).futureValue
    user
  }

  private def backoffice(user: BackOfficeUser, method: String, path: String): WSResponse = {
    val cookie = sessionCookieBaker.encodeAsCookie(Session(Map("bousr" -> user.randomId)))
    ws.url(s"http://127.0.0.1:$port$path")
      .withFollowRedirects(false)
      .withHttpHeaders("Host" -> env.backOfficeHost, "Cookie" -> s"${cookie.name}=${cookie.value}")
      .withMethod(method)
      .execute()
      .futureValue
  }

  // a saml assertion was accepted whatever its validity window, its audience, its recipient and the request it answers
  def samlConditions(): Unit = {
    import org.opensaml.saml.saml2.core.*
    org.opensaml.core.config.InitializationService.initialize()
    def build[T](qname: javax.xml.namespace.QName): T = otoroshi.auth.SAMLModule.buildObject(qname).asInstanceOf[T]
    val now                                           = java.time.Instant.now()
    def response(
        audience: String = "otoroshi-sp",
        notOnOrAfter: java.time.Instant = now.plusSeconds(300),
        recipient: String = "https://privateapps.oto.tools/privateapps/generic/callback",
        inResponseTo: Option[String] = Some("zrequest")
    ): Response = {
      val audienceElement  = build[Audience](Audience.DEFAULT_ELEMENT_NAME)
      audienceElement.setURI(audience)
      val restriction      = build[AudienceRestriction](AudienceRestriction.DEFAULT_ELEMENT_NAME)
      restriction.getAudiences.add(audienceElement)
      val conditions       = build[Conditions](Conditions.DEFAULT_ELEMENT_NAME)
      conditions.setNotBefore(now.minusSeconds(10))
      conditions.setNotOnOrAfter(notOnOrAfter)
      conditions.getAudienceRestrictions.add(restriction)
      val confirmationData = build[SubjectConfirmationData](SubjectConfirmationData.DEFAULT_ELEMENT_NAME)
      confirmationData.setRecipient(recipient)
      confirmationData.setNotOnOrAfter(notOnOrAfter)
      inResponseTo.foreach(confirmationData.setInResponseTo)
      val confirmation     = build[SubjectConfirmation](SubjectConfirmation.DEFAULT_ELEMENT_NAME)
      confirmation.setMethod(SubjectConfirmation.METHOD_BEARER)
      confirmation.setSubjectConfirmationData(confirmationData)
      val subject          = build[Subject](Subject.DEFAULT_ELEMENT_NAME)
      subject.getSubjectConfirmations.add(confirmation)
      val assertion        = build[Assertion](Assertion.DEFAULT_ELEMENT_NAME)
      assertion.setConditions(conditions)
      assertion.setSubject(subject)
      val result           = build[Response](Response.DEFAULT_ELEMENT_NAME)
      inResponseTo.foreach(result.setInResponseTo)
      result.getAssertions.add(assertion)
      result
    }
    def check(r: Response, requestId: Option[String] = Some("zrequest")) = otoroshi.auth.ValidatorUtils
      .validateConditions(r, "otoroshi-sp", Some("http://privateapps.oto.tools:8080/privateapps/generic/callback"), requestId, 60L)
    withClue("a valid assertion ") { check(response()).isRight mustBe true }
    withClue("an idp initiated assertion ") {
      check(response(inResponseTo = None), requestId = None).isRight mustBe true
    }
    withClue("an expired assertion ") { check(response(notOnOrAfter = now.minusSeconds(120))).isLeft mustBe true }
    withClue("an assertion for another service provider ") { check(response(audience = "another-sp")).isLeft mustBe true }
    withClue("an assertion for another recipient ") {
      check(response(recipient = "https://another-sp.oto.tools/callback")).isLeft mustBe true
    }
    withClue("an answer to another request ") { check(response(inResponseTo = Some("zanother"))).isLeft mustBe true }
    withClue("an answer to a request without relay state ") {
      check(response(), requestId = None).isLeft mustBe true
    }
  }

  // the origin of a webauthn ceremony comes from the browser, any page sharing the rp id could relay a ceremony
  def webAuthnOrigin(): Unit = {
    WebAuthnSupport.originAllowed(s"http://${env.backOfficeHost}:9999")(using env) mustBe true
    WebAuthnSupport.originAllowed(s"https://${env.privateAppsHost}")(using env) mustBe true
    WebAuthnSupport.originAllowed("https://another-page.oto.tools")(using env) mustBe false
    WebAuthnSupport.originAllowed("not an origin")(using env) mustBe false
  }

  // password checks used to be unlimited
  def passwordAttempts(): Unit = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[SimpleBasicAuth],
          config = NgPluginInstanceConfig(SimpleBasicAuthConfig(users = Map("john" -> "the-password")).json.as[JsObject])
        )
      ),
      target = Some(NgTarget(id = "auth-hardening-backend", hostname = "127.0.0.1", port = backend.port, tls = false))
    ).futureValue
    def basicAuth(password: String, address: String): Int = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders(
        "Host"            -> route.frontend.domains.head.domain,
        "X-Forwarded-For" -> address,
        "Authorization"   -> ("Basic " + Base64.getEncoder.encodeToString(
          s"john:$password".getBytes(StandardCharsets.UTF_8)
        ))
      )
      .get()
      .futureValue
      .status
    (1 to 10).foreach { _ => basicAuth("wrong-password", "10.0.0.1") mustBe Status.UNAUTHORIZED }
    withClue("the right password after 10 failures from that address ") {
      basicAuth("the-password", "10.0.0.1") mustBe Status.TOO_MANY_REQUESTS
    }
    withClue("the right password from another address ") { basicAuth("the-password", "10.0.0.2") mustBe Status.OK }
    def login(address: String): Int = ws
      .url(s"http://127.0.0.1:$port/bo/simple/login")
      .withHttpHeaders(
        "Host"            -> env.backOfficeHost,
        "Content-Type"    -> "application/json",
        "X-Forwarded-For" -> address
      )
      .post(Json.stringify(Json.obj("username" -> "nobody@otoroshi.io", "password" -> "wrong-password")))
      .futureValue
      .status
    (1 to 10).foreach { _ => login("10.0.0.3") mustBe Status.UNAUTHORIZED }
    withClue("a backoffice login after 10 failures from that address ") {
      login("10.0.0.3") mustBe Status.TOO_MANY_REQUESTS
    }
    deleteOtoroshiRoute(route).futureValue
  }

  // the metrics of every route of every tenant used to be served to any backoffice user
  def backofficeMetrics(): Unit = {
    val teamUser = backofficeUser(
      "auth-hardening-team",
      UserRights(Seq(UserRight(TenantAccess("default"), Seq(TeamAccess("default")))))
    )
    val admin    = backofficeUser("auth-hardening-admin", UserRights.superAdmin)
    withClue("a team user ") { backoffice(teamUser, "GET", "/bo/api/metrics").status mustBe Status.FORBIDDEN }
    withClue("a super admin ") { backoffice(admin, "GET", "/bo/api/metrics").status mustBe Status.OK }
  }

  // the tooling that calls a url given by the user, and returns its response, used to be open to read only users
  def backofficeTooling(): Unit = {
    val readOnly = backofficeUser(
      "auth-hardening-readonly",
      UserRights(
        Seq(
          UserRight(
            TenantAccess("default", canRead = true, canWrite = false),
            Seq(TeamAccess("default", canRead = true, canWrite = false))
          )
        )
      )
    )
    val writer   = backofficeUser(
      "auth-hardening-writer",
      UserRights(Seq(UserRight(TenantAccess("default"), Seq(TeamAccess("default")))))
    )
    val url      = s"/bo/api/graphqlproxy?url=http://127.0.0.1:${backend.port}/schema"
    withClue("a read only user ") { backoffice(readOnly, "GET", url).status mustBe Status.FORBIDDEN }
    withClue("a writer ") { backoffice(writer, "GET", url).status mustBe Status.OK }
  }

  // the ref of a multi auth login used to select any auth module, not only one of the route
  def multiAuthRef(): Unit = {
    def module(id: String): otoroshi.auth.BasicAuthModuleConfig = otoroshi.auth.BasicAuthModuleConfig(
      id = id,
      name = id,
      desc = id,
      users = Seq.empty,
      clientSideSessionEnabled = false,
      userValidators = Seq.empty,
      remoteValidators = Seq.empty,
      tags = Seq.empty,
      metadata = Map.empty,
      sessionCookieValues = otoroshi.auth.SessionCookieValues(httpOnly = true, secure = false),
      location = EntityLocation(),
      allowedUsers = Seq.empty,
      deniedUsers = Seq.empty
    )
    val listed  = module("auth-hardening-listed-module")
    val foreign = module("auth-hardening-foreign-module")
    createAuthModule(listed).futureValue
    createAuthModule(foreign).futureValue
    val route   = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[otoroshi.next.plugins.MultiAuthModule],
          config = NgPluginInstanceConfig(
            otoroshi.next.plugins.NgMultiAuthModuleConfig(modules = Seq(listed.id)).json.as[JsObject]
          )
        )
      ),
      target = Some(NgTarget(id = "auth-hardening-backend", hostname = "127.0.0.1", port = backend.port, tls = false))
    ).futureValue
    await(1.second)
    def login(ref: String): Int = ws
      .url(s"http://127.0.0.1:$port/privateapps/generic/login")
      .withFollowRedirects(false)
      .withHttpHeaders("Host" -> env.privateAppsHost)
      .withQueryStringParameters("route" -> route.id, "ref" -> ref)
      .get()
      .futureValue
      .status
    withClue("a module of the route ") { login(listed.id) must not be Status.INTERNAL_SERVER_ERROR }
    withClue("a module of another route ") { login(foreign.id) mustBe Status.INTERNAL_SERVER_ERROR }
    deleteOtoroshiRoute(route).futureValue
    deleteAuthModule(listed).futureValue
    deleteAuthModule(foreign).futureValue
  }

  // the plugin datastore of wasm plugins takes key patterns: some datastores turn them into regexes
  def wasmPluginDatastorePatterns(): Unit = {
    otoroshi.wasm.DataStore.isSafePattern("my-key:*") mustBe true
    otoroshi.wasm.DataStore.isSafePattern("*|otoroshi:*") mustBe false
    otoroshi.wasm.DataStore.isSafePattern("(.*)") mustBe false
  }

  def stop(): Unit = backend.stop()
}
