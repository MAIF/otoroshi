package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{AllowHttpMethods, ApikeyCalls, NgAllowedMethodsConfig, NgApikeyCallsConfig, OverrideHost}
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class ApikeysTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[ApikeyCalls],
        config = NgPluginInstanceConfig(
          NgApikeyCallsConfig(
          ).json.as[JsObject]
        )
      )
    )
  )

  createPluginsRouteApiKeys()

  val unknownCaller = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> PLUGINS_HOST
    )
    .get()
    .futureValue

  unknownCaller.status mustBe 400

  val authorizedCall = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host"                   -> PLUGINS_HOST,
      "Otoroshi-Client-Id"     -> getValidApiKeyForPluginsRoute.clientId,
      "Otoroshi-Client-Secret" -> getValidApiKeyForPluginsRoute.clientSecret
    )
    .get()
    .futureValue

  authorizedCall.status mustBe Status.OK

  deletePluginsRouteApiKeys()
  deleteOtoroshiRoute(route).futureValue
}
