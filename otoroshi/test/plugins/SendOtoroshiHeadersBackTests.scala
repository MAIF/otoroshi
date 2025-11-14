package plugins

import functional.PluginsTestSpec
import otoroshi.models.{ApiKey, ApiKeyRotation, RouteIdentifier}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{
  AdditionalCookieIn,
  AdditionalCookieInConfig,
  ApikeyCalls,
  OverrideHost,
  SendOtoroshiHeadersBack
}
import otoroshi.utils.syntax.implicits.BetterJsValue
import play.api.http.Status
import play.api.libs.json._

class SendOtoroshiHeadersBackTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[ApikeyCalls]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[SendOtoroshiHeadersBack]
      )
    )
  )

  val apikey = ApiKey(
    clientId = "apikey-test",
    clientSecret = "1234",
    clientName = "apikey-test",
    authorizedEntities = Seq(RouteIdentifier(PLUGINS_ROUTE_ID)),
    rotation = ApiKeyRotation(enabled = true)
  )

  createOtoroshiApiKey(apikey).futureValue

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host"                   -> PLUGINS_HOST,
      "Otoroshi-Client-Id"     -> getValidApiKeyForPluginsRoute.clientId,
      "Otoroshi-Client-Secret" -> getValidApiKeyForPluginsRoute.clientSecret
    )
    .get()
    .futureValue

  val body = Json.parse(resp.body).as[JsValue]

  resp.header(env.Headers.OtoroshiRequestId) mustBe defined
  resp.header(env.Headers.OtoroshiRequestTimestamp) mustBe defined
  resp.header(env.Headers.OtoroshiProxyLatency) mustBe defined
  resp.header(env.Headers.OtoroshiUpstreamLatency) mustBe defined

  val requestId = resp.header(env.Headers.OtoroshiRequestId).get
  requestId.nonEmpty mustBe true

  val timestamp = resp.header(env.Headers.OtoroshiRequestTimestamp).get
  timestamp must include("T")

  val proxyLatency = resp.header(env.Headers.OtoroshiProxyLatency).get
  proxyLatency.toLong >= 0 mustBe true

  val upstreamLatency = resp.header(env.Headers.OtoroshiUpstreamLatency).get
  upstreamLatency.toLong >= -1 mustBe true

  resp.header(env.Headers.OtoroshiDailyCallsRemaining) mustBe defined
  resp.header(env.Headers.OtoroshiMonthlyCallsRemaining) mustBe defined

  val dailyRemaining = resp.header(env.Headers.OtoroshiDailyCallsRemaining).get
  dailyRemaining.toLong >= 0 mustBe true

  val monthlyRemaining = resp.header(env.Headers.OtoroshiMonthlyCallsRemaining).get
  monthlyRemaining.toLong >= 0 mustBe true

  resp.header("Otoroshi-ApiKey-Rotation-At") mustBe defined
  resp.header("Otoroshi-ApiKey-Rotation-Remaining") mustBe defined

  val rotationAt = resp.header("Otoroshi-ApiKey-Rotation-At").get
  rotationAt must include("T")

  val rotationRemaining = resp.header("Otoroshi-ApiKey-Rotation-Remaining").get
  rotationRemaining.toLong >= 0 mustBe true

  deleteOtoroshiApiKey(apikey).futureValue
  deleteOtoroshiRoute(route).futureValue
}
