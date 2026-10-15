package plugins.configs

import org.joda.time.DateTime
import otoroshi.models.*
import otoroshi.next.models.ApikeyAccessModeConfiguration
import otoroshi.next.plugins.*
import play.api.libs.json.*

class ApiManagementPluginConfigsSpec extends PluginConfigFormatSpec {

  private val template = ApikeyAccessModeConfiguration(
    clientNamePattern = Some("consumer ${client_id}"),
    description = Some("created by a plan"),
    authorizedEntities = Seq(ApiIdentifier("api_1"), RouteIdentifier("route_1")),
    enabled = false,
    readOnly = true,
    allowClientIdOnly = true,
    constrainedServicesOnly = true,
    restrictions = Restrictions(enabled = true, allowLast = false, allowed = Seq(RestrictionPath("GET", "/api/.*"))),
    validUntil = Some(new DateTime(4102444800000L)),
    rotation = ApiKeyRotation(enabled = true, rotationEvery = 24, gracePeriod = 2),
    tags = Seq("plan", "gold"),
    metadata = Map("tier" -> "gold")
  )

  private val fixedWindow = FixedWindowStrategyConfig(
    bucketKey = Some("plan-gold"),
    windowDurationMs = 60000L,
    quota = AllowedQuota(window = 100L, daily = 1000L, monthly = 10000L)
  )

  private val legacyThrottling = LegacyThrottlingStrategyConfig(AllowedQuota(window = 10L, daily = 20L, monthly = 30L))

  "ApikeyFromPlan" should {

    "read create_if_missing and default it to true" in {
      ApikeyFromPlan.createIfMissing(Json.obj("create_if_missing" -> false)) mustBe false
      ApikeyFromPlan.createIfMissing(Json.obj()) mustBe true
      ApikeyFromPlan.createIfMissing(Json.obj("create_if_missing" -> "false")) mustBe true
    }

    "read the api and plan ids" in {
      val json = Json.obj("api_id" -> "api_1", "plan_id" -> "plan_1")
      ApikeyFromPlan.apiId(json) mustBe Some("api_1")
      ApikeyFromPlan.planId(json) mustBe Some("plan_1")
    }

    "read null or missing api and plan ids as missing" in {
      ApikeyFromPlan.apiId(Json.obj("api_id" -> JsNull)) mustBe None
      ApikeyFromPlan.planId(Json.obj()) mustBe None
    }

    "read a throttling strategy from its id" in {
      ApikeyFromPlan.throttlingStrategy(Json.obj("throttling_strategy" -> fixedWindow.json)) mustBe Some(fixedWindow)
      ApikeyFromPlan.throttlingStrategy(Json.obj("throttling_strategy" -> legacyThrottling.json)) mustBe Some(
        legacyThrottling
      )
    }

    "ignore a null throttling strategy" in {
      ApikeyFromPlan.throttlingStrategy(Json.obj("throttling_strategy" -> JsNull)) mustBe None
    }

    "ignore a throttling strategy of an unknown kind" in {
      ApikeyFromPlan.throttlingStrategy(
        Json.obj("throttling_strategy" -> Json.obj("id" -> "LeakyBucket", "quota" -> Json.obj()))
      ) mustBe None
    }

    "ignore a throttling strategy without id" in {
      ApikeyFromPlan.throttlingStrategy(Json.obj("throttling_strategy" -> Json.obj("quota" -> Json.obj()))) mustBe None
    }

    "read the apikey template" in {
      ApikeyFromPlan.apikeyTemplate(Json.obj("apikey" -> template.json)) mustBe template
    }

    "read a missing or null apikey template as the default template" in {
      ApikeyFromPlan.apikeyTemplate(Json.obj()) mustBe ApikeyAccessModeConfiguration()
      ApikeyFromPlan.apikeyTemplate(Json.obj("apikey" -> JsNull)) mustBe ApikeyAccessModeConfiguration()
    }

    "read the legacy prefixed authorized entities of the apikey template" in {
      ApikeyFromPlan
        .apikeyTemplate(Json.obj("apikey" -> Json.obj("authorizedEntities" -> Json.arr("api_api_1", "route_route_1"))))
        .authorizedEntities mustBe Seq(ApiIdentifier("api_1"), RouteIdentifier("route_1"))
    }

    "drop the metadata with an empty key from the apikey template" in {
      ApikeyFromPlan
        .apikeyTemplate(Json.obj("apikey" -> Json.obj("metadata" -> Json.obj("" -> "x", "tier" -> "gold"))))
        .metadata mustBe Map("tier" -> "gold")
    }

    "write the plan settings" in {
      ApikeyFromPlan.json(
        NgExpressionApikeyExtractorConfig(
          createIfMissing = false,
          apiId = Some("api_1"),
          planId = Some("plan_1"),
          throttlingStrategy = Some(legacyThrottling)
        )
      ) mustBe Json.obj(
        "create_if_missing"   -> false,
        "api_id"              -> "api_1",
        "plan_id"             -> "plan_1",
        "throttling_strategy" -> legacyThrottling.json,
        "apikey"              -> ApikeyAccessModeConfiguration().json
      )
    }

    "write missing plan settings as null" in {
      ApikeyFromPlan.json(NgExpressionApikeyExtractorConfig()) mustBe Json.obj(
        "create_if_missing"   -> true,
        "api_id"              -> JsNull,
        "plan_id"             -> JsNull,
        "throttling_strategy" -> JsNull,
        "apikey"              -> ApikeyAccessModeConfiguration().json
      )
    }
  }

