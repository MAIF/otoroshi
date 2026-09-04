package plugins

import functional.PluginsTestSpecBase
import otoroshi.models.{ApiKey, RouteIdentifier}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.OverrideHost
import otoroshi.next.plugins.wrappers.PreRoutingWrapper
import otoroshi.next.plugins.api.NgPluginHelper
import play.api.http.Status
import play.api.libs.json.Json
import play.api.libs.ws.WSAuthScheme

// the legacy pre-routing plugin is still reachable from the new engine through PreRoutingWrapper, so
// it gets the same coverage as its new counterpart
class LegacyApikeyAuthModuleTests(parent: PluginsTestSpecBase) {
  import parent.*

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[PreRoutingWrapper],
        config = NgPluginInstanceConfig(
          Json.obj(
            "plugin"            -> "cp:otoroshi.plugins.apikeys.ApikeyAuthModule",
            "ApikeyAuthModule" -> Json.obj("realm" -> "legacy-apikey-auth-module-realm")
          )
        )
      )
    )
  ).futureValue

  val goodApikey = ApiKey(
    clientName = "legacy-good",
    clientId = "legacy-good",
    clientSecret = "bar",
    authorizedEntities = Seq(RouteIdentifier(route.id))
  )

  val disabledApikey = ApiKey(
    clientName = "legacy-disabled",
    clientId = "legacy-disabled",
    clientSecret = "bar",
    authorizedEntities = Seq(RouteIdentifier(route.id)),
    enabled = false
  )

  val unauthorizedApikey = ApiKey(
    clientName = "legacy-unauthorized",
    clientId = "legacy-unauthorized",
    clientSecret = "bar",
    authorizedEntities = Seq(RouteIdentifier("some-other-route"))
  )

  createOtoroshiApiKey(goodApikey).futureValue
  createOtoroshiApiKey(disabledApikey).futureValue
  // not through the group endpoint: it forces group_default into authorizedEntities, which would make
  // the apikey authorized on the route through its group
  otoroshiApiCall("POST", "/api/apikeys", Some(unauthorizedApikey.toJson)).futureValue

  def callWith(clientId: String, secret: String) = ws
    .url(s"http://127.0.0.1:$port/api")
    .withAuth(clientId, secret, WSAuthScheme.BASIC)
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  val authorized    = callWith(goodApikey.clientId, goodApikey.clientSecret).status
  val wrongSecret   = callWith(goodApikey.clientId, "not-the-secret").status
  val disabled      = callWith(disabledApikey.clientId, disabledApikey.clientSecret).status
  val unauthorized  = callWith(unauthorizedApikey.clientId, unauthorizedApikey.clientSecret).status

  withClue(
    s"authorized=$authorized wrongSecret=$wrongSecret disabled=$disabled unauthorized=$unauthorized "
  ) {
    authorized mustBe Status.OK
    wrongSecret mustBe Status.UNAUTHORIZED
    disabled mustBe Status.UNAUTHORIZED
    unauthorized mustBe Status.UNAUTHORIZED
  }

  deleteOtoroshiApiKey(goodApikey)
  deleteOtoroshiApiKey(disabledApikey)
  otoroshiApiCall("DELETE", s"/api/apikeys/${unauthorizedApikey.clientId}").futureValue
  deleteOtoroshiRoute(route).futureValue
}
