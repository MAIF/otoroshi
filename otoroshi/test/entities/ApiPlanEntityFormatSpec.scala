package entities

import org.joda.time.DateTime
import otoroshi.models.{
  ApiIdentifier,
  ApiKeyRotation,
  EntityLocation,
  RestrictionPath,
  Restrictions,
  RouteIdentifier,
  ServiceGroupIdentifier
}
import otoroshi.next.models.*
import otoroshi.next.plugins.{AllowedQuota, FixedWindowStrategyConfig, LegacyThrottlingStrategyConfig}
import play.api.libs.json.*

class ApiPlanEntityFormatSpec extends EntityFormatSpec {

  private val overrideHost = NgPluginInstance(plugin = "cp:otoroshi.next.plugins.OverrideHost")
  private val headersIn    = NgPluginInstance(
    plugin = "cp:otoroshi.next.plugins.AdditionalHeadersIn",
    config = NgPluginInstanceConfig(Json.obj("headers" -> Json.obj("x-plan" -> "gold")))
  )

  private val apikeyConfig = ApikeyAccessModeConfiguration(
    clientNamePattern = Some("${plan.name}-client"),
    description = Some("an apikey of the gold plan"),
    authorizedEntities = Seq(RouteIdentifier("route-1"), ServiceGroupIdentifier("group-1")),
    enabled = false,
    readOnly = true,
    allowClientIdOnly = true,
    constrainedServicesOnly = true,
    restrictions = Restrictions(enabled = true, allowLast = false, allowed = Seq(RestrictionPath("GET", "/users"))),
    validUntil = Some(new DateTime(4102444800000L)),
    rotation = ApiKeyRotation(enabled = true, rotationEvery = 24, gracePeriod = 2, nextSecret = Some("next")),
    tags = Seq("gold"),
    metadata = Map("tier" -> "gold")
  )

  private def plan(fields: (String, Json.JsValueWrapper)*): ApiPlan =
    ApiPlan(Json.obj("id" -> "plan-1", "name" -> "gold") ++ Json.obj(fields*))

  private def api(plans: ApiPlan*): Api = Api(
    location = EntityLocation(),
    id = "api-1",
    name = "api",
    description = "",
    domain = "api.oto.tools",
    contextPath = "/",
    version = "1.0.0",
    debugFlow = false,
    capture = false,
    exportReporting = false,
    groups = Seq.empty,
    state = ApiPublished,
    blueprint = ApiBlueprint.REST,
    plans = plans,
    flows = Seq(ApiFlows("shared", "shared", NgPlugins(Seq(overrideHost)))),
    testing = ApiTesting(headerValue = "x")
  )

