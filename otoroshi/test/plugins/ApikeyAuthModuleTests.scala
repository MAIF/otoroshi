package plugins

import functional.PluginsTestSpec
import otoroshi.models.{ApiKey, ApiKeyRouteMatcher, RouteIdentifier}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import play.api.http.Status
import play.api.libs.json.JsObject
import play.api.libs.ws.WSAuthScheme

class ApikeyAuthModuleTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[ApikeyAuthModule],
        config = NgPluginInstanceConfig(
          ApikeyAuthModuleConfig(
            matcher = Some(
              ApiKeyRouteMatcher(
                oneTagIn = Seq("foo")
              )
            )
          ).json.as[JsObject]
        )
      )
    )
  )

  val goodApikey = ApiKey(
    clientName = "foo",
    clientId = "foo",
    clientSecret = "bar",
    authorizedEntities = Seq(RouteIdentifier(route.id)),
    tags = Seq("foo")
  )

  val badApikey = ApiKey(
    clientName = "foo",
    clientId = "foo",
    clientSecret = "baz",
    authorizedEntities = Seq(RouteIdentifier(route.id)),
    tags = Seq("foo")
  )

  val apikeyWithBadTags = ApiKey(
    clientName = "foo",
    clientId = "foo",
    clientSecret = "baz",
    authorizedEntities = Seq(RouteIdentifier(route.id)),
    tags = Seq("foo")
  )

  createOtoroshiApiKey(goodApikey).futureValue
  createOtoroshiApiKey(badApikey).futureValue
  createOtoroshiApiKey(apikeyWithBadTags).futureValue

  val unknownCaller = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  unknownCaller.status mustBe Status.UNAUTHORIZED

  {
    val authorizedCall = ws
      .url(s"http://127.0.0.1:$port/api")
      .withAuth(goodApikey.clientId, goodApikey.clientSecret, WSAuthScheme.BASIC)
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    authorizedCall.status mustBe Status.OK
  }

  {
    val call = ws
      .url(s"http://127.0.0.1:$port/api")
      .withAuth(badApikey.clientId, badApikey.clientSecret, WSAuthScheme.BASIC)
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    call.status mustBe Status.UNAUTHORIZED
  }

  {
    val call = ws
      .url(s"http://127.0.0.1:$port/api")
      .withAuth(apikeyWithBadTags.clientId, apikeyWithBadTags.clientSecret, WSAuthScheme.BASIC)
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    call.status mustBe Status.UNAUTHORIZED
  }

  deletePluginsRouteApiKeys(route.id)
  deleteOtoroshiApiKey(goodApikey)
  deleteOtoroshiApiKey(badApikey)
  deleteOtoroshiApiKey(apikeyWithBadTags)
  deleteOtoroshiRoute(route).futureValue
}
