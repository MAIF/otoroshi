package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{BuildMode, ForwardedHeader, OverrideHost}
import play.api.http.Status

class ForwardedHeadersTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[ForwardedHeader]
      )
    )
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withFollowRedirects(false)
    .withHttpHeaders(
      "X-Forwarded-For" -> "1.1.1.2",
      "Host"            -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  getInHeader(resp, "x-forwarded-proto") mustBe Some("https")
  getInHeader(resp, "x-forwarded-for").contains("1.1.1.2") mustBe false
  getInHeader(resp, "x-forwarded-port") mustBe Some("443")
  getInHeader(resp, "forwarded").isDefined mustBe true

  deleteOtoroshiRoute(route).futureValue
}
