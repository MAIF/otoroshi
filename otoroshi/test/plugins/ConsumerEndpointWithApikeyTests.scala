package plugins

import functional.PluginsTestSpec
import otoroshi.models.ApiKey
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{ApikeyCalls, ConsumerEndpoint, OverrideHost}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.http.Status
import play.api.libs.json._

class ConsumerEndpointWithApikeyTests(parent: PluginsTestSpec) {
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
        plugin = NgPluginHelper.pluginId[ConsumerEndpoint]
      )
    )
  )

  val apikey = ApiKey(
    clientId = IdGenerator.token(16),
    clientSecret = IdGenerator.token(64),
    clientName = "apikey1",
    authorizedEntities = Seq.empty,
    metadata = Map("foo" -> "bar"),
    tags = Seq("foo")
  )
  createOtoroshiApiKey(apikey).futureValue

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host"                   -> route.frontend.domains.head.domain,
      "Otoroshi-Client-Id"     -> apikey.clientId,
      "Otoroshi-Client-Secret" -> apikey.clientSecret
    )
    .get()
    .futureValue

  resp.status mustBe Status.OK

  Json.parse(resp.body).selectAsString("access_type") mustEqual "apikey"
  Json.parse(resp.body).selectAsString("clientId") mustEqual apikey.clientId
  Json.parse(resp.body).selectAsString("clientName") mustEqual apikey.clientName

  deleteOtoroshiApiKey(apikey).futureValue
  deleteOtoroshiRoute(route).futureValue
}
