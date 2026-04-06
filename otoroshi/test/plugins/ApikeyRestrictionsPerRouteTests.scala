package plugins

import functional.PluginsTestSpec
import otoroshi.models.{ApiKey, Restrictions, RestrictionPath, RouteIdentifier}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{ApikeyCalls, NgApikeyCallsConfig, OverrideHost}
import otoroshi.security.IdGenerator
import play.api.http.Status
import play.api.libs.json.JsObject

class ApikeyRestrictionsPerRouteTests(parent: PluginsTestSpec) {
  import parent._

  def routeSpecificNotFoundRestriction(): Unit = {

    val routeId1 = IdGenerator.uuid
    val routeId2 = IdGenerator.uuid

    val route1 = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ApikeyCalls],
          config = NgPluginInstanceConfig(NgApikeyCallsConfig().json.as[JsObject])
        )
      ),
      id = routeId1,
      domain = Some(s"$routeId1.oto.tools")
    ).futureValue

    val route2 = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ApikeyCalls],
          config = NgPluginInstanceConfig(NgApikeyCallsConfig().json.as[JsObject])
        )
      ),
      id = routeId2,
      domain = Some(s"$routeId2.oto.tools")
    ).futureValue

    val apikey = ApiKey(
      clientId = s"client-${IdGenerator.uuid}",
      clientSecret = "1234",
      clientName = s"name-${IdGenerator.uuid}",
      authorizedEntities = Seq(RouteIdentifier(route1.id), RouteIdentifier(route2.id)),
      restrictions = Restrictions(
        enabled = true,
        allowLast = true,
        notFound = Seq(
          RestrictionPath(
            method = "GET",
            path = "/foo/bar/b",
            authorizedEntity = Some(RouteIdentifier(route2.id))
          )
        )
      )
    )

    createOtoroshiApiKey(apikey).futureValue

    // GET /foo/bar/a on route1 => 200
    val resp1a = ws
      .url(s"http://127.0.0.1:$port/foo/bar/a")
      .withHttpHeaders(
        "Host"                   -> route1.frontend.domains.head.domain,
        "Otoroshi-Client-Id"     -> apikey.clientId,
        "Otoroshi-Client-Secret" -> apikey.clientSecret
      )
      .get()
      .futureValue

    resp1a.status mustBe Status.OK

    // GET /foo/bar/a on route2 => 200
    val resp2a = ws
      .url(s"http://127.0.0.1:$port/foo/bar/a")
      .withHttpHeaders(
        "Host"                   -> route2.frontend.domains.head.domain,
        "Otoroshi-Client-Id"     -> apikey.clientId,
        "Otoroshi-Client-Secret" -> apikey.clientSecret
      )
      .get()
      .futureValue

    resp2a.status mustBe Status.OK

    // GET /foo/bar/b on route1 => 200 (restriction only targets route2)
    val resp1b = ws
      .url(s"http://127.0.0.1:$port/foo/bar/b")
      .withHttpHeaders(
        "Host"                   -> route1.frontend.domains.head.domain,
        "Otoroshi-Client-Id"     -> apikey.clientId,
        "Otoroshi-Client-Secret" -> apikey.clientSecret
      )
      .get()
      .futureValue

    resp1b.status mustBe Status.OK

    // GET /foo/bar/b on route2 => 404 (restriction targets this route)
    val resp2b = ws
      .url(s"http://127.0.0.1:$port/foo/bar/b")
      .withHttpHeaders(
        "Host"                   -> route2.frontend.domains.head.domain,
        "Otoroshi-Client-Id"     -> apikey.clientId,
        "Otoroshi-Client-Secret" -> apikey.clientSecret
      )
      .get()
      .futureValue

    resp2b.status mustBe Status.NOT_FOUND

    deleteOtoroshiApiKey(apikey).futureValue
    deleteOtoroshiRoute(route1).futureValue
    deleteOtoroshiRoute(route2).futureValue
  }
}
