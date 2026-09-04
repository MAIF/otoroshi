package plugins

import functional.PluginsTestSpecBase
import otoroshi.models.{ApiKey, RouteIdentifier}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{ApikeyCalls, NgApikeyCallsConfig, OverrideHost}
import play.api.http.Status
import play.api.libs.json.JsObject

class OtoBearerAuthTests(parent: PluginsTestSpecBase) {
  import parent.*

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[ApikeyCalls],
        config = NgPluginInstanceConfig(NgApikeyCallsConfig().json.as[JsObject])
      )
    )
  ).futureValue

  val apikey = ApiKey(
    clientId = "oto-bearer-apikey",
    clientSecret = "the-real-secret",
    clientName = "oto bearer apikey",
    authorizedEntities = Seq(RouteIdentifier(route.id))
  )

  createOtoroshiApiKey(apikey).futureValue

  // the bearer is "otoapk_<clientId>_<hmac of the prefix with the secret>": knowing the clientId is
  // enough to build the shape, the signature is the only part that proves the secret is known
  val forgedBearer = s"otoapk_${apikey.clientId}_forged"

  def call(path: String, bearer: String) = ws
    .url(s"http://127.0.0.1:$port$path")
    .withHttpHeaders(
      "Host"          -> route.frontend.domains.head.domain,
      "Authorization" -> s"Bearer $bearer"
    )
    .get()
    .futureValue

  val forgedOnProxy  = call("/api", forgedBearer)
  val realOnProxy    = call("/api", apikey.toBearer())
  val forgedOnMe     = call("/.well-known/otoroshi/me", forgedBearer)
  val realOnMe       = call("/.well-known/otoroshi/me", apikey.toBearer())

  withClue(
    s"proxy: forged=${forgedOnProxy.status} real=${realOnProxy.status}, " +
      s"me: forged=${forgedOnMe.status} real=${realOnMe.status} "
  ) {
    forgedOnProxy.status mustBe Status.UNAUTHORIZED
    realOnProxy.status mustBe Status.OK
    forgedOnMe.status mustBe Status.UNAUTHORIZED
    realOnMe.status mustBe Status.OK
  }

  deleteOtoroshiApiKey(apikey)
  deleteOtoroshiRoute(route).futureValue
}
