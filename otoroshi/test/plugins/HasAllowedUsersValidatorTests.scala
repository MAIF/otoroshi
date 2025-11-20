package plugins

import com.microsoft.playwright._
import com.microsoft.playwright.options.AriaRole
import functional.PluginsTestSpec
import otoroshi.auth.{BasicAuthModuleConfig, BasicAuthUser, SessionCookieValues}
import otoroshi.models._
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgRoute}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json._
import play.api.libs.ws.DefaultWSCookie

import scala.jdk.CollectionConverters.asScalaBufferConverter

class HasAllowedUsersValidatorTests(parent: PluginsTestSpec) {

  import parent._

  private def getUser(email: String, name: String, metadata: JsObject): BasicAuthUser = {
    BasicAuthUser(
      name,
      password = "$2a$10$RtYWagxgvorxpxNIYTi4Be2tU.n8294eHpwle1ad0Tmh7.NiVXOEq",
      email,
      metadata = metadata,
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
  }

  val playwright = Playwright.create()
  val browser    = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))

  private def login(user: BasicAuthUser, route: NgRoute): Seq[DefaultWSCookie] = {
    val context = browser.newContext()
    val page    = context.newPage()

    page.navigate(s"http://${route.frontend.domains.head.domain}:$port")

    page.locator("input[name='username']").click()
    page.locator("input[name='username']").fill(user.email)
    page.locator("input[name='password']").click()
    page.locator("input[name='password']").fill("password")
    page.fill("input[name='password']", "password")
    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login")).nth(0).click()

    context.cookies.asScala.map { c =>
      DefaultWSCookie(
        name = c.name,
        value = c.value,
        domain = Option(c.domain),
        path = Option(c.path).getOrElse("/").some,
        secure = c.secure,
        httpOnly = c.httpOnly
      )
    }
  }

  val user             = getUser("john@oto.tools", "john", Json.obj("foo" -> "bar", "bar" -> "baz"))
  val unauthorizedUser = getUser("unauthorized.user@oto.tools", "foo", Json.obj("foo" -> "bar"))

  val moduleConfiguration = BasicAuthModuleConfig(
    id = "BasicAuthModuleConfig",
    name = "BasicAuthModuleConfig",
    desc = "BasicAuthModuleConfig",
    users = Seq(
      user,
      unauthorizedUser
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

  private def getRoute(config: NgHasAllowedUsersValidatorConfig): NgRoute = {
    val id = IdGenerator.uuid
    createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[AuthModule],
          config = NgPluginInstanceConfig(
            NgAuthModuleConfig(module = moduleConfiguration.id.some).json
              .as[JsObject]
          )
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[NgHasAllowedUsersValidator],
          config = NgPluginInstanceConfig(config.json.as[JsObject])
        )
      ),
      domain = s"$id.oto.tools",
      id
    )
  }

  val acceptOnlyUsernames = getRoute(
    NgHasAllowedUsersValidatorConfig(
      usernames = Seq("john")
    )
  )

  val acceptOnlyEmails = getRoute(
    NgHasAllowedUsersValidatorConfig(
      emails = Seq("john@oto.tools")
    )
  )

  val acceptOnlyEmailDomains = getRoute(
    NgHasAllowedUsersValidatorConfig(
      emailDomains = Seq("oto.tools")
    )
  )

  val acceptOnlyMetadata = getRoute(
    NgHasAllowedUsersValidatorConfig(
      metadataMatch = Seq("foo"),
      metadataNotMatch = Seq("bar")
    )
  )

  val acceptEmptyMetadataMatch = getRoute(
    NgHasAllowedUsersValidatorConfig(
      metadataMatch = Seq.empty,
      metadataNotMatch = Seq("bar")
    )
  )

  val acceptOnlyProfile = getRoute(
    NgHasAllowedUsersValidatorConfig(
      profileMatch = Seq("email"),
      profileNotMatch = Seq("baz")
    )
  )

  private def call(user: BasicAuthUser, route: NgRoute, expected: Int) = {
    val cookies = login(user, route)
    val resp    = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .withCookies(cookies: _*)
      .get()
      .futureValue

    resp.status mustBe expected
  }

  call(user, acceptOnlyUsernames, Status.OK)
  call(unauthorizedUser, acceptOnlyUsernames, Status.FORBIDDEN)

  call(user, acceptOnlyEmails, Status.OK)
  call(unauthorizedUser, acceptOnlyEmails, Status.FORBIDDEN)

  call(user, acceptOnlyEmailDomains, Status.OK)
  call(unauthorizedUser, acceptOnlyEmailDomains, Status.OK)

  call(user, acceptOnlyMetadata, Status.FORBIDDEN)
  call(unauthorizedUser, acceptOnlyMetadata, Status.OK)

  call(user, acceptEmptyMetadataMatch, Status.FORBIDDEN)
  call(unauthorizedUser, acceptEmptyMetadataMatch, Status.OK)

  call(user, acceptOnlyProfile, Status.OK)
  call(unauthorizedUser, acceptOnlyProfile, Status.OK)

  browser.close()
  playwright.close()

  deleteAuthModule(moduleConfiguration).futureValue

  deleteOtoroshiRoute(acceptOnlyEmails).futureValue
  deleteOtoroshiRoute(acceptOnlyUsernames).futureValue
  deleteOtoroshiRoute(acceptOnlyEmailDomains).futureValue
  deleteOtoroshiRoute(acceptOnlyMetadata).futureValue
  deleteOtoroshiRoute(acceptEmptyMetadataMatch).futureValue
}
