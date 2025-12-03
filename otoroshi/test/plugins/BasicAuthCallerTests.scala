package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgRoute}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins._
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json._
import play.api.libs.ws.WSAuthScheme

class BasicAuthCallerTests(parent: PluginsTestSpec) {
  import parent._

  def checkProcess() = {
    def simpleBasicAuthRoute(): NgRoute = {
      createRouteWithExternalTarget(
        Seq(
          NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[SimpleBasicAuth],
            config = NgPluginInstanceConfig(
              SimpleBasicAuthConfig(
                users = Map("foo" -> "bar")
              ).json
                .as[JsObject]
            )
          )
        ),
        id = IdGenerator.uuid,
        domain = "basiauth.oto.tools".some
      )
    }

    def basicAuthCallerRoute(): NgRoute = {
      createRouteWithExternalTarget(
        Seq(
          NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[BasicAuthCaller],
            config = NgPluginInstanceConfig(
              BasicAuthCallerConfig(
                username = "foo".some,
                password = "bar".some
              ).json
                .as[JsObject]
            )
          )
        ),
        id = IdGenerator.uuid,
        domain = "basiauth.oto.tools".some
      )
    }

    def verify(simpleBasicAuthRoute: NgRoute): Unit = {
      val resp = ws
        .url(s"http://127.0.0.1:$port")
        .withHttpHeaders("Host" -> simpleBasicAuthRoute.frontend.domains.head.domain)
        .get()
        .futureValue

      resp.status mustBe 401

      val callWithUser = ws
        .url(s"http://127.0.0.1:$port")
        .withHttpHeaders("Host" -> simpleBasicAuthRoute.frontend.domains.head.domain)
        .withAuth("foo", "bar", WSAuthScheme.BASIC)
        .get()
        .futureValue

      callWithUser.status mustBe 200
    }

    val basicAuthRoute = simpleBasicAuthRoute()
    val callerRouter   = basicAuthCallerRoute()
    verify(basicAuthRoute)

    deleteOtoroshiRoute(basicAuthRoute).futureValue
    deleteOtoroshiRoute(callerRouter).futureValue
  }

  def checkValue() = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[BasicAuthCaller],
          config = NgPluginInstanceConfig(
            BasicAuthCallerConfig(
              username = "foo".some,
              password = "bar".some,
              headerName = "foo",
              headerValueFormat = "Foo %s"
            ).json.as[JsObject]
          )
        )
      )
    )

    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
    getInHeader(resp, "foo") mustBe Some("Foo Zm9vOmJhcg==")

    deleteOtoroshiRoute(route).futureValue
  }
}
