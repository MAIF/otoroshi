package plugins

import functional.PluginsTestSpec
import otoroshi.models.ApiKey
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{ApikeyCalls, ConsumerEndpoint, OverrideHost}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.http.Status
import play.api.libs.json._

class ConsumerEndpointWithoutApikeyTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[ConsumerEndpoint]
      )
    )
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> PLUGINS_HOST
    )
    .get()
    .futureValue

  resp.status mustBe Status.OK
  Json.parse(resp.body).selectAsString("access_type") mustEqual "public"

  deleteOtoroshiRoute(route).futureValue
}