  "ApiPlan" should {

    "read its id, name and description" in {
      val p = plan("description" -> "the gold plan")
      p.id mustBe "plan-1"
      p.name mustBe "gold"
      p.description mustBe "the gold plan"
    }

    "fail on access to a missing id" in {
      a[JsResultException] must be thrownBy ApiPlan(Json.obj("name" -> "gold")).id
    }

    "describe itself as without description by default" in {
      plan().description mustBe "No description"
    }

    "be keyless by default" in {
      plan().accessModeConfigurationType mustBe "keyless"
    }

    "be keyless when its access mode type is not a string" in {
      plan("access_mode_configuration_type" -> 1).accessModeConfigurationType mustBe "keyless"
    }

    "be published by default" in {
      plan().status mustBe ApiPlanStatus.Published
    }

    "read each status" in {
      plan("status" -> "staging").status mustBe ApiPlanStatus.Staging
      plan("status" -> "published").status mustBe ApiPlanStatus.Published
      plan("status" -> "deprecated").status mustBe ApiPlanStatus.Deprecated
      plan("status" -> "closed").status mustBe ApiPlanStatus.Closed
    }

    "read its status case insensitively" in {
      plan("status" -> "DEPRECATED").status mustBe ApiPlanStatus.Deprecated
    }

    "read an unknown status as closed" in {
      plan("status" -> "archived").status mustBe ApiPlanStatus.Closed
    }

    "read a status that is not a string as published" in {
      plan("status" -> 1).status mustBe ApiPlanStatus.Published
    }

    "have no tags nor metadata by default" in {
      plan().tags mustBe empty
      plan().metadata mustBe empty
    }

    "read its tags and metadata" in {
      val p = plan("tags" -> Json.arr("gold"), "metadata" -> Json.obj("tier" -> "gold"))
      p.tags mustBe Seq("gold")
      p.metadata mustBe Map("tier" -> "gold")
    }

    "validate subscriptions automatically by default" in {
      plan().validation mustBe ApiPlanValidation.Auto
    }

    "read its validation" in {
      plan("validation" -> Json.obj("kind" -> "manual", "config" -> Json.obj("a" -> 1))).validation mustBe
        ApiPlanValidation(ApiDocumentationPlanValidationKind.Manual, Json.obj("a" -> 1))
    }

    "read a validation that is not an object as automatic" in {
      plan("validation" -> "manual").validation mustBe ApiPlanValidation.Auto
    }

    "be public by default" in {
      plan().visibility mustBe ApiPlanVisibility.Public
    }

    "read its visibility" in {
      plan("visibility" -> Json.obj("kind" -> "semi_public")).visibility.kind mustBe ApiDocumentationPlanVisibilityKind.SemiPublic
    }

    "rate limit with the legacy strategy and the default quotas by default" in {
      plan().rateLimiting mustBe Some(LegacyThrottlingStrategyConfig())
    }

    "read its rate limiting strategy" in {
      val strategy = FixedWindowStrategyConfig(bucketKey = Some("b"), windowDurationMs = 1000, quota = AllowedQuota(10, 100, 1000))
      plan("rateLimiting" -> Json.obj("strategy" -> strategy.json)).rateLimiting mustBe Some(strategy)
    }

    "read its rate limiting strategy from the snake case key" in {
      val strategy = LegacyThrottlingStrategyConfig(AllowedQuota(1, 2, 3))
      plan("rate_limiting" -> Json.obj("strategy" -> strategy.json)).rateLimiting mustBe Some(strategy)
    }

    "prefer the camel case rate limiting key" in {
      val camel = LegacyThrottlingStrategyConfig(AllowedQuota(1, 2, 3))
      val snake = LegacyThrottlingStrategyConfig(AllowedQuota(4, 5, 6))
      plan(
        "rateLimiting"  -> Json.obj("strategy" -> camel.json),
        "rate_limiting" -> Json.obj("strategy" -> snake.json)
      ).rateLimiting mustBe Some(camel)
    }

    "not fall back on the snake case key when the camel case one has no valid strategy" in {
      val snake = LegacyThrottlingStrategyConfig(AllowedQuota(4, 5, 6))
      plan(
        "rateLimiting"  -> Json.obj("strategy" -> Json.obj("id" -> "Unknown")),
        "rate_limiting" -> Json.obj("strategy" -> snake.json)
      ).rateLimiting mustBe Some(LegacyThrottlingStrategyConfig())
    }

    "read an unknown rate limiting strategy as the legacy one" in {
      plan("rateLimiting" -> Json.obj("strategy" -> Json.obj("id" -> "Unknown"))).rateLimiting mustBe
        Some(LegacyThrottlingStrategyConfig())
    }

    "read the configuration of a keyless plan" in {
      plan(
        "access_mode_configuration_type" -> "keyless",
        "access_mode_configuration"      -> Json.obj("expr" -> "${req.headers.x-user}", "create_if_missing" -> false)
      ).accessModeConfiguration mustBe Some(KeylessAccessModeConfiguration(Some("${req.headers.x-user}"), createIfMissing = false))
    }

    "read the configuration of an apikey plan" in {
      plan(
        "access_mode_configuration_type" -> "apikey",
        "access_mode_configuration"      -> apikeyConfig.json
      ).accessModeConfiguration mustBe Some(apikeyConfig)
    }

    "read the configuration of a jwt plan" in {
      plan(
        "access_mode_configuration_type" -> "jwt",
        "access_mode_configuration"      -> Json.obj("verifier" -> "verifier-1", "client_id_path" -> "sub")
      ).accessModeConfiguration mustBe Some(JWTAccessModeConfiguration(Some("verifier-1"), Some("sub"), createIfMissing = true))
    }

    "read the configuration of a mtls plan" in {
      plan(
        "access_mode_configuration_type" -> "mtls",
        "access_mode_configuration"      -> Json.obj("regex_subject_dns" -> Json.arr("CN=.*"))
      ).accessModeConfiguration mustBe Some(MtlsAccessModeConfiguration(regexSubjectDNs = Seq("CN=.*"), createIfMissing = true))
    }

    "read the configuration of an oauth2 local plan" in {
      plan(
        "access_mode_configuration_type" -> "oauth2-local",
        "access_mode_configuration"      -> Json.obj("default_key_pair" -> "kp-1", "expiration" -> 3600)
      ).accessModeConfiguration mustBe Some(OAuth2AccessModeConfiguration("kp-1", 3600))
    }

    "read the configuration of an oauth2 remote plan" in {
      plan(
        "access_mode_configuration_type" -> "oauth2-remote",
        "access_mode_configuration"      -> Json.obj("verifier" -> "auth-1", "fetch_user" -> true)
      ).accessModeConfiguration mustBe Some(
        OAuth2RemoteAccessModeConfiguration(Some("auth-1"), None, fetchUser = true, createIfMissing = true)
      )
    }

    "have no configuration for an unknown access mode" in {
      plan("access_mode_configuration_type" -> "basic", "access_mode_configuration" -> Json.obj()).accessModeConfiguration mustBe None
    }

    "have no configuration when none is given" in {
      plan("access_mode_configuration_type" -> "apikey").accessModeConfiguration mustBe None
    }

    "have no configuration when an oauth2 local one cannot be read" in {
      plan("access_mode_configuration_type" -> "oauth2-local", "access_mode_configuration" -> Json.obj())
        .accessModeConfiguration mustBe None
    }

    "read the access mode type case sensitively" in {
      plan("access_mode_configuration_type" -> "APIKEY", "access_mode_configuration" -> Json.obj())
        .accessModeConfiguration mustBe None
    }

    "hand over the apikey configuration of an apikey plan as apikey template" in {
      plan("access_mode_configuration_type" -> "apikey", "access_mode_configuration" -> apikeyConfig.json).apikeyTemplate mustBe
        apikeyConfig
    }

    "hand over its tags and metadata only as apikey template for the other kinds" in {
      plan(
        "access_mode_configuration_type" -> "oauth2-local",
        "access_mode_configuration"      -> Json.obj("default_key_pair" -> "kp-1", "expiration" -> 3600),
        "tags"                           -> Json.arr("t"),
        "metadata"                       -> Json.obj("m" -> "v")
      ).apikeyTemplate mustBe ApikeyAccessModeConfiguration(tags = Seq("t"), metadata = Map("m" -> "v"))
    }

    "read its pricing" in {
      val pricing = ApiPricing("price-1", true, "monthly", 9.99, "EUR", Json.obj("trial" -> 30))
      plan("pricing" -> pricing.json).pricing mustBe pricing
    }

    "fail on access to a missing pricing" in {
      a[JsResultException] must be thrownBy plan().pricing
    }

    "have no plugins by default" in {
      plan().plugins mustBe None
      plan().hasPlugins mustBe false
    }

    "read its plugins" in {
      val plugins = ApiPlanPlugins(NgPlugins(Seq(headersIn)), overrides = true, flowRef = Some("shared"))
      plan("plugins" -> plugins.json).plugins mustBe Some(plugins)
    }

    "ignore plugins that are not an object" in {
      plan("plugins" -> Json.arr(headersIn.json)).plugins mustBe None
    }

    "have plugins when it lists some" in {
      plan("plugins" -> ApiPlanPlugins(NgPlugins(Seq(headersIn)), overrides = false).json).hasPlugins mustBe true
    }

    "have plugins when it only points at a flow" in {
      plan("plugins" -> ApiPlanPlugins(NgPlugins.empty, overrides = false, flowRef = Some("shared")).json).hasPlugins mustBe true
    }

    "have no plugins when it lists none and points at no flow" in {
      plan("plugins" -> ApiPlanPlugins(NgPlugins.empty, overrides = true).json).hasPlugins mustBe false
    }

    "put the plugins of the flow it points at before its own" in {
      val p = plan("plugins" -> ApiPlanPlugins(NgPlugins(Seq(headersIn)), overrides = true, flowRef = Some("shared")).json)
      p.computedPlugins(api(p)) mustBe NgPluginsWithOverride(NgPlugins(Seq(overrideHost, headersIn)), overrides = true)
    }

    "ignore a flow that the api does not have" in {
      val p = plan("plugins" -> ApiPlanPlugins(NgPlugins(Seq(headersIn)), overrides = false, flowRef = Some("missing")).json)
      p.computedPlugins(api(p)) mustBe NgPluginsWithOverride(NgPlugins(Seq(headersIn)), overrides = false)
    }

    "compute no plugin and no override without plugins" in {
      plan().computedPlugins(api()) mustBe NgPluginsWithOverride(NgPlugins.empty, overrides = false)
    }
  }