  "NgJwtApikeyExtractorConfig" should {

    val format = NgJwtApikeyExtractorConfig.format

    val full = NgJwtApikeyExtractorConfig(
      verifier = "jwt_verifier_1",
      clientIdPath = "azp",
      clientIdPrefix = "oauth_",
      strict = false,
      createIfMissing = false,
      apiId = Some("api_1"),
      planId = Some("plan_gold"),
      throttlingStrategy = Some(fixedWindow),
      apikey = template
    )

    "default to a strict extraction of client_id, prefixed by jwt_, creating missing apikeys" in {
      val config = NgJwtApikeyExtractorConfig("none")
      config.clientIdPath mustBe "client_id"
      config.clientIdPrefix mustBe "jwt_"
      config.strict mustBe true
      config.createIfMissing mustBe true
      config.apiId mustBe None
      config.planId mustBe None
      config.throttlingStrategy mustBe None
      config.apikey mustBe ApikeyAccessModeConfiguration()
    }

    "write the plugin default config" in {
      format.writes(NgJwtApikeyExtractorConfig("none")) mustBe Json.obj(
        "verifier"            -> "none",
        "client_id_path"      -> "client_id",
        "client_id_prefix"    -> "jwt_",
        "strict"              -> true,
        "create_if_missing"   -> true,
        "api_id"              -> JsNull,
        "plan_id"             -> JsNull,
        "throttling_strategy" -> JsNull,
        "apikey"              -> ApikeyAccessModeConfiguration().json
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "verifier"            -> "jwt_verifier_1",
        "client_id_path"      -> "azp",
        "client_id_prefix"    -> "oauth_",
        "strict"              -> false,
        "create_if_missing"   -> false,
        "api_id"              -> "api_1",
        "plan_id"             -> "plan_gold",
        "throttling_strategy" -> fixedWindow.json,
        "apikey"              -> template.json
      )
    }

    "round trip the plugin default config" in {
      roundTrip(format, NgJwtApikeyExtractorConfig("none"))
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a legacy throttling strategy" in {
      roundTrip(format, full.copy(throttlingStrategy = Some(legacyThrottling))).throttlingStrategy mustBe Some(
        legacyThrottling
      )
    }

    "round trip a fixed window strategy without bucket key" in {
      val strategy = fixedWindow.copy(bucketKey = None)
      roundTrip(format, full.copy(throttlingStrategy = Some(strategy))).throttlingStrategy mustBe Some(strategy)
    }

    "round trip an apikey template with a service group" in {
      val withGroup = template.copy(authorizedEntities = Seq(ServiceGroupIdentifier("default"), ApiIdentifier("api_1")))
      roundTrip(format, full.copy(apikey = withGroup)).apikey.authorizedEntities mustBe Seq(
        ServiceGroupIdentifier("default"),
        ApiIdentifier("api_1")
      )
    }

    "round trip an empty client id prefix" in {
      roundTrip(format, full.copy(clientIdPrefix = "")).clientIdPrefix mustBe ""
    }

    "read the verifier alone and default the other fields" in {
      readOk(format, Json.obj("verifier" -> "jwt_verifier_1")) mustBe NgJwtApikeyExtractorConfig("jwt_verifier_1")
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a json string" in {
      readError(format, JsString("jwt_verifier_1"))
    }

    "not read a json array" in {
      readError(format, Json.arr("jwt_verifier_1"))
    }

    "not read a config without verifier" in {
      readError(format, Json.obj("client_id_path" -> "azp", "strict" -> false))
    }

    "not read a verifier that is not a string" in {
      readError(format, Json.obj("verifier" -> Json.arr("jwt_verifier_1")))
    }

    "read the plan settings with the verifier" in {
      val back = readOk(format, Json.obj("verifier" -> "v", "api_id" -> "api_1", "plan_id" -> "plan_1"))
      back.apiId mustBe Some("api_1")
      back.planId mustBe Some("plan_1")
      back.createIfMissing mustBe true
    }

    "ignore a strict flag written as a string" in {
      readOk(format, Json.obj("verifier" -> "v", "strict" -> "false")).strict mustBe true
    }

    "ignore a client id path that is not a string" in {
      readOk(format, Json.obj("verifier" -> "v", "client_id_path" -> 1)).clientIdPath mustBe "client_id"
    }

    "ignore an apikey template that is not an object" in {
      readOk(format, Json.obj("verifier" -> "v", "apikey" -> "template")).apikey mustBe ApikeyAccessModeConfiguration()
    }

    "not read the camel case keys" in {
      readOk(
        format,
        Json.obj("verifier" -> "v", "clientIdPath" -> "azp", "createIfMissing" -> false, "apiId" -> "api_1")
      ) mustBe NgJwtApikeyExtractorConfig("v")
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of NgJwtApikeyExtractor" in {
      defaultConfigReadsBack(new NgJwtApikeyExtractor(), format) mustBe NgJwtApikeyExtractorConfig("none")
    }
  }

