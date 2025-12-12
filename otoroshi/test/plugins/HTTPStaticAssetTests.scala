package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{OverrideHost, StaticAssetEndpoint, StaticAssetEndpointConfiguration}
import otoroshi.utils.syntax.implicits.{BetterJsValueReader, BetterSyntax}
import play.api.http.Status
import play.api.libs.json._

class HTTPStaticAssetTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[StaticAssetEndpoint],
        include = Seq("/api/assets/.*"),
        config = NgPluginInstanceConfig(
          StaticAssetEndpointConfiguration(
            url = Some(s"http://static-asset.oto.tools:$port")
          ).json.as[JsObject]
        )
      )
    )
  ).futureValue

  val staticAssetRoute = createLocalRoute(
    Seq(),
    rawDomain = "static-asset.oto.tools".some,
    result = _ => Json.obj("foo" -> "bar_from_child")
  ).futureValue

  val resp = ws
    .url(s"http://127.0.0.1:$port/api/assets/foo")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  resp.status mustBe Status.OK
  Json.parse(resp.body) mustEqual Json.obj("foo" -> "bar_from_child")

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api/")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
    Json.parse(resp.body).selectAsOptString("path").isDefined mustBe true
  }

  deleteOtoroshiRoute(staticAssetRoute).futureValue
  deleteOtoroshiRoute(route).futureValue
}
