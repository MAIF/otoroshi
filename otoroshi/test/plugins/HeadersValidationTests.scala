package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{HeadersValidation, NgHeaderValuesConfig, OverrideHost}
import play.api.http.Status
import play.api.libs.json.JsObject

class HeadersValidationTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[HeadersValidation],
        config = NgPluginInstanceConfig(
          NgHeaderValuesConfig(
            headers = Map(
              "foo"        -> "${req.headers.bar}",
              "raw_header" -> "raw_value"
            )
          ).json.as[JsObject]
        )
      )
    )
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  resp.status mustBe 400

  val resp2 = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host"       -> route.frontend.domains.head.domain,
      "foo"        -> "bar",
      "bar"        -> "bar",
      "raw_header" -> "raw_value"
    )
    .get()
    .futureValue

  resp2.status mustBe Status.OK

  val resp3 = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host"      -> route.frontend.domains.head.domain,
      "foo"       -> "bar",
      "raw_value" -> "bar"
    )
    .get()
    .futureValue

  resp3.status mustBe 400

  deleteOtoroshiRoute(route).futureValue
}
