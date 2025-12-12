package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgRoute}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.libs.json._
import play.api.libs.ws.WSAuthScheme

class SimpleBasicAuthTests(parent: PluginsTestSpec) {

  import parent._

  def simpleBasicAuthRoute() = {
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

  // def basicAuthCallerRoute(): NgRoute = {
  //   createRouteWithExternalTarget(
  //     Seq(
  //       NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
  //       NgPluginInstance(
  //         plugin = NgPluginHelper.pluginId[BasicAuthCaller],
  //         config = NgPluginInstanceConfig(
  //           BasicAuthCallerConfig(
  //             username = "foo".some,
  //             password = "bar".some
  //           ).json
  //             .as[JsObject]
  //         )
  //       )
  //     ),
  //     id = IdGenerator.uuid,
  //     domain = "basiauth.oto.tools".some
  //   )
  // }

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

  val basicAuthRoute = simpleBasicAuthRoute().futureValue
  verify(basicAuthRoute)

  deleteOtoroshiRoute(basicAuthRoute).futureValue
}
