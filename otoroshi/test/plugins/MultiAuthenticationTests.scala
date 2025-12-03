package plugins

import com.dimafeng.testcontainers.GenericContainer
import com.microsoft.playwright._
import com.microsoft.playwright.options.AriaRole
import functional.PluginsTestSpec
import org.testcontainers.containers.wait.strategy.Wait
import otoroshi.auth.{BasicAuthModuleConfig, BasicAuthUser, GenericOauth2ModuleConfig, SessionCookieValues}
import otoroshi.models._
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgRoute}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.{BetterJsValueReader, BetterSyntax}
import play.api.libs.json._
import play.api.libs.ws.DefaultWSCookie

import scala.concurrent.Future
import scala.jdk.CollectionConverters.asScalaBufferConverter

class MultiAuthenticationTests(parent: PluginsTestSpec) {

  import parent._

  def emailFlow() = {
    def createBasicAuthModule(): BasicAuthModuleConfig = {
      val basicModuleConfiguration = BasicAuthModuleConfig(
        id = s"inmemory-${IdGenerator.uuid}",
        name = "inmemory",
        desc = "inmemory",
        users = Seq(
          BasicAuthUser(
            name = "foo",
            password = "$2a$10$RtYWagxgvorxpxNIYTi4Be2tU.n8294eHpwle1ad0Tmh7.NiVXOEq",
            email = "user@oto.tools",
            tags = Seq.empty,
            rights = UserRights(rights =
              Seq(
                UserRight(
                  tenant = TenantAccess("*", canRead = true, canWrite = true),
                  teams = Seq(TeamAccess("*", canRead = true, canWrite = true))
                )
              )
            ),
            adminEntityValidators = Map.empty
          )
        ),
        clientSideSessionEnabled = false,
        userValidators = Seq.empty,
        remoteValidators = Seq.empty,
        tags = Seq.empty,
        metadata = Map.empty,
        sessionCookieValues = SessionCookieValues(httpOnly = true, secure = false),
        location = otoroshi.models.EntityLocation(),
        allowedUsers = Seq.empty,
        deniedUsers = Seq.empty
      )
      createAuthModule(basicModuleConfiguration).futureValue
      basicModuleConfiguration
    }

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

      val createUserResponse = env.Ws
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

    def createOAuth2Module(keycloakHost: String, keycloakPort: Int): GenericOauth2ModuleConfig = {
      val oauth2Configuration = GenericOauth2ModuleConfig(
        id = s"keycloak-${IdGenerator.uuid}",
        name = "Keycloak",
        desc = "Keycloak",
        clientId = "otoroshi",
        clientSecret = "DF0LZqCtU85vOwH2lfqz6pxRF9hh5ALr",
        tokenUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/token",
        authorizeUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/auth",
        userInfoUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/userinfo",
        introspectionUrl =
          s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/token/introspect",
        loginUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/auth",
        logoutUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/logout",
        callbackUrl = s"http://privateapps.oto.tools:${port}/privateapps/generic/callback",
        scope = "openid roles phone web-origins profile email acr microprofile-jwt offline_access address",
        clientSideSessionEnabled = true,
        noWildcardRedirectURI = true,
        userValidators = Seq.empty,
        remoteValidators = Seq.empty,
        tags = Seq.empty,
        metadata = Map.empty,
        sessionCookieValues = SessionCookieValues(httpOnly = true, secure = false),
        location = otoroshi.models.EntityLocation(),
        allowedUsers = Seq.empty,
        deniedUsers = Seq.empty
      )
      createAuthModule(oauth2Configuration).futureValue
      oauth2Configuration
    }

    def createRoute(basicModuleId: String, oauth2ModuleId: String) = {
      createRouteWithExternalTarget(
        Seq(
          NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[ApikeyCalls],
            config = NgPluginInstanceConfig(
              Json.obj(
                "mandatory"    -> false,
                "plugin_index" -> Json.obj(
                  "match_route"       -> 0,
                  "validate_access"   -> 1,
                  "transform_request" -> 1
                )
              )
            )
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[MultiAuthModule],
            config = NgPluginInstanceConfig(
              NgMultiAuthModuleConfig(
                modules = Seq(basicModuleId, oauth2ModuleId),
                passWithApikey = true,
                useEmailPrompt = true,
                usersGroups = Json.obj(
                  oauth2ModuleId -> Json.arr("test@example.com"),
                  basicModuleId  -> Json.arr("Wildcard(*@oto.tools)")
                )
              ).json
                .as[JsObject]
                .deepMerge(
                  Json.obj(
                    "plugin_index" -> Json.obj(
                      "validate_access" -> 2
                    )
                  )
                )
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

    def performInMemoryLogin(browser: Browser, route: NgRoute) = {
      val context = browser.newContext()
      val page    = context.newPage()

      page.navigate(s"http://${route.frontend.domains.head.domain}:$port")

      page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Email")).click()
      page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Email")).fill("user@oto.tools")
      page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Continue")).click()

      page.locator("input[name='username']").click()
      page.locator("input[name='username']").fill("user@oto.tools")
      page.locator("input[name='password']").click()
      page.locator("input[name='password']").fill("password")
      page.fill("input[name='password']", "password")
      page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login")).nth(0).click()

      page.content().contains("GET") mustBe true
    }

    def performKeycloakLogin(browser: Browser, route: NgRoute): BrowserContext = {
      val context = browser.newContext()
      val page    = context.newPage()

      page.navigate(s"http://${route.frontend.domains.head.domain}:$port")

      page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Email")).click()
      page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Email")).fill("test@example.com")
      page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Continue")).click()

      page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Username or email")).click()
      page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Username or email")).fill("testuser")
      page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Username or email")).press("Tab")
      page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Password")).fill("testpassword")
      page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign In")).click()

      page.content().contains("GET") mustBe true
      context
    }

    def extractCookies(context: BrowserContext): Seq[DefaultWSCookie] = {
      context.cookies.asScala.map { c =>
        DefaultWSCookie(
          name = c.name,
          value = c.value,
          domain = Option(c.domain),
          path = Option(c.path).getOrElse("/").some,
          secure = c.secure,
          httpOnly = c.httpOnly
        )
      }.toSeq
    }

    def verifyAuthenticatedAccess(route: NgRoute, cookies: Seq[DefaultWSCookie]): Unit = {
      val callWithUser = ws
        .url(s"http://127.0.0.1:$port/.well-known/otoroshi/me")
        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
        .withCookies(cookies: _*)
        .get()
        .futureValue

      callWithUser.status mustBe 200
      Json.parse(callWithUser.body).selectAsString("email") mustBe "test@example.com"
      Json.parse(callWithUser.body).selectAsString("name") mustBe "Test User"
    }

    def verifyUnauthenticatedAccess(route: NgRoute): Unit = {
      val callWithoutCookies = ws
        .url(s"http://127.0.0.1:$port/.well-known/otoroshi/me")
        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
        .withFollowRedirects(false)
        .get()
        .futureValue

      callWithoutCookies.status mustBe 401

      val callWithoutCookies2 = ws
        .url(s"http://127.0.0.1:$port")
        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
        .withFollowRedirects(false)
        .get()
        .futureValue

      callWithoutCookies2.status mustBe 303
    }

    def callWithApikey(route: NgRoute): Unit = {
      val apikey = ApiKey(
        clientId = "apikey-test",
        clientSecret = "1234",
        clientName = "apikey-test",
        authorizedEntities = Seq(RouteIdentifier(route.id))
      )

      createOtoroshiApiKey(apikey).futureValue

      val callWithApikey = ws
        .url(s"http://127.0.0.1:$port")
        .withHttpHeaders(
          "Host"                   -> route.frontend.domains.head.domain,
          "Otoroshi-Client-Id"     -> apikey.clientId,
          "Otoroshi-Client-Secret" -> apikey.clientSecret
        )
        .get()
        .futureValue

      callWithApikey.status mustBe 200

      val callWithoutApikey = ws
        .url(s"http://127.0.0.1:$port")
        .withFollowRedirects(false)
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain
        )
        .get()
        .futureValue

      callWithoutApikey.status mustBe 303
    }

    val basicModuleConfiguration = createBasicAuthModule()

    val keycloakContainer = startKeycloakContainer()
    val keycloakUrl       = getKeycloakUrl(keycloakContainer)
    configureKeycloak(keycloakUrl).futureValue

    val keycloakHost        = keycloakContainer.host
    val keycloakPort        = keycloakContainer.mappedPort(8080)
    val oauth2Configuration = createOAuth2Module(keycloakHost, keycloakPort)

    val route = createRoute(basicModuleConfiguration.id, oauth2Configuration.id)

    verifyKeycloakTokenEndpoint(keycloakUrl)

    val playwright = Playwright.create()
    val browser    = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))
    val context    = performKeycloakLogin(browser, route)
    performInMemoryLogin(browser, route)
    val wsCookies  = extractCookies(context)

    verifyAuthenticatedAccess(route, wsCookies)
    verifyUnauthenticatedAccess(route)
    callWithApikey(route)

    browser.close()
    playwright.close()
    deleteAuthModule(oauth2Configuration).futureValue
    deleteAuthModule(basicModuleConfiguration).futureValue
    deleteOtoroshiRoute(route).futureValue
    keycloakContainer.stop()
  }

  def passWithApikey() = {
    def createBasicAuthModule(): BasicAuthModuleConfig = {
      val basicModuleConfiguration = BasicAuthModuleConfig(
        id = s"inmemory-${IdGenerator.uuid}",
        name = "inmemory",
        desc = "inmemory",
        users = Seq(
          BasicAuthUser(
            name = "foo",
            password = "$2a$10$RtYWagxgvorxpxNIYTi4Be2tU.n8294eHpwle1ad0Tmh7.NiVXOEq",
            email = "user@oto.tools",
            tags = Seq.empty,
            rights = UserRights(rights =
              Seq(
                UserRight(
                  tenant = TenantAccess("*", canRead = true, canWrite = true),
                  teams = Seq(TeamAccess("*", canRead = true, canWrite = true))
                )
              )
            ),
            adminEntityValidators = Map.empty
          )
        ),
        clientSideSessionEnabled = false,
        userValidators = Seq.empty,
        remoteValidators = Seq.empty,
        tags = Seq.empty,
        metadata = Map.empty,
        sessionCookieValues = SessionCookieValues(httpOnly = true, secure = false),
        location = otoroshi.models.EntityLocation(),
        allowedUsers = Seq.empty,
        deniedUsers = Seq.empty
      )
      createAuthModule(basicModuleConfiguration).futureValue
      basicModuleConfiguration
    }

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

    def createOAuth2Module(keycloakHost: String, keycloakPort: Int): GenericOauth2ModuleConfig = {
      val oauth2Configuration = GenericOauth2ModuleConfig(
        id = s"keycloak-${IdGenerator.uuid}",
        name = "Keycloak",
        desc = "Keycloak",
        clientId = "otoroshi",
        clientSecret = "DF0LZqCtU85vOwH2lfqz6pxRF9hh5ALr",
        tokenUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/token",
        authorizeUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/auth",
        userInfoUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/userinfo",
        introspectionUrl =
          s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/token/introspect",
        loginUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/auth",
        logoutUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/logout",
        callbackUrl = s"http://privateapps.oto.tools:${port}/privateapps/generic/callback",
        scope = "openid roles phone web-origins profile email acr microprofile-jwt offline_access address",
        clientSideSessionEnabled = true,
        noWildcardRedirectURI = true,
        userValidators = Seq.empty,
        remoteValidators = Seq.empty,
        tags = Seq.empty,
        metadata = Map.empty,
        sessionCookieValues = SessionCookieValues(httpOnly = true, secure = false),
        location = otoroshi.models.EntityLocation(),
        allowedUsers = Seq.empty,
        deniedUsers = Seq.empty
      )
      createAuthModule(oauth2Configuration).futureValue
      oauth2Configuration
    }

    def createRoute(basicModuleId: String, oauth2ModuleId: String) = {
      createRouteWithExternalTarget(
        Seq(
          NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[ApikeyCalls],
            config = NgPluginInstanceConfig(
              Json.obj(
                "mandatory"    -> false,
                "plugin_index" -> Json.obj(
                  "match_route"       -> 0,
                  "validate_access"   -> 1,
                  "transform_request" -> 1
                )
              )
            )
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[MultiAuthModule],
            config = NgPluginInstanceConfig(
              NgMultiAuthModuleConfig(modules = Seq(basicModuleId, oauth2ModuleId), passWithApikey = true).json
                .as[JsObject]
                .deepMerge(
                  Json.obj(
                    "plugin_index" -> Json.obj(
                      "validate_access" -> 2
                    )
                  )
                )
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

    def performKeycloakLogin(browser: Browser, route: NgRoute): BrowserContext = {
      val context = browser.newContext()
      val page    = context.newPage()

      page.navigate(s"http://${route.frontend.domains.head.domain}:$port")
      page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Continue with keycloak")).click()
      page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Username or email")).click()
      page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Username or email")).fill("testuser")
      page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Username or email")).press("Tab")
      page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Password")).fill("testpassword")
      page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign In")).click()

      page.content().contains("GET") mustBe true
      context
    }

    def extractCookies(context: BrowserContext): Seq[DefaultWSCookie] = {
      context.cookies.asScala.map { c =>
        DefaultWSCookie(
          name = c.name,
          value = c.value,
          domain = Option(c.domain),
          path = Option(c.path).getOrElse("/").some,
          secure = c.secure,
          httpOnly = c.httpOnly
        )
      }.toSeq
    }

    def verifyAuthenticatedAccess(route: NgRoute, cookies: Seq[DefaultWSCookie]): Unit = {
      val callWithUser = ws
        .url(s"http://127.0.0.1:$port/.well-known/otoroshi/me")
        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
        .withCookies(cookies: _*)
        .get()
        .futureValue

      callWithUser.status mustBe 200
      Json.parse(callWithUser.body).selectAsString("email") mustBe "test@example.com"
      Json.parse(callWithUser.body).selectAsString("name") mustBe "Test User"
    }

    def verifyUnauthenticatedAccess(route: NgRoute): Unit = {
      val callWithoutCookies = ws
        .url(s"http://127.0.0.1:$port/.well-known/otoroshi/me")
        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
        .withFollowRedirects(false)
        .get()
        .futureValue

      callWithoutCookies.status mustBe 401

      val callWithoutCookies2 = ws
        .url(s"http://127.0.0.1:$port")
        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
        .withFollowRedirects(false)
        .get()
        .futureValue

      callWithoutCookies2.status mustBe 303
    }

    def callWithApikey(route: NgRoute): Unit = {
      val apikey = ApiKey(
        clientId = "apikey-test",
        clientSecret = "1234",
        clientName = "apikey-test",
        authorizedEntities = Seq(RouteIdentifier(route.id))
      )

      createOtoroshiApiKey(apikey).futureValue

      val callWithApikey = ws
        .url(s"http://127.0.0.1:$port")
        .withHttpHeaders(
          "Host"                   -> route.frontend.domains.head.domain,
          "Otoroshi-Client-Id"     -> apikey.clientId,
          "Otoroshi-Client-Secret" -> apikey.clientSecret
        )
        .get()
        .futureValue

      callWithApikey.status mustBe 200

      val callWithoutApikey = ws
        .url(s"http://127.0.0.1:$port")
        .withFollowRedirects(false)
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain
        )
        .get()
        .futureValue

      callWithoutApikey.status mustBe 303
    }

    val basicModuleConfiguration = createBasicAuthModule()

    val keycloakContainer = startKeycloakContainer()
    val keycloakUrl       = getKeycloakUrl(keycloakContainer)
    configureKeycloak(keycloakUrl).futureValue

    val keycloakHost        = keycloakContainer.host
    val keycloakPort        = keycloakContainer.mappedPort(8080)
    val oauth2Configuration = createOAuth2Module(keycloakHost, keycloakPort)

    val route = createRoute(basicModuleConfiguration.id, oauth2Configuration.id)

    verifyKeycloakTokenEndpoint(keycloakUrl)

    val playwright = Playwright.create()
    val browser    = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))
    val context    = performKeycloakLogin(browser, route)
    val wsCookies  = extractCookies(context)

    verifyAuthenticatedAccess(route, wsCookies)
    verifyUnauthenticatedAccess(route)
    callWithApikey(route)

    browser.close()
    playwright.close()
    deleteAuthModule(oauth2Configuration).futureValue
    deleteAuthModule(basicModuleConfiguration).futureValue
    deleteOtoroshiRoute(route).futureValue
    keycloakContainer.stop()
  }

  def keycloakAndInMemoryAuthentication() = {
    def createBasicAuthModule(): BasicAuthModuleConfig = {
      val basicModuleConfiguration = BasicAuthModuleConfig(
        id = s"inmemory-${IdGenerator.uuid}",
        name = "inmemory",
        desc = "inmemory",
        users = Seq(
          BasicAuthUser(
            name = "foo",
            password = "$2a$10$RtYWagxgvorxpxNIYTi4Be2tU.n8294eHpwle1ad0Tmh7.NiVXOEq",
            email = "user@oto.tools",
            tags = Seq.empty,
            rights = UserRights(rights =
              Seq(
                UserRight(
                  tenant = TenantAccess("*", canRead = true, canWrite = true),
                  teams = Seq(TeamAccess("*", canRead = true, canWrite = true))
                )
              )
            ),
            adminEntityValidators = Map.empty
          )
        ),
        clientSideSessionEnabled = false,
        userValidators = Seq.empty,
        remoteValidators = Seq.empty,
        tags = Seq.empty,
        metadata = Map.empty,
        sessionCookieValues = SessionCookieValues(httpOnly = true, secure = false),
        location = otoroshi.models.EntityLocation(),
        allowedUsers = Seq.empty,
        deniedUsers = Seq.empty
      )
      createAuthModule(basicModuleConfiguration).futureValue
      basicModuleConfiguration
    }

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
      env.Ws
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

      val createUserResponse = env.Ws
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

    def createOAuth2Module(keycloakHost: String, keycloakPort: Int): GenericOauth2ModuleConfig = {
      val oauth2Configuration = GenericOauth2ModuleConfig(
        id = s"keycloak-${IdGenerator.uuid}",
        name = "Keycloak",
        desc = "Keycloak",
        clientId = "otoroshi",
        clientSecret = "DF0LZqCtU85vOwH2lfqz6pxRF9hh5ALr",
        tokenUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/token",
        authorizeUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/auth",
        userInfoUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/userinfo",
        introspectionUrl =
          s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/token/introspect",
        loginUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/auth",
        logoutUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/logout",
        callbackUrl = s"http://privateapps.oto.tools:${port}/privateapps/generic/callback",
        scope = "openid roles phone web-origins profile email acr microprofile-jwt offline_access address",
        clientSideSessionEnabled = true,
        noWildcardRedirectURI = true,
        userValidators = Seq.empty,
        remoteValidators = Seq.empty,
        tags = Seq.empty,
        metadata = Map.empty,
        sessionCookieValues = SessionCookieValues(httpOnly = true, secure = false),
        location = otoroshi.models.EntityLocation(),
        allowedUsers = Seq.empty,
        deniedUsers = Seq.empty
      )
      createAuthModule(oauth2Configuration).futureValue
      oauth2Configuration
    }

    def createRoute(basicModuleId: String, oauth2ModuleId: String) = {
      createRouteWithExternalTarget(
        Seq(
          NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[MultiAuthModule],
            config = NgPluginInstanceConfig(
              NgMultiAuthModuleConfig(modules = Seq(basicModuleId, oauth2ModuleId)).json
                .as[JsObject]
            )
          )
        ),
        id = IdGenerator.uuid
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

    def performKeycloakLogin(browser: Browser, route: NgRoute): BrowserContext = {
      val context = browser.newContext()
      val page    = context.newPage()

      page.navigate(s"http://${route.frontend.domains.head.domain}:$port")
      page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Continue with keycloak")).click()
      page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Username or email")).click()
      page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Username or email")).fill("testuser")
      page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Username or email")).press("Tab")
      page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Password")).fill("testpassword")
      page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign In")).click()

      page.content().contains("GET") mustBe true
      context
    }

    def extractCookies(context: BrowserContext): Seq[DefaultWSCookie] = {
      context.cookies.asScala.map { c =>
        DefaultWSCookie(
          name = c.name,
          value = c.value,
          domain = Option(c.domain),
          path = Option(c.path).getOrElse("/").some,
          secure = c.secure,
          httpOnly = c.httpOnly
        )
      }.toSeq
    }

    def verifyAuthenticatedAccess(route: NgRoute, cookies: Seq[DefaultWSCookie]): Unit = {
      val callWithUser = ws
        .url(s"http://127.0.0.1:$port/.well-known/otoroshi/me")
        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
        .withCookies(cookies: _*)
        .get()
        .futureValue

      callWithUser.status mustBe 200
      Json.parse(callWithUser.body).selectAsString("email") mustBe "test@example.com"
      Json.parse(callWithUser.body).selectAsString("name") mustBe "Test User"
    }

    def verifyUnauthenticatedAccess(route: NgRoute): Unit = {
      val callWithoutCookies = ws
        .url(s"http://127.0.0.1:$port/.well-known/otoroshi/me")
        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
        .withFollowRedirects(false)
        .get()
        .futureValue

      callWithoutCookies.status mustBe 401

      val callWithoutCookies2 = ws
        .url(s"http://127.0.0.1:$port")
        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
        .withFollowRedirects(false)
        .get()
        .futureValue

      callWithoutCookies2.status mustBe 303
    }

    val basicModuleConfiguration = createBasicAuthModule()

    val keycloakContainer = startKeycloakContainer()
    val keycloakUrl       = getKeycloakUrl(keycloakContainer)
    configureKeycloak(keycloakUrl).futureValue

    val keycloakHost        = keycloakContainer.host
    val keycloakPort        = keycloakContainer.mappedPort(8080)
    val oauth2Configuration = createOAuth2Module(keycloakHost, keycloakPort)

    val route = createRoute(basicModuleConfiguration.id, oauth2Configuration.id)

    verifyKeycloakTokenEndpoint(keycloakUrl)

    val playwright = Playwright.create()
    val browser    = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))
    val context    = performKeycloakLogin(browser, route)
    val wsCookies  = extractCookies(context)

    verifyAuthenticatedAccess(route, wsCookies)
    verifyUnauthenticatedAccess(route)

    browser.close()
    playwright.close()
    deleteAuthModule(oauth2Configuration).futureValue
    deleteAuthModule(basicModuleConfiguration).futureValue
    deleteOtoroshiRoute(route).futureValue
    keycloakContainer.stop()
  }

  def oneModule() = {
    val moduleConfiguration = BasicAuthModuleConfig(
      id = s"BasicAuthModuleConfig-${IdGenerator.uuid}",
      name = "BasicAuthModuleConfig",
      desc = "BasicAuthModuleConfig",
      users = Seq(
        BasicAuthUser(
          name = "foo",
          password = "$2a$10$RtYWagxgvorxpxNIYTi4Be2tU.n8294eHpwle1ad0Tmh7.NiVXOEq",
          email = "user@oto.tools",
          tags = Seq.empty,
          rights = UserRights(rights =
            Seq(
              UserRight(
                tenant = TenantAccess("*", canRead = true, canWrite = true),
                teams = Seq(TeamAccess("*", canRead = true, canWrite = true))
              )
            )
          ),
          adminEntityValidators = Map.empty
        )
      ),
      clientSideSessionEnabled = false,
      userValidators = Seq.empty,
      remoteValidators = Seq.empty,
      tags = Seq.empty,
      metadata = Map.empty,
      sessionCookieValues = SessionCookieValues(httpOnly = true, secure = false),
      location = otoroshi.models.EntityLocation(),
      allowedUsers = Seq.empty,
      deniedUsers = Seq.empty
    )
    createAuthModule(moduleConfiguration).futureValue

    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[MultiAuthModule],
          config = NgPluginInstanceConfig(
            NgMultiAuthModuleConfig(modules = Seq(moduleConfiguration.id)).json
              .as[JsObject]
          )
        )
      )
    )

    val playwright = Playwright.create()
    val browser    = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))
    val context    = browser.newContext()
    val page       = context.newPage()

    page.navigate(s"http://${route.frontend.domains.head.domain}:$port")

    page.locator("input[name='username']").click()
    page.locator("input[name='username']").fill("user@oto.tools")
    page.locator("input[name='password']").click()
    page.locator("input[name='password']").fill("password")
    page.fill("input[name='password']", "password")
    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login")).nth(0).click()

    page.content().contains("GET") mustBe true

    val wsCookies: Seq[DefaultWSCookie] = context.cookies.asScala.map { c =>
      DefaultWSCookie(
        name = c.name,
        value = c.value,
        domain = Option(c.domain),
        path = Option(c.path).getOrElse("/").some,
        secure = c.secure,
        httpOnly = c.httpOnly
      )
    }

    val callWithUser = ws
      .url(s"http://127.0.0.1:$port/.well-known/otoroshi/me")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .withCookies(wsCookies: _*)
      .get()
      .futureValue

    callWithUser.status mustBe 200
    Json.parse(callWithUser.body).selectAsString("email") mustBe "user@oto.tools"
    Json.parse(callWithUser.body).selectAsString("name") mustBe "foo"

    val callWithoutCookies = ws
      .url(s"http://127.0.0.1:$port/.well-known/otoroshi/me")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .withFollowRedirects(false)
      .get()
      .futureValue

    callWithoutCookies.status mustBe 401

    val callWithoutCookies2 = ws
      .url(s"http://127.0.0.1:$port")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .withFollowRedirects(false)
      .get()
      .futureValue

    callWithoutCookies2.status mustBe 303

    browser.close()
    playwright.close()

    deleteAuthModule(moduleConfiguration).futureValue
    deleteOtoroshiRoute(route).futureValue
  }
}
