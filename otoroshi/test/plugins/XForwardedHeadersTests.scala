package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{OverrideHost, XForwardedHeaders}

class XForwardedHeadersTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[XForwardedHeaders]
      )
    )
  ).futureValue

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withFollowRedirects(false)
    .withHttpHeaders(
      "X-Forwarded-For" -> "1.1.1.2",
      "Host"            -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  getInHeader(resp, "x-forwarded-host").isDefined mustBe true
  getInHeader(resp, "x-forwarded-for").isDefined mustBe true
  getInHeader(resp, "x-forwarded-proto").isDefined mustBe true
  getInHeader(resp, "x-forwarded-port").isDefined mustBe true
  getInHeader(resp, "forwarded").isDefined mustBe true

  deleteOtoroshiRoute(route).futureValue
}
