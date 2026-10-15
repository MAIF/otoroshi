package plugins

import functional.{PluginsTestSpecBase, TargetService}
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

  def stop(): Unit = backend.stop()
}