  "ApiPlanStatus and ApiKind" should {

    "name and order the plan statuses" in {
      Seq(ApiPlanStatus.Staging, ApiPlanStatus.Published, ApiPlanStatus.Deprecated, ApiPlanStatus.Closed)
        .map(s => (s.name, s.orderPosition)) mustBe Seq(("staging", 1), ("published", 2), ("deprecated", 3), ("closed", 4))
    }

    "name the access kinds as the access mode types of the plans" in {
      Seq(ApiKind.Apikey, ApiKind.Mtls, ApiKind.Keyless, ApiKind.OAuth2Local, ApiKind.OAuth2Remote, ApiKind.JWT)
        .map(_.name) mustBe Seq("apikey", "mtls", "keyless", "oauth2-local", "oauth2-remote", "jwt")
    }

    "give each access mode configuration its kind" in {
      KeylessAccessModeConfiguration().apiKind mustBe ApiKind.Keyless
      JWTAccessModeConfiguration().apiKind mustBe ApiKind.JWT
      MtlsAccessModeConfiguration().apiKind mustBe ApiKind.Mtls
      OAuth2RemoteAccessModeConfiguration().apiKind mustBe ApiKind.OAuth2Remote
      OAuth2AccessModeConfiguration("kp", 1).apiKind mustBe ApiKind.OAuth2Local
      ApikeyAccessModeConfiguration().apiKind mustBe ApiKind.Apikey
    }

    "give no plugin to an access mode configuration" in {
      KeylessAccessModeConfiguration().plugins mustBe NgPlugins(Seq.empty)
      apikeyConfig.plugins mustBe NgPlugins(Seq.empty)
    }
  }

