package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{HMACCaller, HMACCallerConfig, OverrideHost}
import otoroshi.plugins.hmac.HMACUtils
import otoroshi.utils.crypto.Signatures
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json._

import java.util.Base64

class HMACCallerPluginTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[HMACCaller],
        config = NgPluginInstanceConfig(
          HMACCallerConfig(
            secret = "secret".some,
            algo = "HMAC-SHA512",
            authorizationHeader = "foo".some
          ).json.as[JsObject]
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
  getInHeader(resp, "foo").get.contains(
    Base64.getEncoder
      .encodeToString(Signatures.hmac(HMACUtils.Algo("HMAC-SHA512"), getInHeader(resp, "date").get, "secret"))
  )

  deleteOtoroshiRoute(route).futureValue
}
