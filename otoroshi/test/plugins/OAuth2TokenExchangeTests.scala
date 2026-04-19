package plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import functional.{PluginsTestSpec, TargetService}
import otoroshi.auth.{GenericOauth2ModuleConfig, SessionCookieValues}
import otoroshi.models.HSAlgoSettings
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgTarget}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{OAuth2TokenExchange, OAuth2TokenExchangeConfig, OAuth2TokenExchangeParams, OverrideHost}
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class OAuth2TokenExchangeTests(parent: PluginsTestSpec) {
  import parent._

  def tokenExchangeBasic() = {

    val exchangedToken = "exchanged-access-token-12345"

    // fake token endpoint that mimics a RFC 8693 token exchange response
    val fakeIdp = new TargetService(
      TargetService.freePort,
      None,
      "/token",
      "application/json",
      _ =>
        (
          200,
          Json.stringify(
            Json.obj(
              "access_token" -> exchangedToken,
              "token_type"   -> "Bearer",
              "expires_in"   -> 3600
            )
          ),
          None,
          List.empty
        )
    ).await()

    val token      = JWT.create().withIssuer("foo").sign(Algorithm.HMAC256("secret"))
    val authModule = GenericOauth2ModuleConfig(
      id = "auth_token_exchange",
      name = "auth_token_exchange",
      desc = "auth_token_exchange",
      clientSideSessionEnabled = false,
      sessionCookieValues = SessionCookieValues(true),
      tags = Seq.empty,
      metadata = Map.empty,
      clientId = "my-client",
      clientSecret = "my-secret",
      tokenUrl = s"http://127.0.0.1:${fakeIdp.port}/token",
      jwtVerifier = Some(HSAlgoSettings(256, "secret"))
    )
    createAuthModule(authModule).futureValue

    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OAuth2TokenExchange],
          config = NgPluginInstanceConfig(
            OAuth2TokenExchangeConfig(
              ref = Some(authModule.id),
              exchange = OAuth2TokenExchangeParams(
                audience = Some("backend-api")
              )
            ).json.as[JsObject]
          )
        )
      )
    ).futureValue

    // no token => 401
    ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue
      .status mustBe Status.UNAUTHORIZED

    // invalid token => error
    ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders(
        "Host"          -> route.frontend.domains.head.domain,
        "Authorization" -> "Bearer invalid-jwt-token"
      )
      .get()
      .futureValue
      .status mustBe Status.BAD_REQUEST

    // valid token => exchange + forward => 200
    val resp = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders(
        "Host"          -> route.frontend.domains.head.domain,
        "Authorization" -> s"Bearer $token"
      )
      .get()
      .futureValue
    resp.status mustBe Status.OK

    deleteOtoroshiRoute(route).futureValue
    deleteAuthModule(authModule).futureValue
    fakeIdp.stop()
  }

  def tokenExchangeNoJwtVerifier() = {

    val exchangedToken = "exchanged-opaque-token-67890"

    // fake token endpoint
    val fakeIdp = new TargetService(
      TargetService.freePort,
      None,
      "/token",
      "application/json",
      _ =>
        (
          200,
          Json.stringify(
            Json.obj(
              "access_token" -> exchangedToken,
              "token_type"   -> "Bearer",
              "expires_in"   -> 3600
            )
          ),
          None,
          List.empty
        )
    ).await()

    // auth module WITHOUT jwtVerifier => opaque token mode
    val authModule = GenericOauth2ModuleConfig(
      id = "auth_token_exchange_opaque",
      name = "auth_token_exchange_opaque",
      desc = "auth_token_exchange_opaque",
      clientSideSessionEnabled = false,
      sessionCookieValues = SessionCookieValues(true),
      tags = Seq.empty,
      metadata = Map.empty,
      clientId = "my-client",
      clientSecret = "my-secret",
      tokenUrl = s"http://127.0.0.1:${fakeIdp.port}/token",
      jwtVerifier = None
    )
    createAuthModule(authModule).futureValue

    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OAuth2TokenExchange],
          config = NgPluginInstanceConfig(
            OAuth2TokenExchangeConfig(
              ref = Some(authModule.id),
              exchange = OAuth2TokenExchangeParams(
                audience = Some("backend-api")
              )
            ).json.as[JsObject]
          )
        )
      )
    ).futureValue

    // no token => 401
    ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue
      .status mustBe Status.UNAUTHORIZED

    // any opaque token => skip local validation, go straight to exchange => 200
    val resp = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders(
        "Host"          -> route.frontend.domains.head.domain,
        "Authorization" -> "Bearer some-opaque-token"
      )
      .get()
      .futureValue
    resp.status mustBe Status.OK

    deleteOtoroshiRoute(route).futureValue
    deleteAuthModule(authModule).futureValue
    fakeIdp.stop()
  }

  def tokenExchangeIdpFailure() = {

    // fake token endpoint that rejects the exchange
    val fakeIdp = new TargetService(
      TargetService.freePort,
      None,
      "/token",
      "application/json",
      _ =>
        (
          400,
          Json.stringify(
            Json.obj(
              "error"             -> "invalid_grant",
              "error_description" -> "token exchange denied"
            )
          ),
          None,
          List.empty
        )
    ).await()

    val token      = JWT.create().withIssuer("foo").sign(Algorithm.HMAC256("secret"))
    val authModule = GenericOauth2ModuleConfig(
      id = "auth_token_exchange_fail",
      name = "auth_token_exchange_fail",
      desc = "auth_token_exchange_fail",
      clientSideSessionEnabled = false,
      sessionCookieValues = SessionCookieValues(true),
      tags = Seq.empty,
      metadata = Map.empty,
      clientId = "my-client",
      clientSecret = "my-secret",
      tokenUrl = s"http://127.0.0.1:${fakeIdp.port}/token",
      jwtVerifier = Some(HSAlgoSettings(256, "secret"))
    )
    createAuthModule(authModule).futureValue

    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OAuth2TokenExchange],
          config = NgPluginInstanceConfig(
            OAuth2TokenExchangeConfig(
              ref = Some(authModule.id),
              exchange = OAuth2TokenExchangeParams(
                audience = Some("backend-api")
              )
            ).json.as[JsObject]
          )
        )
      )
    ).futureValue

    // valid JWT but IdP rejects exchange => 502
    val resp = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders(
        "Host"          -> route.frontend.domains.head.domain,
        "Authorization" -> s"Bearer $token"
      )
      .get()
      .futureValue
    resp.status mustBe Status.BAD_GATEWAY

    deleteOtoroshiRoute(route).futureValue
    deleteAuthModule(authModule).futureValue
    fakeIdp.stop()
  }
}
