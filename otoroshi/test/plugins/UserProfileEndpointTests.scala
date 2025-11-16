package plugins

import com.microsoft.playwright._
import com.microsoft.playwright.options.AriaRole
import functional.PluginsTestSpec
import otoroshi.auth.{BasicAuthModuleConfig, BasicAuthUser, SessionCookieValues}
import otoroshi.models._
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.{BetterJsValueReader, BetterSyntax}
import play.api.libs.json._
import play.api.libs.ws.DefaultWSCookie

import scala.jdk.CollectionConverters.asScalaBufferConverter

class UserProfileEndpointTests(parent: PluginsTestSpec) {

  import parent._

  val moduleConfiguration = BasicAuthModuleConfig(
    id = "BasicAuthModuleConfig",
    name = "BasicAuthModuleConfig",
    desc = "BasicAuthModuleConfig",
    users = Seq(
      BasicAuthUser(
        name = "foo",
        password = "$2a$10$RtYWagxgvorxpxNIYTi4Be2tU.n8294eHpwle1ad0Tmh7.NiVXOEq",
        email = "user@oto.tools",
        tags = Seq.empty,
        rights = UserRights(rights =
          Seq(
            UserRight(
              tenant = TenantAccess("*", canRead = true, canWrite = true),
              teams = Seq(TeamAccess("*", canRead = true, canWrite = true))
            )
          )
        ),
        adminEntityValidators = Map.empty
      )
    ),
    clientSideSessionEnabled = false,
    userValidators = Seq.empty,
    remoteValidators = Seq.empty,
    tags = Seq.empty,
    metadata = Map.empty,
    sessionCookieValues = SessionCookieValues(httpOnly = true, secure = false),
    location = otoroshi.models.EntityLocation(),
    allowedUsers = Seq.empty,
    deniedUsers = Seq.empty
  )
  createAuthModule(moduleConfiguration).futureValue

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[AuthModule],
        config = NgPluginInstanceConfig(
          NgAuthModuleConfig(module = moduleConfiguration.id.some).json
            .as[JsObject]
        )
      ),
      NgPluginInstance(plugin = NgPluginHelper.pluginId[UserProfileEndpoint])
    ),
    id = IdGenerator.uuid
  )

  val playwright = Playwright.create()
  val browser    = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))
  val context    = browser.newContext()
  val page       = context.newPage()

  page.navigate(s"http://${route.frontend.domains.head.domain}:$port")

  page.locator("input[name='username']").click()
  page.locator("input[name='username']").fill("user@oto.tools")
  page.locator("input[name='password']").click()
  page.locator("input[name='password']").fill("password")
  page.fill("input[name='password']", "password")
  page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login")).nth(0).click()

  val wsCookies: Seq[DefaultWSCookie] = context.cookies.asScala.map { c =>
    DefaultWSCookie(
      name = c.name,
      value = c.value,
      domain = Option(c.domain),
      path = Option(c.path).getOrElse("/").some,
      secure = c.secure,
      httpOnly = c.httpOnly
    )
  }

  val callWithUser = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
    .withCookies(wsCookies: _*)
    .get()
    .futureValue

  println(callWithUser.body)

  callWithUser.status mustBe 200
  Json.parse(callWithUser.body).selectAsString("email").contains("user@oto.tools") mustBe true

  browser.close()
  playwright.close()

  deleteAuthModule(moduleConfiguration).futureValue
  deleteOtoroshiRoute(route).futureValue
}
