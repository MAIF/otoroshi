package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{
  NgServiceQuotas,
  NgServiceQuotasConfig,
  OverrideHost,
  ZipFileBackend,
  ZipFileBackendConfig
}
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class NgServiceQuotasTests(parent: PluginsTestSpec) {

  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgServiceQuotas],
        config = NgPluginInstanceConfig(
          NgServiceQuotasConfig(
            throttlingQuota = 1,
            dailyQuota = 2,
            monthlyQuota = 2
          ).json.as[JsObject]
        )
      )
    )
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .stream()
    .futureValue

  resp.status mustBe Status.OK

  val notFoundFile = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .stream()
    .futureValue

  notFoundFile.status mustBe Status.FORBIDDEN

  deleteOtoroshiRoute(route).futureValue
}
