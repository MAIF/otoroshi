package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{MissingHeadersIn, NgHeaderValuesConfig, OverrideHost}
import play.api.http.Status
import play.api.libs.json.JsObject

class MissingHeadersInTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[MissingHeadersIn],
        config = NgPluginInstanceConfig(
          NgHeaderValuesConfig(
            headers = Map(
              "foo"  -> "foo_value",
              "foo2" -> "foo2_value"
            )
          ).json.as[JsObject]
        )
      )
    )
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain,
      "foo2" -> "client_value"
    )
    .get()
    .futureValue

  resp.status mustBe Status.OK
  getInHeader(resp, "foo") mustBe Some("foo_value")
  getInHeader(resp, "foo2") mustBe Some("client_value")

  deleteOtoroshiRoute(route).futureValue
}
