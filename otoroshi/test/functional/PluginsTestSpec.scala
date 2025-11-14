package functional

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import play.api.{Configuration, Logger}
import plugins._

class PluginsTestSpec extends OtoroshiSpec with BeforeAndAfterAll {

  implicit lazy val mat = otoroshiComponents.materializer
  implicit lazy val env = otoroshiComponents.env

  def configurationSpec: Configuration = Configuration.empty

  val logger          = Logger("otoroshi-tests-plugins")
  implicit val system = ActorSystem("otoroshi-test")

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString("{}".stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  override def beforeAll(): Unit = {
    startOtoroshi()
    getOtoroshiRoutes().futureValue // WARM UP
  }

  override def afterAll(): Unit = {
    system.terminate()
    stopAll()
  }

  s"plugins" should {
    "Allow HTTP Methods" in {
      new AllowHTTPMethodsTests(this)
    }
    "Apikeys" in {
      new ApikeysTests(this)
    }
    "Additional headers in" in {
      new AdditionalHeadersInTests(this)
    }
    "Additional headers out" in {
      new AdditionalHeadersOutTests(this)
    }
    "Headers validation" in {
      new HeadersValidationTests(this)
    }
    "Missing headers in" in {
      new MissingHeadersInTests(this)
    }
    "Missing headers out" in {
      new MissingHeadersOutTests(this)
    }
    "Override Host Header" in {
      new OverrideHostHeaderTests(this)
    }
    "Override Location Header: redirect to relative path" in {
      new OverrideLocationHeaderTests(this).redirectToRelativePath()
    }
    "Override Location Header: redirect to relative path" in {
      new OverrideLocationHeaderTests(this).redirectToDomainAndPath()
    }
    "Security Txt" in {
      new SecurityTxtTests(this)
    }
    "Yes Websocket plugin: send 'y' messages periodically to websocket clients" in {
      new YesWebsocketPluginTests(this).sendYMessagesPeriodicallyToWebsocketClients()
    }
    "Yes Websocket plugin: reject connection with fail=yes query parameter" in {
      new YesWebsocketPluginTests(this).rejectConnectionWithFailYesQueryParameter()
    }
    "Remove headers in" in {
      new RemoveHeadersInTests(this)
    }
    "Remove headers out" in {
      new RemoveHeadersOutTests(this)
    }
    "Build mode" in {
      new BuildModeTests(this)
    }
    "Maintenance mode" in {
      new MaintenanceModeTests(this)
    }
    "Custom error template" in {
      new CustomErrorTemplateTests(this)
    }
    "Error response rewrite" in {
      new ErrorResponseRewriteTests(this)
    }
    "Reject headers out too long" in {
      new RejectHeadersOutTooLongTests(this)
    }
    "Reject headers in too long" in {
      new RejectHeadersInTooLongTests(this)
    }
    "Additional cookies in" in {
      new AdditionalCookiesInTests(this)
    }
    "Additional cookies out" in {
      new AdditionalCookiesOutTests(this)
    }
    "Limit headers in too long" in {
      new LimitHeadersInTooLongTests(this)
    }
    "Limit headers out too long" in {
      new LimitHeadersOutTooLongTests(this)
    }
    "Basic Auth. caller" in {
      new BasicAuthCallerTests(this).checkValue()
    }
    "Force HTTPS traffic" in {
      new ForceHTTPsTrafficTests(this)
    }
    "Forwarded header" in {
      new ForwardedHeadersTests(this)
    }
    "Mock responses" in {
      new MockReponsesTests(this)
    }
    "Block non HTTPS traffic" in {
      new BlockNonHTTPsTrafficTests(this)
    }
    "Consumer endpoint with apikey" in {
      new ConsumerEndpointWithApikeyTests(this)
    }
    "Consumer endpoint without apikey" in {
      new ConsumerEndpointWithoutApikeyTests(this)
    }
    "Missing cookies in" in {
      new MissingCookiesInTests(this)
    }
    "Missing cookies out" in {
      new MissingCookiesOutTests(this)
    }
    "Default request body" in {
      new DefaultRequestBodyTests(this)
    }
    "HMAC caller plugin" in {
      new HMACCallerPluginTests(this)
    }
    "HMAC access validator" in {
      new HMACAccessValidatorTests(this).default()
    }
    "HMAC access validator with apikey as secret" in {
      new HMACAccessValidatorTests(this).withApikeyAsSecret()
    }
    "Static Response" in {
      new StaticResponseTests(this)
    }
    "Http static asset" in {
      new HTTPStaticAssetTests(this)
    }
    "Disable HTTP/1.0" in {
      new DisableHTTP10Tests(this)
    }
    "Query param transformer" in {
      new QueryParamTransformerTests(this)
    }
    "Read only requests" in {
      new ReadOnlyRequestsTests(this)
    }
    "Response body xml-to-json" in {
      new ResponseBodyXmlToJsonTests(this)
    }
    "User-Agent details extractor" in {
      new UserAgentDetailsExtractorTests(this)
    }
    "User-Agent details extractor + User-Agent header" in {
      new UserAgentDetailsExtractorUserAgentHeaderTests(this)
    }
    "User-Agent details extractor + User-Agent endpoint" in {
      new UserAgentDetailsExtractorUserAgentEndpointTests(this)
    }
    "Request body xml-to-json" in {
      new RequestBodyXmlToJsonTests(this)
    }
    "Jwt signer" in {
      new JwtSignerTests(this).default()
    }
    "Jwt signer should not replace the incoming token" in {
      new JwtSignerTests(this).shouldNotReplaceTheIncomingToken()
    }
    "Jwt verification only (without verifier)" in {
      new JwtVerificationOnlyTests(this).withoutVerifier()
    }
    "Jwt verification only (without token)" in {
      new JwtVerificationOnlyTests(this).withoutToken()
    }
    "Jwt verification only with token" in {
      new JwtVerificationOnlyTests(this).withToken()
    }
    "Jwt verifiers" in {
      new JwtVerifiersTests(this)
    }
    "Jwt user extractor" in {
      new JwtUserExtractorTests(this)
    }
    "Otoroshi Health endpoint" in {
      new OtoroshiHealthEndpointTests(this)
    }
    "Public/Private paths" in {
      new PublicPrivatePathsTests(this)
    }
    "Remove cookies in" in {
      new RemoveCookiesInTests(this)
    }
    "Remove cookies out" in {
      new RemoveCookiesOutTests(this)
    }
    "Basic auth. from auth. module" in {
      new BasicAuthFromAuthModuleTests(this)
    }
    "Request Echo" in {
      new RequestEchoTests(this)
    }
    "Request body Echo" in {
      new RequestBodyEchoTests(this)
    }
    "Custom quotas (per route)" in {
      new CustomQuotasTests(this).perRoute()
    }
    "Custom quotas (global)" in {
      new CustomQuotasTests(this).global()
    }
    "Defer Responses" in {
      new DeferResponsesTests(this)
    }
    "Otoroshi info. token" in {
      new OtoroshiInfoTokenTests(this).default()
    }
    "Otoroshi info. token with apikeys" in {
      new OtoroshiInfoTokenTests(this).withApikeys()
    }
    "Otoroshi info. token with user" in {
      new OtoroshiInfoTokenTests(this).withUser()
    }
    "Websocket json format validator (drop)" in {
      new WebsocketJsonFormatValidatorTests(this).drop()
    }
    "Websocket json format validator (drop)" in {
      new WebsocketJsonFormatValidatorTests(this).closeConnection()
    }
    "Websocket content validator" in {
      new WebsocketContentValidatorTests(this)
    }
    "Websocket type validator" in {
      new WebsocketTypeValidatorTests(this)
    }
    "Websocket size validator" in {
      new WebsocketSizeValidatorTests(this)
    }
    "Websocket JQ Transformer" in {
      new WebsocketJQTransformerTests(this)
    }
    "S3Backend" in {
      new S3BackendTests(this)
    }
  }

  //    "Static backend" in {
  //      val tempRoot: Path = Files.createTempDirectory("testRoot")
  //
  //      val file = tempRoot.resolve("index.html")
  //      Files.write(file, "<div>Hello from file system</div>".getBytes())
  //
  //      Files.exists(file) mustBe true
  //      Files.exists(tempRoot) mustBe true
  //
  //      new String(Files.readAllBytes(file)).contains("Hello from file system") mustBe true
  //
  //      val route = createRequestOtoroshiIORoute(
  //        Seq(
  //          NgPluginInstance(
  //            plugin = NgPluginHelper.pluginId[OverrideHost]
  //          ),
  //          NgPluginInstance(
  //            plugin = NgPluginHelper.pluginId[StaticBackend],
  //            config = NgPluginInstanceConfig(
  //              StaticBackendConfig(tempRoot.toAbsolutePath.toString).json
  //                .as[JsObject]
  //            )
  //          )
  //        ),
  //        id = IdGenerator.uuid,
  //        domain = "s3backend.oto.tools"
  //      )
  //
  //      val resp2 = ws
  //        .url(s"http://127.0.0.1:$port/index.html")
  //        .withHttpHeaders(
  //          "Host" -> route.frontend.domains.head.domain
  //        )
  //        .get()
  //        .futureValue
  //
  //      resp2.status mustBe 200
  //      resp2.body contains "Hello from file system" mustBe true
  //
  //      Files
  //        .walk(tempRoot)
  //        .sorted(java.util.Comparator.reverseOrder())
  //        .forEach(Files.delete)
  //
  //      deleteOtoroshiRoute(route).futureValue
  //    }
  //
  //    "Context Validator" in {
  //      val route = createRequestOtoroshiIORoute(
  //        Seq(
  //          NgPluginInstance(
  //            plugin = NgPluginHelper.pluginId[OverrideHost]
  //          ),
  //          NgPluginInstance(
  //            plugin = NgPluginHelper.pluginId[ApikeyCalls],
  //            config = NgPluginInstanceConfig(
  //              NgApikeyCallsConfig().json.as[JsObject]
  //            )
  //          ),
  //          NgPluginInstance(
  //            plugin = NgPluginHelper.pluginId[ContextValidation],
  //            config = NgPluginInstanceConfig(
  //              ContextValidationConfig(
  //                validators = Seq(
  //                  JsonPathValidator("$.apikey.metadata.foo", JsString("Contains(bar)")),
  //                  JsonPathValidator("$.request.headers.foo", JsString("Contains(bar)"))
  //                )
  //              ).json
  //                .as[JsObject]
  //            )
  //          )
  //        ),
  //        id = IdGenerator.uuid
  //      )
  //
  //      val apikey = ApiKey(
  //        clientId = IdGenerator.token(16),
  //        clientSecret = IdGenerator.token(64),
  //        clientName = "apikey1",
  //        authorizedEntities = Seq.empty,
  //        metadata = Map("foo" -> "bar")
  //      )
  //      createOtoroshiApiKey(apikey).futureValue
  //
  //      apikey.enabled mustBe true
  //
  //      val resp = ws
  //        .url(s"http://127.0.0.1:$port")
  //        .withHttpHeaders(
  //          "Host"                   -> route.frontend.domains.head.domain,
  //          "Otoroshi-Client-Id"     -> apikey.clientId,
  //          "Otoroshi-Client-Secret" -> apikey.clientSecret,
  //          "foo"                    -> "bar"
  //        )
  //        .get()
  //        .futureValue
  //
  //      resp.status mustBe 200
  //
  //      val resp2 = ws
  //        .url(s"http://127.0.0.1:$port")
  //        .withHttpHeaders(
  //          "Host"                   -> route.frontend.domains.head.domain,
  //          "Otoroshi-Client-Id"     -> apikey.clientId,
  //          "Otoroshi-Client-Secret" -> apikey.clientSecret
  //        )
  //        .get()
  //        .futureValue
  //
  //      resp2.status mustBe 403
  //
  //      deleteOtoroshiApiKey(apikey).futureValue
  //      deleteOtoroshiRoute(route).futureValue
  //    }
  //
  //    "HTTP Client Cache - add cache headers when method, status, and content-type match" in {
  //      val route = createRequestOtoroshiIORoute(
  //        Seq(
  //          NgPluginInstance(
  //            plugin = NgPluginHelper.pluginId[OverrideHost]
  //          ),
  //          NgPluginInstance(
  //            plugin = NgPluginHelper.pluginId[NgHttpClientCache],
  //            config = NgPluginInstanceConfig(
  //              NgHttpClientCacheConfig.default
  //                .copy(mimeTypes = Seq("*"))
  //                .json
  //                .as[JsObject]
  //            )
  //          )
  //        ),
  //        id = IdGenerator.uuid
  //      )
  //
  //      val resp = ws
  //        .url(s"http://127.0.0.1:$port")
  //        .withHttpHeaders(
  //          "Host" -> route.frontend.domains.head.domain
  //        )
  //        .get()
  //        .futureValue
  //
  //      resp.status mustBe 200
  //
  //      resp.headers.contains("Cache-Control") mustBe true
  //      resp.headers.contains("Date") mustBe true
  //      resp.headers.contains("Expires") mustBe true
  //      resp.headers.contains("ETag") mustBe true
  //      resp.headers.contains("Last-Modified") mustBe true
  //      resp.headers.contains("Vary") mustBe true
  //      resp.headers.get("Cache-Control").exists(values => values.exists(v => v.contains("max-age="))) mustBe true
  //
  //      deleteOtoroshiRoute(route).futureValue
  //    }
  //
  //    "HTTP Client Cache - does not add cache headers if HTTP method does not match" in {
  //      val route = createRequestOtoroshiIORoute(
  //        Seq(
  //          NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
  //          NgPluginInstance(
  //            plugin = NgPluginHelper.pluginId[NgHttpClientCache],
  //            config = NgPluginInstanceConfig(
  //              NgHttpClientCacheConfig.default.copy(methods = Seq("POST")).json.as[JsObject]
  //            )
  //          )
  //        ),
  //        id = IdGenerator.uuid
  //      )
  //
  //      val resp = ws
  //        .url(s"http://127.0.0.1:$port")
  //        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //        .get()
  //        .futureValue
  //
  //      resp.status mustBe 200
  //      resp.headers.contains("Cache-Control") mustBe false
  //
  //      deleteOtoroshiRoute(route).futureValue
  //    }
  //
  //    "HTTP Client Cache - does not add cache headers if status does not match" in {
  //      val route = createRequestOtoroshiIORoute(
  //        Seq(
  //          NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
  //          NgPluginInstance(
  //            plugin = NgPluginHelper.pluginId[NgHttpClientCache],
  //            config = NgPluginInstanceConfig(
  //              NgHttpClientCacheConfig.default.copy(status = Seq(404)).json.as[JsObject]
  //            )
  //          )
  //        ),
  //        id = IdGenerator.uuid
  //      )
  //
  //      val resp = ws
  //        .url(s"http://127.0.0.1:$port")
  //        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //        .get()
  //        .futureValue
  //
  //      resp.status mustBe 200
  //      resp.headers.contains("Cache-Control") mustBe false
  //
  //      deleteOtoroshiRoute(route).futureValue
  //    }
  //
  //    "HTTP Client Cache - does not add cache headers if content type does not match" in {
  //      val route = createRequestOtoroshiIORoute(
  //        Seq(
  //          NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
  //          NgPluginInstance(
  //            plugin = NgPluginHelper.pluginId[NgHttpClientCache],
  //            config = NgPluginInstanceConfig(
  //              NgHttpClientCacheConfig.default.copy(mimeTypes = Seq("text/html")).json.as[JsObject]
  //            )
  //          )
  //        ),
  //        id = IdGenerator.uuid
  //      )
  //
  //      val resp = ws
  //        .url(s"http://127.0.0.1:$port")
  //        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //        .get()
  //        .futureValue
  //
  //      resp.status mustBe 200
  //      resp.headers.contains("Cache-Control") mustBe false
  //
  //      deleteOtoroshiRoute(route).futureValue
  //    }
  //
  //    "HTTP Client Cache - matches wildcard mime type '*'" in {
  //      val route = createRequestOtoroshiIORoute(
  //        Seq(
  //          NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
  //          NgPluginInstance(
  //            plugin = NgPluginHelper.pluginId[NgHttpClientCache],
  //            config = NgPluginInstanceConfig(
  //              NgHttpClientCacheConfig.default.copy(mimeTypes = Seq("*")).json.as[JsObject]
  //            )
  //          )
  //        ),
  //        id = IdGenerator.uuid
  //      )
  //
  //      val resp = ws
  //        .url(s"http://127.0.0.1:$port")
  //        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //        .get()
  //        .futureValue
  //
  //      resp.status mustBe 200
  //      resp.headers.contains("Cache-Control") mustBe true
  //
  //      deleteOtoroshiRoute(route).futureValue
  //    }
  //
  //    "Authentication - in memory" in {
  //      import com.microsoft.playwright._
  //
  //      val moduleConfiguration = BasicAuthModuleConfig(
  //        id = "BasicAuthModuleConfig",
  //        name = "BasicAuthModuleConfig",
  //        desc = "BasicAuthModuleConfig",
  //        users = Seq(
  //          BasicAuthUser(
  //            name = "foo",
  //            password = "$2a$10$RtYWagxgvorxpxNIYTi4Be2tU.n8294eHpwle1ad0Tmh7.NiVXOEq",
  //            email = "user@oto.tools",
  //            tags = Seq.empty,
  //            rights = UserRights(rights =
  //              Seq(
  //                UserRight(
  //                  tenant = TenantAccess("*", canRead = true, canWrite = true),
  //                  teams = Seq(TeamAccess("*", canRead = true, canWrite = true))
  //                )
  //              )
  //            ),
  //            adminEntityValidators = Map.empty
  //          )
  //        ),
  //        clientSideSessionEnabled = false,
  //        userValidators = Seq.empty,
  //        remoteValidators = Seq.empty,
  //        tags = Seq.empty,
  //        metadata = Map.empty,
  //        sessionCookieValues = SessionCookieValues(httpOnly = true, secure = false),
  //        location = otoroshi.models.EntityLocation(),
  //        allowedUsers = Seq.empty,
  //        deniedUsers = Seq.empty
  //      )
  //      createAuthModule(moduleConfiguration).futureValue
  //
  //      val route = createRequestOtoroshiIORoute(
  //        Seq(
  //          NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
  //          NgPluginInstance(
  //            plugin = NgPluginHelper.pluginId[AuthModule],
  //            config = NgPluginInstanceConfig(
  //              NgAuthModuleConfig(module = moduleConfiguration.id.some).json
  //                .as[JsObject]
  //            )
  //          )
  //        ),
  //        id = IdGenerator.uuid
  //      )
  //
  //      val playwright = Playwright.create()
  //      val browser    = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))
  //      val context    = browser.newContext()
  //      val page       = context.newPage()
  //
  //      page.navigate(s"http://${route.frontend.domains.head.domain}:$port")
  //
  //      page.locator("input[name='username']").click()
  //      page.locator("input[name='username']").fill("user@oto.tools")
  //      page.locator("input[name='password']").click()
  //      page.locator("input[name='password']").fill("password")
  //      page.fill("input[name='password']", "password")
  //      page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login")).nth(0).click()
  //
  //      page.content().contains("GET") mustBe true
  //
  //      val wsCookies: Seq[DefaultWSCookie] = context.cookies.asScala.map { c =>
  //        DefaultWSCookie(
  //          name = c.name,
  //          value = c.value,
  //          domain = Option(c.domain),
  //          path = Option(c.path).getOrElse("/").some,
  //          secure = c.secure,
  //          httpOnly = c.httpOnly
  //        )
  //      }
  //
  //      val callWithUser = ws
  //        .url(s"http://127.0.0.1:$port/.well-known/otoroshi/me")
  //        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //        .withCookies(wsCookies: _*)
  //        .get()
  //        .futureValue
  //
  //      callWithUser.status mustBe 200
  //      Json.parse(callWithUser.body).selectAsString("email") mustBe "user@oto.tools"
  //      Json.parse(callWithUser.body).selectAsString("name") mustBe "foo"
  //
  //      val callWithoutCookies = ws
  //        .url(s"http://127.0.0.1:$port/.well-known/otoroshi/me")
  //        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //        .withFollowRedirects(false)
  //        .get()
  //        .futureValue
  //
  //      callWithoutCookies.status mustBe 401
  //
  //      val callWithoutCookies2 = ws
  //        .url(s"http://127.0.0.1:$port")
  //        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //        .withFollowRedirects(false)
  //        .get()
  //        .futureValue
  //
  //      callWithoutCookies2.status mustBe 303
  //
  //      browser.close()
  //      playwright.close()
  //
  //      deleteAuthModule(moduleConfiguration).futureValue
  //      deleteOtoroshiRoute(route).futureValue
  //    }
  //
  //    "Authentication - pass with apikey" in {
  //      import com.microsoft.playwright._
  //
  //      val moduleConfiguration = BasicAuthModuleConfig(
  //        id = "BasicAuthModuleConfig",
  //        name = "BasicAuthModuleConfig",
  //        desc = "BasicAuthModuleConfig",
  //        users = Seq(
  //          BasicAuthUser(
  //            name = "foo",
  //            password = "$2a$10$RtYWagxgvorxpxNIYTi4Be2tU.n8294eHpwle1ad0Tmh7.NiVXOEq",
  //            email = "user@oto.tools",
  //            tags = Seq.empty,
  //            rights = UserRights(rights =
  //              Seq(
  //                UserRight(
  //                  tenant = TenantAccess("*", canRead = true, canWrite = true),
  //                  teams = Seq(TeamAccess("*", canRead = true, canWrite = true))
  //                )
  //              )
  //            ),
  //            adminEntityValidators = Map.empty
  //          )
  //        ),
  //        clientSideSessionEnabled = false,
  //        userValidators = Seq.empty,
  //        remoteValidators = Seq.empty,
  //        tags = Seq.empty,
  //        metadata = Map.empty,
  //        sessionCookieValues = SessionCookieValues(httpOnly = true, secure = false),
  //        location = otoroshi.models.EntityLocation(),
  //        allowedUsers = Seq.empty,
  //        deniedUsers = Seq.empty
  //      )
  //      createAuthModule(moduleConfiguration).futureValue
  //
  //      val route = createRequestOtoroshiIORoute(
  //        Seq(
  //          NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
  //          NgPluginInstance(
  //            plugin = NgPluginHelper.pluginId[ApikeyCalls],
  //            config = NgPluginInstanceConfig(
  //              Json.obj(
  //                "mandatory"    -> false,
  //                "plugin_index" -> Json.obj(
  //                  "match_route"       -> 0,
  //                  "validate_access"   -> 1,
  //                  "transform_request" -> 1
  //                )
  //              )
  //            )
  //          ),
  //          NgPluginInstance(
  //            plugin = NgPluginHelper.pluginId[AuthModule],
  //            config = NgPluginInstanceConfig(
  //              NgAuthModuleConfig(module = moduleConfiguration.id.some, passWithApikey = true).json
  //                .as[JsObject]
  //                .deepMerge(
  //                  Json.obj(
  //                    "plugin_index" -> Json.obj(
  //                      "validate_access" -> 2
  //                    )
  //                  )
  //                )
  //            )
  //          )
  //        ),
  //        id = IdGenerator.uuid
  //      )
  //
  //      val playwright = Playwright.create()
  //      val browser    = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))
  //      val context    = browser.newContext()
  //      val page       = context.newPage()
  //
  //      page.navigate(s"http://${route.frontend.domains.head.domain}:$port")
  //
  //      page.locator("input[name='username']").click()
  //      page.locator("input[name='username']").fill("user@oto.tools")
  //      page.locator("input[name='password']").click()
  //      page.locator("input[name='password']").fill("password")
  //      page.fill("input[name='password']", "password")
  //      page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login")).nth(0).click()
  //
  //      page.content().contains("GET") mustBe true
  //
  //      val wsCookies: Seq[DefaultWSCookie] = context.cookies.asScala.map { c =>
  //        DefaultWSCookie(
  //          name = c.name,
  //          value = c.value,
  //          domain = Option(c.domain),
  //          path = Option(c.path).getOrElse("/").some,
  //          secure = c.secure,
  //          httpOnly = c.httpOnly
  //        )
  //      }
  //
  //      val callWithUser = ws
  //        .url(s"http://127.0.0.1:$port/.well-known/otoroshi/me")
  //        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //        .withCookies(wsCookies: _*)
  //        .get()
  //        .futureValue
  //
  //      callWithUser.status mustBe 200
  //      Json.parse(callWithUser.body).selectAsString("email") mustBe "user@oto.tools"
  //      Json.parse(callWithUser.body).selectAsString("name") mustBe "foo"
  //
  //      val apikey = ApiKey(
  //        clientId = "apikey-test",
  //        clientSecret = "1234",
  //        clientName = "apikey-test",
  //        authorizedEntities = Seq(RouteIdentifier(PLUGINS_ROUTE_ID))
  //      )
  //
  //      createOtoroshiApiKey(apikey).futureValue
  //
  //      val callWithApikey = ws
  //        .url(s"http://127.0.0.1:$port")
  //        .withHttpHeaders(
  //          "Host"                   -> route.frontend.domains.head.domain,
  //          "Otoroshi-Client-Id"     -> apikey.clientId,
  //          "Otoroshi-Client-Secret" -> apikey.clientSecret
  //        )
  //        .get()
  //        .futureValue
  //
  //      callWithApikey.status mustBe 200
  //
  //      val callWithoutApikey = ws
  //        .url(s"http://127.0.0.1:$port")
  //        .withFollowRedirects(false)
  //        .withHttpHeaders(
  //          "Host" -> route.frontend.domains.head.domain
  //        )
  //        .get()
  //        .futureValue
  //
  //      callWithoutApikey.status mustBe 303
  //
  //      browser.close()
  //      playwright.close()
  //
  //      deleteOtoroshiApiKey(apikey).futureValue
  //      deleteAuthModule(moduleConfiguration).futureValue
  //      deleteOtoroshiRoute(route).futureValue
  //    }
  //
  //    "Multi Authentication - one module" in {
  //      import com.microsoft.playwright._
  //
  //      val moduleConfiguration = BasicAuthModuleConfig(
  //        id = "BasicAuthModuleConfig",
  //        name = "BasicAuthModuleConfig",
  //        desc = "BasicAuthModuleConfig",
  //        users = Seq(
  //          BasicAuthUser(
  //            name = "foo",
  //            password = "$2a$10$RtYWagxgvorxpxNIYTi4Be2tU.n8294eHpwle1ad0Tmh7.NiVXOEq",
  //            email = "user@oto.tools",
  //            tags = Seq.empty,
  //            rights = UserRights(rights = Seq
  //            (UserRight(
  //              tenant = TenantAccess("*", canRead = true, canWrite = true),
  //              teams = Seq(TeamAccess("*", canRead = true, canWrite = true))))),
  //            adminEntityValidators = Map.empty
  //          )
  //        ),
  //        clientSideSessionEnabled = false,
  //        userValidators = Seq.empty,
  //        remoteValidators = Seq.empty,
  //        tags = Seq.empty,
  //        metadata = Map.empty,
  //        sessionCookieValues = SessionCookieValues(httpOnly = true, secure = false),
  //        location = otoroshi.models.EntityLocation(),
  //        allowedUsers = Seq.empty,
  //        deniedUsers = Seq.empty
  //      )
  //      createAuthModule(moduleConfiguration).futureValue
  //
  //      val route = createRequestOtoroshiIORoute(
  //        Seq(
  //          NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
  //          NgPluginInstance(
  //            plugin = NgPluginHelper.pluginId[MultiAuthModule],
  //            config = NgPluginInstanceConfig(
  //              NgMultiAuthModuleConfig(modules = Seq(moduleConfiguration.id))
  //                .json
  //                .as[JsObject]
  //            )
  //          )
  //        ),
  //        id = IdGenerator.uuid
  //      )
  //
  //      val playwright = Playwright.create()
  //      val browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))
  //      val context = browser.newContext()
  //      val page = context.newPage()
  //
  //      page.navigate(s"http://${route.frontend.domains.head.domain}:$port")
  //
  //      page.locator("input[name='username']").click()
  //      page.locator("input[name='username']").fill("user@oto.tools")
  //      page.locator("input[name='password']").click()
  //      page.locator("input[name='password']").fill("password")
  //      page.fill("input[name='password']", "password")
  //      page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login")).nth(0).click()
  //
  //      page.content().contains("GET") mustBe true
  //
  //      val wsCookies: Seq[DefaultWSCookie] = context.cookies.asScala.map { c =>
  //        DefaultWSCookie(
  //          name = c.name,
  //          value = c.value,
  //          domain = Option(c.domain),
  //          path = Option(c.path).getOrElse("/").some,
  //          secure = c.secure,
  //          httpOnly = c.httpOnly
  //        )
  //      }
  //
  //      val callWithUser = ws.url(s"http://127.0.0.1:$port/.well-known/otoroshi/me")
  //        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //        .withCookies(wsCookies:_*)
  //        .get()
  //        .futureValue
  //
  //      callWithUser.status mustBe 200
  //      Json.parse(callWithUser.body).selectAsString("email") mustBe "user@oto.tools"
  //      Json.parse(callWithUser.body).selectAsString("name") mustBe "foo"
  //
  //      val callWithoutCookies = ws.url(s"http://127.0.0.1:$port/.well-known/otoroshi/me")
  //        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //        .withFollowRedirects(false)
  //        .get()
  //        .futureValue
  //
  //      callWithoutCookies.status mustBe 401
  //
  //      val callWithoutCookies2 = ws.url(s"http://127.0.0.1:$port")
  //        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //        .withFollowRedirects(false)
  //        .get()
  //        .futureValue
  //
  //      callWithoutCookies2.status mustBe 303
  //
  //      browser.close()
  //      playwright.close()
  //
  //      deleteAuthModule(moduleConfiguration).futureValue
  //      deleteOtoroshiRoute(route).futureValue
  //    }
  //
  //    "Multi Authentication - keycloak and in memory authentication" in {
  //
  //      import com.microsoft.playwright._
  //
  //      def createBasicAuthModule(): BasicAuthModuleConfig = {
  //        val basicModuleConfiguration = BasicAuthModuleConfig(
  //          id = "inmemory",
  //          name = "inmemory",
  //          desc = "inmemory",
  //          users = Seq(
  //            BasicAuthUser(
  //              name = "foo",
  //              password = "$2a$10$RtYWagxgvorxpxNIYTi4Be2tU.n8294eHpwle1ad0Tmh7.NiVXOEq",
  //              email = "user@oto.tools",
  //              tags = Seq.empty,
  //              rights = UserRights(rights = Seq
  //              (UserRight(
  //                tenant = TenantAccess("*", canRead = true, canWrite = true),
  //                teams = Seq(TeamAccess("*", canRead = true, canWrite = true))))),
  //              adminEntityValidators = Map.empty
  //            )
  //          ),
  //          clientSideSessionEnabled = false,
  //          userValidators = Seq.empty,
  //          remoteValidators = Seq.empty,
  //          tags = Seq.empty,
  //          metadata = Map.empty,
  //          sessionCookieValues = SessionCookieValues(httpOnly = true, secure = false),
  //          location = otoroshi.models.EntityLocation(),
  //          allowedUsers = Seq.empty,
  //          deniedUsers = Seq.empty
  //        )
  //        createAuthModule(basicModuleConfiguration).futureValue
  //        basicModuleConfiguration
  //      }
  //
  //      def startKeycloakContainer(): GenericContainer = {
  //        val keycloakContainer = GenericContainer(
  //          dockerImage = "quay.io/keycloak/keycloak:26.4",
  //          exposedPorts = Seq(8080),
  //          env = Map(
  //            "KEYCLOAK_ADMIN" -> "admin",
  //            "KEYCLOAK_ADMIN_PASSWORD" -> "admin"
  //          ),
  //          command = Seq("start-dev"),
  //          waitStrategy = Wait.forHttp("/realms/master")
  //            .forPort(8080)
  //            .forStatusCode(200)
  //            .withStartupTimeout(java.time.Duration.ofMinutes(2))
  //        )
  //        keycloakContainer.start()
  //        keycloakContainer
  //      }
  //
  //      def getKeycloakUrl(container: GenericContainer): String =
  //        s"http://${container.host}:${container.mappedPort(8080)}"
  //
  //      def getClientConfig(): String = s"""{
  //        "clientId": "otoroshi",
  //        "name": "otoroshi",
  //        "description": "otoroshi",
  //        "rootUrl": "http://plugins.oto.tools:${port}",
  //        "adminUrl": "",
  //        "baseUrl": "http://plugins.oto.tools:$port",
  //        "surrogateAuthRequired": false,
  //        "enabled": true,
  //        "alwaysDisplayInConsole": true,
  //        "clientAuthenticatorType": "client-secret",
  //        "secret": "DF0LZqCtU85vOwH2lfqz6pxRF9hh5ALr",
  //        "redirectUris": [
  //          "http://privateapps.oto.tools:$port/privateapps/generic/callback*"
  //        ],
  //        "webOrigins": [
  //          "http://plugins.oto.tools:$port",
  //          "http://privateapp.oto.toos:$port"
  //        ],
  //        "notBefore": 0,
  //        "bearerOnly": false,
  //        "consentRequired": false,
  //        "standardFlowEnabled": true,
  //        "implicitFlowEnabled": false,
  //        "directAccessGrantsEnabled": true,
  //        "serviceAccountsEnabled": true,
  //        "authorizationServicesEnabled": true,
  //        "publicClient": false,
  //        "frontchannelLogout": true,
  //        "protocol": "openid-connect",
  //        "attributes": {
  //          "oidc.ciba.grant.enabled": "false",
  //          "backchannel.logout.session.required": "true",
  //          "login_theme": "keycloak",
  //          "post.logout.redirect.uris": "http://privateapps.oto.tools:$port/privateapps/generic/logout",
  //          "oauth2.device.authorization.grant.enabled": "false",
  //          "display.on.consent.screen": "false",
  //          "use.jwks.url": "false",
  //          "backchannel.logout.revoke.offline.tokens": "false"
  //        },
  //        "fullScopeAllowed": true,
  //        "protocolMappers": [
  //          {
  //            "name": "Client IP Address",
  //            "protocol": "openid-connect",
  //            "protocolMapper": "oidc-usersessionmodel-note-mapper",
  //            "consentRequired": false,
  //            "config": {
  //              "user.session.note": "clientAddress",
  //              "id.token.claim": "true",
  //              "access.token.claim": "true",
  //              "claim.name": "clientAddress",
  //              "jsonType.label": "String"
  //            }
  //          },
  //          {
  //            "name": "Client ID",
  //            "protocol": "openid-connect",
  //            "protocolMapper": "oidc-usersessionmodel-note-mapper",
  //            "consentRequired": false,
  //            "config": {
  //              "user.session.note": "client_id",
  //              "id.token.claim": "true",
  //              "access.token.claim": "true",
  //              "claim.name": "client_id",
  //              "jsonType.label": "String"
  //            }
  //          },
  //          {
  //            "name": "Client Host",
  //            "protocol": "openid-connect",
  //            "protocolMapper": "oidc-usersessionmodel-note-mapper",
  //            "consentRequired": false,
  //            "config": {
  //              "user.session.note": "clientHost",
  //              "id.token.claim": "true",
  //              "access.token.claim": "true",
  //              "claim.name": "clientHost",
  //              "jsonType.label": "String"
  //            }
  //          }
  //        ],
  //        "defaultClientScopes": [
  //          "web-origins",
  //          "acr",
  //          "roles",
  //          "profile",
  //          "email"
  //        ],
  //        "optionalClientScopes": [
  //          "address",
  //          "phone",
  //          "offline_access",
  //          "microprofile-jwt"
  //        ]
  //      }"""
  //
  //      def getAdminToken(keycloakUrl: String): String = {
  //        val tokenResponse = env.Ws.url(
  //          s"$keycloakUrl/realms/master/protocol/openid-connect/token"
  //        ).post(Map(
  //            "grant_type" -> "password",
  //            "client_id" -> "admin-cli",
  //            "username" -> "admin",
  //            "password" -> "admin"
  //        )).futureValue
  //        Json.parse(tokenResponse.body).selectAsString("access_token")
  //      }
  //
  //      def createKeycloakClient(keycloakUrl: String, adminToken: String, clientConfig: String): Unit = {
  //        val createClientResponse = env.Ws
  //          .url(s"$keycloakUrl/admin/realms/master/clients")
  //          .withHttpHeaders(
  //            "Authorization" -> s"Bearer $adminToken",
  //            "Content-Type" -> "application/json"
  //          )
  //          .post(clientConfig)
  //          .futureValue
  //        println("✓ Client 'otoroshi' created successfully")
  //      }
  //
  //      def createKeycloakUser(keycloakUrl: String, adminToken: String): Unit = {
  //        val userConfig = Json.obj(
  //          "username" -> "testuser",
  //          "email" -> "test@example.com",
  //          "firstName" -> "Test",
  //          "lastName" -> "User",
  //          "enabled" -> true,
  //          "emailVerified" -> true,
  //          "credentials" -> Json.arr(
  //            Json.obj(
  //              "type" -> "password",
  //              "value" -> "testpassword",
  //              "temporary" -> false
  //            )
  //          )
  //        )
  //
  //        val createUserResponse = env.Ws
  //          .url(s"$keycloakUrl/admin/realms/master/users")
  //          .withHttpHeaders(
  //            "Authorization" -> s"Bearer $adminToken",
  //            "Content-Type" -> "application/json"
  //          )
  //          .post(userConfig)
  //          .futureValue
  //        println("✓ Test user created: testuser / testpassword")
  //      }
  //
  //      def configureKeycloak(keycloakUrl: String): Future[Unit] = {
  //        val adminToken = getAdminToken(keycloakUrl)
  //        val clientConfig = getClientConfig()
  //        createKeycloakClient(keycloakUrl, adminToken, clientConfig)
  //        createKeycloakUser(keycloakUrl, adminToken)
  //        Future.successful(())
  //      }
  //
  //      def createOAuth2Module(keycloakHost: String, keycloakPort: Int): GenericOauth2ModuleConfig = {
  //        val oauth2Configuration = GenericOauth2ModuleConfig(
  //          id = "keycloak",
  //          name = "Keycloak",
  //          desc = "Keycloak",
  //          clientId = "otoroshi",
  //          clientSecret = "DF0LZqCtU85vOwH2lfqz6pxRF9hh5ALr",
  //          tokenUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/token",
  //          authorizeUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/auth",
  //          userInfoUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/userinfo",
  //          introspectionUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/token/introspect",
  //          loginUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/auth",
  //          logoutUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/logout",
  //          callbackUrl = s"http://privateapps.oto.tools:${port}/privateapps/generic/callback",
  //          scope = "openid roles phone web-origins profile email acr microprofile-jwt offline_access address",
  //          clientSideSessionEnabled = true,
  //          noWildcardRedirectURI = true,
  //          userValidators = Seq.empty,
  //          remoteValidators = Seq.empty,
  //          tags = Seq.empty,
  //          metadata = Map.empty,
  //          sessionCookieValues = SessionCookieValues(httpOnly = true, secure = false),
  //          location = otoroshi.models.EntityLocation(),
  //          allowedUsers = Seq.empty,
  //          deniedUsers = Seq.empty
  //        )
  //        createAuthModule(oauth2Configuration).futureValue
  //        oauth2Configuration
  //      }
  //
  //      def createRoute(basicModuleId: String, oauth2ModuleId: String) = {
  //        createRequestOtoroshiIORoute(
  //          Seq(
  //            NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
  //            NgPluginInstance(
  //              plugin = NgPluginHelper.pluginId[MultiAuthModule],
  //              config = NgPluginInstanceConfig(
  //                NgMultiAuthModuleConfig(modules = Seq(basicModuleId, oauth2ModuleId))
  //                  .json
  //                  .as[JsObject]
  //              )
  //            )
  //          ),
  //          id = IdGenerator.uuid
  //        )
  //      }
  //
  //      def verifyKeycloakTokenEndpoint(keycloakUrl: String) = {
  //        val response = env.Ws
  //          .url(s"$keycloakUrl/realms/master/protocol/openid-connect/token")
  //          .post(
  //            Map(
  //              "grant_type" -> Seq("password"),
  //              "client_id" -> Seq("otoroshi"),
  //              "client_secret" -> Seq("DF0LZqCtU85vOwH2lfqz6pxRF9hh5ALr"),
  //              "username" -> Seq("test@example.com"),
  //              "password" -> Seq("testpassword")
  //            )
  //          )
  //          .futureValue
  //
  //        response.status mustBe 200
  //        val accessToken = Json.parse(response.body).selectAsString("access_token")
  //        accessToken.isEmpty mustBe false
  //      }
  //
  //      def performKeycloakLogin(browser: Browser, route: NgRoute): BrowserContext = {
  //        val context = browser.newContext()
  //        val page = context.newPage()
  //
  //        page.navigate(s"http://${route.frontend.domains.head.domain}:$port")
  //        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Continue with keycloak")).click()
  //        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Username or email")).click()
  //        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Username or email")).fill("testuser")
  //        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Username or email")).press("Tab")
  //        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Password")).fill("testpassword")
  //        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign In")).click()
  //
  //        page.content().contains("GET") mustBe true
  //        context
  //      }
  //
  //      def extractCookies(context: BrowserContext): Seq[DefaultWSCookie] = {
  //        context.cookies.asScala.map { c =>
  //          DefaultWSCookie(
  //            name = c.name,
  //            value = c.value,
  //            domain = Option(c.domain),
  //            path = Option(c.path).getOrElse("/").some,
  //            secure = c.secure,
  //            httpOnly = c.httpOnly
  //          )
  //        }.toSeq
  //      }
  //
  //      def verifyAuthenticatedAccess(route: NgRoute, cookies: Seq[DefaultWSCookie]): Unit = {
  //        val callWithUser = ws.url(s"http://127.0.0.1:$port/.well-known/otoroshi/me")
  //          .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //          .withCookies(cookies:_*)
  //          .get()
  //          .futureValue
  //
  //        callWithUser.status mustBe 200
  //        Json.parse(callWithUser.body).selectAsString("email") mustBe "test@example.com"
  //        Json.parse(callWithUser.body).selectAsString("name") mustBe "Test User"
  //      }
  //
  //      def verifyUnauthenticatedAccess(route: NgRoute): Unit = {
  //        val callWithoutCookies = ws.url(s"http://127.0.0.1:$port/.well-known/otoroshi/me")
  //          .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //          .withFollowRedirects(false)
  //          .get()
  //          .futureValue
  //
  //        callWithoutCookies.status mustBe 401
  //
  //        val callWithoutCookies2 = ws.url(s"http://127.0.0.1:$port")
  //          .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //          .withFollowRedirects(false)
  //          .get()
  //          .futureValue
  //
  //        callWithoutCookies2.status mustBe 303
  //      }
  //
  //      val basicModuleConfiguration = createBasicAuthModule()
  //
  //      val keycloakContainer = startKeycloakContainer()
  //      val keycloakUrl = getKeycloakUrl(keycloakContainer)
  //      configureKeycloak(keycloakUrl).futureValue
  //
  //      val keycloakHost = keycloakContainer.host
  //      val keycloakPort = keycloakContainer.mappedPort(8080)
  //      val oauth2Configuration = createOAuth2Module(keycloakHost, keycloakPort)
  //
  //      val route = createRoute(basicModuleConfiguration.id, oauth2Configuration.id)
  //
  //      verifyKeycloakTokenEndpoint(keycloakUrl)
  //
  //      val playwright = Playwright.create()
  //      val browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))
  //      val context = performKeycloakLogin(browser, route)
  //      val wsCookies = extractCookies(context)
  //
  //      verifyAuthenticatedAccess(route, wsCookies)
  //      verifyUnauthenticatedAccess(route)
  //
  //      browser.close()
  //      playwright.close()
  //      deleteAuthModule(oauth2Configuration).futureValue
  //      deleteAuthModule(basicModuleConfiguration).futureValue
  //      deleteOtoroshiRoute(route).futureValue
  //      keycloakContainer.stop()
  //    }
  //
  //    "Multi Authentication - pass with apikey" in {
  //
  //      import com.microsoft.playwright._
  //
  //      def createBasicAuthModule(): BasicAuthModuleConfig = {
  //        val basicModuleConfiguration = BasicAuthModuleConfig(
  //          id = "inmemory",
  //          name = "inmemory",
  //          desc = "inmemory",
  //          users = Seq(
  //            BasicAuthUser(
  //              name = "foo",
  //              password = "$2a$10$RtYWagxgvorxpxNIYTi4Be2tU.n8294eHpwle1ad0Tmh7.NiVXOEq",
  //              email = "user@oto.tools",
  //              tags = Seq.empty,
  //              rights = UserRights(rights = Seq
  //              (UserRight(
  //                tenant = TenantAccess("*", canRead = true, canWrite = true),
  //                teams = Seq(TeamAccess("*", canRead = true, canWrite = true))))),
  //              adminEntityValidators = Map.empty
  //            )
  //          ),
  //          clientSideSessionEnabled = false,
  //          userValidators = Seq.empty,
  //          remoteValidators = Seq.empty,
  //          tags = Seq.empty,
  //          metadata = Map.empty,
  //          sessionCookieValues = SessionCookieValues(httpOnly = true, secure = false),
  //          location = otoroshi.models.EntityLocation(),
  //          allowedUsers = Seq.empty,
  //          deniedUsers = Seq.empty
  //        )
  //        createAuthModule(basicModuleConfiguration).futureValue
  //        basicModuleConfiguration
  //      }
  //
  //      def startKeycloakContainer(): GenericContainer = {
  //        val keycloakContainer = GenericContainer(
  //          dockerImage = "quay.io/keycloak/keycloak:26.4",
  //          exposedPorts = Seq(8080),
  //          env = Map(
  //            "KEYCLOAK_ADMIN" -> "admin",
  //            "KEYCLOAK_ADMIN_PASSWORD" -> "admin"
  //          ),
  //          command = Seq("start-dev"),
  //          waitStrategy = Wait.forHttp("/realms/master")
  //            .forPort(8080)
  //            .forStatusCode(200)
  //            .withStartupTimeout(java.time.Duration.ofMinutes(2))
  //        )
  //        keycloakContainer.start()
  //        keycloakContainer
  //      }
  //
  //      def getKeycloakUrl(container: GenericContainer): String =
  //        s"http://${container.host}:${container.mappedPort(8080)}"
  //
  //      def getClientConfig(): String = s"""{
  //        "clientId": "otoroshi",
  //        "name": "otoroshi",
  //        "description": "otoroshi",
  //        "rootUrl": "http://plugins.oto.tools:${port}",
  //        "adminUrl": "",
  //        "baseUrl": "http://plugins.oto.tools:$port",
  //        "surrogateAuthRequired": false,
  //        "enabled": true,
  //        "alwaysDisplayInConsole": true,
  //        "clientAuthenticatorType": "client-secret",
  //        "secret": "DF0LZqCtU85vOwH2lfqz6pxRF9hh5ALr",
  //        "redirectUris": [
  //          "http://privateapps.oto.tools:$port/privateapps/generic/callback*"
  //        ],
  //        "webOrigins": [
  //          "http://plugins.oto.tools:$port",
  //          "http://privateapp.oto.toos:$port"
  //        ],
  //        "notBefore": 0,
  //        "bearerOnly": false,
  //        "consentRequired": false,
  //        "standardFlowEnabled": true,
  //        "implicitFlowEnabled": false,
  //        "directAccessGrantsEnabled": true,
  //        "serviceAccountsEnabled": true,
  //        "authorizationServicesEnabled": true,
  //        "publicClient": false,
  //        "frontchannelLogout": true,
  //        "protocol": "openid-connect",
  //        "attributes": {
  //          "oidc.ciba.grant.enabled": "false",
  //          "backchannel.logout.session.required": "true",
  //          "login_theme": "keycloak",
  //          "post.logout.redirect.uris": "http://privateapps.oto.tools:$port/privateapps/generic/logout",
  //          "oauth2.device.authorization.grant.enabled": "false",
  //          "display.on.consent.screen": "false",
  //          "use.jwks.url": "false",
  //          "backchannel.logout.revoke.offline.tokens": "false"
  //        },
  //        "fullScopeAllowed": true,
  //        "protocolMappers": [
  //          {
  //            "name": "Client IP Address",
  //            "protocol": "openid-connect",
  //            "protocolMapper": "oidc-usersessionmodel-note-mapper",
  //            "consentRequired": false,
  //            "config": {
  //              "user.session.note": "clientAddress",
  //              "id.token.claim": "true",
  //              "access.token.claim": "true",
  //              "claim.name": "clientAddress",
  //              "jsonType.label": "String"
  //            }
  //          },
  //          {
  //            "name": "Client ID",
  //            "protocol": "openid-connect",
  //            "protocolMapper": "oidc-usersessionmodel-note-mapper",
  //            "consentRequired": false,
  //            "config": {
  //              "user.session.note": "client_id",
  //              "id.token.claim": "true",
  //              "access.token.claim": "true",
  //              "claim.name": "client_id",
  //              "jsonType.label": "String"
  //            }
  //          },
  //          {
  //            "name": "Client Host",
  //            "protocol": "openid-connect",
  //            "protocolMapper": "oidc-usersessionmodel-note-mapper",
  //            "consentRequired": false,
  //            "config": {
  //              "user.session.note": "clientHost",
  //              "id.token.claim": "true",
  //              "access.token.claim": "true",
  //              "claim.name": "clientHost",
  //              "jsonType.label": "String"
  //            }
  //          }
  //        ],
  //        "defaultClientScopes": [
  //          "web-origins",
  //          "acr",
  //          "roles",
  //          "profile",
  //          "email"
  //        ],
  //        "optionalClientScopes": [
  //          "address",
  //          "phone",
  //          "offline_access",
  //          "microprofile-jwt"
  //        ]
  //      }"""
  //
  //      def getAdminToken(keycloakUrl: String): String = {
  //        val tokenResponse = env.Ws.url(
  //          s"$keycloakUrl/realms/master/protocol/openid-connect/token"
  //        ).post(Map(
  //            "grant_type" -> "password",
  //            "client_id" -> "admin-cli",
  //            "username" -> "admin",
  //            "password" -> "admin"
  //        )).futureValue
  //        Json.parse(tokenResponse.body).selectAsString("access_token")
  //      }
  //
  //      def createKeycloakClient(keycloakUrl: String, adminToken: String, clientConfig: String): Unit = {
  //        val createClientResponse = env.Ws
  //          .url(s"$keycloakUrl/admin/realms/master/clients")
  //          .withHttpHeaders(
  //            "Authorization" -> s"Bearer $adminToken",
  //            "Content-Type" -> "application/json"
  //          )
  //          .post(clientConfig)
  //          .futureValue
  //        println("✓ Client 'otoroshi' created successfully")
  //      }
  //
  //      def createKeycloakUser(keycloakUrl: String, adminToken: String): Unit = {
  //        val userConfig = Json.obj(
  //          "username" -> "testuser",
  //          "email" -> "test@example.com",
  //          "firstName" -> "Test",
  //          "lastName" -> "User",
  //          "enabled" -> true,
  //          "emailVerified" -> true,
  //          "credentials" -> Json.arr(
  //            Json.obj(
  //              "type" -> "password",
  //              "value" -> "testpassword",
  //              "temporary" -> false
  //            )
  //          )
  //        )
  //
  //        val createUserResponse = env.Ws
  //          .url(s"$keycloakUrl/admin/realms/master/users")
  //          .withHttpHeaders(
  //            "Authorization" -> s"Bearer $adminToken",
  //            "Content-Type" -> "application/json"
  //          )
  //          .post(userConfig)
  //          .futureValue
  //        println("✓ Test user created: testuser / testpassword")
  //      }
  //
  //      def configureKeycloak(keycloakUrl: String): Future[Unit] = {
  //        val adminToken = getAdminToken(keycloakUrl)
  //        val clientConfig = getClientConfig()
  //        createKeycloakClient(keycloakUrl, adminToken, clientConfig)
  //        createKeycloakUser(keycloakUrl, adminToken)
  //        Future.successful(())
  //      }
  //
  //      def createOAuth2Module(keycloakHost: String, keycloakPort: Int): GenericOauth2ModuleConfig = {
  //        val oauth2Configuration = GenericOauth2ModuleConfig(
  //          id = "keycloak",
  //          name = "Keycloak",
  //          desc = "Keycloak",
  //          clientId = "otoroshi",
  //          clientSecret = "DF0LZqCtU85vOwH2lfqz6pxRF9hh5ALr",
  //          tokenUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/token",
  //          authorizeUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/auth",
  //          userInfoUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/userinfo",
  //          introspectionUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/token/introspect",
  //          loginUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/auth",
  //          logoutUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/logout",
  //          callbackUrl = s"http://privateapps.oto.tools:${port}/privateapps/generic/callback",
  //          scope = "openid roles phone web-origins profile email acr microprofile-jwt offline_access address",
  //          clientSideSessionEnabled = true,
  //          noWildcardRedirectURI = true,
  //          userValidators = Seq.empty,
  //          remoteValidators = Seq.empty,
  //          tags = Seq.empty,
  //          metadata = Map.empty,
  //          sessionCookieValues = SessionCookieValues(httpOnly = true, secure = false),
  //          location = otoroshi.models.EntityLocation(),
  //          allowedUsers = Seq.empty,
  //          deniedUsers = Seq.empty
  //        )
  //        createAuthModule(oauth2Configuration).futureValue
  //        oauth2Configuration
  //      }
  //
  //      def createRoute(basicModuleId: String, oauth2ModuleId: String) = {
  //        createRequestOtoroshiIORoute(
  //          Seq(
  //            NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
  //            NgPluginInstance(plugin = NgPluginHelper.pluginId[ApikeyCalls],
  //              config = NgPluginInstanceConfig(Json.obj(
  //                "mandatory" -> false,
  //                "plugin_index" -> Json.obj(
  //                  "match_route"        -> 0,
  //                  "validate_access"    -> 1,
  //                  "transform_request"  -> 1
  //                )
  //              ))),
  //            NgPluginInstance(
  //              plugin = NgPluginHelper.pluginId[MultiAuthModule],
  //              config = NgPluginInstanceConfig(
  //                NgMultiAuthModuleConfig(modules = Seq(basicModuleId, oauth2ModuleId), passWithApikey = true)
  //                  .json
  //                  .as[JsObject].deepMerge(Json.obj(
  //                    "plugin_index" -> Json.obj(
  //                     "validate_access"    -> 2
  //                   )
  //                  ))
  //              )
  //            )
  //          ),
  //          id = IdGenerator.uuid
  //        )
  //      }
  //
  //      def verifyKeycloakTokenEndpoint(keycloakUrl: String) = {
  //        val response = env.Ws
  //          .url(s"$keycloakUrl/realms/master/protocol/openid-connect/token")
  //          .post(
  //            Map(
  //              "grant_type" -> Seq("password"),
  //              "client_id" -> Seq("otoroshi"),
  //              "client_secret" -> Seq("DF0LZqCtU85vOwH2lfqz6pxRF9hh5ALr"),
  //              "username" -> Seq("test@example.com"),
  //              "password" -> Seq("testpassword")
  //            )
  //          )
  //          .futureValue
  //
  //        response.status mustBe 200
  //        val accessToken = Json.parse(response.body).selectAsString("access_token")
  //        accessToken.isEmpty mustBe false
  //      }
  //
  //      def performKeycloakLogin(browser: Browser, route: NgRoute): BrowserContext = {
  //        val context = browser.newContext()
  //        val page = context.newPage()
  //
  //        page.navigate(s"http://${route.frontend.domains.head.domain}:$port")
  //        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Continue with keycloak")).click()
  //        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Username or email")).click()
  //        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Username or email")).fill("testuser")
  //        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Username or email")).press("Tab")
  //        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Password")).fill("testpassword")
  //        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign In")).click()
  //
  //        page.content().contains("GET") mustBe true
  //        context
  //      }
  //
  //      def extractCookies(context: BrowserContext): Seq[DefaultWSCookie] = {
  //        context.cookies.asScala.map { c =>
  //          DefaultWSCookie(
  //            name = c.name,
  //            value = c.value,
  //            domain = Option(c.domain),
  //            path = Option(c.path).getOrElse("/").some,
  //            secure = c.secure,
  //            httpOnly = c.httpOnly
  //          )
  //        }.toSeq
  //      }
  //
  //      def verifyAuthenticatedAccess(route: NgRoute, cookies: Seq[DefaultWSCookie]): Unit = {
  //        val callWithUser = ws.url(s"http://127.0.0.1:$port/.well-known/otoroshi/me")
  //          .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //          .withCookies(cookies:_*)
  //          .get()
  //          .futureValue
  //
  //        callWithUser.status mustBe 200
  //        Json.parse(callWithUser.body).selectAsString("email") mustBe "test@example.com"
  //        Json.parse(callWithUser.body).selectAsString("name") mustBe "Test User"
  //      }
  //
  //      def verifyUnauthenticatedAccess(route: NgRoute): Unit = {
  //        val callWithoutCookies = ws.url(s"http://127.0.0.1:$port/.well-known/otoroshi/me")
  //          .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //          .withFollowRedirects(false)
  //          .get()
  //          .futureValue
  //
  //        callWithoutCookies.status mustBe 401
  //
  //        val callWithoutCookies2 = ws.url(s"http://127.0.0.1:$port")
  //          .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //          .withFollowRedirects(false)
  //          .get()
  //          .futureValue
  //
  //        callWithoutCookies2.status mustBe 303
  //      }
  //
  //      def callWithApikey(route: NgRoute): Unit = {
  //          val apikey = ApiKey(
  //          clientId = "apikey-test",
  //          clientSecret = "1234",
  //          clientName = "apikey-test",
  //          authorizedEntities = Seq(RouteIdentifier(PLUGINS_ROUTE_ID))
  //        )
  //
  //        createOtoroshiApiKey(apikey).futureValue
  //
  //        val callWithApikey = ws.url(s"http://127.0.0.1:$port")
  //          .withHttpHeaders(
  //            "Host" -> route.frontend.domains.head.domain,
  //            "Otoroshi-Client-Id"     -> apikey.clientId,
  //            "Otoroshi-Client-Secret" -> apikey.clientSecret
  //          )
  //          .get()
  //          .futureValue
  //
  //        callWithApikey.status mustBe 200
  //
  //        val callWithoutApikey = ws.url(s"http://127.0.0.1:$port")
  //          .withFollowRedirects(false)
  //          .withHttpHeaders(
  //            "Host" -> route.frontend.domains.head.domain
  //          )
  //          .get()
  //          .futureValue
  //
  //        callWithoutApikey.status mustBe 303
  //      }
  //
  //      val basicModuleConfiguration = createBasicAuthModule()
  //
  //      val keycloakContainer = startKeycloakContainer()
  //      val keycloakUrl = getKeycloakUrl(keycloakContainer)
  //      configureKeycloak(keycloakUrl).futureValue
  //
  //      val keycloakHost = keycloakContainer.host
  //      val keycloakPort = keycloakContainer.mappedPort(8080)
  //      val oauth2Configuration = createOAuth2Module(keycloakHost, keycloakPort)
  //
  //      val route = createRoute(basicModuleConfiguration.id, oauth2Configuration.id)
  //
  //      verifyKeycloakTokenEndpoint(keycloakUrl)
  //
  //      val playwright = Playwright.create()
  //      val browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))
  //      val context = performKeycloakLogin(browser, route)
  //      val wsCookies = extractCookies(context)
  //
  //      verifyAuthenticatedAccess(route, wsCookies)
  //      verifyUnauthenticatedAccess(route)
  //      callWithApikey(route)
  //
  //      browser.close()
  //      playwright.close()
  //      deleteAuthModule(oauth2Configuration).futureValue
  //      deleteAuthModule(basicModuleConfiguration).futureValue
  //      deleteOtoroshiRoute(route).futureValue
  //      keycloakContainer.stop()
  //    }
  //
  //    "Multi Authentication - email flow" in {
  //
  //      import com.microsoft.playwright._
  //
  //      def createBasicAuthModule(): BasicAuthModuleConfig = {
  //        val basicModuleConfiguration = BasicAuthModuleConfig(
  //          id = "inmemory",
  //          name = "inmemory",
  //          desc = "inmemory",
  //          users = Seq(
  //            BasicAuthUser(
  //              name = "foo",
  //              password = "$2a$10$RtYWagxgvorxpxNIYTi4Be2tU.n8294eHpwle1ad0Tmh7.NiVXOEq",
  //              email = "user@oto.tools",
  //              tags = Seq.empty,
  //              rights = UserRights(rights = Seq
  //              (UserRight(
  //                tenant = TenantAccess("*", canRead = true, canWrite = true),
  //                teams = Seq(TeamAccess("*", canRead = true, canWrite = true))))),
  //              adminEntityValidators = Map.empty
  //            )
  //          ),
  //          clientSideSessionEnabled = false,
  //          userValidators = Seq.empty,
  //          remoteValidators = Seq.empty,
  //          tags = Seq.empty,
  //          metadata = Map.empty,
  //          sessionCookieValues = SessionCookieValues(httpOnly = true, secure = false),
  //          location = otoroshi.models.EntityLocation(),
  //          allowedUsers = Seq.empty,
  //          deniedUsers = Seq.empty
  //        )
  //        createAuthModule(basicModuleConfiguration).futureValue
  //        basicModuleConfiguration
  //      }
  //
  //      def startKeycloakContainer(): GenericContainer = {
  //        val keycloakContainer = GenericContainer(
  //          dockerImage = "quay.io/keycloak/keycloak:26.4",
  //          exposedPorts = Seq(8080),
  //          env = Map(
  //            "KEYCLOAK_ADMIN" -> "admin",
  //            "KEYCLOAK_ADMIN_PASSWORD" -> "admin"
  //          ),
  //          command = Seq("start-dev"),
  //          waitStrategy = Wait.forHttp("/realms/master")
  //            .forPort(8080)
  //            .forStatusCode(200)
  //            .withStartupTimeout(java.time.Duration.ofMinutes(2))
  //        )
  //        keycloakContainer.start()
  //        keycloakContainer
  //      }
  //
  //      def getKeycloakUrl(container: GenericContainer): String =
  //        s"http://${container.host}:${container.mappedPort(8080)}"
  //
  //      def getClientConfig(): String = s"""{
  //        "clientId": "otoroshi",
  //        "name": "otoroshi",
  //        "description": "otoroshi",
  //        "rootUrl": "http://plugins.oto.tools:${port}",
  //        "adminUrl": "",
  //        "baseUrl": "http://plugins.oto.tools:$port",
  //        "surrogateAuthRequired": false,
  //        "enabled": true,
  //        "alwaysDisplayInConsole": true,
  //        "clientAuthenticatorType": "client-secret",
  //        "secret": "DF0LZqCtU85vOwH2lfqz6pxRF9hh5ALr",
  //        "redirectUris": [
  //          "http://privateapps.oto.tools:$port/privateapps/generic/callback*"
  //        ],
  //        "webOrigins": [
  //          "http://plugins.oto.tools:$port",
  //          "http://privateapp.oto.toos:$port"
  //        ],
  //        "notBefore": 0,
  //        "bearerOnly": false,
  //        "consentRequired": false,
  //        "standardFlowEnabled": true,
  //        "implicitFlowEnabled": false,
  //        "directAccessGrantsEnabled": true,
  //        "serviceAccountsEnabled": true,
  //        "authorizationServicesEnabled": true,
  //        "publicClient": false,
  //        "frontchannelLogout": true,
  //        "protocol": "openid-connect",
  //        "attributes": {
  //          "oidc.ciba.grant.enabled": "false",
  //          "backchannel.logout.session.required": "true",
  //          "login_theme": "keycloak",
  //          "post.logout.redirect.uris": "http://privateapps.oto.tools:$port/privateapps/generic/logout",
  //          "oauth2.device.authorization.grant.enabled": "false",
  //          "display.on.consent.screen": "false",
  //          "use.jwks.url": "false",
  //          "backchannel.logout.revoke.offline.tokens": "false"
  //        },
  //        "fullScopeAllowed": true,
  //        "protocolMappers": [
  //          {
  //            "name": "Client IP Address",
  //            "protocol": "openid-connect",
  //            "protocolMapper": "oidc-usersessionmodel-note-mapper",
  //            "consentRequired": false,
  //            "config": {
  //              "user.session.note": "clientAddress",
  //              "id.token.claim": "true",
  //              "access.token.claim": "true",
  //              "claim.name": "clientAddress",
  //              "jsonType.label": "String"
  //            }
  //          },
  //          {
  //            "name": "Client ID",
  //            "protocol": "openid-connect",
  //            "protocolMapper": "oidc-usersessionmodel-note-mapper",
  //            "consentRequired": false,
  //            "config": {
  //              "user.session.note": "client_id",
  //              "id.token.claim": "true",
  //              "access.token.claim": "true",
  //              "claim.name": "client_id",
  //              "jsonType.label": "String"
  //            }
  //          },
  //          {
  //            "name": "Client Host",
  //            "protocol": "openid-connect",
  //            "protocolMapper": "oidc-usersessionmodel-note-mapper",
  //            "consentRequired": false,
  //            "config": {
  //              "user.session.note": "clientHost",
  //              "id.token.claim": "true",
  //              "access.token.claim": "true",
  //              "claim.name": "clientHost",
  //              "jsonType.label": "String"
  //            }
  //          }
  //        ],
  //        "defaultClientScopes": [
  //          "web-origins",
  //          "acr",
  //          "roles",
  //          "profile",
  //          "email"
  //        ],
  //        "optionalClientScopes": [
  //          "address",
  //          "phone",
  //          "offline_access",
  //          "microprofile-jwt"
  //        ]
  //      }"""
  //
  //      def getAdminToken(keycloakUrl: String): String = {
  //        val tokenResponse = env.Ws.url(
  //          s"$keycloakUrl/realms/master/protocol/openid-connect/token"
  //        ).post(Map(
  //            "grant_type" -> "password",
  //            "client_id" -> "admin-cli",
  //            "username" -> "admin",
  //            "password" -> "admin"
  //        )).futureValue
  //        Json.parse(tokenResponse.body).selectAsString("access_token")
  //      }
  //
  //      def createKeycloakClient(keycloakUrl: String, adminToken: String, clientConfig: String): Unit = {
  //        val createClientResponse = env.Ws
  //          .url(s"$keycloakUrl/admin/realms/master/clients")
  //          .withHttpHeaders(
  //            "Authorization" -> s"Bearer $adminToken",
  //            "Content-Type" -> "application/json"
  //          )
  //          .post(clientConfig)
  //          .futureValue
  //        println("✓ Client 'otoroshi' created successfully")
  //      }
  //
  //      def createKeycloakUser(keycloakUrl: String, adminToken: String): Unit = {
  //        val userConfig = Json.obj(
  //          "username" -> "testuser",
  //          "email" -> "test@example.com",
  //          "firstName" -> "Test",
  //          "lastName" -> "User",
  //          "enabled" -> true,
  //          "emailVerified" -> true,
  //          "credentials" -> Json.arr(
  //            Json.obj(
  //              "type" -> "password",
  //              "value" -> "testpassword",
  //              "temporary" -> false
  //            )
  //          )
  //        )
  //
  //        val createUserResponse = env.Ws
  //          .url(s"$keycloakUrl/admin/realms/master/users")
  //          .withHttpHeaders(
  //            "Authorization" -> s"Bearer $adminToken",
  //            "Content-Type" -> "application/json"
  //          )
  //          .post(userConfig)
  //          .futureValue
  //        println("✓ Test user created: testuser / testpassword")
  //      }
  //
  //      def configureKeycloak(keycloakUrl: String): Future[Unit] = {
  //        val adminToken = getAdminToken(keycloakUrl)
  //        val clientConfig = getClientConfig()
  //        createKeycloakClient(keycloakUrl, adminToken, clientConfig)
  //        createKeycloakUser(keycloakUrl, adminToken)
  //        Future.successful(())
  //      }
  //
  //      def createOAuth2Module(keycloakHost: String, keycloakPort: Int): GenericOauth2ModuleConfig = {
  //        val oauth2Configuration = GenericOauth2ModuleConfig(
  //          id = "keycloak",
  //          name = "Keycloak",
  //          desc = "Keycloak",
  //          clientId = "otoroshi",
  //          clientSecret = "DF0LZqCtU85vOwH2lfqz6pxRF9hh5ALr",
  //          tokenUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/token",
  //          authorizeUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/auth",
  //          userInfoUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/userinfo",
  //          introspectionUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/token/introspect",
  //          loginUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/auth",
  //          logoutUrl = s"http://${keycloakHost}:${keycloakPort}/realms/master/protocol/openid-connect/logout",
  //          callbackUrl = s"http://privateapps.oto.tools:${port}/privateapps/generic/callback",
  //          scope = "openid roles phone web-origins profile email acr microprofile-jwt offline_access address",
  //          clientSideSessionEnabled = true,
  //          noWildcardRedirectURI = true,
  //          userValidators = Seq.empty,
  //          remoteValidators = Seq.empty,
  //          tags = Seq.empty,
  //          metadata = Map.empty,
  //          sessionCookieValues = SessionCookieValues(httpOnly = true, secure = false),
  //          location = otoroshi.models.EntityLocation(),
  //          allowedUsers = Seq.empty,
  //          deniedUsers = Seq.empty
  //        )
  //        createAuthModule(oauth2Configuration).futureValue
  //        oauth2Configuration
  //      }
  //
  //      def createRoute(basicModuleId: String, oauth2ModuleId: String) = {
  //        createRequestOtoroshiIORoute(
  //          Seq(
  //            NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
  //            NgPluginInstance(plugin = NgPluginHelper.pluginId[ApikeyCalls],
  //              config = NgPluginInstanceConfig(Json.obj(
  //                "mandatory" -> false,
  //                "plugin_index" -> Json.obj(
  //                  "match_route"        -> 0,
  //                  "validate_access"    -> 1,
  //                  "transform_request"  -> 1
  //                )
  //              ))),
  //            NgPluginInstance(
  //              plugin = NgPluginHelper.pluginId[MultiAuthModule],
  //              config = NgPluginInstanceConfig(
  //                NgMultiAuthModuleConfig(
  //                  modules = Seq(basicModuleId, oauth2ModuleId),
  //                  passWithApikey = true,
  //                  useEmailPrompt = true,
  //                  usersGroups = Json.obj(
  //                    oauth2ModuleId    -> Json.arr("test@example.com"),
  //                    basicModuleId     -> Json.arr("Wildcard(*@oto.tools)")
  //                  )
  //                )
  //                  .json
  //                  .as[JsObject].deepMerge(Json.obj(
  //                    "plugin_index" -> Json.obj(
  //                     "validate_access"    -> 2
  //                   )
  //                  ))
  //              )
  //            )
  //          ),
  //          id = IdGenerator.uuid
  //        )
  //      }
  //
  //      def verifyKeycloakTokenEndpoint(keycloakUrl: String) = {
  //        val response = env.Ws
  //          .url(s"$keycloakUrl/realms/master/protocol/openid-connect/token")
  //          .post(
  //            Map(
  //              "grant_type" -> Seq("password"),
  //              "client_id" -> Seq("otoroshi"),
  //              "client_secret" -> Seq("DF0LZqCtU85vOwH2lfqz6pxRF9hh5ALr"),
  //              "username" -> Seq("test@example.com"),
  //              "password" -> Seq("testpassword")
  //            )
  //          )
  //          .futureValue
  //
  //        response.status mustBe 200
  //        val accessToken = Json.parse(response.body).selectAsString("access_token")
  //        accessToken.isEmpty mustBe false
  //      }
  //
  //      def performInMemoryLogin(browser: Browser, route: NgRoute) = {
  //        val context = browser.newContext()
  //        val page = context.newPage()
  //
  //        page.navigate(s"http://${route.frontend.domains.head.domain}:$port")
  //
  //        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Email")).click()
  //        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Email")).fill("user@oto.tools")
  //        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Continue")).click()
  //
  //        page.locator("input[name='username']").click()
  //        page.locator("input[name='username']").fill("user@oto.tools")
  //        page.locator("input[name='password']").click()
  //        page.locator("input[name='password']").fill("password")
  //        page.fill("input[name='password']", "password")
  //        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login")).nth(0).click()
  //
  //        page.content().contains("GET") mustBe true
  //      }
  //
  //      def performKeycloakLogin(browser: Browser, route: NgRoute): BrowserContext = {
  //        val context = browser.newContext()
  //        val page = context.newPage()
  //
  //        page.navigate(s"http://${route.frontend.domains.head.domain}:$port")
  //
  //        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Email")).click()
  //        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Email")).fill("test@example.com")
  //        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Continue")).click()
  //
  //        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Username or email")).click()
  //        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Username or email")).fill("testuser")
  //        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Username or email")).press("Tab")
  //        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Password")).fill("testpassword")
  //        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign In")).click()
  //
  //        page.content().contains("GET") mustBe true
  //        context
  //      }
  //
  //      def extractCookies(context: BrowserContext): Seq[DefaultWSCookie] = {
  //        context.cookies.asScala.map { c =>
  //          DefaultWSCookie(
  //            name = c.name,
  //            value = c.value,
  //            domain = Option(c.domain),
  //            path = Option(c.path).getOrElse("/").some,
  //            secure = c.secure,
  //            httpOnly = c.httpOnly
  //          )
  //        }.toSeq
  //      }
  //
  //      def verifyAuthenticatedAccess(route: NgRoute, cookies: Seq[DefaultWSCookie]): Unit = {
  //        val callWithUser = ws.url(s"http://127.0.0.1:$port/.well-known/otoroshi/me")
  //          .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //          .withCookies(cookies:_*)
  //          .get()
  //          .futureValue
  //
  //        callWithUser.status mustBe 200
  //        Json.parse(callWithUser.body).selectAsString("email") mustBe "test@example.com"
  //        Json.parse(callWithUser.body).selectAsString("name") mustBe "Test User"
  //      }
  //
  //      def verifyUnauthenticatedAccess(route: NgRoute): Unit = {
  //        val callWithoutCookies = ws.url(s"http://127.0.0.1:$port/.well-known/otoroshi/me")
  //          .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //          .withFollowRedirects(false)
  //          .get()
  //          .futureValue
  //
  //        callWithoutCookies.status mustBe 401
  //
  //        val callWithoutCookies2 = ws.url(s"http://127.0.0.1:$port")
  //          .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //          .withFollowRedirects(false)
  //          .get()
  //          .futureValue
  //
  //        callWithoutCookies2.status mustBe 303
  //      }
  //
  //      def callWithApikey(route: NgRoute): Unit = {
  //          val apikey = ApiKey(
  //          clientId = "apikey-test",
  //          clientSecret = "1234",
  //          clientName = "apikey-test",
  //          authorizedEntities = Seq(RouteIdentifier(PLUGINS_ROUTE_ID))
  //        )
  //
  //        createOtoroshiApiKey(apikey).futureValue
  //
  //        val callWithApikey = ws.url(s"http://127.0.0.1:$port")
  //          .withHttpHeaders(
  //            "Host" -> route.frontend.domains.head.domain,
  //            "Otoroshi-Client-Id"     -> apikey.clientId,
  //            "Otoroshi-Client-Secret" -> apikey.clientSecret
  //          )
  //          .get()
  //          .futureValue
  //
  //        callWithApikey.status mustBe 200
  //
  //        val callWithoutApikey = ws.url(s"http://127.0.0.1:$port")
  //          .withFollowRedirects(false)
  //          .withHttpHeaders(
  //            "Host" -> route.frontend.domains.head.domain
  //          )
  //          .get()
  //          .futureValue
  //
  //        callWithoutApikey.status mustBe 303
  //      }
  //
  //      val basicModuleConfiguration = createBasicAuthModule()
  //
  //      val keycloakContainer = startKeycloakContainer()
  //      val keycloakUrl = getKeycloakUrl(keycloakContainer)
  //      configureKeycloak(keycloakUrl).futureValue
  //
  //      val keycloakHost = keycloakContainer.host
  //      val keycloakPort = keycloakContainer.mappedPort(8080)
  //      val oauth2Configuration = createOAuth2Module(keycloakHost, keycloakPort)
  //
  //      val route = createRoute(basicModuleConfiguration.id, oauth2Configuration.id)
  //
  //      verifyKeycloakTokenEndpoint(keycloakUrl)
  //
  //      val playwright = Playwright.create()
  //      val browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))
  //      val context = performKeycloakLogin(browser, route)
  //      performInMemoryLogin(browser, route)
  //      val wsCookies = extractCookies(context)
  //
  //      verifyAuthenticatedAccess(route, wsCookies)
  //      verifyUnauthenticatedAccess(route)
  //      callWithApikey(route)
  //
  //      browser.close()
  //      playwright.close()
  //      deleteAuthModule(oauth2Configuration).futureValue
  //      deleteAuthModule(basicModuleConfiguration).futureValue
  //      deleteOtoroshiRoute(route).futureValue
  //      keycloakContainer.stop()
  //    }
  //
  //    "OAuth2 caller - client credentials" in {
  //      def startKeycloakContainer(): GenericContainer = {
  //        val keycloakContainer = GenericContainer(
  //          dockerImage = "quay.io/keycloak/keycloak:26.4",
  //          exposedPorts = Seq(8080),
  //          env = Map(
  //            "KEYCLOAK_ADMIN" -> "admin",
  //            "KEYCLOAK_ADMIN_PASSWORD" -> "admin"
  //          ),
  //          command = Seq("start-dev"),
  //          waitStrategy = Wait.forHttp("/realms/master")
  //            .forPort(8080)
  //            .forStatusCode(200)
  //            .withStartupTimeout(java.time.Duration.ofMinutes(2))
  //        )
  //        keycloakContainer.start()
  //        keycloakContainer
  //      }
  //
  //      def getKeycloakUrl(container: GenericContainer): String =
  //        s"http://${container.host}:${container.mappedPort(8080)}"
  //
  //      def getClientConfig(): String = s"""{
  //        "clientId": "otoroshi",
  //        "name": "otoroshi",
  //        "description": "otoroshi",
  //        "rootUrl": "http://plugins.oto.tools:${port}",
  //        "adminUrl": "",
  //        "baseUrl": "http://plugins.oto.tools:$port",
  //        "surrogateAuthRequired": false,
  //        "enabled": true,
  //        "alwaysDisplayInConsole": true,
  //        "clientAuthenticatorType": "client-secret",
  //        "secret": "DF0LZqCtU85vOwH2lfqz6pxRF9hh5ALr",
  //        "redirectUris": [
  //          "http://privateapps.oto.tools:$port/privateapps/generic/callback*"
  //        ],
  //        "webOrigins": [
  //          "http://plugins.oto.tools:$port",
  //          "http://privateapp.oto.toos:$port"
  //        ],
  //        "notBefore": 0,
  //        "bearerOnly": false,
  //        "consentRequired": false,
  //        "standardFlowEnabled": true,
  //        "implicitFlowEnabled": false,
  //        "directAccessGrantsEnabled": true,
  //        "serviceAccountsEnabled": true,
  //        "authorizationServicesEnabled": true,
  //        "publicClient": false,
  //        "frontchannelLogout": true,
  //        "protocol": "openid-connect",
  //        "attributes": {
  //          "oidc.ciba.grant.enabled": "false",
  //          "backchannel.logout.session.required": "true",
  //          "login_theme": "keycloak",
  //          "post.logout.redirect.uris": "http://privateapps.oto.tools:$port/privateapps/generic/logout",
  //          "oauth2.device.authorization.grant.enabled": "false",
  //          "display.on.consent.screen": "false",
  //          "use.jwks.url": "false",
  //          "backchannel.logout.revoke.offline.tokens": "false"
  //        },
  //        "fullScopeAllowed": true,
  //        "protocolMappers": [
  //          {
  //            "name": "Client IP Address",
  //            "protocol": "openid-connect",
  //            "protocolMapper": "oidc-usersessionmodel-note-mapper",
  //            "consentRequired": false,
  //            "config": {
  //              "user.session.note": "clientAddress",
  //              "id.token.claim": "true",
  //              "access.token.claim": "true",
  //              "claim.name": "clientAddress",
  //              "jsonType.label": "String"
  //            }
  //          },
  //          {
  //            "name": "Client ID",
  //            "protocol": "openid-connect",
  //            "protocolMapper": "oidc-usersessionmodel-note-mapper",
  //            "consentRequired": false,
  //            "config": {
  //              "user.session.note": "client_id",
  //              "id.token.claim": "true",
  //              "access.token.claim": "true",
  //              "claim.name": "client_id",
  //              "jsonType.label": "String"
  //            }
  //          },
  //          {
  //            "name": "Client Host",
  //            "protocol": "openid-connect",
  //            "protocolMapper": "oidc-usersessionmodel-note-mapper",
  //            "consentRequired": false,
  //            "config": {
  //              "user.session.note": "clientHost",
  //              "id.token.claim": "true",
  //              "access.token.claim": "true",
  //              "claim.name": "clientHost",
  //              "jsonType.label": "String"
  //            }
  //          }
  //        ],
  //        "defaultClientScopes": [
  //          "web-origins",
  //          "acr",
  //          "roles",
  //          "profile",
  //          "email"
  //        ],
  //        "optionalClientScopes": [
  //          "address",
  //          "phone",
  //          "offline_access",
  //          "microprofile-jwt"
  //        ]
  //      }"""
  //
  //      def getAdminToken(keycloakUrl: String): String = {
  //        val tokenResponse = env.Ws.url(
  //          s"$keycloakUrl/realms/master/protocol/openid-connect/token"
  //        ).post(Map(
  //            "grant_type" -> "password",
  //            "client_id" -> "admin-cli",
  //            "username" -> "admin",
  //            "password" -> "admin"
  //        )).futureValue
  //        Json.parse(tokenResponse.body).selectAsString("access_token")
  //      }
  //
  //      def createKeycloakClient(keycloakUrl: String, adminToken: String, clientConfig: String): Unit = {
  //        val createClientResponse = env.Ws
  //          .url(s"$keycloakUrl/admin/realms/master/clients")
  //          .withHttpHeaders(
  //            "Authorization" -> s"Bearer $adminToken",
  //            "Content-Type" -> "application/json"
  //          )
  //          .post(clientConfig)
  //          .futureValue
  //        println("✓ Client 'otoroshi' created successfully")
  //      }
  //
  //      def configureKeycloak(keycloakUrl: String): Future[Unit] = {
  //        val adminToken = getAdminToken(keycloakUrl)
  //        val clientConfig = getClientConfig()
  //        createKeycloakClient(keycloakUrl, adminToken, clientConfig)
  //        Future.successful(())
  //      }
  //
  //      def createRoute(keycloakPort: Int) = {
  //        createRequestOtoroshiIORoute(
  //          Seq(
  //            NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
  //            NgPluginInstance(
  //              plugin = NgPluginHelper.pluginId[OAuth2Caller],
  //              config = NgPluginInstanceConfig(
  //                OAuth2CallerConfig(
  //                  kind        = OAuth2Kind.ClientCredentials,
  //                  url         = s"http://localhost:$keycloakPort/realms/master/protocol/openid-connect/token",
  //                  method      = "POST",
  //                  headerName  = "Authorization",
  //                  headerValueFormat   = "Bearer %s",
  //                  jsonPayload         = false,
  //                  clientId            = "otoroshi",
  //                  clientSecret        = "DF0LZqCtU85vOwH2lfqz6pxRF9hh5ALr",
  //                  scope               = Some("openid profile email"),
  //                  audience            = None,
  //                  user                = None,
  //                  password            = None,
  //                  cacheTokenSeconds   = (10L * 60L).seconds,
  //                  tlsConfig           = MtlsConfig()
  //                )
  //                  .json
  //                  .as[JsObject]
  //              )
  //            )
  //          ),
  //          id = IdGenerator.uuid
  //        )
  //      }
  //
  //      def verify(route: NgRoute): Unit = {
  //        val resp = ws.url(s"http://127.0.0.1:$port")
  //          .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //          .get()
  //          .futureValue
  //
  //        resp.status mustBe 200
  //        resp.body.contains("GET") mustBe true
  //      }
  //
  //      val keycloakContainer = startKeycloakContainer()
  //      val keycloakUrl = getKeycloakUrl(keycloakContainer)
  //      configureKeycloak(keycloakUrl).futureValue
  //
  //      val route = createRoute(keycloakContainer.mappedPort(8080))
  //      verify(route)
  //
  //      deleteOtoroshiRoute(route).futureValue
  //      keycloakContainer.stop()
  //    }
  //
  //    "OAuth2 caller - password flow" in {
  //      def startKeycloakContainer(): GenericContainer = {
  //        val keycloakContainer = GenericContainer(
  //          dockerImage = "quay.io/keycloak/keycloak:26.4",
  //          exposedPorts = Seq(8080),
  //          env = Map(
  //            "KEYCLOAK_ADMIN" -> "admin",
  //            "KEYCLOAK_ADMIN_PASSWORD" -> "admin"
  //          ),
  //          command = Seq("start-dev"),
  //          waitStrategy = Wait.forHttp("/realms/master")
  //            .forPort(8080)
  //            .forStatusCode(200)
  //            .withStartupTimeout(java.time.Duration.ofMinutes(2))
  //        )
  //        keycloakContainer.start()
  //        keycloakContainer
  //      }
  //
  //      def getKeycloakUrl(container: GenericContainer): String =
  //        s"http://${container.host}:${container.mappedPort(8080)}"
  //
  //      def getClientConfig(): String = s"""{
  //        "clientId": "otoroshi",
  //        "name": "otoroshi",
  //        "description": "otoroshi",
  //        "rootUrl": "http://plugins.oto.tools:${port}",
  //        "adminUrl": "",
  //        "baseUrl": "http://plugins.oto.tools:$port",
  //        "surrogateAuthRequired": false,
  //        "enabled": true,
  //        "alwaysDisplayInConsole": true,
  //        "clientAuthenticatorType": "client-secret",
  //        "secret": "DF0LZqCtU85vOwH2lfqz6pxRF9hh5ALr",
  //        "redirectUris": [
  //          "http://privateapps.oto.tools:$port/privateapps/generic/callback*"
  //        ],
  //        "webOrigins": [
  //          "http://plugins.oto.tools:$port",
  //          "http://privateapp.oto.toos:$port"
  //        ],
  //        "notBefore": 0,
  //        "bearerOnly": false,
  //        "consentRequired": false,
  //        "standardFlowEnabled": true,
  //        "implicitFlowEnabled": false,
  //        "directAccessGrantsEnabled": true,
  //        "serviceAccountsEnabled": true,
  //        "authorizationServicesEnabled": true,
  //        "publicClient": false,
  //        "frontchannelLogout": true,
  //        "protocol": "openid-connect",
  //        "attributes": {
  //          "oidc.ciba.grant.enabled": "false",
  //          "backchannel.logout.session.required": "true",
  //          "login_theme": "keycloak",
  //          "post.logout.redirect.uris": "http://privateapps.oto.tools:$port/privateapps/generic/logout",
  //          "oauth2.device.authorization.grant.enabled": "false",
  //          "display.on.consent.screen": "false",
  //          "use.jwks.url": "false",
  //          "backchannel.logout.revoke.offline.tokens": "false"
  //        },
  //        "fullScopeAllowed": true,
  //        "protocolMappers": [
  //          {
  //            "name": "Client IP Address",
  //            "protocol": "openid-connect",
  //            "protocolMapper": "oidc-usersessionmodel-note-mapper",
  //            "consentRequired": false,
  //            "config": {
  //              "user.session.note": "clientAddress",
  //              "id.token.claim": "true",
  //              "access.token.claim": "true",
  //              "claim.name": "clientAddress",
  //              "jsonType.label": "String"
  //            }
  //          },
  //          {
  //            "name": "Client ID",
  //            "protocol": "openid-connect",
  //            "protocolMapper": "oidc-usersessionmodel-note-mapper",
  //            "consentRequired": false,
  //            "config": {
  //              "user.session.note": "client_id",
  //              "id.token.claim": "true",
  //              "access.token.claim": "true",
  //              "claim.name": "client_id",
  //              "jsonType.label": "String"
  //            }
  //          },
  //          {
  //            "name": "Client Host",
  //            "protocol": "openid-connect",
  //            "protocolMapper": "oidc-usersessionmodel-note-mapper",
  //            "consentRequired": false,
  //            "config": {
  //              "user.session.note": "clientHost",
  //              "id.token.claim": "true",
  //              "access.token.claim": "true",
  //              "claim.name": "clientHost",
  //              "jsonType.label": "String"
  //            }
  //          }
  //        ],
  //        "defaultClientScopes": [
  //          "web-origins",
  //          "acr",
  //          "roles",
  //          "profile",
  //          "email"
  //        ],
  //        "optionalClientScopes": [
  //          "address",
  //          "phone",
  //          "offline_access",
  //          "microprofile-jwt"
  //        ]
  //      }"""
  //
  //      def getAdminToken(keycloakUrl: String): String = {
  //        val tokenResponse = env.Ws.url(
  //          s"$keycloakUrl/realms/master/protocol/openid-connect/token"
  //        ).post(Map(
  //            "grant_type" -> "password",
  //            "client_id" -> "admin-cli",
  //            "username" -> "admin",
  //            "password" -> "admin"
  //        )).futureValue
  //        Json.parse(tokenResponse.body).selectAsString("access_token")
  //      }
  //
  //      def createKeycloakClient(keycloakUrl: String, adminToken: String, clientConfig: String): Unit = {
  //        val createClientResponse = env.Ws
  //          .url(s"$keycloakUrl/admin/realms/master/clients")
  //          .withHttpHeaders(
  //            "Authorization" -> s"Bearer $adminToken",
  //            "Content-Type" -> "application/json"
  //          )
  //          .post(clientConfig)
  //          .futureValue
  //        println("✓ Client 'otoroshi' created successfully")
  //      }
  //
  //      def createKeycloakUser(keycloakUrl: String, adminToken: String): Unit = {
  //        val userConfig = Json.obj(
  //          "username" -> "testuser",
  //          "email" -> "test@example.com",
  //          "firstName" -> "Test",
  //          "lastName" -> "User",
  //          "enabled" -> true,
  //          "emailVerified" -> true,
  //          "credentials" -> Json.arr(
  //            Json.obj(
  //              "type" -> "password",
  //              "value" -> "testpassword",
  //              "temporary" -> false
  //            )
  //          )
  //        )
  //
  //        val createUserResponse = env.Ws
  //          .url(s"$keycloakUrl/admin/realms/master/users")
  //          .withHttpHeaders(
  //            "Authorization" -> s"Bearer $adminToken",
  //            "Content-Type" -> "application/json"
  //          )
  //          .post(userConfig)
  //          .futureValue
  //        println("✓ Test user created: testuser / testpassword")
  //      }
  //
  //      def configureKeycloak(keycloakUrl: String): Future[Unit] = {
  //        val adminToken = getAdminToken(keycloakUrl)
  //        val clientConfig = getClientConfig()
  //        createKeycloakClient(keycloakUrl, adminToken, clientConfig)
  //        createKeycloakUser(keycloakUrl, adminToken)
  //        Future.successful(())
  //      }
  //
  //      def createRoute(keycloakPort: Int) = {
  //        createRequestOtoroshiIORoute(
  //          Seq(
  //            NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
  //            NgPluginInstance(
  //              plugin = NgPluginHelper.pluginId[OAuth2Caller],
  //              config = NgPluginInstanceConfig(
  //                OAuth2CallerConfig(
  //                  kind        = OAuth2Kind.Password,
  //                  url         = s"http://localhost:$keycloakPort/realms/master/protocol/openid-connect/token",
  //                  method      = "POST",
  //                  headerName  = "Authorization",
  //                  headerValueFormat   = "Bearer %s",
  //                  jsonPayload         = false,
  //                  clientId            = "otoroshi",
  //                  clientSecret        = "DF0LZqCtU85vOwH2lfqz6pxRF9hh5ALr",
  //                  scope               = Some("openid profile email"),
  //                  audience            = None,
  //                  user                = "test@example.com".some,
  //                  password            = "testpassword".some,
  //                  cacheTokenSeconds   = (10L * 60L).seconds,
  //                  tlsConfig           = MtlsConfig()
  //                )
  //                  .json
  //                  .as[JsObject]
  //              )
  //            )
  //          ),
  //          id = IdGenerator.uuid
  //        )
  //      }
  //
  //      def verifyKeycloakTokenEndpoint(keycloakUrl: String) = {
  //        val response = env.Ws
  //          .url(s"$keycloakUrl/realms/master/protocol/openid-connect/token")
  //          .post(
  //            Map(
  //              "grant_type" -> Seq("password"),
  //              "client_id" -> Seq("otoroshi"),
  //              "client_secret" -> Seq("DF0LZqCtU85vOwH2lfqz6pxRF9hh5ALr"),
  //              "username" -> Seq("test@example.com"),
  //              "password" -> Seq("testpassword")
  //            )
  //          )
  //          .futureValue
  //
  //        response.status mustBe 200
  //        val accessToken = Json.parse(response.body).selectAsString("access_token")
  //        accessToken.isEmpty mustBe false
  //      }
  //
  //      def verify(route: NgRoute): Unit = {
  //        val resp = ws.url(s"http://127.0.0.1:$port")
  //          .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //          .get()
  //          .futureValue
  //
  //        resp.status mustBe 200
  //        resp.body.contains("GET") mustBe true
  //      }
  //
  //      val keycloakContainer = startKeycloakContainer()
  //      val keycloakUrl = getKeycloakUrl(keycloakContainer)
  //      configureKeycloak(keycloakUrl).futureValue
  //
  //      verifyKeycloakTokenEndpoint(keycloakUrl)
  //
  //      val route = createRoute(keycloakContainer.mappedPort(8080))
  //      verify(route)
  //
  //      deleteOtoroshiRoute(route).futureValue
  //      keycloakContainer.stop()
  //    }
  //
  //    "Simple Basic Auth" in {
  //      def createRoute() = {
  //        createRequestOtoroshiIORoute(
  //          Seq(
  //            NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
  //            NgPluginInstance(
  //              plugin = NgPluginHelper.pluginId[SimpleBasicAuth],
  //              config = NgPluginInstanceConfig(
  //                SimpleBasicAuthConfig(
  //                 users = Map("foo"-> "bar")
  //                )
  //                  .json
  //                  .as[JsObject]
  //              )
  //            )
  //          ),
  //          id = IdGenerator.uuid
  //        )
  //      }
  //
  //      def verify(route: NgRoute): Unit = {
  //        val resp = ws.url(s"http://127.0.0.1:$port")
  //          .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //          .get()
  //          .futureValue
  //
  //        resp.status mustBe 401
  //
  //        val callWithUser = ws.url(s"http://127.0.0.1:$port")
  //          .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
  //          .withAuth("foo", "bar", WSAuthScheme.BASIC)
  //          .get()
  //          .futureValue
  //
  //        callWithUser.status mustBe 200
  //      }
  //
  //      val route = createRoute()
  //      verify(route)
  //
  //      deleteOtoroshiRoute(route).futureValue
  //    }
  //
  //    "Basic Auth Caller" in {
  //      def simpleBasicAuthRoute(): NgRoute = {
  //        createRequestOtoroshiIORoute(
  //          Seq(
  //            NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
  //            NgPluginInstance(
  //              plugin = NgPluginHelper.pluginId[SimpleBasicAuth],
  //              config = NgPluginInstanceConfig(
  //                SimpleBasicAuthConfig(
  //                 users = Map("foo"-> "bar")
  //                )
  //                  .json
  //                  .as[JsObject]
  //              )
  //            )
  //          ),
  //          id = IdGenerator.uuid,
  //          domain = "basiauth.oto.tools"
  //        )
  //      }
  //
  //      def basicAuthCallerRoute(): NgRoute = {
  //        createRequestOtoroshiIORoute(
  //          Seq(
  //            NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
  //            NgPluginInstance(
  //              plugin = NgPluginHelper.pluginId[BasicAuthCaller],
  //              config = NgPluginInstanceConfig(
  //                BasicAuthCallerConfig(
  //                  username = "foo".some,
  //                  password = "bar".some
  //                )
  //                  .json
  //                  .as[JsObject]
  //              )
  //            )
  //          ),
  //          id = IdGenerator.uuid,
  //          domain = "basiauth.oto.tools"
  //        )
  //      }
  //
  //      def verify(simpleBasicAuthRoute: NgRoute): Unit = {
  //        val resp = ws.url(s"http://127.0.0.1:$port")
  //          .withHttpHeaders("Host" -> simpleBasicAuthRoute.frontend.domains.head.domain)
  //          .get()
  //          .futureValue
  //
  //        resp.status mustBe 401
  //
  //        val callWithUser = ws.url(s"http://127.0.0.1:$port")
  //          .withHttpHeaders("Host" -> simpleBasicAuthRoute.frontend.domains.head.domain)
  //          .withAuth("foo", "bar", WSAuthScheme.BASIC)
  //          .get()
  //          .futureValue
  //
  //        callWithUser.status mustBe 200
  //      }
  //
  //      val basicAuthRoute = simpleBasicAuthRoute()
  //      val callerRouter = basicAuthCallerRoute()
  //      verify(basicAuthRoute)
  //
  //      deleteOtoroshiRoute(basicAuthRoute).futureValue
  //      deleteOtoroshiRoute(callerRouter).futureValue
  //    }
  //
  //    "Time Restricted Access Plugin" in {
  //      val dnow = DateTime.now()
  //      val now = LocalTime.now()
  //      val route = createRequestOtoroshiIORoute(
  //        Seq(
  //          NgPluginInstance(
  //            plugin = NgPluginHelper.pluginId[OverrideHost]
  //          ),
  //          NgPluginInstance(
  //            plugin = NgPluginHelper.pluginId[TimeRestrictedAccessPlugin],
  //            config = NgPluginInstanceConfig(
  //              TimeRestrictedAccessPluginConfig(
  //                rules = Seq(
  //                  TimeRestrictedAccessPluginConfigRule(
  //                    timeStart = now.plusSeconds(5),
  //                    timeEnd = now.plusSeconds(10),
  //                    dayStart = dnow.getDayOfWeek,
  //                    dayEnd = dnow.getDayOfWeek,
  //                  ),
  //                  TimeRestrictedAccessPluginConfigRule(
  //                    timeStart = now.plusSeconds(15),
  //                    timeEnd = now.plusSeconds(20),
  //                    dayStart = dnow.getDayOfWeek,
  //                    dayEnd = dnow.getDayOfWeek,
  //                  )
  //                )
  //              ).json.as[JsObject]
  //            )
  //          )
  //        )
  //      )
  //
  //      def call(): Int = {
  //        ws
  //          .url(s"http://127.0.0.1:$port/api")
  //          .withHttpHeaders(
  //            "Host" -> route.frontend.domains.head.domain,
  //          )
  //          .get()
  //          .futureValue
  //          .status
  //      }
  //
  //      call() mustBe 403
  //
  //      await(6.seconds)
  //
  //      call() mustBe Status.OK
  //
  //      await(5.seconds)
  //
  //      call() mustBe 403
  //
  //      await(5.seconds)
  //
  //      call() mustBe Status.OK
  //
  //      await(5.seconds)
  //
  //      call() mustBe 403
  //
  //      deleteOtoroshiRoute(route).futureValue
  //    }
  //
  //    "Security Headers Plugin" in {
  //      val route = createRequestOtoroshiIORoute(
  //        Seq(
  //          NgPluginInstance(
  //            plugin = NgPluginHelper.pluginId[OverrideHost]
  //          ),
  //          NgPluginInstance(
  //            plugin = NgPluginHelper.pluginId[SecurityHeadersPlugin],
  //            config = NgPluginInstanceConfig(
  //              SecurityHeadersPluginConfig(
  //                frameOptions = FrameOptions.SAMEORIGIN,
  //                xssProtection = XssProtection.BLOCK,
  //                contentTypeOptions = true,
  //                hsts = HstsConf(
  //                  enabled = true,
  //                  includeSubdomains = true,
  //                  maxAge = 1000,
  //                  preload = true,
  //                  onHttp = true,
  //                ),
  //                csp = CspConf(ENABLED, "default-src none; script-src self; connect-src self; img-src self; style-src self;")
  //              ).json.as[JsObject]
  //            )
  //          )
  //        )
  //      )
  //
  //      def call(): Map[String, String] = {
  //        ws
  //          .url(s"http://127.0.0.1:$port/api")
  //          .withHttpHeaders(
  //            "Host" -> route.frontend.domains.head.domain,
  //          )
  //          .get()
  //          .futureValue
  //          .headers
  //          .mapValues(_.last)
  //      }
  //
  //      val headers = call()
  //
  //      headers("X-Frame-Options") mustBe "SAMEORIGIN"
  //      headers("X-XSS-Protection") mustBe "1; mode=block"
  //      headers("X-Content-Type-Options") mustBe "nosniff"
  //      headers("Strict-Transport-Security") mustBe "max-age=1000; includeSubDomains; preload"
  //      headers("Content-Security-Policy") mustBe "default-src none; script-src self; connect-src self; img-src self; style-src self;"
  //
  //      deleteOtoroshiRoute(route).futureValue
  //    }
  //  }
}

// "Time Restricted Access Plugin" in {
//       val dnow  = DateTime.now()
//       val now   = LocalTime.now()
//       val route = createRequestOtoroshiIORoute(
//         Seq(
//           NgPluginInstance(
//             plugin = NgPluginHelper.pluginId[OverrideHost]
//           ),
//           NgPluginInstance(
//             plugin = NgPluginHelper.pluginId[TimeRestrictedAccessPlugin],
//             config = NgPluginInstanceConfig(
//               TimeRestrictedAccessPluginConfig(
//                 rules = Seq(
//                   TimeRestrictedAccessPluginConfigRule(
//                     timeStart = now.plusSeconds(5),
//                     timeEnd = now.plusSeconds(10),
//                     dayStart = dnow.getDayOfWeek,
//                     dayEnd = dnow.getDayOfWeek
//                   ),
//                   TimeRestrictedAccessPluginConfigRule(
//                     timeStart = now.plusSeconds(15),
//                     timeEnd = now.plusSeconds(20),
//                     dayStart = dnow.getDayOfWeek,
//                     dayEnd = dnow.getDayOfWeek
//                   )
//                 )
//               ).json.as[JsObject]
//             )
//           )
//         )
//       )

//       def call(): Int = {
//         ws
//           .url(s"http://127.0.0.1:$port/api")
//           .withHttpHeaders(
//             "Host" -> route.frontend.domains.head.domain
//           )
//           .get()
//           .futureValue
//           .status
//       }

//       call() mustBe 403

//       await(6.seconds)

//       call() mustBe Status.OK

//       await(5.seconds)

//       call() mustBe 403

//       await(5.seconds)

//       call() mustBe Status.OK

//       await(5.seconds)

//       call() mustBe 403

//       deleteOtoroshiRoute(route).futureValue
//     }

//     "Security Headers Plugin" in {
//       val route = createRequestOtoroshiIORoute(
//         Seq(
//           NgPluginInstance(
//             plugin = NgPluginHelper.pluginId[OverrideHost]
//           ),
//           NgPluginInstance(
//             plugin = NgPluginHelper.pluginId[SecurityHeadersPlugin],
//             config = NgPluginInstanceConfig(
//               SecurityHeadersPluginConfig(
//                 frameOptions = FrameOptions.SAMEORIGIN,
//                 xssProtection = XssProtection.BLOCK,
//                 contentTypeOptions = true,
//                 hsts = HstsConf(
//                   enabled = true,
//                   includeSubdomains = true,
//                   maxAge = 1000,
//                   preload = true,
//                   onHttp = true
//                 ),
//                 csp =
//                   CspConf(ENABLED, "default-src none; script-src self; connect-src self; img-src self; style-src self;")
//               ).json.as[JsObject]
//             )
//           )
//         )
//       )

//       def call(): Map[String, String] = {
//         ws
//           .url(s"http://127.0.0.1:$port/api")
//           .withHttpHeaders(
//             "Host" -> route.frontend.domains.head.domain
//           )
//           .get()
//           .futureValue
//           .headers
//           .mapValues(_.last)
//       }

//       val headers = call()

//       headers("X-Frame-Options") mustBe "SAMEORIGIN"
//       headers("X-XSS-Protection") mustBe "1; mode=block"
//       headers("X-Content-Type-Options") mustBe "nosniff"
//       headers("Strict-Transport-Security") mustBe "max-age=1000; includeSubDomains; preload"
//       headers(
//         "Content-Security-Policy"
//       ) mustBe "default-src none; script-src self; connect-src self; img-src self; style-src self;"

//       deleteOtoroshiRoute(route).futureValue
//     }
