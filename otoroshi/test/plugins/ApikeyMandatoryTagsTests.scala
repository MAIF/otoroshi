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
  NgApikeyMandatoryTags,
  NgApikeyMandatoryTagsConfig,
  OverrideHost
}
import otoroshi.security.IdGenerator
import play.api.http.Status
import play.api.libs.json.JsObject

class ApikeyMandatoryTagsTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
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
        plugin = NgPluginHelper.pluginId[NgApikeyMandatoryTags],
        config = NgPluginInstanceConfig(
          NgApikeyMandatoryTagsConfig(
            tags = Seq("foo", "bar")
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
    tags = Seq("bar", "foo", "baz")
  )

  val invalidApikey = ApiKey(
    clientId = IdGenerator.token(16),
    clientSecret = IdGenerator.token(64),
    clientName = "apikey1",
    authorizedEntities = Seq.empty,
    tags = Seq("baz")
  )

  createOtoroshiApiKey(apikey).futureValue
  createOtoroshiApiKey(invalidApikey).futureValue

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

  val invalidCall = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host"                   -> route.frontend.domains.head.domain,
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
