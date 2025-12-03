package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import play.api.http.Status

class OtoroshiHeadersInTests(parent: PluginsTestSpec) {

  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OtoroshiHeadersIn]
      )
    )
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/api/users")
    .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
    .get()
    .futureValue

  resp.status mustBe Status.OK
  getInHeader(resp, env.Headers.OtoroshiProxiedHost.toLowerCase).isDefined mustBe true
  getInHeader(resp, env.Headers.OtoroshiRequestId.toLowerCase).isDefined mustBe true
  getInHeader(resp, env.Headers.OtoroshiRequestTimestamp.toLowerCase).isDefined mustBe true

  deleteOtoroshiRoute(route).futureValue
}