  "KeylessAccessModeConfiguration" should {

    val format = KeylessAccessModeConfiguration.fmt

    "write a configuration" in {
      format.writes(KeylessAccessModeConfiguration(Some("${req.ip}"), createIfMissing = true)) mustBe Json.obj(
        "expr"              -> "${req.ip}",
        "create_if_missing" -> true
      )
    }

    "write a missing expression as null" in {
      format.writes(KeylessAccessModeConfiguration()) mustBe Json.obj("expr" -> JsNull, "create_if_missing" -> true)
    }

    "round trip a configuration" in {
      roundTrip(format, KeylessAccessModeConfiguration(Some("${req.ip}"), createIfMissing = true))
    }

    "round trip the default configuration" in {
      roundTrip(format, KeylessAccessModeConfiguration())
    }

    "read an empty object as creating the missing consumers, like the constructor default" in {
      KeylessAccessModeConfiguration().createIfMissing mustBe true
      readOk(format, Json.obj()) mustBe KeylessAccessModeConfiguration(None, createIfMissing = true)
    }
  }

  "JWTAccessModeConfiguration" should {

    val format = JWTAccessModeConfiguration.fmt

    "default to the client_id claim" in {
      JWTAccessModeConfiguration() mustBe JWTAccessModeConfiguration(None, Some("client_id"), createIfMissing = true)
    }

    "write a configuration" in {
      format.writes(JWTAccessModeConfiguration(Some("v1"), Some("sub"), createIfMissing = true)) mustBe Json.obj(
        "verifier"          -> "v1",
        "client_id_path"    -> "sub",
        "create_if_missing" -> true
      )
    }

    "round trip a configuration" in {
      roundTrip(format, JWTAccessModeConfiguration(Some("v1"), Some("sub"), createIfMissing = true))
    }

    "round trip a configuration without verifier nor client id path" in {
      roundTrip(format, JWTAccessModeConfiguration(None, None, createIfMissing = false))
    }

    "read an empty object without client id path, unlike the constructor default" in {
      readOk(format, Json.obj()) mustBe JWTAccessModeConfiguration(None, None, createIfMissing = true)
    }

    "ignore a verifier that is not a string" in {
      readOk(format, Json.obj("verifier" -> 1)).verifier mustBe None
    }
  }

