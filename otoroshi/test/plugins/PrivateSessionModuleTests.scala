package plugins

import functional.PluginsTestSpecBase
import otoroshi.auth.{BasicAuthModuleConfig, SessionCookieValues}
import otoroshi.models.{EntityLocation, PrivateAppsUser, PrivateAppsUserHelper}
import otoroshi.next.models.NgRoute
import otoroshi.security.IdGenerator
import play.api.libs.json.Json
import play.api.mvc.{Cookie, RequestHeader}
import play.api.test.FakeRequest

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

// a private apps session is only valid for the auth. module that created it
class PrivateSessionModuleTests(parent: PluginsTestSpecBase) {
  import parent.*

  private given ec: ExecutionContext = env.otoroshiExecutionContext

  private def module(id: String) = BasicAuthModuleConfig(
    id = id,
    name = id,
    desc = id,
    clientSideSessionEnabled = false,
    tags = Seq.empty,
    metadata = Map.empty,
    sessionCookieValues = SessionCookieValues()
  )

  private val moduleA    = module(s"auth_mod_${IdGenerator.uuid}")
  private val moduleB    = module(s"auth_mod_${IdGenerator.uuid}")
  private val descriptor = NgRoute.empty.legacy

  private val session = PrivateAppsUser(
    randomId = IdGenerator.token(64),
    name = "user",
    email = "user@oto.tools",
    profile = Json.obj(),
    realm = moduleA.cookieSuffix(descriptor),
    authConfigId = moduleA.id,
    otoroshiData = None,
    tags = Seq.empty,
    metadata = Map.empty,
    location = EntityLocation.default
  ).save(10.minutes).futureValue
  private val signed  = s"${env.sign(session.randomId)}::${session.randomId}"

  private def requests(cookieName: String): Seq[RequestHeader] = Seq(
    FakeRequest("GET", "/").withCookies(Cookie(cookieName, signed)),
    FakeRequest("GET", s"/?pappsToken=$signed"),
    FakeRequest("GET", "/").withHeaders("Otoroshi-Token" -> signed)
  )

  private def sessionFor(req: RequestHeader, auth: BasicAuthModuleConfig): Option[PrivateAppsUser] =
    PrivateAppsUserHelper.isPrivateAppsSessionValidWithAuth(req, descriptor, auth).futureValue

  requests(s"oto-papps-${moduleA.cookieSuffix(descriptor)}").foreach { req =>
    sessionFor(req, moduleA).map(_.randomId) mustBe Some(session.randomId)
  }
  requests(s"oto-papps-${moduleB.cookieSuffix(descriptor)}").foreach { req =>
    sessionFor(req, moduleB) mustBe None
  }

  session.delete().futureValue
}
