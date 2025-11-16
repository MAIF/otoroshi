package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{MissingHeadersOut, NgHeaderValuesConfig, OverrideHost}
import play.api.http.Status
import play.api.libs.json.JsObject

class MissingHeadersOutTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[MissingHeadersOut],
        config = NgPluginInstanceConfig(
          NgHeaderValuesConfig(
            headers = Map(
              "foo" -> "foo_value"
            )
          ).json.as[JsObject]
        )
      )
    )
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> PLUGINS_HOST,
      "foo2" -> "client_value"
    )
    .get()
    .futureValue

  resp.status mustBe Status.OK
  getOutHeader(resp, "foo") mustBe Some("foo_value")

  deleteOtoroshiRoute(route).futureValue
}