  "MtlsAccessModeConfiguration" should {

    val format = MtlsAccessModeConfiguration.fmt

    val full = MtlsAccessModeConfiguration(
      regexSubjectDNs = Seq("CN=client.*"),
      regexIssuerDNs = Seq("CN=Otoroshi CA"),
      clientIdField = Some("CN"),
      createIfMissing = true
    )

    "write a configuration" in {
      format.writes(full) mustBe Json.obj(
        "regex_subject_dns" -> Json.arr("CN=client.*"),
        "regex_issuer_dns"  -> Json.arr("CN=Otoroshi CA"),
        "client_id_field"   -> "CN",
        "create_if_missing" -> true
      )
    }

    "round trip a configuration" in {
      roundTrip(format, full)
    }

    "round trip the default configuration" in {
      roundTrip(format, MtlsAccessModeConfiguration())
    }

    "read a blank client id field as none" in {
      readOk(format, format.writes(full.copy(clientIdField = Some(" ")))).clientIdField mustBe None
    }

    "read an empty object with the defaults, creating the missing consumers" in {
      readOk(format, Json.obj()) mustBe MtlsAccessModeConfiguration(createIfMissing = true)
    }

    "ignore subject dns given as a single string" in {
      readOk(format, Json.obj("regex_subject_dns" -> "CN=.*")).regexSubjectDNs mustBe empty
    }
  }

  "OAuth2RemoteAccessModeConfiguration" should {

    val format = OAuth2RemoteAccessModeConfiguration.fmt

    val full = OAuth2RemoteAccessModeConfiguration(Some("auth-1"), Some("azp"), fetchUser = true, "profile", createIfMissing = true)

    "write a configuration" in {
      format.writes(full) mustBe Json.obj(
        "verifier"          -> "auth-1",
        "client_id_path"    -> "azp",
        "fetch_user"        -> true,
        "user_metadata_key" -> "profile",
        "create_if_missing" -> true
      )
    }

    "round trip a configuration" in {
      roundTrip(format, full)
    }

    "round trip the default configuration" in {
      roundTrip(format, OAuth2RemoteAccessModeConfiguration())
    }

    "read an empty object with the defaults, without client id path and creating the missing consumers" in {
      readOk(format, Json.obj()) mustBe OAuth2RemoteAccessModeConfiguration(
        verifier = None,
        clientIdPath = None,
        fetchUser = false,
        userMetadataKey = "user_profile",
        createIfMissing = true
      )
    }
  }

  "OAuth2AccessModeConfiguration" should {

    val format = OAuth2AccessModeConfiguration.fmt

    "write a configuration" in {
      format.writes(OAuth2AccessModeConfiguration("kp-1", 3600)) mustBe Json.obj("default_key_pair" -> "kp-1", "expiration" -> 3600)
    }

    "round trip a configuration" in {
      roundTrip(format, OAuth2AccessModeConfiguration("kp-1", 3600))
    }

    "not read a configuration without key pair" in {
      readError(format, Json.obj("expiration" -> 3600))
    }

    "not read a configuration without expiration" in {
      readError(format, Json.obj("default_key_pair" -> "kp-1"))
    }

    "not read an expiration that is not a number" in {
      readError(format, Json.obj("default_key_pair" -> "kp-1", "expiration" -> "1h"))
    }
  }

