package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{ForceHttpsTraffic, OverrideHost}
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json._

class ForceHTTPsTrafficTests(parent: PluginsTestSpec) {
  import parent._

  val route = createLocalRoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[ForceHttpsTraffic]
      )
    ),
    result = _ => Json.obj(),
    rawDomain = "force.oto.tools".some,
    https = true
  ).futureValue

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withFollowRedirects(false)
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  resp.status mustBe Status.SEE_OTHER
  getOutHeader(resp, "Location") mustBe Some("https://force.oto.tools:8443/api")

  deleteOtoroshiRoute(route).futureValue
}
