package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.http.Status
import play.api.libs.json.{JsArray, Json}

class OtoroshiMetricsEndpointTests(parent: PluginsTestSpec) {

  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(plugin = NgPluginHelper.pluginId[OtoroshiMetricsEndpoint])
    )
  ).futureValue

  val resp = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
    .get()
    .futureValue

  resp.status mustBe Status.OK
  Json.parse(resp.body).isInstanceOf[JsArray] mustBe true

  deleteOtoroshiRoute(route).futureValue

}