  "ApikeyAccessModeConfiguration" should {

    val format = ApikeyAccessModeConfiguration.fmt

    "write a configuration" in {
      format.writes(apikeyConfig) mustBe Json.obj(
        "clientNamePattern"       -> "${plan.name}-client",
        "description"             -> "an apikey of the gold plan",
        "authorizedGroup"         -> "group-1",
        "authorizedEntities"      -> Json.arr("route_route-1", "group_group-1"),
        "authorizations"          -> Json.arr(Json.obj("kind" -> "route", "id" -> "route-1"), Json.obj("kind" -> "group", "id" -> "group-1")),
        "enabled"                 -> false,
        "readOnly"                -> true,
        "allowClientIdOnly"       -> true,
        "constrainedServicesOnly" -> true,
        "restrictions"            -> apikeyConfig.restrictions.json,
        "rotation"                -> apikeyConfig.rotation.json,
        "validUntil"              -> 4102444800000L,
        "tags"                    -> Json.arr("gold"),
        "metadata"                -> Json.obj("tier" -> "gold")
      )
    }

    "write the default configuration" in {
      format.writes(ApikeyAccessModeConfiguration()) mustBe Json.obj(
        "clientNamePattern"       -> JsNull,
        "description"             -> JsNull,
        "authorizedGroup"         -> JsNull,
        "authorizedEntities"      -> Json.arr(),
        "authorizations"          -> Json.arr(),
        "enabled"                 -> true,
        "readOnly"                -> false,
        "allowClientIdOnly"       -> false,
        "constrainedServicesOnly" -> false,
        "restrictions"            -> Restrictions().json,
        "rotation"                -> ApiKeyRotation().json,
        "validUntil"              -> JsNull,
        "tags"                    -> Json.arr(),
        "metadata"                -> Json.obj()
      )
    }

    "round trip a configuration where every field is set" in {
      roundTrip(format, apikeyConfig)
    }

    "round trip the default configuration" in {
      roundTrip(format, ApikeyAccessModeConfiguration())
    }

    "round trip an enabled configuration valid until a future date" in {
      roundTrip(format, apikeyConfig.copy(enabled = true)).enabled mustBe true
    }

    "round trip the authorizations of every kind in order" in {
      val entities = Seq(ApiIdentifier("api-1"), ServiceGroupIdentifier("group-1"), RouteIdentifier("route-1"))
      roundTrip(format, apikeyConfig.copy(authorizedEntities = entities)).authorizedEntities mustBe entities
    }

    "write a configuration valid until a past date as disabled" in {
      val expired = apikeyConfig.copy(enabled = true, validUntil = Some(new DateTime(1000000000000L)))
      (written(format, expired) \ "enabled").as[Boolean] mustBe false
      readOk(format, format.writes(expired)).enabled mustBe false
    }

    "write the first group only as authorized group" in {
      val config = apikeyConfig.copy(authorizedEntities = Seq(ServiceGroupIdentifier("g1"), ServiceGroupIdentifier("g2")))
      (written(format, config) \ "authorizedGroup").as[String] mustBe "g1"
    }

    "read the legacy authorized group" in {
      readOk(format, Json.obj("authorizedGroup" -> "group-1")).authorizedEntities mustBe Seq(ServiceGroupIdentifier("group-1"))
    }

    "read the legacy prefixed authorized entities" in {
      readOk(format, Json.obj("authorizedEntities" -> Json.arr("route_r1", "api_a1", "g1"))).authorizedEntities mustBe Seq(
        RouteIdentifier("r1"),
        ApiIdentifier("a1"),
        ServiceGroupIdentifier("g1")
      )
    }

    "read authorizations given as prefixed strings" in {
      readOk(format, Json.obj("authorizations" -> Json.arr("route_r1"))).authorizedEntities mustBe Seq(RouteIdentifier("r1"))
    }

    "merge the authorizations, the legacy entities and the legacy group without duplicate" in {
      readOk(
        format,
        Json.obj(
          "authorizations"     -> Json.arr(Json.obj("kind" -> "api", "id" -> "a1")),
          "authorizedEntities" -> Json.arr("api_a1", "route_r1"),
          "authorizedGroup"    -> "g1"
        )
      ).authorizedEntities mustBe Seq(ApiIdentifier("a1"), RouteIdentifier("r1"), ServiceGroupIdentifier("g1"))
    }

    "read an empty object with the defaults" in {
      readOk(format, Json.obj()) mustBe ApikeyAccessModeConfiguration()
    }

    "drop the metadata with an empty key" in {
      readOk(format, Json.obj("metadata" -> Json.obj("" -> "x", "k" -> "v"))).metadata mustBe Map("k" -> "v")
      (written(format, ApikeyAccessModeConfiguration(metadata = Map("" -> "x", "k" -> "v"))) \ "metadata").as[JsObject] mustBe
        Json.obj("k" -> "v")
    }

    "read restrictions that are not an object as the default ones" in {
      readOk(format, Json.obj("restrictions" -> "none")).restrictions mustBe Restrictions()
    }

    "read a validity that is not a number as no validity" in {
      readOk(format, Json.obj("validUntil" -> "2100-01-01")).validUntil mustBe None
    }

    "ignore an enabled flag that is not a boolean" in {
      readOk(format, Json.obj("enabled" -> "false")).enabled mustBe true
    }

    "not read the quotas, which belong to the rate limiting of the plan" in {
      writtenKeys(format, apikeyConfig) must contain noneOf ("throttlingQuota", "dailyQuota", "monthlyQuota")
    }
  }

