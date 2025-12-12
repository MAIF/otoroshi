package plugins

import functional.PluginsTestSpec
import otoroshi.models.ApiKey
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins._
import otoroshi.security.IdGenerator
import otoroshi.utils.JsonPathValidator
import play.api.libs.json._

class ContextValidatorTests(parent: PluginsTestSpec) {

  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[ApikeyCalls],
        config = NgPluginInstanceConfig(
          NgApikeyCallsConfig().json.as[JsObject]
        )
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[ContextValidation],
        config = NgPluginInstanceConfig(
          ContextValidationConfig(
            validators = Seq(
              JsonPathValidator("$.apikey.metadata.foo", JsString("Contains(bar)")),
              JsonPathValidator("$.request.headers.foo", JsString("Contains(bar)"))
            )
          ).json
            .as[JsObject]
        )
      )
    ),
    id = IdGenerator.uuid
  ).futureValue

  val apikey = ApiKey(
    clientId = IdGenerator.token(16),
    clientSecret = IdGenerator.token(64),
    clientName = "apikey1",
    authorizedEntities = Seq.empty,
    metadata = Map("foo" -> "bar")
  )
  createOtoroshiApiKey(apikey).futureValue

  apikey.enabled mustBe true

  val resp = ws
    .url(s"http://127.0.0.1:$port")
    .withHttpHeaders(
      "Host"                   -> route.frontend.domains.head.domain,
      "Otoroshi-Client-Id"     -> apikey.clientId,
      "Otoroshi-Client-Secret" -> apikey.clientSecret,
      "foo"                    -> "bar"
    )
    .get()
    .futureValue

  resp.status mustBe 200

  val resp2 = ws
    .url(s"http://127.0.0.1:$port")
    .withHttpHeaders(
      "Host"                   -> route.frontend.domains.head.domain,
      "Otoroshi-Client-Id"     -> apikey.clientId,
      "Otoroshi-Client-Secret" -> apikey.clientSecret
    )
    .get()
    .futureValue

  resp2.status mustBe 403

  deleteOtoroshiApiKey(apikey).futureValue
  deleteOtoroshiRoute(route).futureValue
}
