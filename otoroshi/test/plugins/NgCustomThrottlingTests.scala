package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{NgCustomThrottling, NgCustomThrottlingConfig, OverrideHost}
import play.api.http.Status
import play.api.libs.json.JsObject

class NgCustomThrottlingTests(parent: PluginsTestSpec) {

  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgCustomThrottling],
        config = NgPluginInstanceConfig(
          NgCustomThrottlingConfig(
            throttlingQuota = 1,
            perRoute = false,
            global = true,
            group = None,
            expression = "${req.headers.foo}"
          ).json.as[JsObject]
        )
      )
    )
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain,
      "foo"  -> "bar"
    )
    .stream()
    .futureValue

  resp.status mustBe Status.OK

  val notFoundFile = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain,
      "foo"  -> "bar"
    )
    .stream()
    .futureValue

  notFoundFile.status mustBe Status.FORBIDDEN

  deleteOtoroshiRoute(route).futureValue
}
