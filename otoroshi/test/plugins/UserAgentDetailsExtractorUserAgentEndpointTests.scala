package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{NgUserAgentExtractor, NgUserAgentInfoEndpoint, OverrideHost}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.http.Status
import play.api.libs.json.Json

class UserAgentDetailsExtractorUserAgentEndpointTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgUserAgentExtractor]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgUserAgentInfoEndpoint]
      )
    ),
    id = IdGenerator.uuid
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/.well-known/otoroshi/plugins/user-agent")
    .withHttpHeaders(
      "Host"       -> route.frontend.domains.head.domain,
      "User-Agent" -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:109.0) Gecko/20100101 Firefox/119.0"
    )
    .get()
    .futureValue

  resp.status mustBe Status.OK
  Json.parse(resp.body).selectAsString("browser") mustBe "Firefox"

  deleteOtoroshiRoute(route).futureValue
}