  "ApiPlanPlugins" should {

    val format = ApiPlanPlugins.format

    val full = ApiPlanPlugins(NgPlugins(Seq(headersIn)), overrides = true, flowRef = Some("shared"))

    "write plugins" in {
      format.writes(full) mustBe Json.obj("plugins" -> Json.arr(headersIn.json), "overrides" -> true, "flow_ref" -> "shared")
    }

    "write a missing flow reference as null" in {
      (format.writes(full.copy(flowRef = None)) \ "flow_ref").get mustBe JsNull
    }

    "round trip plugins" in {
      roundTrip(format, full)
    }

    "round trip plugins without flow reference" in {
      roundTrip(format, full.copy(flowRef = None))
    }

    "read a blank flow reference as none" in {
      readOk(format, Json.obj("flow_ref" -> " ")).flowRef mustBe None
    }

    "read an empty object as no plugin, no override and no flow" in {
      readOk(format, Json.obj()) mustBe ApiPlanPlugins(NgPlugins.empty, overrides = false, flowRef = None)
    }

    "ignore plugins that are not an array" in {
      readOk(format, Json.obj("plugins" -> headersIn.json)).plugins mustBe NgPlugins.empty
    }

    "not read a plugin without plugin id" in {
      readError(format, Json.obj("plugins" -> Json.arr(Json.obj("enabled" -> true))))
    }
  }

  "NgPluginsWithOverride" should {

    val format = NgPluginsWithOverride.format

    "write plugins" in {
      format.writes(NgPluginsWithOverride(NgPlugins(Seq(headersIn)), overrides = true)) mustBe Json.obj(
        "plugins"   -> Json.arr(headersIn.json),
        "overrides" -> true
      )
    }

    "round trip plugins" in {
      roundTrip(format, NgPluginsWithOverride(NgPlugins(Seq(headersIn, overrideHost)), overrides = true))
    }

    "read an empty object as no plugin and no override" in {
      readOk(format, Json.obj()) mustBe NgPluginsWithOverride(NgPlugins.empty, overrides = false)
    }
  }

  "ApiPlanVisibility" should {

    val format = ApiPlanVisibility.format

    "name its kinds" in {
      Seq(
        ApiDocumentationPlanVisibilityKind.Public,
        ApiDocumentationPlanVisibilityKind.SemiPublic,
        ApiDocumentationPlanVisibilityKind.Private,
        ApiDocumentationPlanVisibilityKind.Custom
      ).map(_.name) mustBe Seq("public", "semi_public", "private", "custom")
    }

    "read an unknown kind as private" in {
      ApiDocumentationPlanVisibilityKind("internal") mustBe ApiDocumentationPlanVisibilityKind.Private
    }

    "write a visibility" in {
      format.writes(ApiPlanVisibility(ApiDocumentationPlanVisibilityKind.Custom, Json.obj("a" -> 1))) mustBe Json.obj(
        "kind"   -> "custom",
        "config" -> Json.obj("a" -> 1)
      )
    }

    "round trip every kind" in {
      Seq(
        ApiDocumentationPlanVisibilityKind.Public,
        ApiDocumentationPlanVisibilityKind.SemiPublic,
        ApiDocumentationPlanVisibilityKind.Private,
        ApiDocumentationPlanVisibilityKind.Custom
      ).foreach(kind => roundTrip(format, ApiPlanVisibility(kind, Json.obj("a" -> 1))).kind mustBe kind)
    }

    "read a missing kind as public" in {
      readOk(format, Json.obj()) mustBe ApiPlanVisibility.Public
    }

    "expose through json what the format writes" in {
      ApiPlanVisibility.Public.json mustBe format.writes(ApiPlanVisibility.Public)
    }
  }

