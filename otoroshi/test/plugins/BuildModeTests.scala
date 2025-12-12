package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{BuildMode, OverrideHost}
import play.api.http.Status

class BuildModeTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[BuildMode]
      )
    )
  ).futureValue

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  resp.status mustBe Status.SERVICE_UNAVAILABLE
  resp.body.contains("Service under construction") mustBe true

  deleteOtoroshiRoute(route).futureValue
}
