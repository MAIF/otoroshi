package plugins

import com.dimafeng.testcontainers.GenericContainer
import functional.PluginsTestSpec
import org.testcontainers.containers.wait.strategy.Wait
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgRoute}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{OAuth2Caller, OAuth2CallerConfig, OverrideHost}
import otoroshi.plugins.authcallers.OAuth2Kind
import otoroshi.security.IdGenerator
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.syntax.implicits.{BetterJsValueReader, BetterSyntax}
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.Future
import scala.concurrent.duration.DurationLong

class OAuth2CallerTests(parent: PluginsTestSpec) {
  import parent._

  def startKeycloakContainer(): GenericContainer = {
    val keycloakContainer = GenericContainer(
      dockerImage = "quay.io/keycloak/keycloak:26.4",
      exposedPorts = Seq(8080),
      env = Map(
        "KEYCLOAK_ADMIN"          -> "admin",
        "KEYCLOAK_ADMIN_PASSWORD" -> "admin"
      ),
      command = Seq("start-dev"),
      waitStrategy = Wait
        .forHttp("/realms/master")
        .forPort(8080)
        .forStatusCode(200)
        .withStartupTimeout(java.time.Duration.ofMinutes(2))
    )
    keycloakContainer.start()
    keycloakContainer
  }

  def getKeycloakUrl(container: GenericContainer): String =
    s"http://${container.host}:${container.mappedPort(8080)}"

  def getClientConfig(): String = s"""{
    "clientId": "otoroshi",
    "name": "otoroshi",
    "description": "otoroshi",
    "rootUrl": "http://plugins.oto.tools:${port}",
    "adminUrl": "",
    "baseUrl": "http://plugins.oto.tools:$port",
    "surrogateAuthRequired": false,
    "enabled": true,
    "alwaysDisplayInConsole": true,
    "clientAuthenticatorType": "client-secret",
    "secret": "DF0LZqCtU85vOwH2lfqz6pxRF9hh5ALr",
    "redirectUris": [
      "http://privateapps.oto.tools:$port/privateapps/generic/callback*"
    ],
    "webOrigins": [
      "http://plugins.oto.tools:$port",
      "http://privateapp.oto.toos:$port"
    ],
    "notBefore": 0,
    "bearerOnly": false,
    "consentRequired": false,
    "standardFlowEnabled": true,
    "implicitFlowEnabled": false,
    "directAccessGrantsEnabled": true,
    "serviceAccountsEnabled": true,
    "authorizationServicesEnabled": true,
    "publicClient": false,
    "frontchannelLogout": true,
    "protocol": "openid-connect",
    "attributes": {
      "oidc.ciba.grant.enabled": "false",
      "backchannel.logout.session.required": "true",
      "login_theme": "keycloak",
      "post.logout.redirect.uris": "http://privateapps.oto.tools:$port/privateapps/generic/logout",
      "oauth2.device.authorization.grant.enabled": "false",
      "display.on.consent.screen": "false",
      "use.jwks.url": "false",
      "backchannel.logout.revoke.offline.tokens": "false"
    },
    "fullScopeAllowed": true,
    "protocolMappers": [
      {
        "name": "Client IP Address",
        "protocol": "openid-connect",
        "protocolMapper": "oidc-usersessionmodel-note-mapper",
        "consentRequired": false,
        "config": {
          "user.session.note": "clientAddress",
          "id.token.claim": "true",
          "access.token.claim": "true",
          "claim.name": "clientAddress",
          "jsonType.label": "String"
        }
      },
      {
        "name": "Client ID",
        "protocol": "openid-connect",
        "protocolMapper": "oidc-usersessionmodel-note-mapper",
        "consentRequired": false,
        "config": {
          "user.session.note": "client_id",
          "id.token.claim": "true",
          "access.token.claim": "true",
          "claim.name": "client_id",
          "jsonType.label": "String"
        }
      },
      {
        "name": "Client Host",
        "protocol": "openid-connect",
        "protocolMapper": "oidc-usersessionmodel-note-mapper",
        "consentRequired": false,
        "config": {
          "user.session.note": "clientHost",
          "id.token.claim": "true",
          "access.token.claim": "true",
          "claim.name": "clientHost",
          "jsonType.label": "String"
        }
      }
    ],
    "defaultClientScopes": [
      "web-origins",
      "acr",
      "roles",
      "profile",
      "email"
    ],
    "optionalClientScopes": [
      "address",
      "phone",
      "offline_access",
      "microprofile-jwt"
    ]
  }"""

