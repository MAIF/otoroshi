package plugins

import functional.PluginsTestSpec
import otoroshi.models.{ApiKey, ApiKeyRouteMatcher, RouteIdentifier}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import play.api.http.Status
import play.api.libs.json.JsObject
import play.api.libs.ws.WSAuthScheme

class ApikeyQuotasTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[ApikeyAuthModule],
        config = NgPluginInstanceConfig(
          ApikeyAuthModuleConfig(
            matcher = Some(
              ApiKeyRouteMatcher(
                oneTagIn = Seq("foo")
              )
            )
          ).json.as[JsObject]
        )
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[ApikeyQuotas]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[SendOtoroshiHeadersBack]
      )
    )
  )

  val goodApikey = ApiKey(
    clientName = "foo",
    clientId = "foo",
    clientSecret = "bar",
    authorizedEntities = Seq(RouteIdentifier(route.id)),
    tags = Seq("foo"),
    throttlingQuota = 1,
    dailyQuota = 10
  )

  createOtoroshiApiKey(goodApikey).futureValue

  val authorizedCall = ws
    .url(s"http://127.0.0.1:$port/api")
    .withAuth(goodApikey.clientId, goodApikey.clientSecret, WSAuthScheme.BASIC)
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  authorizedCall.status mustBe Status.OK

  val call = ws
    .url(s"http://127.0.0.1:$port/api")
    .withAuth(goodApikey.clientId, goodApikey.clientSecret, WSAuthScheme.BASIC)
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  call.status mustBe Status.OK

  val dailyRemaining = call.header(env.Headers.OtoroshiDailyCallsRemaining).get
  dailyRemaining.toLong == 8 mustBe true

  deletePluginsRouteApiKeys(route.id)
  deleteOtoroshiApiKey(goodApikey)
  deleteOtoroshiRoute(route).futureValue
}
