package plugins

import functional.PluginsTestSpec
import otoroshi.models.{ApiKey, ApiKeyRouteMatcher, RemainingQuotas, RouteIdentifier}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import play.api.http.Status
import play.api.libs.json.{JsObject, JsValue}
import play.api.libs.ws.WSAuthScheme

class LegacyQuotasReportingTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[ApikeyAuthModule],
        config = NgPluginInstanceConfig(
          ApikeyAuthModuleConfig(
            matcher = Some(ApiKeyRouteMatcher(oneTagIn = Seq("legacyquota")))
          ).json.as[JsObject]
        )
      ),
      NgPluginInstance(plugin = NgPluginHelper.pluginId[ApikeyQuotas]),
      NgPluginInstance(plugin = NgPluginHelper.pluginId[SendOtoroshiHeadersBack])
    )
  ).futureValue

  val apikey = ApiKey(
    clientName = "legacy-quota",
    clientId = "legacy-quota",
    clientSecret = "secret",
    authorizedEntities = Seq(RouteIdentifier(route.id)),
    tags = Seq("legacyquota"),
    throttlingQuota = 100,
    dailyQuota = 100,
    monthlyQuota = 1000
  )

  apikey.throttlingStrategy mustBe None

  createOtoroshiApiKey(apikey).futureValue

  def adminQuotas(): JsValue =
    ws
      .url(s"http://localhost:$port/api/apikeys/${apikey.clientId}/quotas")
      .withHttpHeaders("Host" -> "otoroshi-api.oto.tools")
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .get()
      .futureValue
      .json

  def gatewayCall() =
    ws
      .url(s"http://127.0.0.1:$port/api")
      .withAuth(apikey.clientId, apikey.clientSecret, WSAuthScheme.BASIC)
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

  val before = adminQuotas()
  (before \ "authorizedCallsPerWindow").as[Long] mustBe 100L
  (before \ "authorizedCallsPerDay").as[Long] mustBe 100L
  (before \ "authorizedCallsPerMonth").as[Long] mustBe 1000L
  (before \ "authorizedCallsPerDay").as[Long] must not be RemainingQuotas.MaxValue
  (before \ "authorizedCallsPerMonth").as[Long] must not be RemainingQuotas.MaxValue
  (before \ "currentCallsPerDay").as[Long] mustBe 0L
  (before \ "remainingCallsPerDay").as[Long] mustBe 100L
  (before \ "remainingCallsPerMonth").as[Long] mustBe 1000L

  gatewayCall().status mustBe Status.OK
  gatewayCall().status mustBe Status.OK

  val after = adminQuotas()
  (after \ "authorizedCallsPerDay").as[Long] mustBe 100L
  (after \ "authorizedCallsPerMonth").as[Long] mustBe 1000L
  (after \ "currentCallsPerDay").as[Long] mustBe 2L
  (after \ "remainingCallsPerDay").as[Long] mustBe 98L

  deleteOtoroshiApiKey(apikey).futureValue
  deleteOtoroshiRoute(route).futureValue
}