  def getAdminToken(keycloakUrl: String): String = {
    val tokenResponse = env.Ws
      .url(
        s"$keycloakUrl/realms/master/protocol/openid-connect/token"
      )
      .post(
        Map(
          "grant_type" -> "password",
          "client_id"  -> "admin-cli",
          "username"   -> "admin",
          "password"   -> "admin"
        )
      )
      .futureValue
    Json.parse(tokenResponse.body).selectAsString("access_token")
  }

  def createKeycloakClient(keycloakUrl: String, adminToken: String, clientConfig: String): Unit = {
    val createClientResponse = env.Ws
      .url(s"$keycloakUrl/admin/realms/master/clients")
      .withHttpHeaders(
        "Authorization" -> s"Bearer $adminToken",
        "Content-Type"  -> "application/json"
      )
      .post(clientConfig)
      .futureValue
    println("✓ Client 'otoroshi' created successfully")
  }

  def createKeycloakUser(keycloakUrl: String, adminToken: String): Unit = {
    val userConfig = Json.obj(
      "username"      -> "testuser",
      "email"         -> "test@example.com",
      "firstName"     -> "Test",
      "lastName"      -> "User",
      "enabled"       -> true,
      "emailVerified" -> true,
      "credentials"   -> Json.arr(
        Json.obj(
          "type"      -> "password",
          "value"     -> "testpassword",
          "temporary" -> false
        )
      )
    )

    env.Ws
      .url(s"$keycloakUrl/admin/realms/master/users")
      .withHttpHeaders(
        "Authorization" -> s"Bearer $adminToken",
        "Content-Type"  -> "application/json"
      )
      .post(userConfig)
      .futureValue
    println("✓ Test user created: testuser / testpassword")
  }

  def configureKeycloak(keycloakUrl: String): Future[Unit] = {
    val adminToken   = getAdminToken(keycloakUrl)
    val clientConfig = getClientConfig()
    createKeycloakClient(keycloakUrl, adminToken, clientConfig)
    createKeycloakUser(keycloakUrl, adminToken)
    Future.successful(())
  }

  def createRoute(keycloakPort: Int) = {
    createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OAuth2Caller],
          config = NgPluginInstanceConfig(
            OAuth2CallerConfig(
              kind = OAuth2Kind.Password,
              url = s"http://localhost:$keycloakPort/realms/master/protocol/openid-connect/token",
              method = "POST",
              headerName = "Authorization",
              headerValueFormat = "Bearer %s",
              jsonPayload = false,
              clientId = "otoroshi",
              clientSecret = "DF0LZqCtU85vOwH2lfqz6pxRF9hh5ALr",
              scope = Some("openid profile email"),
              audience = None,
              user = "test@example.com".some,
              password = "testpassword".some,
              cacheTokenSeconds = (10L * 60L).seconds,
              tlsConfig = MtlsConfig()
            ).json
              .as[JsObject]
          )
        )
      )
    )
  }

  def verifyKeycloakTokenEndpoint(keycloakUrl: String) = {
    val response = env.Ws
      .url(s"$keycloakUrl/realms/master/protocol/openid-connect/token")
      .post(
        Map(
          "grant_type"    -> Seq("password"),
          "client_id"     -> Seq("otoroshi"),
          "client_secret" -> Seq("DF0LZqCtU85vOwH2lfqz6pxRF9hh5ALr"),
          "username"      -> Seq("test@example.com"),
          "password"      -> Seq("testpassword")
        )
      )
      .futureValue

    response.status mustBe 200
    val accessToken = Json.parse(response.body).selectAsString("access_token")
    accessToken.isEmpty mustBe false
  }

  def verify(route: NgRoute): Unit = {
    val resp = ws
      .url(s"http://127.0.0.1:$port")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    resp.status mustBe 200
    resp.body.contains("GET") mustBe true
  }

  val keycloakContainer = startKeycloakContainer()
  val keycloakUrl       = getKeycloakUrl(keycloakContainer)
  configureKeycloak(keycloakUrl).futureValue

  verifyKeycloakTokenEndpoint(keycloakUrl)

  val route = createRoute(keycloakContainer.mappedPort(8080))
  verify(route)

  deleteOtoroshiRoute(route).futureValue
  keycloakContainer.stop()
}
