package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{NgDeferPlugin, NgDeferPluginConfig, OverrideHost}
import otoroshi.security.IdGenerator
import play.api.http.Status
import play.api.libs.json._

import scala.concurrent.duration.DurationInt

class DeferResponsesTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgDeferPlugin],
        config = NgPluginInstanceConfig(
          NgDeferPluginConfig(
            duration = 2.seconds
          ).json.as[JsObject]
        )
      )
    ),
    id = IdGenerator.uuid
  )

  val lastStart = System.currentTimeMillis()

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  resp.status mustBe Status.OK
  System.currentTimeMillis() - lastStart > 1000 mustBe true

  deleteOtoroshiRoute(route).futureValue
}
