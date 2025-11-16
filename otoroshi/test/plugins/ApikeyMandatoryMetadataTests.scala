package plugins

import functional.PluginsTestSpec
import otoroshi.models.ApiKey
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{
  ApikeyCalls,
  NgApikeyCallsConfig,
  NgApikeyMandatoryMetadata,
  NgApikeyMandatoryMetadataConfig,
  OverrideHost
}
import otoroshi.security.IdGenerator
import play.api.http.Status
import play.api.libs.json.JsObject

class ApikeyMandatoryMetadataTests(parent: PluginsTestSpec) {
  import parent._

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
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgApikeyMandatoryMetadata],
        config = NgPluginInstanceConfig(
          NgApikeyMandatoryMetadataConfig(
            metadata = Map(
              "foo" -> "bar",
              "baz" -> "foo2"
            )
          ).json
            .as[JsObject]
        )
      )
    )
  )

  val apikey = ApiKey(
    clientId = IdGenerator.token(16),
    clientSecret = IdGenerator.token(64),
    clientName = "apikey1",
    authorizedEntities = Seq.empty,
    metadata = Map("foo" -> "bar", "baz" -> "foo2")
  )

  val invalidApikey = ApiKey(
    clientId = IdGenerator.token(16),
    clientSecret = IdGenerator.token(64),
    clientName = "apikey1",
    authorizedEntities = Seq.empty,
    metadata = Map("foo" -> "bar")
  )

  createOtoroshiApiKey(apikey).futureValue
  createOtoroshiApiKey(invalidApikey).futureValue

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
      "Otoroshi-Client-Id"     -> apikey.clientId,
      "Otoroshi-Client-Secret" -> apikey.clientSecret
    )
    .get()
    .futureValue

  authorizedCall.status mustBe Status.OK

  val invalidCall = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host"                   -> PLUGINS_HOST,
      "Otoroshi-Client-Id"     -> invalidApikey.clientId,
      "Otoroshi-Client-Secret" -> invalidApikey.clientSecret
    )
    .get()
    .futureValue

  invalidCall.status mustBe Status.FORBIDDEN

  deleteOtoroshiApiKey(apikey).futureValue
  deleteOtoroshiApiKey(invalidApikey).futureValue
  deleteOtoroshiRoute(route).futureValue
}
