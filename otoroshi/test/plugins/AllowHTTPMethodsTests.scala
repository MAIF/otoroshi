package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{AllowHttpMethods, NgAllowedMethodsConfig, OverrideHost}
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class AllowHTTPMethodsTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[AllowHttpMethods],
        config = NgPluginInstanceConfig(
          NgAllowedMethodsConfig(allowed = Seq("GET"), forbidden = Seq("POST")).json.as[JsObject]
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

  resp.status mustBe Status.OK

  val resp2 = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .post(Json.obj())
    .futureValue

  resp2.status mustBe 405

  deleteOtoroshiRoute(route).futureValue
}
