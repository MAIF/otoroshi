package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{NgErrorRewriter, NgErrorRewriterConfig, OverrideHost, ResponseStatusRange}
import play.api.http.Status
import play.api.libs.json.JsObject

class ErrorResponseRewriteTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgErrorRewriter],
        config = NgPluginInstanceConfig(
          NgErrorRewriterConfig(
            ranges = Seq(ResponseStatusRange(200, 299)),
            templates = Map(
              "default"          -> "custom response",
              "application/json" -> "custom json response"
            ),
            log = false,
            export = false
          ).json.as[JsObject]
        )
      )
    )
  )

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> PLUGINS_HOST
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
    resp.body mustEqual "custom response"
  }

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host"   -> PLUGINS_HOST,
        "Accept" -> "application/json"
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
    resp.body mustEqual "custom json response"
  }

  deleteOtoroshiRoute(route).futureValue
}