  "NgExpressionApikeyExtractorConfig" should {

    val format = NgExpressionApikeyExtractorConfig.format

    val full = NgExpressionApikeyExtractorConfig(
      expression = "${req.headers.X-Tenant}",
      clientIdPrefix = "tenant_",
      strict = true,
      createIfMissing = false,
      apiId = Some("api_1"),
      planId = Some("plan_free"),
      throttlingStrategy = Some(fixedWindow),
      apikey = template
    )

    "default to a lenient extraction from the caller ip, prefixed by public_" in {
      val config = NgExpressionApikeyExtractorConfig()
      config.expression mustBe "${req.ip}"
      config.clientIdPrefix mustBe "public_"
      config.strict mustBe false
      config.createIfMissing mustBe true
    }

    "write the default config" in {
      format.writes(NgExpressionApikeyExtractorConfig()) mustBe Json.obj(
        "expression"          -> "${req.ip}",
        "client_id_prefix"    -> "public_",
        "strict"              -> false,
        "create_if_missing"   -> true,
        "api_id"              -> JsNull,
        "plan_id"             -> JsNull,
        "throttling_strategy" -> JsNull,
        "apikey"              -> ApikeyAccessModeConfiguration().json
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgExpressionApikeyExtractorConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a config with plan ids but no throttling strategy" in {
      roundTrip(format, full.copy(throttlingStrategy = None)).throttlingStrategy mustBe None
    }

    "round trip a constant expression" in {
      roundTrip(format, NgExpressionApikeyExtractorConfig(expression = "everybody")).expression mustBe "everybody"
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgExpressionApikeyExtractorConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgExpressionApikeyExtractorConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("${req.ip}")) mustBe NgExpressionApikeyExtractorConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("${req.ip}")) mustBe NgExpressionApikeyExtractorConfig()
    }

