package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{OverrideHost, StaticResponse, StaticResponseConfig}
import otoroshi.utils.syntax.implicits.BetterJsValue
import play.api.http.Status
import play.api.libs.json._

class StaticResponseTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[StaticResponse],
        config = NgPluginInstanceConfig(
          StaticResponseConfig(
            status = Status.OK,
            headers = Map("baz" -> "bar"),
            body = Json.obj("foo" -> "${req.headers.foo}").stringify,
            applyEl = true
          ).json.as[JsObject]
        )
      )
    )
  ).futureValue

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain,
      "foo"  -> "client value"
    )
    .get()
    .futureValue

  resp.status mustBe Status.OK
  getOutHeader(resp, "baz") mustBe Some("bar")
  Json.parse(resp.body) mustEqual Json.obj("foo" -> "client value")

  deleteOtoroshiRoute(route).futureValue
}
