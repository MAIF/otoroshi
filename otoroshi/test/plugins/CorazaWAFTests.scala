package plugins

import akka.stream.scaladsl.Sink
import akka.util.ByteString
import functional.PluginsTestSpec
import otoroshi.models.EntityLocation
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.OverrideHost
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.wasm.proxywasm.{CorazaWafConfig, NgCorazaWAF, NgCorazaWAFConfig}
import play.api.http.Status
import play.api.libs.json.JsObject

class CorazaWAFTests(parent: PluginsTestSpec) {

  import parent._

  val coraza    = CorazaWafConfig(
    location = EntityLocation.default,
    id = IdGenerator.uuid,
    name = "Coraza",
    description = "Coraza",
    tags = Seq.empty,
    metadata = Map.empty,
    inspectInputBody = true,
    inspectOutputBody = true,
    includeOwaspCRS = true,
    isBlockingMode = true,
    directives = Seq(
      "SecRule REQUEST_URI \"@streq /admin\" \"id:101,phase:1,t:lowercase,deny,msg:'ADMIN PATH forbidden'\""
    ),
    poolCapacity = 2
  )
  val wafResult = createOtoroshiWAF(coraza).futureValue

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgCorazaWAF],
        config = NgPluginInstanceConfig(
          NgCorazaWAFConfig(
            ref = coraza.id
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
    .stream()
    .futureValue

  resp.status mustBe 200

  val unauthorizedCall = ws
    .url(s"http://127.0.0.1:$port/admin")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .stream()
    .futureValue

  unauthorizedCall.status mustBe 403

  deleteOtoroshiWAF(coraza).futureValue
  deleteOtoroshiRoute(route).futureValue
}
