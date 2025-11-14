package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{AdditionalCookieIn, AdditionalCookieInConfig, BasicAuthCaller, BasicAuthCallerConfig, OverrideHost}
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import play.api.http.Status
import play.api.libs.json._

class BasicAuthCallerTests(parent: PluginsTestSpec) {
  import parent._

  def checkValue() = {
    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[BasicAuthCaller],
          config = NgPluginInstanceConfig(
            BasicAuthCallerConfig(
              username = "foo".some,
              password = "bar".some,
              headerName = "foo",
              headerValueFormat = "Foo %s"
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
    getInHeader(resp, "foo") mustBe Some("Foo Zm9vOmJhcg==")

    deleteOtoroshiRoute(route).futureValue
  }
}
