package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{OverrideHost, RejectHeaderConfig, RejectHeaderInTooLong}
import play.api.http.Status
import play.api.libs.json.JsObject

class RejectHeadersInTooLongTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[RejectHeaderInTooLong],
        config = NgPluginInstanceConfig(
          RejectHeaderConfig(
            value = 30
          ).json.as[JsObject]
        )
      )
    )
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> PLUGINS_HOST,
      "foo"  -> "bar",
      "baz"  -> "very very very very very very very very very long header value"
    )
    .get()
    .futureValue

  resp.status mustBe Status.OK
  getInHeader(resp, "foo") mustBe Some("bar")
  getInHeader(resp, "baz") mustBe None

  deleteOtoroshiRoute(route).futureValue
}
