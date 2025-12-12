package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{EchoBackend, EchoBackendConfig, OverrideHost}
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class RequestEchoTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[EchoBackend],
        config = NgPluginInstanceConfig(
          EchoBackendConfig(
            limit = 12
          ).json.as[JsObject]
        )
      )
    )
  ).futureValue

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .post(Json.obj("f" -> "b"))
      .futureValue

    resp.status mustBe Status.OK
  }

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .post(Json.obj("foo" -> "bar"))
      .futureValue

    resp.status mustBe Status.REQUEST_ENTITY_TOO_LARGE
  }

  deleteOtoroshiRoute(route).futureValue
}
