package plugins

import functional.PluginsTestSpec
import otoroshi.models.ApiKey
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{ApikeyCalls, BlockHttpTraffic, BlockHttpTrafficConfig, OverrideHost}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json._

import scala.concurrent.duration.DurationInt

class BlockNonHTTPsTrafficTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[ApikeyCalls]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[BlockHttpTraffic],
        config = NgPluginInstanceConfig(
          BlockHttpTrafficConfig(
            revokeApikeys = true,
            message = "you shall not pass".some,
            revokeUserSession = false
          ).json.as[JsObject]
        )
      )
    )
  )

  val apikey = ApiKey(
    clientId = IdGenerator.token(16),
    clientSecret = IdGenerator.token(64),
    clientName = "apikey1",
    authorizedEntities = Seq.empty
  )
  createOtoroshiApiKey(apikey).futureValue

  apikey.enabled mustBe true

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host"                   -> route.frontend.domains.head.domain,
      "Otoroshi-Client-Id"     -> apikey.clientId,
      "Otoroshi-Client-Secret" -> apikey.clientSecret
    )
    .get()
    .futureValue

  resp.status mustBe Status.UPGRADE_REQUIRED
  Json.parse(resp.body) mustBe Json.obj("message" -> "you shall not pass")

  awaitF(10.seconds).futureValue
  env.proxyState
    .apikey(apikey.clientId)
    .map(_.enabled mustBe false)

  deleteOtoroshiApiKey(apikey).futureValue
  deleteOtoroshiRoute(route).futureValue
}
