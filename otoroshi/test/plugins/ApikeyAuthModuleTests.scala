package plugins

import functional.PluginsTestSpecBase
import org.joda.time.DateTime
import otoroshi.models.{ApiKey, ApiKeyRouteMatcher, RouteIdentifier}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.*
import otoroshi.next.plugins.api.NgPluginHelper
import play.api.http.Status
import play.api.libs.json.JsObject
import play.api.libs.ws.WSAuthScheme

class ApikeyAuthModuleTests(parent: PluginsTestSpecBase) {
  import parent.*

  val route = createRouteWithExternalTarget(
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
  ).futureValue

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

  // the plugin used to authenticate on the secret alone, so these three carry a valid secret and the
  // right tags: only the checks the plugin was missing can turn them down
  val disabledApikey = ApiKey(
    clientName = "disabled",
    clientId = "disabled",
    clientSecret = "bar",
    authorizedEntities = Seq(RouteIdentifier(route.id)),
    enabled = false,
    tags = Seq("foo")
  )

  val expiredApikey = ApiKey(
    clientName = "expired",
    clientId = "expired",
    clientSecret = "bar",
    authorizedEntities = Seq(RouteIdentifier(route.id)),
    validUntil = Some(DateTime.now().minusDays(1)),
    tags = Seq("foo")
  )

  val unauthorizedApikey = ApiKey(
    clientName = "unauthorized",
    clientId = "unauthorized",
    clientSecret = "bar",
    authorizedEntities = Seq(RouteIdentifier("some-other-route")),
    tags = Seq("foo")
  )

  createOtoroshiApiKey(goodApikey).futureValue
  createOtoroshiApiKey(badApikey).futureValue
  createOtoroshiApiKey(apikeyWithBadTags).futureValue
  createOtoroshiApiKey(disabledApikey).futureValue
  createOtoroshiApiKey(expiredApikey).futureValue
  // not through the group endpoint: it forces group_default into authorizedEntities, which would make
  // the apikey authorized on the route through its group
  otoroshiApiCall("POST", "/api/apikeys", Some(unauthorizedApikey.toJson)).futureValue

  def callWith(apikey: ApiKey) = ws
    .url(s"http://127.0.0.1:$port/api")
    .withAuth(apikey.clientId, apikey.clientSecret, WSAuthScheme.BASIC)
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

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

  val disabledStatus     = callWith(disabledApikey).status
  val expiredStatus      = callWith(expiredApikey).status
  val unauthorizedStatus = callWith(unauthorizedApikey).status

  withClue(s"disabled=$disabledStatus expired=$expiredStatus unauthorized=$unauthorizedStatus ") {
    disabledStatus mustBe Status.UNAUTHORIZED
    expiredStatus mustBe Status.UNAUTHORIZED
    unauthorizedStatus mustBe Status.UNAUTHORIZED
  }

  deleteOtoroshiApiKey(goodApikey)
  deleteOtoroshiApiKey(badApikey)
  deleteOtoroshiApiKey(apikeyWithBadTags)
  deleteOtoroshiApiKey(disabledApikey)
  deleteOtoroshiApiKey(expiredApikey)
  otoroshiApiCall("DELETE", s"/api/apikeys/${unauthorizedApikey.clientId}").futureValue
  deleteOtoroshiRoute(route).futureValue
}
