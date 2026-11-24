package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{OverrideHost, W3CTracing, W3CTracingConfig, W3CTracingConfigKind}
import play.api.http.Status
import play.api.libs.json.JsObject

class W3CTracingTests(parent: PluginsTestSpec) {
  import parent.*

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[W3CTracing],
        config = NgPluginInstanceConfig(
          W3CTracingConfig(kind = W3CTracingConfigKind.Noop).json.as[JsObject]
        )
      )
    )
  ).futureValue

  // requests without a traceparent header must each start a new trace (#2970): the trace id the
  // backend receives must never be one of a previous request, whatever thread served it
  val traceIds = (1 to 20).map { _ =>
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
    getInHeader(resp, "traceparent").map(_.split("-")(1))
  }

  traceIds.flatten.size mustBe 20
  traceIds.flatten.distinct.size mustBe 20

  deleteOtoroshiRoute(route).futureValue
}
