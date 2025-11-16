package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins._
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json.JsObject

class RemoveCookiesOutTests(parent: PluginsTestSpec) {
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
            name = "foo",
            value = "bar",
            domain = PLUGINS_HOST.some
          ).json.as[JsObject]
        )
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[RemoveCookiesOut],
        config = NgPluginInstanceConfig(
          RemoveCookiesInConfig(
            names = Seq("foo")
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
  resp.cookies.isEmpty mustBe true

  deleteOtoroshiRoute(route).futureValue
}