  "ApiPlanValidation" should {

    val format = ApiPlanValidation.format

    "name its kinds" in {
      Seq(
        ApiDocumentationPlanValidationKind.Auto,
        ApiDocumentationPlanValidationKind.Manual,
        ApiDocumentationPlanValidationKind.Webhook,
        ApiDocumentationPlanValidationKind.Workflow,
        ApiDocumentationPlanValidationKind.Wasm,
        ApiDocumentationPlanValidationKind.Custom
      ).map(_.name) mustBe Seq("auto", "manual", "webhook", "workflow", "wasm", "custom")
    }

    "read an unknown kind as manual" in {
      ApiDocumentationPlanValidationKind("approve") mustBe ApiDocumentationPlanValidationKind.Manual
    }

    "read the validation kinds whatever their case" in {
      ApiDocumentationPlanValidationKind("Manual") mustBe ApiDocumentationPlanValidationKind.Manual
      ApiDocumentationPlanValidationKind("AUTO") mustBe ApiDocumentationPlanValidationKind.Auto
      ApiDocumentationPlanValidationKind(" webhook ") mustBe ApiDocumentationPlanValidationKind.Webhook
    }

    "read a validation without kind as automatic" in {
      ApiPlanValidation.format.reads(Json.obj()).get.kind mustBe ApiDocumentationPlanValidationKind.Auto
    }

    "tell an automatic validation" in {
      ApiPlanValidation.Auto.isAuto mustBe true
      ApiPlanValidation(ApiDocumentationPlanValidationKind.Manual).isAuto mustBe false
    }

    "write a validation" in {
      format.writes(ApiPlanValidation(ApiDocumentationPlanValidationKind.Webhook, Json.obj("url" -> "https://hook"))) mustBe
        Json.obj("kind" -> "webhook", "config" -> Json.obj("url" -> "https://hook"))
    }

    "round trip every kind" in {
      Seq(
        ApiDocumentationPlanValidationKind.Auto,
        ApiDocumentationPlanValidationKind.Manual,
        ApiDocumentationPlanValidationKind.Webhook,
        ApiDocumentationPlanValidationKind.Workflow,
        ApiDocumentationPlanValidationKind.Wasm,
        ApiDocumentationPlanValidationKind.Custom
      ).foreach(kind => roundTrip(format, ApiPlanValidation(kind, Json.obj("a" -> 1))).kind mustBe kind)
    }

    "read an empty object as automatic" in {
      readOk(format, Json.obj()) mustBe ApiPlanValidation.Auto
    }

    "read a config that is not an object as an empty config" in {
      readOk(format, Json.obj("kind" -> "manual", "config" -> "x")).config mustBe Json.obj()
    }
  }

  "ApiPricing" should {

    val format = ApiPricing.format

    val pricing = ApiPricing("price-1", true, "monthly", 9.99, "EUR", Json.obj("trial" -> 30))

    "write a pricing" in {
      format.writes(pricing) mustBe Json.obj(
        "id"       -> "price-1",
        "enabled"  -> true,
        "name"     -> "monthly",
        "price"    -> 9.99,
        "currency" -> "EUR",
        "params"   -> Json.obj("trial" -> 30)
      )
    }

    "round trip a pricing" in {
      roundTrip(format, pricing)
    }

    "read missing params as an empty object" in {
      readOk(format, written(format, pricing) - "params").params mustBe Json.obj()
    }

    "read an integer price" in {
      readOk(format, written(format, pricing) ++ Json.obj("price" -> 10)).price mustBe 10.0
    }

    "not read a pricing without currency" in {
      readError(format, written(format, pricing) - "currency")
    }

    "not read a pricing without enabled flag" in {
      readError(format, written(format, pricing) - "enabled")
    }
  }

  "ApiRef" should {

    val format = ApiRef.format

    "write the subscription under the sub key" in {
      format.writes(ApiRef("api-1", "plan-1", "sub-1")) mustBe Json.obj("api" -> "api-1", "plan" -> "plan-1", "sub" -> "sub-1")
    }

    "round trip a reference" in {
      roundTrip(format, ApiRef("api-1", "plan-1", "sub-1"))
    }

    "not read the subscription from a subscription key" in {
      readError(format, Json.obj("api" -> "api-1", "plan" -> "plan-1", "subscription" -> "sub-1"))
    }

    "not read a reference without plan" in {
      readError(format, Json.obj("api" -> "api-1", "sub" -> "sub-1"))
    }
  }
}
