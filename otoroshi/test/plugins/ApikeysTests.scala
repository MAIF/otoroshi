package plugins

import functional.PluginsTestSpec
import otoroshi.models.{ApiKey, RouteIdentifier}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{
  AllowHttpMethods,
  ApikeyCalls,
  NgAllowedMethodsConfig,
  NgApikeyCallsConfig,
  NgApikeyExtractorCustomHeaders,
  NgApikeyExtractors,
  OverrideHost
}
import otoroshi.security.IdGenerator
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class ApikeysTests(parent: PluginsTestSpec) {
  import parent._

  def default() = {
    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ApikeyCalls],
          config = NgPluginInstanceConfig(
            NgApikeyCallsConfig(
            ).json.as[JsObject]
          )
        )
      )
    )

    createPluginsRouteApiKeys()

    val unknownCaller = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> PLUGINS_HOST
      )
      .get()
      .futureValue

    unknownCaller.status mustBe 400

    val authorizedCall = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host"                   -> PLUGINS_HOST,
        "Otoroshi-Client-Id"     -> getValidApiKeyForPluginsRoute.clientId,
        "Otoroshi-Client-Secret" -> getValidApiKeyForPluginsRoute.clientSecret
      )
      .get()
      .futureValue

    authorizedCall.status mustBe Status.OK

    deletePluginsRouteApiKeys()
    deleteOtoroshiRoute(route).futureValue
  }

  def passApikeyToBackend() = {
    val id    = IdGenerator.uuid
    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ApikeyCalls],
          config = NgPluginInstanceConfig(
            NgApikeyCallsConfig(
              wipeBackendRequest = false
            ).json.as[JsObject]
          )
        )
      ),
      domain = s"$id.oto.tools",
      id
    )

    val apikey = ApiKey(
      clientId = "apikey-test",
      clientSecret = "1234",
      clientName = "apikey-test",
      authorizedEntities = Seq(RouteIdentifier(PLUGINS_ROUTE_ID))
    )
    createOtoroshiApiKey(apikey).futureValue

    val authorizedCall = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host"                   -> route.frontend.domains.head.domain,
        "Otoroshi-Client-Id"     -> apikey.clientId,
        "Otoroshi-Client-Secret" -> apikey.clientSecret
      )
      .get()
      .futureValue

    authorizedCall.status mustBe Status.OK
    getInHeader(authorizedCall, "otoroshi-client-id").isDefined mustBe true
    getInHeader(authorizedCall, "otoroshi-client-secret").isDefined mustBe true

    deleteOtoroshiApiKey(apikey).futureValue
    deleteOtoroshiRoute(route).futureValue
  }

  def passApikeyToBackendWithCustomHeaders() = {
    val id    = IdGenerator.uuid
    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ApikeyCalls],
          config = NgPluginInstanceConfig(
            NgApikeyCallsConfig(
              wipeBackendRequest = false,
              extractors = NgApikeyExtractors(
                customHeaders = NgApikeyExtractorCustomHeaders(
                  enabled = true,
                  clientIdHeaderName = Some("id"),
                  clientSecretHeaderName = Some("secret")
                )
              )
            ).json.as[JsObject]
          )
        )
      ),
      domain = s"$id.oto.tools",
      id
    )

    val apikey = ApiKey(
      clientId = "apikey-test",
      clientSecret = "1234",
      clientName = "apikey-test",
      authorizedEntities = Seq(RouteIdentifier(PLUGINS_ROUTE_ID))
    )
    createOtoroshiApiKey(apikey).futureValue

    {
      val call = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"                   -> route.frontend.domains.head.domain,
          "Otoroshi-Client-Id"     -> apikey.clientId,
          "Otoroshi-Client-Secret" -> apikey.clientSecret
        )
        .get()
        .futureValue

      call.status mustBe Status.BAD_REQUEST
    }
    val authorizedCall = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host"   -> route.frontend.domains.head.domain,
        "id"     -> apikey.clientId,
        "secret" -> apikey.clientSecret
      )
      .get()
      .futureValue

    authorizedCall.status mustBe Status.OK
    getInHeader(authorizedCall, "id").isDefined mustBe true
    getInHeader(authorizedCall, "secret").isDefined mustBe true

    deleteOtoroshiApiKey(apikey).futureValue
    deleteOtoroshiRoute(route).futureValue
  }
}
