package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{
  AdditionalCookieIn,
  AdditionalCookieInConfig,
  IpAddressBlockList,
  NgIpAddressesConfig,
  OverrideHost
}
import otoroshi.utils.syntax.implicits.BetterJsValue
import play.api.http.Status
import play.api.libs.json._

class IpAddressBlockListTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[IpAddressBlockList],
        config = NgPluginInstanceConfig(
          NgIpAddressesConfig(
            addresses = Seq("1.2.3.4")
          ).json.as[JsObject]
        )
      )
    )
  ).futureValue

  val unknownIP = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  unknownIP.status mustBe Status.OK

  val allowCall = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host"            -> route.frontend.domains.head.domain,
      "X-Forwarded-For" -> "1.2.3.4"
    )
    .get()
    .futureValue

  allowCall.status mustBe Status.FORBIDDEN

  deleteOtoroshiRoute(route).futureValue
}
