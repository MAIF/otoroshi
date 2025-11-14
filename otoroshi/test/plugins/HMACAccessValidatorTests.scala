package plugins

import functional.PluginsTestSpec
import otoroshi.models.ApiKey
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{ApikeyCalls, HMACValidator, HMACValidatorConfig, OverrideHost}
import otoroshi.plugins.hmac.HMACUtils
import otoroshi.security.IdGenerator
import otoroshi.utils.crypto.Signatures
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json._

import java.util.Base64

class HMACAccessValidatorTests(parent: PluginsTestSpec) {
  import parent._

  def default() = {
    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[HMACValidator],
          config = NgPluginInstanceConfig(
            HMACValidatorConfig(
              secret = "secret".some,
              authorizationHeader = "foo".some
            ).json.as[JsObject]
          )
        )
      )
    )

    val base = System.currentTimeMillis().toString
    val signature = Base64.getEncoder.encodeToString(Signatures.hmac(HMACUtils.Algo("HMAC-SHA512"), base, "secret"))

    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain,
        "base" -> base,
        "foo" -> s"""hmac algorithm="HMAC-SHA512", headers="base", signature="$signature""""
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK

    deleteOtoroshiRoute(route).futureValue
  }

  def withApikeyAsSecret() = {
    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ApikeyCalls]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[HMACValidator],
          config = NgPluginInstanceConfig(
            HMACValidatorConfig(
              authorizationHeader = "foo".some
            ).json.as[JsObject]
          )
        )
      )
    )

    val apikey = ApiKey(
      clientId = IdGenerator.token(16),
      clientSecret = "apikey secret",
      clientName = "apikey1",
      authorizedEntities = Seq.empty
    )
    createOtoroshiApiKey(apikey).futureValue

    val base      = System.currentTimeMillis().toString
    val signature =
      Base64.getEncoder.encodeToString(Signatures.hmac(HMACUtils.Algo("HMAC-SHA512"), base, apikey.clientSecret))

    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host"                   -> route.frontend.domains.head.domain,
        "Otoroshi-Client-Id"     -> apikey.clientId,
        "Otoroshi-Client-Secret" -> apikey.clientSecret,
        "base"                   -> base,
        "foo"                    -> s"""hmac algorithm="HMAC-SHA512", headers="base", signature="$signature""""
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK

    deleteOtoroshiApiKey(apikey)
    deleteOtoroshiRoute(route).futureValue
  }
}
