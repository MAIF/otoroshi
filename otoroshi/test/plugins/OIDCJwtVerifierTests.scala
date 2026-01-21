package plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import functional.PluginsTestSpec
import otoroshi.auth.{GenericOauth2Module, GenericOauth2ModuleConfig, SessionCookieValues}
import otoroshi.models.{AlgoSettings, HSAlgoSettings}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{NgServiceQuotas, NgServiceQuotasConfig, OIDCJwtVerifier, OIDCJwtVerifierConfig, OverrideHost}
import play.api.http.Status
import play.api.libs.json.JsObject

class OIDCJwtVerifierTests(parent: PluginsTestSpec) {
  import parent._

  def verifyOIDC() = {
    val token = JWT.create().withIssuer("foo").sign(Algorithm.HMAC256("secret"))
    val authModule = GenericOauth2ModuleConfig(
      id = "auth_oidc",
      name = "auth_oidc",
      desc = "auth_oidc",
      clientSideSessionEnabled = false,
      sessionCookieValues = SessionCookieValues(true),
      tags = Seq.empty,
      metadata = Map.empty,
      jwtVerifier = Some(HSAlgoSettings(256, "secret"))
    )
    createAuthModule(authModule).futureValue
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OIDCJwtVerifier],
          config = NgPluginInstanceConfig(
            OIDCJwtVerifierConfig(
              ref = Some(authModule.id)
            ).json.as[JsObject]
          )
        )
      )
    ).futureValue
    val resp = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp.status mustBe Status.BAD_REQUEST

    val resp2 = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain,
        "Authorization" -> s"Bearer ${token}"
      )
      .get()
      .futureValue

    resp2.status mustBe Status.OK

    deleteOtoroshiRoute(route).futureValue
  }

}
