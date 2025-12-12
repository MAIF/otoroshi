package plugins

import com.microsoft.playwright._
import com.microsoft.playwright.options.AriaRole
import functional.PluginsTestSpec
import org.apache.commons.codec.binary.{Base64 => ApacheBase64}
import otoroshi.auth.{BasicAuthModuleConfig, BasicAuthUser, SessionCookieValues}
import otoroshi.models._
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.{BetterJsValueReader, BetterSyntax}
import play.api.libs.json._
import play.api.libs.ws.DefaultWSCookie

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.asScalaBufferConverter

class NgAuthModuleUserExtractorTests(parent: PluginsTestSpec) {

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

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[AuthModule],
        config = NgPluginInstanceConfig(
          NgAuthModuleConfig(module = moduleConfiguration.id.some).json
            .as[JsObject]
        )
      )
    ),
    id = IdGenerator.uuid
  ).futureValue

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
  page.content().contains("GET") mustBe true

  val routeToCheck = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgAuthModuleUserExtractor],
        config = NgPluginInstanceConfig(
          NgAuthModuleUserExtractorConfig(module = moduleConfiguration.id.some).json
            .as[JsObject]
        )
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OtoroshiInfos],
        config = NgPluginInstanceConfig(
          NgOtoroshiInfoConfig
            .apply(
              secComVersion = SecComInfoTokenVersionLatest,
              secComTtl = 30.seconds,
              headerName = Some("foo"),
              addFields = None,
              projection = Json.obj(),
              algo = HSAlgoSettings(512, "secret", base64 = false)
            )
            .json
            .as[JsObject]
        )
      )
    ),
    id = IdGenerator.uuid
  ).futureValue

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
    .withHttpHeaders("Host" -> routeToCheck.frontend.domains.head.domain)
    .withCookies(wsCookies: _*)
    .get()
    .futureValue

  callWithUser.status mustBe 200
  val tokenBody = getInHeader(callWithUser, "foo").get.split("\\.")(1)
  Json.parse(ApacheBase64.decodeBase64(tokenBody)).as[JsObject].selectAsString("access_type") mustBe "user"

  val callWithoutCookies = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders("Host" -> routeToCheck.frontend.domains.head.domain)
    .get()
    .futureValue

  callWithoutCookies.status mustBe 200
  val public = getInHeader(callWithoutCookies, "foo").get.split("\\.")(1)
  Json.parse(ApacheBase64.decodeBase64(public)).as[JsObject].selectAsString("access_type") mustBe "public"

  browser.close()
  playwright.close()

  deleteAuthModule(moduleConfiguration).futureValue
  deleteOtoroshiRoute(route).futureValue
}
