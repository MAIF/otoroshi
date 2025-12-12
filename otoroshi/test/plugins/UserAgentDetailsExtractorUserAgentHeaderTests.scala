package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{NgUserAgentExtractor, NgUserAgentInfoHeader, NgUserAgentInfoHeaderConfig, OverrideHost}
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class UserAgentDetailsExtractorUserAgentHeaderTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgUserAgentExtractor]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgUserAgentInfoHeader],
        config = NgPluginInstanceConfig(
          NgUserAgentInfoHeaderConfig(
            headerName = "foo"
          ).json.as[JsObject]
        )
      )
    )
  ).futureValue

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host"       -> route.frontend.domains.head.domain,
      "User-Agent" -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:109.0) Gecko/20100101 Firefox/119.0"
    )
    .get()
    .futureValue

  resp.status mustBe Status.OK
  getInHeader(resp, "foo").isDefined mustBe true
  getInHeader(resp, "foo").map(foo => Json.parse(foo).selectAsString("browser") mustBe "Firefox")

  deleteOtoroshiRoute(route).futureValue
}
