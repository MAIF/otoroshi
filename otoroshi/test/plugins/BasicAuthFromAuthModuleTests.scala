package plugins

import functional.PluginsTestSpec
import otoroshi.auth.{BasicAuthModuleConfig, BasicAuthUser, SessionCookieValues}
import otoroshi.models.{TeamAccess, TenantAccess, UserRight, UserRights}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins._
import otoroshi.security.IdGenerator
import play.api.http.Status
import play.api.libs.json.JsObject

class BasicAuthFromAuthModuleTests(parent: PluginsTestSpec) {
  import parent._

  val authenticationModule = BasicAuthModuleConfig(
    id = IdGenerator.namedId("auth_mod", env),
    name = "New auth. module",
    desc = "New auth. module",
    tags = Seq.empty,
    metadata = Map.empty,
    sessionCookieValues = SessionCookieValues(),
    clientSideSessionEnabled = true,
    users = Seq(
      BasicAuthUser(
        name = "Stefanie Koss",
        password = "$2a$10$RtYWagxgvorxpxNIYTi4Be2tU.n8294eHpwle1ad0Tmh7.NiVXOEq",
        email = "user@oto.tools",
        tags = Seq.empty,
        rights = UserRights(
          Seq(
            UserRight(
              TenantAccess("*"),
              Seq(TeamAccess("*"))
            )
          )
        ),
        adminEntityValidators = Map()
      )
    )
  )

  createAuthModule(authenticationModule).futureValue

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[BasicAuthWithAuthModule],
        config = NgPluginInstanceConfig(
          BasicAuthWithAuthModuleConfig(
            ref = authenticationModule.id,
            addAuthenticateHeader = true
          ).json.as[JsObject]
        )
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[RemoveCookiesOut],
        config = NgPluginInstanceConfig(
          RemoveCookiesInConfig(
            names = Seq("foo")
          ).json.as[JsObject]
        )
      )
    )
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host"          -> PLUGINS_HOST,
      "Authorization" -> "Basic dXNlckBvdG8udG9vbHM6cGFzc3dvcmQ="
    )
    .get()
    .futureValue

  resp.status mustBe Status.OK
  resp.cookies.isEmpty mustBe true

  deleteAuthModule(authenticationModule).futureValue
  deleteOtoroshiRoute(route).futureValue
}
