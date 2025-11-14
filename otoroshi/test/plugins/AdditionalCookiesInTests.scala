package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{AdditionalCookieIn, AdditionalCookieInConfig, OverrideHost}
import otoroshi.utils.syntax.implicits.BetterJsValue
import play.api.http.Status
import play.api.libs.json._

class AdditionalCookiesInTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[AdditionalCookieIn],
        config = NgPluginInstanceConfig(
          AdditionalCookieInConfig(
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
  val cookies = Json
    .parse(resp.body)
    .as[JsValue]
    .select("cookies")
    .as[Map[String, String]]

  cookies.get("cookie") mustBe Some("value")

  deleteOtoroshiRoute(route).futureValue
}