    "read the expression alone" in {
      readOk(format, Json.obj("expression" -> "${user.email}")) mustBe NgExpressionApikeyExtractorConfig(
        expression = "${user.email}"
      )
    }

    "read the strict flag alone" in {
      readOk(format, Json.obj("strict" -> true)) mustBe NgExpressionApikeyExtractorConfig(strict = true)
    }

    "read create_if_missing alone" in {
      readOk(format, Json.obj("create_if_missing" -> false)) mustBe NgExpressionApikeyExtractorConfig(
        createIfMissing = false
      )
    }

    "keep an empty expression" in {
      readOk(format, Json.obj("expression" -> "")).expression mustBe ""
    }

    "ignore an expression that is not a string" in {
      readOk(format, Json.obj("expression" -> 42)).expression mustBe "${req.ip}"
    }

    "ignore a client id prefix that is null" in {
      readOk(format, Json.obj("client_id_prefix" -> JsNull)).clientIdPrefix mustBe "public_"
    }

    "ignore a strict flag written as a string" in {
      readOk(format, Json.obj("strict" -> "true")).strict mustBe false
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
      jsonMatchesFormat(format, NgExpressionApikeyExtractorConfig())
    }

    "be the default config of NgExpressionApikeyExtractor" in {
      defaultConfigReadsBack(new NgExpressionApikeyExtractor(), format) mustBe NgExpressionApikeyExtractorConfig()
    }
  }

  "NgClientCertApikeyExtractorConfig" should {

    val format = NgClientCertApikeyExtractorConfig.format

    val full = NgClientCertApikeyExtractorConfig(
      regexSubjectDNs = Seq("CN=.*\\.partners\\.oto\\.tools.*", "O=Partner.*"),
      regexIssuerDNs = Seq("CN=Otoroshi Intermediate CA.*"),
      clientIdField = Some("CN"),
      clientIdPrefix = "partner_",
      strict = false,
      createIfMissing = false,
      apiId = Some("api_1"),
      planId = Some("plan_mtls"),
      throttlingStrategy = Some(legacyThrottling),
      apikey = template
    )

    "default to a strict extraction without dn constraint, prefixed by mtls_" in {
      val config = NgClientCertApikeyExtractorConfig()
      config.regexSubjectDNs mustBe empty
      config.regexIssuerDNs mustBe empty
      config.clientIdField mustBe None
      config.clientIdPrefix mustBe "mtls_"
      config.strict mustBe true
      config.createIfMissing mustBe true
    }

    "write the default config" in {
      format.writes(NgClientCertApikeyExtractorConfig()) mustBe Json.obj(
        "regex_subject_dns"   -> Json.arr(),
        "regex_issuer_dns"    -> Json.arr(),
        "client_id_field"     -> JsNull,
        "client_id_prefix"    -> "mtls_",
        "strict"              -> true,
        "create_if_missing"   -> true,
        "api_id"              -> JsNull,
        "plan_id"             -> JsNull,
        "throttling_strategy" -> JsNull,
        "apikey"              -> ApikeyAccessModeConfiguration().json
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "regex_subject_dns"   -> Json.arr("CN=.*\\.partners\\.oto\\.tools.*", "O=Partner.*"),
        "regex_issuer_dns"    -> Json.arr("CN=Otoroshi Intermediate CA.*"),
        "client_id_field"     -> "CN",
        "client_id_prefix"    -> "partner_",
        "strict"              -> false,
        "create_if_missing"   -> false,
        "api_id"              -> "api_1",
        "plan_id"             -> "plan_mtls",
        "throttling_strategy" -> legacyThrottling.json,
        "apikey"              -> template.json
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgClientCertApikeyExtractorConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip subject patterns only" in {
      roundTrip(format, NgClientCertApikeyExtractorConfig(regexSubjectDNs = Seq("CN=foo"))).regexSubjectDNs mustBe Seq(
        "CN=foo"
      )
    }

    "round trip issuer patterns only" in {
      roundTrip(format, NgClientCertApikeyExtractorConfig(regexIssuerDNs = Seq("CN=ca"))).regexIssuerDNs mustBe Seq(
        "CN=ca"
      )
    }

    "keep the spaces around a client id field" in {
      roundTrip(format, NgClientCertApikeyExtractorConfig(clientIdField = Some(" cn "))).clientIdField mustBe Some(" cn ")
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgClientCertApikeyExtractorConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgClientCertApikeyExtractorConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("CN=foo")) mustBe NgClientCertApikeyExtractorConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("CN=foo")) mustBe NgClientCertApikeyExtractorConfig()
    }

    "read the client id field alone" in {
      readOk(format, Json.obj("client_id_field" -> "OU")) mustBe NgClientCertApikeyExtractorConfig(
        clientIdField = Some("OU")
      )
    }

    "read an empty client id field as no field" in {
      readOk(format, Json.obj("client_id_field" -> "")).clientIdField mustBe None
    }

    "read a blank client id field as no field" in {
      readOk(format, Json.obj("client_id_field" -> "  ")).clientIdField mustBe None
    }

    "ignore patterns written as a string" in {
      readOk(format, Json.obj("regex_subject_dns" -> "CN=foo")).regexSubjectDNs mustBe empty
    }

    "ignore a whole pattern list as soon as one pattern is not a string" in {
      readOk(format, Json.obj("regex_issuer_dns" -> Json.arr("CN=ca", 1))).regexIssuerDNs mustBe empty
    }

    "ignore a strict flag written as a string" in {
      readOk(format, Json.obj("strict" -> "false")).strict mustBe true
    }

    "not read the camel case keys" in {
      readOk(
        format,
        Json.obj("regexSubjectDNs" -> Json.arr("CN=foo"), "clientIdField" -> "CN")
      ) mustBe NgClientCertApikeyExtractorConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
      jsonMatchesFormat(format, NgClientCertApikeyExtractorConfig())
    }

    "be the default config of NgClientCertApikeyExtractor" in {
      defaultConfigReadsBack(new NgClientCertApikeyExtractor(), format) mustBe NgClientCertApikeyExtractorConfig()
    }
  }

  "NgOidcApikeyExtractorConfig" should {

    val format = NgOidcApikeyExtractorConfig.format

    val full = NgOidcApikeyExtractorConfig(
      ref = Some("auth_mod_keycloak"),
      clientIdPath = "azp",
      clientIdPrefix = "kc_",
      source = Some(InHeader("X-Access-Token", "Bearer ")),
      fetchUser = true,
      userMetadataKey = "profile",
      strict = false,
      createIfMissing = false,
      apiId = Some("api_1"),
      planId = Some("plan_oidc"),
      throttlingStrategy = Some(fixedWindow),
      apikey = template
    )

    "default to a strict extraction of client_id, prefixed by oauth2_, without fetching the user" in {
      val config = NgOidcApikeyExtractorConfig()
      config.ref mustBe None
      config.clientIdPath mustBe "client_id"
      config.clientIdPrefix mustBe "oauth2_"
      config.source mustBe None
      config.fetchUser mustBe false
      config.userMetadataKey mustBe "user_profile"
      config.strict mustBe true
      config.createIfMissing mustBe true
    }

    "write the default config" in {
      format.writes(NgOidcApikeyExtractorConfig()) mustBe Json.obj(
        "ref"                 -> JsNull,
        "client_id_path"      -> "client_id",
        "client_id_prefix"    -> "oauth2_",
        "source"              -> JsNull,
        "fetch_user"          -> false,
        "user_metadata_key"   -> "user_profile",
        "strict"              -> true,
        "create_if_missing"   -> true,
        "api_id"              -> JsNull,
        "plan_id"             -> JsNull,
        "throttling_strategy" -> JsNull,
        "apikey"              -> ApikeyAccessModeConfiguration().json
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "ref"                 -> "auth_mod_keycloak",
        "client_id_path"      -> "azp",
        "client_id_prefix"    -> "kc_",
        "source"              -> Json.obj("type" -> "InHeader", "name" -> "X-Access-Token", "remove" -> "Bearer "),
        "fetch_user"          -> true,
        "user_metadata_key"   -> "profile",
        "strict"              -> false,
        "create_if_missing"   -> false,
        "api_id"              -> "api_1",
        "plan_id"             -> "plan_oidc",
        "throttling_strategy" -> fixedWindow.json,
        "apikey"              -> template.json
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgOidcApikeyExtractorConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a token read from a header" in {
      roundTrip(format, NgOidcApikeyExtractorConfig(source = Some(InHeader("Authorization")))).source mustBe Some(
        InHeader("Authorization", "")
      )
    }

    "round trip a token read from a query param" in {
      roundTrip(format, NgOidcApikeyExtractorConfig(source = Some(InQueryParam("access_token")))).source mustBe Some(
        InQueryParam("access_token")
      )
    }

    "round trip a token read from a cookie" in {
      roundTrip(format, NgOidcApikeyExtractorConfig(source = Some(InCookie("jwt")))).source mustBe Some(InCookie("jwt"))
    }

    "round trip an empty auth module ref" in {
      roundTrip(format, NgOidcApikeyExtractorConfig(ref = Some(""))).ref mustBe Some("")
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgOidcApikeyExtractorConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgOidcApikeyExtractorConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("auth_mod_keycloak")) mustBe NgOidcApikeyExtractorConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("auth_mod_keycloak")) mustBe NgOidcApikeyExtractorConfig()
    }

    "read the auth module ref alone" in {
      readOk(format, Json.obj("ref" -> "auth_mod_1")) mustBe NgOidcApikeyExtractorConfig(ref = Some("auth_mod_1"))
    }

    "read the fetch user flag alone" in {
      readOk(format, Json.obj("fetch_user" -> true)) mustBe NgOidcApikeyExtractorConfig(fetchUser = true)
    }

    "read a header source without remove part" in {
      readOk(format, Json.obj("source" -> Json.obj("type" -> "InHeader", "name" -> "X-Jwt"))).source mustBe Some(
        InHeader("X-Jwt", "")
      )
    }

    "ignore a source of an unknown type" in {
      readOk(format, Json.obj("source" -> Json.obj("type" -> "InBody", "name" -> "jwt"))).source mustBe None
    }

    "ignore a source without name" in {
      readOk(format, Json.obj("source" -> Json.obj("type" -> "InCookie"))).source mustBe None
    }

    "ignore a source that is not an object" in {
      readOk(format, Json.obj("source" -> "InHeader")).source mustBe None
    }

    "ignore an auth module ref that is not a string" in {
      readOk(format, Json.obj("ref" -> 42)).ref mustBe None
    }

    "ignore a fetch user flag written as a string" in {
      readOk(format, Json.obj("fetch_user" -> "true")).fetchUser mustBe false
    }

    "not read the camel case keys" in {
      readOk(
        format,
        Json.obj("clientIdPath" -> "azp", "fetchUser" -> true, "userMetadataKey" -> "profile")
      ) mustBe NgOidcApikeyExtractorConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
      jsonMatchesFormat(format, NgOidcApikeyExtractorConfig())
    }

    "list the fields of its config flow in its config schema" in {
      NgOidcApikeyExtractorConfig.configFlow.foreach { field =>
        NgOidcApikeyExtractorConfig.configSchema.get.keys must contain(field)
      }
    }

    "only list written fields in its config flow" in {
      val keys = writtenKeys(format, NgOidcApikeyExtractorConfig())
      NgOidcApikeyExtractorConfig.configFlow.foreach { field =>
        keys must contain(field)
      }
    }

    "be the default config of NgOidcApikeyExtractor" in {
      defaultConfigReadsBack(new NgOidcApikeyExtractor(), format) mustBe NgOidcApikeyExtractorConfig()
    }

    "be described by the config flow and schema of NgOidcApikeyExtractor" in {
      val plugin = new NgOidcApikeyExtractor()
      plugin.configFlow mustBe NgOidcApikeyExtractorConfig.configFlow
      plugin.configSchema mustBe NgOidcApikeyExtractorConfig.configSchema
    }
  }

  "the api management plugins without configuration" should {

    "have no default config for NgApiConsumerEnforcer" in {
      hasNoDefaultConfig(new NgApiConsumerEnforcer())
    }
  }
}
