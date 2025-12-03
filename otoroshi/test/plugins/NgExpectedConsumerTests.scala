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
import play.api.http.Status
import play.api.libs.json._
import play.api.libs.ws.DefaultWSCookie

import scala.jdk.CollectionConverters.asScalaBufferConverter

class NgExpectedConsumerTests(parent: PluginsTestSpec) {

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
        plugin = NgPluginHelper.pluginId[ApikeyCalls],
        include = Seq("/restricted"),
        config = NgPluginInstanceConfig(
          Json.obj(
            "mandatory"    -> false,
            "plugin_index" -> Json.obj(
              "match_route"       -> 0,
              "validate_access"   -> 1,
              "transform_request" -> 1
            )
          )
        )
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[AuthModule],
        include = Seq("/restricted"),
        config = NgPluginInstanceConfig(
          NgAuthModuleConfig(module = moduleConfiguration.id.some, passWithApikey = true).json
            .as[JsObject]
            .deepMerge(
              Json.obj(
                "plugin_index" -> Json.obj(
                  "validate_access" -> 2
                )
              )
            )
        )
      ),
      NgPluginInstance(plugin = NgPluginHelper.pluginId[NgExpectedConsumer])
    ),
    id = IdGenerator.uuid
  )

  val playwright = Playwright.create()
  val browser    = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))
  val context    = browser.newContext()
  val page       = context.newPage()

  page.navigate(s"http://${route.frontend.domains.head.domain}:$port/restricted")

  page.locator("input[name='username']").click()
  page.locator("input[name='username']").fill("user@oto.tools")
  page.locator("input[name='password']").click()
  page.locator("input[name='password']").fill("password")
  page.fill("input[name='password']", "password")
  page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login")).nth(0).click()

  page.content().contains("GET") mustBe true

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
    .url(s"http://127.0.0.1:$port/restricted")
    .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
    .withCookies(wsCookies: _*)
    .get()
    .futureValue

  callWithUser.status mustBe 200

  val apikey = ApiKey(
    clientId = "apikey-test",
    clientSecret = "1234",
    clientName = "apikey-test",
    authorizedEntities = Seq(RouteIdentifier(route.id))
  )

  createOtoroshiApiKey(apikey).futureValue

  val callWithApikey = ws
    .url(s"http://127.0.0.1:$port/restricted")
    .withHttpHeaders(
      "Host"                   -> route.frontend.domains.head.domain,
      "Otoroshi-Client-Id"     -> apikey.clientId,
      "Otoroshi-Client-Secret" -> apikey.clientSecret
    )
    .get()
    .futureValue

  callWithApikey.status mustBe 200

  val callWithoutApikey = ws
    .url(s"http://127.0.0.1:$port")
    .withFollowRedirects(false)
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  callWithoutApikey.status mustBe Status.UNAUTHORIZED

  browser.close()
  playwright.close()

  deleteOtoroshiApiKey(apikey).futureValue
  deleteAuthModule(moduleConfiguration).futureValue
  deleteOtoroshiRoute(route).futureValue

}
