package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{NgHeaderNamesConfig, OverrideHost, RemoveHeadersIn}
import play.api.http.Status
import play.api.libs.json.JsObject

class RemoveHeadersInTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[RemoveHeadersIn],
        config = NgPluginInstanceConfig(
          NgHeaderNamesConfig(
            names = Seq("foo")
          ).json.as[JsObject]
        )
      )
    )
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> PLUGINS_HOST,
      "foo2" -> "client_value",
      "foo"  -> "bar"
    )
    .get()
    .futureValue

  resp.status mustBe Status.OK
  getInHeader(resp, "foo") mustBe None
  getInHeader(resp, "foo2") mustBe Some("client_value")

  deleteOtoroshiRoute(route).futureValue
}
