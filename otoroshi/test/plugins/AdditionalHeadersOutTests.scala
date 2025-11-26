package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{AdditionalHeadersOut, NgHeaderValuesConfig, OverrideHost}
import play.api.http.Status
import play.api.libs.json.JsObject

class AdditionalHeadersOutTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[AdditionalHeadersOut],
        config = NgPluginInstanceConfig(
          NgHeaderValuesConfig(
            headers = Map("foo" -> "bar")
          ).json.as[JsObject]
        )
      )
    )
  )

  createPluginsRouteApiKeys(route.id)

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  resp.status mustBe Status.OK
  getOutHeader(resp, "foo") mustBe Some("bar")

  deletePluginsRouteApiKeys(route.id)
  deleteOtoroshiRoute(route).futureValue
}
