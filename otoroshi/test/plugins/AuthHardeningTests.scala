package plugins

import functional.{PluginsTestSpecBase, TargetService}
import otoroshi.auth.WebAuthnSupport
import otoroshi.models.*
import play.api.http.Status
import play.api.libs.crypto.CookieSignerProvider
import play.api.libs.json.*
import play.api.libs.ws.WSResponse
import play.api.mvc.{DefaultSessionCookieBaker, Session}

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

  def stop(): Unit = backend.stop()
}
