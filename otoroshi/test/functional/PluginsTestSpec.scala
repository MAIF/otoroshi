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
    "Override Location Header: redirect to domain path" in {
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
    "Basic Auth. caller - check value" in {
      new BasicAuthCallerTests(this).checkValue()
    }
    "Basic Auth. caller - check process" in {
      new BasicAuthCallerTests(this).checkProcess()
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
    "Websocket json format validator (close connection)" in {
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
    "Time Restricted Access Plugin" in {
      new TimeRestrictedAccessPluginTests(this)
    }
    "Security Headers Plugin" in {
      new SecurityHeadersPluginTests(this)
    }
    "Static backend" in {
      new StaticBackendTests(this)
    }
    "Context Validator" in {
      new ContextValidatorTests(this)
    }
    "HTTP Client Cache - add cache headers when method, status, and content-type match" in {
      new HTTPClientCacheTests(this).addCacheHeadersWhenMethodStatusAndContentTypeMatch()
    }
    "HTTP Client Cache - does not add cache headers if HTTP method does not match" in {
      new HTTPClientCacheTests(this).doesNotAddCacheHeadersIfHTTPMethodDoesNotMatch()
    }
    "HTTP Client Cache - does not add cache headers if status does not match" in {
      new HTTPClientCacheTests(this).doesNotAddCacheHeadersIfStatusDoesNotMatch()
    }
    "HTTP Client Cache - does not add cache headers if content type does not match" in {
      new HTTPClientCacheTests(this).doesNotAddCacheHeadersIfContentTypeDoesNotMatch()
    }
    "HTTP Client Cache - matches wildcard mime type '*'" in {
      new HTTPClientCacheTests(this).matchesWildcardMimeType()
    }
    "Authentication - in memory" in {
      new AuthenticationTests(this).inMemory()
    }
    "Authentication - pass with apikey" in {
      new AuthenticationTests(this).passWithApikey()
    }
    "Multi Authentication - one module" in {
      new MultiAuthenticationTests(this).oneModule()
    }
    "Multi Authentication - keycloak and in memory authentication" in {
      new MultiAuthenticationTests(this).keycloakAndInMemoryAuthentication()
    }
    "Multi Authentication - pass with apikey" in {
      new MultiAuthenticationTests(this).passWithApikey()
    }
    "Multi Authentication - email flow" in {
      new MultiAuthenticationTests(this).emailFlow()
    }
    "OAuth2 caller - password flow" in {
      new OAuth2CallerTests(this)
    }
    "Simple Basic Auth" in {
      new SimpleBasicAuthTests(this)
    }
    "Cookies validation" in {
      new CookiesValidationTests(this)
    }
    "Image Replacer" in {
      new ImageReplacerTests(this)
    }
    "External request validator - pass validator" in {
      new ExternalRequestValidatorTests(this).valid()
    }
    "External request validator - reject validator" in {
      new ExternalRequestValidatorTests(this).rejectRequest()
    }
    "Send otoroshi headers back" in {
      new SendOtoroshiHeadersBackTests(this)
    }
    "User profile endpoint" in {
      new UserProfileEndpointTests(this)
    }
    "Apikey mandatory metadata" in {
      new ApikeyMandatoryMetadataTests(this)
    }
    "Robots" in {
      new RobotsTests(this)
    }
    "User logged in expected" in {
      new NgAuthModuleExpectedUserTests(this)
    }
    "IP block list" in {
      new IpAddressBlockListTests(this)
    }
    "IP Allowed list" in {
      new IpAddressAllowedListTests(this)
    }
    "Request body json-to-xml" in {
      new RequestBodyJsonToXMLTests(this)
    }
    "Response body json-to-xml" in {
      new ResponseBodyJsonToXMLTests(this)
    }
    "Apikey mandatory tags" in {
      new ApikeyMandatoryTagsTests(this)
    }
    "User extraction from auth. module" in {
      new NgAuthModuleUserExtractorTests(this)
    }
    "Expected consumer" in {
      new NgExpectedConsumerTests(this)
    }
    "Generic allowed list" in {
      new GenericAllowedListTests(this)
    }
    "Generic block list" in {
      new GenericBlockListTests(this)
    }
    "Allowed users only" in {
      new HasAllowedUsersValidatorTests(this)
    }
    "Regex Response Headers Rewriter" in {
      new RegexResponseHeadersRewriterTests(this)
    }
    "GraphQL Composer - json" in {
      new GraphQLBackendTests(this).jsonDirective()
    }
    "GraphQL Composer - mock" in {
      new GraphQLBackendTests(this).mockDirective()
    }
    "GraphQL Composer - permissions" in {
      new GraphQLBackendTests(this).permissions()
    }
  }
}
