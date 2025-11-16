package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{AdditionalCookieOut, AdditionalCookieOutConfig, OverrideHost}
import play.api.http.Status
import play.api.libs.json._

class AdditionalCookiesOutTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[AdditionalCookieOut],
        config = NgPluginInstanceConfig(
          AdditionalCookieOutConfig(
            name = "cookie",
            value = "value"
          ).json.as[JsObject]
        )
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
  resp.cookies.exists(_.name == "cookie") mustBe true
  resp.cookies.find(_.name == "cookie").map(_.value) mustBe Some("value")

  deleteOtoroshiRoute(route).futureValue
}
