package plugins

import functional.PluginsTestSpec
import otoroshi.auth.{GenericOauth2ModuleConfig, SessionCookieValues}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{OAuthProtectedResourceMetadata, OAuthProtectedResourceMetadataConfig}
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class Rfc9728MetadataTests(parent: PluginsTestSpec) {
  import parent._

  private val WellKnownPath = "/.well-known/oauth-protected-resource"

  def withOverrideOnly(): Unit = {
    val resourceId   = "https://api.example.com/"
    val asOverride   = Seq("https://issuer-a.example.com", "https://issuer-b.example.com")

    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OAuthProtectedResourceMetadata],
          config = NgPluginInstanceConfig(
            OAuthProtectedResourceMetadataConfig(
              resource = Some(resourceId),
              authorizationServersOverride = asOverride,
              scopesSupported = Seq("read", "write"),
              resourceName = Some("Example API"),
              bearerMethodsSupported = Seq("header")
            ).json.as[JsObject]
          )
        )
      )
    ).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port$WellKnownPath")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    resp.status mustBe Status.OK
    resp.contentType must include("application/json")

    val body = resp.json
    (body \ "resource").as[String] mustBe resourceId
    (body \ "authorization_servers").as[Seq[String]] mustBe asOverride
    (body \ "scopes_supported").as[Seq[String]] mustBe Seq("read", "write")
    (body \ "resource_name").as[String] mustBe "Example API"
    (body \ "bearer_methods_supported").as[Seq[String]] mustBe Seq("header")
    // signed_metadata is NOT emitted when the feature is off
    (body \ "signed_metadata").toOption mustBe None

    deleteOtoroshiRoute(route).futureValue
  }

  def withAuthModuleRefOidConfig(): Unit = {
    val expectedIssuer = "https://idp.example.com"
    val authModule     = GenericOauth2ModuleConfig(
      id = "rfc9728_test_oidc_oidconfig",
      name = "rfc9728_test_oidc_oidconfig",
      desc = "auth module for rfc9728 functional test",
      clientSideSessionEnabled = false,
      sessionCookieValues = SessionCookieValues(true),
      tags = Seq.empty,
      metadata = Map.empty,
      clientId = "client",
      clientSecret = "secret",
      tokenUrl = s"$expectedIssuer/oauth/token",
      authorizeUrl = s"$expectedIssuer/oauth/authorize",
      oidConfig = Some(s"$expectedIssuer/.well-known/openid-configuration")
    )
    createAuthModule(authModule).futureValue

    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OAuthProtectedResourceMetadata],
          config = NgPluginInstanceConfig(
            OAuthProtectedResourceMetadataConfig(
              ref = Some(authModule.id),
              resource = Some("https://api.example.com/")
            ).json.as[JsObject]
          )
        )
      )
    ).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port$WellKnownPath")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    resp.status mustBe Status.OK
    (resp.json \ "authorization_servers").as[Seq[String]] mustBe Seq(expectedIssuer)

    deleteOtoroshiRoute(route).futureValue
    deleteAuthModule(authModule).futureValue
  }

  def withAuthModuleRefTokenUrlFallback(): Unit = {
    // No oidConfig => the plugin must derive the issuer from `tokenUrl`'s scheme+authority.
    val authModule = GenericOauth2ModuleConfig(
      id = "rfc9728_test_oidc_tokenurl",
      name = "rfc9728_test_oidc_tokenurl",
      desc = "auth module for rfc9728 functional test",
      clientSideSessionEnabled = false,
      sessionCookieValues = SessionCookieValues(true),
      tags = Seq.empty,
      metadata = Map.empty,
      clientId = "client",
      clientSecret = "secret",
      tokenUrl = "https://idp.example.com/oauth/token",
      oidConfig = None
    )
    createAuthModule(authModule).futureValue

    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OAuthProtectedResourceMetadata],
          config = NgPluginInstanceConfig(
            OAuthProtectedResourceMetadataConfig(
              ref = Some(authModule.id),
              resource = Some("https://api.example.com/")
            ).json.as[JsObject]
          )
        )
      )
    ).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port$WellKnownPath")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    resp.status mustBe Status.OK
    (resp.json \ "authorization_servers").as[Seq[String]] mustBe Seq("https://idp.example.com")

    deleteOtoroshiRoute(route).futureValue
    deleteAuthModule(authModule).futureValue
  }

  def withInferredResource(): Unit = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OAuthProtectedResourceMetadata],
          config = NgPluginInstanceConfig(
            OAuthProtectedResourceMetadataConfig(
              authorizationServersOverride = Seq("https://issuer.example.com")
            ).json.as[JsObject]
          )
        )
      )
    ).futureValue

    val host = route.frontend.domains.head.domain
    val resp = ws
      .url(s"http://127.0.0.1:$port$WellKnownPath")
      .withHttpHeaders("Host" -> host)
      .get()
      .futureValue

    resp.status mustBe Status.OK
    // The inbound request is plain HTTP on the test loopback, so the resource is derived as http://<host>/.
    (resp.json \ "resource").as[String] mustBe s"http://$host/"

    deleteOtoroshiRoute(route).futureValue
  }

  def withExtraMetadataAndOptionalFields(): Unit = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OAuthProtectedResourceMetadata],
          config = NgPluginInstanceConfig(
            OAuthProtectedResourceMetadataConfig(
              resource = Some("https://api.example.com/"),
              authorizationServersOverride = Seq("https://issuer.example.com"),
              jwksUri = Some("https://api.example.com/.well-known/jwks.json"),
              scopesSupported = Seq("read", "write", "admin"),
              bearerMethodsSupported = Seq("header", "body"),
              resourceSigningAlgValuesSupported = Seq("RS256", "ES256"),
              resourceDocumentation = Some("https://docs.example.com/api"),
              tlsClientCertificateBoundAccessTokens = true,
              dpopBoundAccessTokensRequired = true,
              dpopSigningAlgValuesSupported = Seq("ES256"),
              extraMetadata = Json.obj("custom_field" -> "custom_value", "x_vendor_flag" -> true)
            ).json.as[JsObject]
          )
        )
      )
    ).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port$WellKnownPath")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    resp.status mustBe Status.OK
    val body = resp.json

    (body \ "jwks_uri").as[String] mustBe "https://api.example.com/.well-known/jwks.json"
    (body \ "scopes_supported").as[Seq[String]] mustBe Seq("read", "write", "admin")
    (body \ "bearer_methods_supported").as[Seq[String]] mustBe Seq("header", "body")
    (body \ "resource_signing_alg_values_supported").as[Seq[String]] mustBe Seq("RS256", "ES256")
    (body \ "resource_documentation").as[String] mustBe "https://docs.example.com/api"
    (body \ "tls_client_certificate_bound_access_tokens").as[Boolean] mustBe true
    (body \ "dpop_bound_access_tokens_required").as[Boolean] mustBe true
    (body \ "dpop_signing_alg_values_supported").as[Seq[String]] mustBe Seq("ES256")
    // extraMetadata is merged in
    (body \ "custom_field").as[String] mustBe "custom_value"
    (body \ "x_vendor_flag").as[Boolean] mustBe true

    deleteOtoroshiRoute(route).futureValue
  }
}
