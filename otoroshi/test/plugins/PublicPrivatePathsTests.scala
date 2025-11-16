package plugins

import functional.PluginsTestSpec
import otoroshi.models._
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgRoute}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins._
import otoroshi.security.IdGenerator
import play.api.http.Status
import play.api.libs.json.JsObject

class PublicPrivatePathsTests(parent: PluginsTestSpec) {
  import parent._

  val strictRoute = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        NgPluginHelper.pluginId[ApikeyCalls],
        config = NgPluginInstanceConfig(
          NgApikeyCallsConfig(
            mandatory = false
          ).json.as[JsObject]
        )
      ),
      NgPluginInstance(
        NgPluginHelper.pluginId[PublicPrivatePaths],
        config = NgPluginInstanceConfig(
          NgPublicPrivatePathsConfig(
            strict = true,
            publicPatterns = Seq("/public"),
            privatePatterns = Seq("/private")
          ).json.as[JsObject]
        )
      )
    ),
    id = IdGenerator.uuid
  )

  val nonStrictRoute = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        NgPluginHelper.pluginId[ApikeyCalls],
        config = NgPluginInstanceConfig(
          NgApikeyCallsConfig(
            mandatory = false
          ).json.as[JsObject]
        )
      ),
      NgPluginInstance(
        NgPluginHelper.pluginId[PublicPrivatePaths],
        config = NgPluginInstanceConfig(
          NgPublicPrivatePathsConfig(
            strict = true,
            publicPatterns = Seq("/public"),
            privatePatterns = Seq("/private")
          ).json.as[JsObject]
        )
      )
    ),
    id = IdGenerator.uuid
  )

  val apikey = ApiKey(
    clientId = "apikey-test",
    clientSecret = "1234",
    clientName = "apikey-test",
    authorizedEntities = Seq(RouteIdentifier(strictRoute.id), RouteIdentifier(nonStrictRoute.id))
  )

  createOtoroshiApiKey(apikey).futureValue

  def call(route: NgRoute, path: String, addApikey: Boolean = false) = {
    if (addApikey) {
      ws
        .url(s"http://127.0.0.1:$port$path")
        .withHttpHeaders(
          "Host"                   -> route.frontend.domains.head.domain,
          "Otoroshi-Client-Id"     -> getValidApiKeyForPluginsRoute.clientId,
          "Otoroshi-Client-Secret" -> getValidApiKeyForPluginsRoute.clientSecret
        )
        .get()
        .futureValue
    } else {
      ws
        .url(s"http://127.0.0.1:$port$path")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain
        )
        .get()
        .futureValue
    }
  }

  call(nonStrictRoute, "/public").status mustBe Status.OK
  call(nonStrictRoute, "/private").status mustBe Status.UNAUTHORIZED
  call(nonStrictRoute, "/private", addApikey = true).status mustBe Status.OK

  call(strictRoute, "/private", addApikey = true).status mustBe Status.OK
  call(strictRoute, "/private").status mustBe Status.UNAUTHORIZED

  deleteOtoroshiApiKey(apikey).futureValue
  deleteOtoroshiRoute(strictRoute).futureValue
  deleteOtoroshiRoute(nonStrictRoute).futureValue
}
