package entities

import io.otoroshi.wasm4s.scaladsl.WasmoSettings
import io.otoroshi.wasm4s.scaladsl.security.TlsConfig
import org.joda.time.LocalTime
import otoroshi.api.TweakedGlobalConfig
import otoroshi.events.{KafkaConfig, SaslConfig, StatsdConfig}
import otoroshi.models.*
import otoroshi.script.plugins.Plugins
import otoroshi.ssl.ClientAuth
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.letsencrypt.LetsEncryptSettings
import otoroshi.utils.mailer.{ConsoleMailerSettings, EmailLocation, MailgunSettings}
import play.api.libs.json.*
import play.api.libs.ws.DefaultWSProxyServer

import scala.concurrent.duration.*

class GlobalConfigEntityFormatSpec extends EntityFormatSpec {

  private val elastic = ElasticAnalyticsConfig(
    uris = Seq("http://es1:9200", "http://es2:9200"),
    index = Some("otoroshi-events"),
    `type` = Some("event"),
    user = Some("elastic"),
    password = Some("changeme"),
    headers = Map("X-Tenant" -> "prod"),
    indexSettings = IndexSettings(false, 3, 2, "create", IndexSettingsInterval.Month),
    mtlsConfig = MtlsConfig(Seq("cert-1"), Seq("ca-1"), mtls = true, loose = true, trustAll = true),
    applyTemplate = false,
    version = Some("8.0.0"),
    maxBulkSize = 50,
    sendWorkers = 2
  )

  private val kafka = KafkaConfig(
    servers = Seq("kafka1:9092", "kafka2:9092"),
    keyPass = Some("keypass"),
    keystore = Some("/keystore.jks"),
    truststore = Some("/truststore.jks"),
    sendEvents = true,
    hostValidation = false,
    topic = "events",
    mtlsConfig = MtlsConfig(Seq("cert-1"), Seq("ca-1"), mtls = true),
    securityProtocol = "SASL_SSL",
    saslConfig = Some(SaslConfig("user", "password", "SCRAM-SHA-512", Some("jaas"))),
    properties = Some(Map("acks" -> "all"))
  )

  private val wasmo = TlsWasmoSettings(
    settings = WasmoSettings("http://wasmo:5001", "client-id", "client-secret", Some("mine"), legacyAuth = true, None),
    tlsConfig = MtlsConfig(Seq("cert-1"), Seq("ca-1"), mtls = true, loose = true, trustAll = true)
  )

  private val fullTls = TlsSettings(
    defaultDomain = Some("oto.tools"),
    randomIfNotFound = true,
    includeJdkCaServer = false,
    includeJdkCaClient = false,
    trustedCAsServer = Seq("ca-1", "ca-2"),
    bannedAlpnProtocols = Map("legacy.oto.tools" -> Seq("h2", "h3")),
    clientAuth = ClientAuth.Need,
    strictBackendServerValidation = false
  )

  private val fullSnowMonkey = SnowMonkeyConfig(
    enabled = true,
    outageStrategy = AllServicesPerGroup,
    includeUserFacingDescriptors = true,
    dryRun = true,
    timesPerDay = 3,
    startTime = LocalTime.parse("10:00:00"),
    stopTime = LocalTime.parse("18:30:00"),
    outageDurationFrom = 5.minutes,
    outageDurationTo = 20.minutes,
    targetGroups = Seq("group-1"),
    chaosConfig = ChaosConfig(
      enabled = false,
      largeRequestFaultConfig = Some(LargeRequestFaultConfig(0.1, 1024)),
      largeResponseFaultConfig = Some(LargeResponseFaultConfig(0.2, 2048)),
      latencyInjectionFaultConfig = Some(LatencyInjectionFaultConfig(0.3, 10.millis, 20.millis)),
      badResponsesFaultConfig = Some(BadResponsesFaultConfig(0.4, Seq(BadResponse(503, "oops", Map("X-A" -> "b")))))
    )
  )

  private val full = GlobalConfig(
    letsEncryptSettings = LetsEncryptSettings(
      enabled = true,
      server = "acme://letsencrypt.org",
      emails = Seq("ops@oto.tools"),
      contacts = Seq("mailto:ops@oto.tools"),
      publicKey = "public",
      privateKey = "private",
      preferredChain = Some("ISRG Root X1"),
      renewalPercentage = 30,
      renewBeforeDays = Some(10),
      deleteOldCertificatesAfterRenewal = true
    ),
    lines = Seq("prod", "dev"),
    anonymousReporting = true,
    initWithNewEngine = false,
    enableEmbeddedMetrics = false,
    streamEntityOnly = false,
    autoLinkToDefaultGroup = false,
    limitConcurrentRequests = true,
    maxConcurrentRequests = 500,
    maxHttp10ResponseSize = 1024,
    apiReadOnly = true,
    u2fLoginOnly = true,
    maintenanceMode = true,
    trustXForwarded = false,
    trustedProxies = Seq("10.0.0.0/8", "192.168.1.1"),
    clientAddressHeader = "X-Real-IP",
    useLegacyClientIpAddress = true,
    ipFiltering = IpFiltering(Seq("10.0.0.0/8"), Seq("1.2.3.4"), blacklistMatchesForwardedChain = true),
    throttlingQuota = 1000,
    perIpThrottlingQuota = 100,
    elasticReadsConfig = Some(elastic),
    elasticWritesConfigs = Seq(elastic, ElasticAnalyticsConfig(Seq("http://es3:9200"))),
    analyticsWebhooks = Seq(Webhook("http://analytics.oto.tools", Map("X-A" -> "b"), MtlsConfig(mtls = true))),
    logAnalyticsOnServer = true,
    useAkkaHttpClient = true,
    alertsWebhooks = Seq(Webhook("http://alerts.oto.tools")),
    alertsEmails = Seq("ops@oto.tools", "dev@oto.tools"),
    endlessIpAddresses = Seq("6.6.6.6"),
    kafkaConfig = Some(kafka),
    backOfficeAuthRef = Some("auth-module-1"),
    cleverSettings = Some(CleverCloudSettings("consumer-key", "consumer-secret", "token", "secret", "orga-1")),
    mailerSettings = Some(MailgunSettings(eu = true, "mg-key", "mg.oto.tools", Seq(EmailLocation("Ops", "ops@oto.tools")))),
    statsdConfig = Some(StatsdConfig(datadog = true, "statsd.oto.tools", 8125)),
    maxWebhookSize = 50,
    middleFingers = true,
    maxLogsSize = 500,
    otoroshiId = "otoroshi-1",
    snowMonkeyConfig = fullSnowMonkey,
    scripts = GlobalScripts(
      enabled = true,
      transformersRefs = Seq("transformer-1"),
      transformersConfig = Json.obj("t" -> 1),
      validatorRefs = Seq("validator-1"),
      validatorConfig = Json.obj("v" -> 2),
      preRouteRefs = Seq("pre-route-1"),
      preRouteConfig = Json.obj("p" -> 3),
      sinkRefs = Seq("sink-1"),
      sinkConfig = Json.obj("s" -> 4),
      jobRefs = Seq("job-1"),
      jobConfig = Json.obj("j" -> 5)
    ),
    geolocationSettings = MaxmindGeolocationSettings(enabled = true, "/geo/city.mmdb"),
    userAgentSettings = UserAgentSettings(true),
    autoCert = AutoCert(enabled = true, caRef = Some("ca-1"), allowed = Seq("*.oto.tools"), notAllowed = Seq("admin.oto.tools"), replyNicely = true),
    tlsSettings = fullTls,
    quotasSettings = QuotasAlmostExceededSettings(true, 0.5, 0.9),
    plugins = Plugins(enabled = true, excluded = Seq("/health"), refs = Seq("cp:plugin-1"), config = Json.obj("k" -> "v")),
    templates = DefaultTemplates(route = Some(Json.obj("name" -> "my route")), service = None, apikey = Some(Json.obj("enabled" -> false))),
    wasmoSettings = Some(wasmo),
    tags = Seq("prod", "eu"),
    metadata = Map("owner" -> "ops"),
    env = Json.obj("region" -> "eu-west-1"),
    extensions = Map("my-extension" -> Json.obj("enabled" -> true))
  )

  // with its optional exporters set
  private val minimal = GlobalConfig(
    otoroshiId = "otoroshi-1",
    kafkaConfig = Some(KafkaConfig(Seq("kafka:9092"), saslConfig = Some(SaslConfig("user", "password")))),
    mailerSettings = Some(ConsoleMailerSettings()),
    wasmoSettings = Some(TlsWasmoSettings())
  )

  "GlobalConfig" should {

    val format = GlobalConfig._fmt

    "write every top level key of the config" in {
      writtenKeys(format, GlobalConfig(otoroshiId = "otoroshi-1")) mustBe Set(
        "tags",
        "letsEncryptSettings",
        "lines",
        "anonymousReporting",
        "initWithNewEngine",
        "maintenanceMode",
        "enableEmbeddedMetrics",
        "streamEntityOnly",
        "autoLinkToDefaultGroup",
        "limitConcurrentRequests",
        "maxConcurrentRequests",
        "maxHttp10ResponseSize",
        "useCircuitBreakers",
        "apiReadOnly",
        "u2fLoginOnly",
        "superAdminWhenNoRights",
        "trustXForwarded",
        "trustedProxies",
        "clientAddressHeader",
        "useLegacyClientIpAddress",
        "ipFiltering",
        "throttlingQuota",
        "perIpThrottlingQuota",
        "analyticsWebhooks",
        "alertsWebhooks",
        "elasticWritesConfigs",
        "elasticReadsConfig",
        "alertsEmails",
        "logAnalyticsOnServer",
        "useAkkaHttpClient",
        "endlessIpAddresses",
        "statsdConfig",
        "kafkaConfig",
        "backOfficeAuthRef",
        "mailerSettings",
        "cleverSettings",
        "maxWebhookSize",
        "middleFingers",
        "maxLogsSize",
        "otoroshiId",
        "snowMonkeyConfig",
        "scripts",
        "geolocationSettings",
        "userAgentSettings",
        "autoCert",
        "tlsSettings",
        "quotasSettings",
        "plugins",
        "proxies",
        "wasmoSettings",
        "metadata",
        "env",
        "extensions",
        "templates"
      )
    }

    "write the scalar values of the default config" in {
      val json = written(format, GlobalConfig(otoroshiId = "otoroshi-1"))
      json - "letsEncryptSettings" - "ipFiltering" - "snowMonkeyConfig" - "scripts" - "geolocationSettings" -
        "userAgentSettings" - "autoCert" - "tlsSettings" - "quotasSettings" - "plugins" - "templates" - "proxies" mustBe Json.obj(
        "tags"                     -> Json.arr(),
        "lines"                    -> Json.arr("prod"),
        "anonymousReporting"       -> false,
        "initWithNewEngine"        -> true,
        "maintenanceMode"          -> false,
        "enableEmbeddedMetrics"    -> true,
        "streamEntityOnly"         -> true,
        "autoLinkToDefaultGroup"   -> true,
        "limitConcurrentRequests"  -> false,
        "maxConcurrentRequests"    -> 1000,
        "maxHttp10ResponseSize"    -> 4194304,
        "useCircuitBreakers"       -> true,
        "apiReadOnly"              -> false,
        "u2fLoginOnly"             -> false,
        "superAdminWhenNoRights"   -> false,
        "trustXForwarded"          -> true,
        "trustedProxies"           -> Json.arr(),
        "clientAddressHeader"      -> "X-Forwarded-For",
        "useLegacyClientIpAddress" -> false,
        "throttlingQuota"          -> 10000000L,
        "perIpThrottlingQuota"     -> 10000000L,
        "analyticsWebhooks"        -> Json.arr(),
        "alertsWebhooks"           -> Json.arr(),
        "elasticWritesConfigs"     -> Json.arr(),
        "elasticReadsConfig"       -> JsNull,
        "alertsEmails"             -> Json.arr(),
        "logAnalyticsOnServer"     -> false,
        "useAkkaHttpClient"        -> false,
        "endlessIpAddresses"       -> Json.arr(),
        "statsdConfig"             -> JsNull,
        "kafkaConfig"              -> JsNull,
        "backOfficeAuthRef"        -> JsNull,
        "mailerSettings"           -> JsNull,
        "cleverSettings"           -> JsNull,
        "maxWebhookSize"           -> 100,
        "middleFingers"            -> false,
        "maxLogsSize"              -> 10000,
        "otoroshiId"               -> "otoroshi-1",
        "wasmoSettings"            -> JsNull,
        "metadata"                 -> Json.obj(),
        "env"                      -> Json.obj(),
        "extensions"               -> Json.obj()
      )
    }

    "write the nested settings of the default config with their own formats" in {
      val json = written(format, GlobalConfig(otoroshiId = "otoroshi-1"))
      (json \ "letsEncryptSettings").as[JsValue] mustBe LetsEncryptSettings.format.writes(LetsEncryptSettings())
      (json \ "ipFiltering").as[JsValue] mustBe IpFiltering.format.writes(IpFiltering())
      (json \ "snowMonkeyConfig").as[JsValue] mustBe SnowMonkeyConfig._fmt.writes(SnowMonkeyConfig())
      (json \ "scripts").as[JsValue] mustBe GlobalScripts.format.writes(GlobalScripts())
      (json \ "geolocationSettings").as[JsValue] mustBe Json.obj("type" -> "none")
      (json \ "userAgentSettings").as[JsValue] mustBe Json.obj("enabled" -> false)
      (json \ "autoCert").as[JsValue] mustBe AutoCert.format.writes(AutoCert())
      (json \ "tlsSettings").as[JsValue] mustBe TlsSettings.format.writes(TlsSettings())
      (json \ "quotasSettings").as[JsValue] mustBe QuotasAlmostExceededSettings.format.writes(
        QuotasAlmostExceededSettings(false, 0.8, 0.8)
      )
      (json \ "plugins").as[JsValue] mustBe Plugins.format.writes(Plugins())
      (json \ "templates").as[JsValue] mustBe DefaultTemplates.format.writes(DefaultTemplates())
    }

    "write the optional settings of a config where they are set with their own formats" in {
      val json = written(format, full)
      (json \ "elasticReadsConfig").as[JsValue] mustBe ElasticAnalyticsConfig.format.writes(elastic)
      (json \ "kafkaConfig").as[JsValue] mustBe KafkaConfig.format.writes(kafka)
      (json \ "wasmoSettings").as[JsValue] mustBe TlsWasmoSettings.format.writes(wasmo)
      (json \ "mailerSettings").as[JsValue] mustBe full.mailerSettings.get.json
      (json \ "statsdConfig").as[JsValue] mustBe Json.obj("host" -> "statsd.oto.tools", "port" -> 8125, "datadog" -> true)
      (json \ "cleverSettings").as[JsValue] mustBe Json.obj(
        "consumerKey"    -> "consumer-key",
        "consumerSecret" -> "consumer-secret",
        "token"          -> "token",
        "secret"         -> "secret",
        "orgaId"         -> "orga-1"
      )
      (json \ "backOfficeAuthRef").as[String] mustBe "auth-module-1"
    }

    "not write any location" in {
      written(format, full).keys must not contain "_loc"
    }

    "round trip the default config once its optional exporters are set" in {
      roundTrip(format, minimal)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip the default config" in {
      val back = roundTrip(format, GlobalConfig(otoroshiId = "otoroshi-1"))
      back.kafkaConfig mustBe None
      back.mailerSettings mustBe None
      back.wasmoSettings mustBe None
    }

    "round trip a proxy for every usage of the config" in {
      val proxies = Proxies(
        alertEmails = Some(DefaultWSProxyServer("mail-proxy.oto.tools", 3128)),
        services = Some(DefaultWSProxyServer("proxy.oto.tools", 3128, principal = Some("user"), password = Some("pass"))),
        auth = Some(DefaultWSProxyServer("auth-proxy.oto.tools", 8080)),
        elastic = Some(DefaultWSProxyServer("elastic-proxy.oto.tools", 8080))
      )
      roundTrip(format, minimal.copy(proxies = proxies)).proxies mustBe proxies
    }

    "write the proxies with their own format" in {
      val proxies = Proxies(services = Some(DefaultWSProxyServer("proxy.oto.tools", 3128)))
      (written(format, minimal.copy(proxies = proxies)) \ "proxies").as[JsValue] mustBe Proxies.format.writes(proxies)
    }

    "read a null kafka config as no kafka config" in {
      readOk(format, Json.obj("kafkaConfig" -> JsNull)).kafkaConfig mustBe None
    }

    "read a null mailer settings as no mailer settings" in {
      readOk(format, Json.obj("mailerSettings" -> JsNull)).mailerSettings mustBe None
    }

    "read a null wasmo settings as no wasmo settings" in {
      readOk(format, Json.obj("wasmoSettings" -> JsNull)).wasmoSettings mustBe None
    }

    "read the legacy wasm manager settings when there is no wasmo settings" in {
      val back = readOk(format, Json.obj("wasmManagerSettings" -> Json.obj("url" -> "http://legacy:5001", "clientId" -> "legacy")))
      back.wasmoSettings.map(_.settings.url) mustBe Some("http://legacy:5001")
      back.wasmoSettings.map(_.settings.clientId) mustBe Some("legacy")
    }

    "prefer the wasmo settings over the legacy wasm manager settings" in {
      val back = readOk(
        format,
        Json.obj(
          "wasmoSettings"       -> Json.obj("url" -> "http://wasmo:5001"),
          "wasmManagerSettings" -> Json.obj("url" -> "http://legacy:5001")
        )
      )
      back.wasmoSettings.map(_.settings.url) mustBe Some("http://wasmo:5001")
    }

    "keep the security settings of the admin api on a round trip" in {
      val back = roundTrip(format, minimal.copy(apiReadOnly = true, u2fLoginOnly = true, maintenanceMode = true))
      back.apiReadOnly mustBe true
      back.u2fLoginOnly mustBe true
      back.maintenanceMode mustBe true
    }

    "keep the client address resolution settings on a round trip" in {
      val back = roundTrip(
        format,
        minimal.copy(
          trustXForwarded = false,
          trustedProxies = Seq("10.0.0.0/8", "2001:db8::/32", "192.168.*"),
          clientAddressHeader = "CF-Connecting-IP",
          useLegacyClientIpAddress = true
        )
      )
      back.trustXForwarded mustBe false
      back.trustedProxies mustBe Seq("10.0.0.0/8", "2001:db8::/32", "192.168.*")
      back.clientAddressHeader mustBe "CF-Connecting-IP"
      back.useLegacyClientIpAddress mustBe true
    }

    "keep the ip filtering and the endless ip addresses on a round trip" in {
      val back = roundTrip(
        format,
        minimal.copy(
          ipFiltering = IpFiltering(Seq("10.0.0.0/8"), Seq("6.6.6.6"), blacklistMatchesForwardedChain = true),
          endlessIpAddresses = Seq("7.7.7.0/24")
        )
      )
      back.ipFiltering mustBe IpFiltering(Seq("10.0.0.0/8"), Seq("6.6.6.6"), blacklistMatchesForwardedChain = true)
      back.endlessIpAddresses mustBe Seq("7.7.7.0/24")
    }

    "keep the tls settings on a round trip" in {
      roundTrip(format, minimal.copy(tlsSettings = fullTls)).tlsSettings mustBe fullTls
    }

    "keep a strict backend server validation on a round trip" in {
      roundTrip(format, minimal.copy(tlsSettings = TlsSettings(strictBackendServerValidation = true)))
        .tlsSettings
        .strictBackendServerValidation mustBe true
    }

    "keep the back office auth module on a round trip" in {
      roundTrip(format, minimal.copy(backOfficeAuthRef = Some("auth-1"))).backOfficeAuthRef mustBe Some("auth-1")
    }

    "keep the order of the trusted proxies and the lines" in {
      val back = roundTrip(format, minimal.copy(trustedProxies = Seq("b", "a"), lines = Seq("z", "a")))
      back.trustedProxies mustBe Seq("b", "a")
      back.lines mustBe Seq("z", "a")
    }

    "round trip each kind of geolocation settings" in {
      roundTrip(format, minimal.copy(geolocationSettings = NoneGeolocationSettings))
      roundTrip(format, minimal.copy(geolocationSettings = MaxmindGeolocationSettings(enabled = false, "/geo.mmdb")))
      roundTrip(format, minimal.copy(geolocationSettings = IpStackGeolocationSettings(enabled = true, "ipstack-key", 500L)))
    }

    "always write and read the circuit breakers as enabled" in {
      val json = written(format, minimal.copy(useCircuitBreakers = false))
      (json \ "useCircuitBreakers").as[Boolean] mustBe true
      readOk(format, json ++ Json.obj("useCircuitBreakers" -> false)).useCircuitBreakers mustBe true
    }

    "read an empty object" in {
      readOk(format, Json.obj()) mustBe a[GlobalConfig]
    }

    "read a json null as a config with the reads defaults" in {
      val back = readOk(format, JsNull)
      back.lines mustBe Seq("prod")
      back.trustXForwarded mustBe true
    }

    "read the defaults of the scalar fields of an empty object" in {
      val back = readOk(format, Json.obj())
      back.lines mustBe Seq("prod")
      back.anonymousReporting mustBe false
      back.enableEmbeddedMetrics mustBe true
      back.streamEntityOnly mustBe true
      back.autoLinkToDefaultGroup mustBe true
      back.limitConcurrentRequests mustBe false
      back.maxConcurrentRequests mustBe 1000L
      back.maxHttp10ResponseSize mustBe 4194304L
      back.useCircuitBreakers mustBe true
      back.throttlingQuota mustBe BaseQuotas.MaxValue
      back.perIpThrottlingQuota mustBe BaseQuotas.MaxValue
      back.logAnalyticsOnServer mustBe false
      back.useAkkaHttpClient mustBe false
      back.maxWebhookSize mustBe 100
      back.middleFingers mustBe false
      back.maxLogsSize mustBe 10000
      back.tags mustBe empty
      back.metadata mustBe empty
      back.env mustBe Json.obj()
      back.extensions mustBe empty
    }

    "read the security defaults of an empty object" in {
      val back = readOk(format, Json.obj())
      back.apiReadOnly mustBe false
      back.u2fLoginOnly mustBe false
      back.maintenanceMode mustBe false
      back.trustXForwarded mustBe true
      back.trustedProxies mustBe empty
      back.clientAddressHeader mustBe "X-Forwarded-For"
      back.useLegacyClientIpAddress mustBe false
      back.ipFiltering mustBe IpFiltering()
      back.endlessIpAddresses mustBe empty
      back.backOfficeAuthRef mustBe None
    }

    "read a missing new engine flag as on, like a new config" in {
      GlobalConfig().initWithNewEngine mustBe true
      readOk(format, Json.obj()).initWithNewEngine mustBe true
    }

    "give no right to the admins and users without rights on a new config" in {
      GlobalConfig().superAdminWhenNoRights mustBe false
    }

    "read a missing superAdminWhenNoRights as on, as for an instance that predates it" in {
      readOk(format, Json.obj()).superAdminWhenNoRights mustBe true
      readOk(format, written(format, minimal) - "superAdminWhenNoRights").superAdminWhenNoRights mustBe true
    }

    "round trip superAdminWhenNoRights on and off" in {
      roundTrip(format, minimal.copy(superAdminWhenNoRights = true)).superAdminWhenNoRights mustBe true
      roundTrip(format, minimal.copy(superAdminWhenNoRights = false)).superAdminWhenNoRights mustBe false
    }

    "ignore a superAdminWhenNoRights that is not a boolean, as for an instance that predates it" in {
      readOk(format, Json.obj("superAdminWhenNoRights" -> "false")).superAdminWhenNoRights mustBe true
    }

    "generate an otoroshi id prefixed with otoroshi_ when it is missing" in {
      val first  = readOk(format, Json.obj()).otoroshiId
      val second = readOk(format, Json.obj()).otoroshiId
      first must startWith("otoroshi_")
      first must not be second
    }

    "read the missing optional settings as absent" in {
      val back = readOk(format, Json.obj())
      back.elasticReadsConfig mustBe None
      back.kafkaConfig mustBe None
      back.mailerSettings mustBe None
      back.statsdConfig mustBe None
      back.cleverSettings mustBe None
      back.elasticWritesConfigs mustBe empty
      back.analyticsWebhooks mustBe empty
      back.alertsWebhooks mustBe empty
      back.alertsEmails mustBe empty
    }

    "read the missing nested settings with their defaults" in {
      val back = readOk(format, Json.obj())
      back.letsEncryptSettings mustBe LetsEncryptSettings()
      back.snowMonkeyConfig mustBe SnowMonkeyConfig()
      back.scripts mustBe GlobalScripts()
      back.geolocationSettings mustBe NoneGeolocationSettings
      back.userAgentSettings mustBe UserAgentSettings(false)
      back.quotasSettings mustBe QuotasAlmostExceededSettings(false, 0.8, 0.8)
      back.plugins mustBe Plugins()
      back.templates mustBe DefaultTemplates()
      back.proxies mustBe Proxies()
    }

    "read a missing auto cert as allowing every domain, while a new config allows none" in {
      AutoCert().allowed mustBe empty
      val autoCert = readOk(format, Json.obj()).autoCert
      autoCert.enabled mustBe false
      autoCert.allowed mustBe Seq("*")
    }

    "read missing tls settings without strict backend validation, while a new config has it" in {
      TlsSettings().strictBackendServerValidation mustBe true
      readOk(format, Json.obj()).tlsSettings mustBe TlsSettings(strictBackendServerValidation = false)
    }

    "fall back to the defaults for fields of the wrong type" in {
      val back = readOk(
        format,
        Json.obj(
          "lines"                 -> "prod",
          "maxConcurrentRequests" -> "1000",
          "apiReadOnly"           -> "true",
          "maintenanceMode"       -> 1,
          "maxLogsSize"           -> "big",
          "env"                   -> Json.arr("a"),
          "tags"                  -> "prod"
        )
      )
      back.lines mustBe Seq("prod")
      back.maxConcurrentRequests mustBe 1000L
      back.apiReadOnly mustBe false
      back.maintenanceMode mustBe false
      back.maxLogsSize mustBe 10000
      back.env mustBe Json.obj()
      back.tags mustBe empty
    }

    "read trusted proxies given as a single string as a one entry list" in {
      readOk(format, Json.obj("trustedProxies" -> "10.0.0.1")).trustedProxies mustBe Seq("10.0.0.1")
    }

    "keep a comma separated string of trusted proxies as one entry" in {
      readOk(format, Json.obj("trustedProxies" -> "10.0.0.0/8, 192.168.1.1")).trustedProxies mustBe Seq(
        "10.0.0.0/8, 192.168.1.1"
      )
    }

    "write trusted proxies given as a single string back as an array" in {
      val back = readOk(format, Json.obj("trustedProxies" -> "10.0.0.1"))
      (written(format, back) \ "trustedProxies").as[JsArray] mustBe Json.arr("10.0.0.1")
    }

    "skip the trusted proxies that are not strings, in order" in {
      readOk(
        format,
        Json.obj("trustedProxies" -> Json.arr("10.0.0.1", 42, JsNull, Json.obj(), "192.168.*", true))
      ).trustedProxies mustBe Seq("10.0.0.1", "192.168.*")
    }

    "read null trusted proxies as an empty list" in {
      readOk(format, Json.obj("trustedProxies" -> JsNull)).trustedProxies mustBe empty
    }

    "read trusted proxies of another type as an empty list" in {
      readOk(format, Json.obj("trustedProxies" -> 42)).trustedProxies mustBe empty
      readOk(format, Json.obj("trustedProxies" -> Json.obj("a" -> "10.0.0.1"))).trustedProxies mustBe empty
    }

    "trim the client address header" in {
      readOk(format, Json.obj("clientAddressHeader" -> "  X-Real-IP ")).clientAddressHeader mustBe "X-Real-IP"
    }

    "read a blank client address header as X-Forwarded-For" in {
      readOk(format, Json.obj("clientAddressHeader" -> "  ")).clientAddressHeader mustBe "X-Forwarded-For"
    }

    "keep the other lists of the ip filtering when one of them is a single string" in {
      readOk(
        format,
        Json.obj("ipFiltering" -> Json.obj("whitelist" -> "10.0.0.1", "blacklist" -> Json.arr("6.6.6.6")))
      ).ipFiltering mustBe IpFiltering(Seq("10.0.0.1"), Seq("6.6.6.6"))
    }

    "read a partial ip filtering with the defaults of its missing fields" in {
      readOk(format, Json.obj("ipFiltering" -> Json.obj("blacklist" -> Json.arr("6.6.6.6")))).ipFiltering mustBe
        IpFiltering(blacklist = Seq("6.6.6.6"))
    }

    "ignore an elastic reads config without any uri" in {
      readOk(format, Json.obj("elasticReadsConfig" -> Json.obj("index" -> "events"))).elasticReadsConfig mustBe None
    }

    "only leave out the elastic writes configs that cannot be read" in {
      val back = readOk(
        format,
        Json.obj("elasticWritesConfigs" -> Json.arr(elastic.toJson, Json.obj("index" -> "events")))
      )
      back.elasticWritesConfigs mustBe Seq(elastic)
    }

    "only leave out the webhooks without url" in {
      val webhooks = Json.arr(Json.obj("url" -> "http://a"), Json.obj("headers" -> Json.obj()), Json.obj("url" -> "http://b"))
      val back     = readOk(format, Json.obj("analyticsWebhooks" -> webhooks, "alertsWebhooks" -> webhooks))
      back.analyticsWebhooks mustBe Seq(Webhook("http://a"), Webhook("http://b"))
      back.alertsWebhooks mustBe Seq(Webhook("http://a"), Webhook("http://b"))
    }

    "read webhooks with their default headers and mtls config" in {
      readOk(format, Json.obj("alertsWebhooks" -> Json.arr(Json.obj("url" -> "http://a")))).alertsWebhooks mustBe Seq(
        Webhook("http://a")
      )
    }

    "read a statsd config with its default datadog flag" in {
      readOk(format, Json.obj("statsdConfig" -> Json.obj("host" -> "statsd", "port" -> 8125))).statsdConfig mustBe Some(
        StatsdConfig(datadog = false, "statsd", 8125)
      )
    }

    "ignore a statsd config without port or with an empty host" in {
      readOk(format, Json.obj("statsdConfig" -> Json.obj("host" -> "statsd"))).statsdConfig mustBe None
      readOk(format, Json.obj("statsdConfig" -> Json.obj("host" -> "", "port" -> 8125))).statsdConfig mustBe None
    }

    "ignore clever cloud settings missing one of their values" in {
      val clever = Json.obj(
        "consumerKey"    -> "ck",
        "consumerSecret" -> "cs",
        "token"          -> "t",
        "secret"         -> "s",
        "orgaId"         -> "o"
      )
      readOk(format, Json.obj("cleverSettings" -> clever)).cleverSettings mustBe Some(CleverCloudSettings("ck", "cs", "t", "s", "o"))
      readOk(format, Json.obj("cleverSettings" -> (clever - "orgaId"))).cleverSettings mustBe None
      readOk(format, Json.obj("cleverSettings" -> (clever ++ Json.obj("token" -> "")))).cleverSettings mustBe None
    }

    "read a kafka config with the defaults of its fields of the wrong type" in {
      readOk(format, Json.obj("kafkaConfig" -> Json.obj("servers" -> Json.arr("k:9092"), "sendEvents" -> "yes"))).kafkaConfig mustBe
        Some(KafkaConfig(Seq("k:9092")))
    }

    "read the templates given as a json string" in {
      val templates = DefaultTemplates(route = Some(Json.obj("name" -> "r")), backend = None)
      readOk(format, Json.obj("templates" -> Json.stringify(templates.json))).templates mustBe templates
    }

    "not read a config whose templates are a string that is not json" in {
      readError(format, Json.obj("templates" -> "not json"))
    }

    "read templates that are neither an object nor a string as no template at all" in {
      readOk(format, Json.obj("templates" -> 42)).templates mustBe DefaultTemplates.format.reads(Json.obj()).get
    }

    "read null templates as no template at all, unlike missing ones" in {
      readOk(format, Json.obj("templates" -> JsNull)).templates.route mustBe None
      readOk(format, Json.obj()).templates.route mustBe Some(Json.obj())
    }

    "read flat wasmo settings, without tls config, as the wasmo settings" in {
      val back = readOk(format, Json.obj("wasmoSettings" -> Json.obj("url" -> "http://wasmo:5001", "clientId" -> "id")))
      back.wasmoSettings mustBe Some(
        TlsWasmoSettings(settings = WasmoSettings(url = "http://wasmo:5001", clientId = "id"), tlsConfig = MtlsConfig())
      )
    }

    "keep wasmo settings without tls config, with the default tls config" in {
      val back = readOk(
        format,
        Json.obj("wasmoSettings" -> Json.obj("settings" -> Json.obj("url" -> "http://wasmo:5001", "clientId" -> "mine")))
      )
      back.wasmoSettings.map(_.settings.url) mustBe Some("http://wasmo:5001")
      back.wasmoSettings.map(_.settings.clientId) mustBe Some("mine")
      back.wasmoSettings.map(_.tlsConfig) mustBe Some(MtlsConfig())
    }

    "read the snow monkey defaults of an empty snow monkey config" in {
      val back = readOk(format, Json.obj("snowMonkeyConfig" -> Json.obj())).snowMonkeyConfig
      back.outageDurationFrom mustBe 10.minutes
      back.outageDurationTo mustBe 1.hour
      back.chaosConfig mustBe ChaosConfig(enabled = true, None, None, None, None)
    }

    "ignore metadata holding a non string value" in {
      readOk(format, Json.obj("metadata" -> Json.obj("a" -> "b", "n" -> 1))).metadata mustBe empty
    }

    "keep extensions of any json type" in {
      val back = readOk(format, Json.obj("extensions" -> Json.obj("a" -> 1, "b" -> Json.arr("x"), "c" -> "s")))
      back.extensions mustBe Map("a" -> JsNumber(1), "b" -> Json.arr("x"), "c" -> JsString("s"))
    }

    "ignore a location" in {
      readOk(format, written(format, minimal) ++ Json.obj("_loc" -> customLocationJson)) mustBe minimal
    }

    "be identified as the global config" in {
      full.internalId mustBe "global"
      GlobalConfig().theId mustBe "global"
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "global", "otoroshi-global-config", "The global config for otoroshi", Seq("prod", "eu"), Map("owner" -> "ops"))
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "expose through toJson what the format writes" in {
      full.toJson mustBe format.writes(full)
      GlobalConfig.toJson(full) mustBe format.writes(full)
    }

    "be built from its json" in {
      GlobalConfig.fromJsons(full.json) mustBe full
      GlobalConfig.fromJsonSafe(full.json) mustBe JsSuccess(full)
    }

    "fail to be built from a json that cannot be read" in {
      a[Throwable] must be thrownBy GlobalConfig.fromJsons(Json.obj("templates" -> "not json"))
      GlobalConfig.fromJsonSafe(Json.obj("templates" -> "not json")).isError mustBe true
    }

    "match the endless ip addresses by address, cidr and wildcard" in {
      val config = GlobalConfig(endlessIpAddresses = Seq("6.6.6.6", "7.7.7.0/24", "8.8.*"))
      config.matchesEndlessIpAddresses("6.6.6.6") mustBe true
      config.matchesEndlessIpAddresses("7.7.7.42") mustBe true
      config.matchesEndlessIpAddresses("8.8.1.1") mustBe true
      config.matchesEndlessIpAddresses("9.9.9.9") mustBe false
    }

    "match no endless ip address by default" in {
      GlobalConfig().matchesEndlessIpAddresses("127.0.0.1") mustBe false
    }
  }

  "TweakedGlobalConfig" should {

    val format = TweakedGlobalConfig.fmt

    "write the json of the global config with its id" in {
      format.writes(TweakedGlobalConfig(full)) mustBe GlobalConfig._fmt.writes(full).as[JsObject] ++ Json.obj("id" -> "global")
    }

    "round trip the wrapped config" in {
      roundTrip(format, TweakedGlobalConfig(full))
    }

    "read what the global config format reads" in {
      readOk(format, Json.obj("maintenanceMode" -> true)).config.maintenanceMode mustBe true
    }

    "not read what the global config format cannot read" in {
      readError(format, Json.obj("templates" -> "not json"))
    }

    "be in the default location whatever the json says" in {
      readOk(format, written(format, TweakedGlobalConfig(minimal)) ++ Json.obj("_loc" -> customLocationJson)).location mustBe
        EntityLocation.default
    }

    "not write any location" in {
      written(format, TweakedGlobalConfig(full)).keys must not contain "_loc"
    }

    "expose the accessors of the wrapped config" in {
      hasAccessors(
        TweakedGlobalConfig(full),
        "global",
        "otoroshi-global-config",
        "The global config for otoroshi",
        Seq("prod", "eu"),
        Map("owner" -> "ops")
      )
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, TweakedGlobalConfig(full))
    }
  }

  "TlsSettings" should {

    val format = TlsSettings.format

    "write the default settings" in {
      format.writes(TlsSettings()) mustBe Json.obj(
        "defaultDomain"                 -> JsNull,
        "randomIfNotFound"              -> false,
        "includeJdkCaServer"            -> true,
        "includeJdkCaClient"            -> true,
        "trustedCAsServer"              -> Json.arr(),
        "bannedAlpnProtocols"           -> Json.obj(),
        "clientAuth"                    -> "None",
        "strictBackendServerValidation" -> true
      )
    }

    "write settings where every field is set" in {
      format.writes(fullTls) mustBe Json.obj(
        "defaultDomain"                 -> "oto.tools",
        "randomIfNotFound"              -> true,
        "includeJdkCaServer"            -> false,
        "includeJdkCaClient"            -> false,
        "trustedCAsServer"              -> Json.arr("ca-1", "ca-2"),
        "bannedAlpnProtocols"           -> Json.obj("legacy.oto.tools" -> Json.arr("h2", "h3")),
        "clientAuth"                    -> "Need",
        "strictBackendServerValidation" -> false
      )
    }

    "round trip the default settings" in {
      roundTrip(format, TlsSettings())
    }

    "round trip settings where every field is set" in {
      roundTrip(format, fullTls)
    }

    "round trip the want client auth" in {
      roundTrip(format, TlsSettings(clientAuth = ClientAuth.Want)).clientAuth mustBe ClientAuth.Want
    }

    "read an empty object with the defaults and no strict backend validation" in {
      readOk(format, Json.obj()) mustBe TlsSettings(strictBackendServerValidation = false)
    }

    "read the client auth whatever its case" in {
      readOk(format, Json.obj("clientAuth" -> "need")).clientAuth mustBe ClientAuth.Need
      readOk(format, Json.obj("clientAuth" -> "WANT")).clientAuth mustBe ClientAuth.Want
    }

    "read an unknown client auth as none" in {
      readOk(format, Json.obj("clientAuth" -> "required")).clientAuth mustBe ClientAuth.None
    }

    "read the dynamic client auth as none, as it is not a global client auth" in {
      format.writes(TlsSettings(clientAuth = ClientAuth.Dynamic)) \ "clientAuth" mustBe JsDefined(JsString("Dynamic"))
      readOk(format, Json.obj("clientAuth" -> "Dynamic")).clientAuth mustBe ClientAuth.None
    }

    "trim the default domain and ignore a blank one" in {
      readOk(format, Json.obj("defaultDomain" -> " oto.tools ")).defaultDomain mustBe Some("oto.tools")
      readOk(format, Json.obj("defaultDomain" -> "  ")).defaultDomain mustBe None
    }

    "ignore trusted cas given as a single string" in {
      readOk(format, Json.obj("trustedCAsServer" -> "ca-1")).trustedCAsServer mustBe empty
    }

    "ignore banned alpn protocols that are not lists" in {
      readOk(format, Json.obj("bannedAlpnProtocols" -> Json.obj("a" -> "h2"))).bannedAlpnProtocols mustBe empty
    }
  }

  "AutoCert" should {

    val format = AutoCert.format

    "write the default config" in {
      format.writes(AutoCert()) mustBe Json.obj(
        "enabled"     -> false,
        "replyNicely" -> false,
        "caRef"       -> JsNull,
        "allowed"     -> Json.arr(),
        "notAllowed"  -> Json.arr()
      )
    }

    "round trip the default config" in {
      roundTrip(format, AutoCert())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, AutoCert(enabled = true, caRef = Some("ca-1"), allowed = Seq("*.oto.tools"), notAllowed = Seq("a.oto.tools"), replyNicely = true))
    }

    "read an empty object as allowing every domain" in {
      readOk(format, Json.obj()) mustBe AutoCert(allowed = Seq("*"))
    }

    "read an empty allowed list as allowing no domain" in {
      readOk(format, Json.obj("allowed" -> Json.arr())).allowed mustBe empty
    }

    "match an allowed domain that is not excluded" in {
      val autoCert = AutoCert(allowed = Seq("*.oto.tools"), notAllowed = Seq("admin.oto.tools"))
      autoCert.matches("api.oto.tools") mustBe true
      autoCert.matches("admin.oto.tools") mustBe false
      autoCert.matches("api.other.tools") mustBe false
    }

    "match no domain without allowed patterns" in {
      AutoCert().matches("api.oto.tools") mustBe false
    }
  }

  "IpFiltering" should {

    val format = IpFiltering.format

    "write the default filtering" in {
      format.writes(IpFiltering()) mustBe Json.obj(
        "whitelist"                      -> Json.arr(),
        "blacklist"                      -> Json.arr(),
        "blacklistMatchesForwardedChain" -> false
      )
    }

    "round trip a filtering where every field is set" in {
      roundTrip(format, IpFiltering(Seq("10.0.0.0/8", "192.168.*"), Seq("6.6.6.6"), blacklistMatchesForwardedChain = true))
    }

    "read an empty object as the default filtering" in {
      readOk(format, Json.obj()) mustBe IpFiltering()
    }

    "read a filtering persisted before the forwarded chain flag existed" in {
      readOk(format, Json.obj("whitelist" -> Json.arr("10.0.0.1"), "blacklist" -> Json.arr())) mustBe IpFiltering(
        whitelist = Seq("10.0.0.1")
      )
    }

    "read a single string as a one entry list" in {
      readOk(format, Json.obj("whitelist" -> "10.0.0.1")) mustBe IpFiltering(whitelist = Seq("10.0.0.1"))
    }

    "keep the entries that are strings and leave the others out" in {
      readOk(format, Json.obj("blacklist" -> Json.arr("6.6.6.6", 42, true, "7.7.7.0/24"))).blacklist mustBe Seq("6.6.6.6", "7.7.7.0/24")
    }

    "read a list of another type as an empty list without touching the other one" in {
      readOk(format, Json.obj("whitelist" -> 42, "blacklist" -> Json.arr("6.6.6.6"))) mustBe IpFiltering(blacklist = Seq("6.6.6.6"))
    }

    "not read an ip filtering that is not an object" in {
      readError(format, Json.arr("10.0.0.1"))
    }

    "match the whitelist by cidr and let everything through when it is empty" in {
      val filtering = IpFiltering(whitelist = Seq("10.0.0.0/8"))
      filtering.matchesWhitelist("10.1.2.3") mustBe true
      filtering.notMatchesWhitelist("11.0.0.1") mustBe true
      filtering.notMatchesWhitelist("10.1.2.3") mustBe false
      IpFiltering().notMatchesWhitelist("11.0.0.1") mustBe false
    }

    "match the blacklist by address and wildcard" in {
      val filtering = IpFiltering(blacklist = Seq("6.6.6.6", "7.7.*"))
      filtering.matchesBlacklist("6.6.6.6") mustBe true
      filtering.matchesBlacklist("7.7.1.1") mustBe true
      filtering.matchesBlacklist("8.8.8.8") mustBe false
    }
  }

  "LetsEncryptSettings" should {

    val format = LetsEncryptSettings.format

    "write the default settings" in {
      format.writes(LetsEncryptSettings()) mustBe Json.obj(
        "enabled"                           -> false,
        "server"                            -> "acme://letsencrypt.org/staging",
        "emails"                            -> Json.arr(),
        "contacts"                          -> Json.arr(),
        "publicKey"                         -> "",
        "privateKey"                        -> "",
        "preferredChain"                    -> JsNull,
        "renewalPercentage"                 -> 20,
        "renewBeforeDays"                   -> JsNull,
        "deleteOldCertificatesAfterRenewal" -> false
      )
    }

    "round trip the default settings" in {
      roundTrip(format, LetsEncryptSettings())
    }

    "round trip settings where every field is set" in {
      roundTrip(format, full.letsEncryptSettings)
    }

    "read an empty object as the default settings" in {
      readOk(format, Json.obj()) mustBe LetsEncryptSettings()
    }

    "trim the emails and contacts and drop the blank ones" in {
      val back = readOk(format, Json.obj("emails" -> Json.arr(" a@oto.tools ", " "), "contacts" -> Json.arr("", "mailto:b")))
      back.emails mustBe Seq("a@oto.tools")
      back.contacts mustBe Seq("mailto:b")
    }

    "read a renewal percentage out of range as 20" in {
      readOk(format, Json.obj("renewalPercentage" -> 0)).renewalPercentage mustBe 20
      readOk(format, Json.obj("renewalPercentage" -> 101)).renewalPercentage mustBe 20
      readOk(format, Json.obj("renewalPercentage" -> 100)).renewalPercentage mustBe 100
    }

    "ignore a renewal margin in days that is not positive" in {
      readOk(format, Json.obj("renewBeforeDays" -> 0)).renewBeforeDays mustBe None
      readOk(format, Json.obj("renewBeforeDays" -> 15)).renewBeforeDays mustBe Some(15)
    }

    "trim the preferred chain and ignore a blank one" in {
      readOk(format, Json.obj("preferredChain" -> " ISRG ")).preferredChain mustBe Some("ISRG")
      readOk(format, Json.obj("preferredChain" -> " ")).preferredChain mustBe None
    }

    "compute the renewal threshold from the percentage" in {
      LetsEncryptSettings().renewalThresholdPercentage(90L * 24L * 3600L * 1000L) mustBe 20L
      LetsEncryptSettings(renewalPercentage = 150).renewalThresholdPercentage(1000L) mustBe 100L
      LetsEncryptSettings(renewalPercentage = -5).renewalThresholdPercentage(1000L) mustBe 0L
    }

    "compute the renewal threshold from the days margin when it is set" in {
      val lifetime = 90L * 24L * 3600L * 1000L
      LetsEncryptSettings(renewBeforeDays = Some(30)).renewalThresholdPercentage(lifetime) mustBe 33L
      LetsEncryptSettings(renewBeforeDays = Some(100)).renewalThresholdPercentage(lifetime) mustBe 100L
      LetsEncryptSettings(renewBeforeDays = Some(30)).renewalThresholdPercentage(0L) mustBe 20L
      LetsEncryptSettings(renewBeforeDays = Some(0)).renewalThresholdPercentage(lifetime) mustBe 20L
    }

    "have no key pair without keys" in {
      LetsEncryptSettings().keyPair mustBe None
    }
  }

  "QuotasAlmostExceededSettings" should {

    val format = QuotasAlmostExceededSettings.format

    "write its fields" in {
      format.writes(QuotasAlmostExceededSettings(true, 0.5, 0.9)) mustBe Json.obj(
        "enabled"                -> true,
        "dailyQuotasThreshold"   -> 0.5,
        "monthlyQuotasThreshold" -> 0.9
      )
    }

    "round trip settings" in {
      roundTrip(format, QuotasAlmostExceededSettings(true, 0.5, 0.9))
    }

    "read an empty object with the default thresholds" in {
      readOk(format, Json.obj()) mustBe QuotasAlmostExceededSettings(false, 0.8, 0.8)
    }

    "read thresholds of the wrong type as the default ones" in {
      readOk(format, Json.obj("dailyQuotasThreshold" -> "half")).dailyQuotasThreshold mustBe 0.8
    }
  }

  "UserAgentSettings" should {

    val format = UserAgentSettings.format

    "write and round trip its flag" in {
      format.writes(UserAgentSettings(true)) mustBe Json.obj("enabled" -> true)
      roundTrip(format, UserAgentSettings(true))
      roundTrip(format, UserAgentSettings(false))
    }

    "read an empty object as disabled" in {
      readOk(format, Json.obj()) mustBe UserAgentSettings(false)
    }
  }

  "GeolocationSettings" should {

    val format = GeolocationSettings.format

    "write each kind of settings" in {
      format.writes(NoneGeolocationSettings) mustBe Json.obj("type" -> "none")
      format.writes(MaxmindGeolocationSettings(true, "/geo.mmdb")) mustBe Json.obj(
        "type"    -> "maxmind",
        "path"    -> "/geo.mmdb",
        "enabled" -> true
      )
      format.writes(IpStackGeolocationSettings(true, "key", 500L)) mustBe Json.obj(
        "type"    -> "ipstack",
        "apikey"  -> "key",
        "timeout" -> 500,
        "enabled" -> true
      )
    }

    "round trip each kind of settings" in {
      roundTrip(format, NoneGeolocationSettings)
      roundTrip(format, MaxmindGeolocationSettings(enabled = true, "/geo.mmdb"))
      roundTrip(format, IpStackGeolocationSettings(enabled = false, "key", 500L))
    }

    "read ipstack settings with their default timeout and disabled" in {
      readOk(format, Json.obj("type" -> "ipstack", "apikey" -> "key")) mustBe IpStackGeolocationSettings(false, "key", 2000L)
    }

    "not read maxmind settings without path" in {
      readError(format, Json.obj("type" -> "maxmind", "enabled" -> true))
      readError(format, Json.obj("type" -> "maxmind", "path" -> " "))
    }

    "not read ipstack settings without api key" in {
      readError(format, Json.obj("type" -> "ipstack"))
    }

    "read an unknown type as no geolocation" in {
      readOk(format, Json.obj("type" -> "geoip")) mustBe NoneGeolocationSettings
    }

    "not read settings without type" in {
      readError(format, Json.obj("path" -> "/geo.mmdb"))
    }

    "be disabled when there is no geolocation" in {
      NoneGeolocationSettings.enabled mustBe false
    }
  }

  "GlobalScripts" should {

    val format = GlobalScripts.format

    "write the default scripts" in {
      format.writes(GlobalScripts()) mustBe Json.obj(
        "enabled"            -> false,
        "transformersRefs"   -> Json.arr(),
        "transformersConfig" -> Json.obj(),
        "validatorRefs"      -> Json.arr(),
        "validatorConfig"    -> Json.obj(),
        "preRouteRefs"       -> Json.arr(),
        "preRouteConfig"     -> Json.obj(),
        "sinkRefs"           -> Json.arr(),
        "sinkConfig"         -> Json.obj(),
        "jobRefs"            -> Json.arr(),
        "jobConfig"          -> Json.obj()
      )
    }

    "round trip scripts where every field is set" in {
      roundTrip(format, full.scripts)
    }

    "read an empty object as the default scripts" in {
      readOk(format, Json.obj()) mustBe GlobalScripts()
    }

    "read refs given as a single string as no ref" in {
      readOk(format, Json.obj("jobRefs" -> "job-1")).jobRefs mustBe empty
    }
  }

  "Plugins" should {

    val format = Plugins.format

    "write the default plugins" in {
      format.writes(Plugins()) mustBe Json.obj(
        "enabled"  -> false,
        "refs"     -> Json.arr(),
        "config"   -> Json.obj(),
        "excluded" -> Json.arr()
      )
    }

    "round trip plugins where every field is set" in {
      roundTrip(format, full.plugins)
    }

    "read an empty object as the default plugins" in {
      readOk(format, Json.obj()) mustBe Plugins()
    }

    "keep a config of any json type" in {
      readOk(format, Json.obj("config" -> Json.arr(1, 2))).config mustBe Json.arr(1, 2)
    }
  }

  "DefaultTemplates" should {

    val format = DefaultTemplates.format

    "write every template as an empty object by default" in {
      val json = written(format, DefaultTemplates())
      json.keys mustBe Set(
        "route",
        "service",
        "backend",
        "target",
        "descriptor",
        "apikey",
        "group",
        "template",
        "verifier",
        "authConfig",
        "certificate",
        "script",
        "draft",
        "api",
        "tcpService",
        "dataExporter",
        "tenant",
        "team",
        "apiSubscription",
        "routeTemplate",
        "userDashboardTemplate",
        "userAlertTemplate"
      )
      json.values.toSet mustBe Set(Json.obj())
    }

    "write a missing template as null" in {
      (written(format, DefaultTemplates(route = None)) \ "route").as[JsValue] mustBe JsNull
    }

    "round trip the default templates" in {
      roundTrip(format, DefaultTemplates())
    }

    "round trip templates where some are set and some are missing" in {
      roundTrip(
        format,
        DefaultTemplates(
          route = Some(Json.obj("name" -> "r")),
          service = None,
          dataExporter = Some(Json.obj("enabled" -> true)),
          userAlertTemplate = None
        )
      )
    }

    "read an empty object as no template at all" in {
      val back = readOk(format, Json.obj())
      back.route mustBe None
      back.apikey mustBe None
      back.userAlertTemplate mustBe None
    }

    "ignore a template that is not an object" in {
      readOk(format, Json.obj("route" -> "r")).route mustBe None
    }
  }

  "TlsWasmoSettings" should {

    val format = TlsWasmoSettings.format

    "write the wasmo settings and the tls config" in {
      format.writes(wasmo) mustBe Json.obj(
        "settings"  -> wasmo.settings.json,
        "tlsConfig" -> wasmo.tlsConfig.json
      )
    }

    "round trip the default settings" in {
      roundTrip(format, TlsWasmoSettings())
    }

    "round trip settings where every field is set" in {
      roundTrip(format, wasmo)
    }

    "round trip the tls config of the wasm4s settings" in {
      val tls  = TlsConfig(certs = Seq("c1"), trustedCerts = Seq("t1"), enabled = true, loose = true, trustAll = false)
      val back = roundTrip(format, wasmo.copy(settings = wasmo.settings.copy(tlsConfig = Some(tls))))
      back.settings.tlsConfig mustBe Some(tls)
    }

    "round trip wasm4s settings without plugins filter" in {
      roundTrip(format, wasmo.copy(settings = wasmo.settings.copy(pluginsFilter = None))).settings.pluginsFilter mustBe None
    }

    "read a null tls config of the wasm4s settings as no tls config" in {
      TlsWasmoSettings.readSettings(wasmo.settings.json).get.tlsConfig mustBe None
    }

    "read a missing plugins filter as every plugin" in {
      val json = wasmo.settings.json.as[JsObject] - "pluginsFilter"
      TlsWasmoSettings.readSettings(json).get.pluginsFilter mustBe Some("*")
    }

    "read the wasm4s tls config from the keys wasm4s reads it from" in {
      val tls  = TlsConfig(certs = Seq("c1"), enabled = true)
      val json = (wasmo.settings.json.as[JsObject] - "tlsConfig") ++ Json.obj("tls" -> tls.json)
      TlsWasmoSettings.readSettings(json).get.tlsConfig mustBe Some(tls)
    }

    "prefer the tls key of wasm4s over the tlsConfig key" in {
      val json = wasmo.settings.json.as[JsObject] ++ Json.obj(
        "tls"       -> TlsConfig(certs = Seq("from-tls")).json,
        "tlsConfig" -> TlsConfig(certs = Seq("from-tlsConfig")).json
      )
      TlsWasmoSettings.readSettings(json).get.tlsConfig.map(_.certs) mustBe Some(Seq("from-tls"))
    }

    "keep the tls config of the wasm4s settings in the global config" in {
      val tls     = TlsConfig(certs = Seq("c1"), enabled = true)
      val config  = minimal.copy(wasmoSettings = Some(TlsWasmoSettings(WasmoSettings(tlsConfig = Some(tls)))))
      roundTrip(GlobalConfig._fmt, config).wasmoSettings.flatMap(_.settings.tlsConfig) mustBe Some(tls)
    }

    "read settings without tls config with the default tls config" in {
      val settings = WasmoSettings(url = "http://wasmo:5001")
      readOk(format, Json.obj("settings" -> settings.json)) mustBe TlsWasmoSettings(settings, MtlsConfig())
    }

    "not read a tls config without settings" in {
      readError(format, Json.obj("tlsConfig" -> MtlsConfig().json))
    }

    "keep the wasmo settings without tls when mtls is disabled" in {
      val settings = WasmoSettings(url = "http://wasmo")
      TlsWasmoSettings(settings, MtlsConfig(mtls = false)).toWasm4sSettings mustBe settings
    }

    "keep the enabled tls config of the wasmo settings" in {
      val settings = WasmoSettings(url = "http://wasmo", tlsConfig = Some(TlsConfig(certs = Seq("c"), enabled = true)))
      TlsWasmoSettings(settings, MtlsConfig(Seq("other"), mtls = true)).toWasm4sSettings mustBe settings
    }

    "give the mtls config to wasmo settings that have no tls config" in {
      val settings = WasmoSettings(url = "http://wasmo")
      TlsWasmoSettings(settings, MtlsConfig(Seq("c1"), Seq("t1"), mtls = true, loose = true, trustAll = false)).toWasm4sSettings mustBe
        settings.copy(tlsConfig = Some(TlsConfig(Seq("c1"), Seq("t1"), enabled = true, loose = true, trustAll = false)))
    }
  }

  "Proxies" should {

    val format = Proxies.format

    val proxy = DefaultWSProxyServer(
      host = "proxy.oto.tools",
      port = 3128,
      protocol = Some("https"),
      principal = Some("user"),
      password = Some("password"),
      ntlmDomain = Some("domain"),
      encoding = Some("UTF-8"),
      nonProxyHosts = Some(Seq("localhost", "*.internal"))
    )

    "write no proxy as nulls" in {
      format.writes(Proxies()) mustBe Json.obj(
        "alertEmails"    -> JsNull,
        "eventsWebhooks" -> JsNull,
        "clevercloud"    -> JsNull,
        "services"       -> JsNull,
        "auth"           -> JsNull,
        "authority"      -> JsNull,
        "jwk"            -> JsNull,
        "elastic"        -> JsNull
      )
    }

    "write a proxy with every field" in {
      (written(format, Proxies(services = Some(proxy))) \ "services").as[JsValue] mustBe Json.obj(
        "host"          -> "proxy.oto.tools",
        "port"          -> 3128,
        "protocol"      -> "https",
        "principal"     -> "user",
        "password"      -> "password",
        "ntlmDomain"    -> "domain",
        "encoding"      -> "UTF-8",
        "nonProxyHosts" -> Json.arr("localhost", "*.internal")
      )
    }

    "round trip no proxy" in {
      roundTrip(format, Proxies())
    }

    "round trip a proxy for every usage" in {
      val minimalProxy = DefaultWSProxyServer("p", 8080)
      roundTrip(
        format,
        Proxies(Some(proxy), Some(minimalProxy), Some(proxy), Some(minimalProxy), Some(proxy), Some(minimalProxy), Some(proxy), Some(minimalProxy))
      )
    }

    "ignore a proxy without port or with a blank host" in {
      readOk(format, Json.obj("auth" -> Json.obj("host" -> "p"))).auth mustBe None
      readOk(format, Json.obj("auth" -> Json.obj("host" -> " ", "port" -> 80))).auth mustBe None
    }

    "ignore the blank optional values of a proxy" in {
      readOk(format, Json.obj("jwk" -> Json.obj("host" -> "p", "port" -> 80, "principal" -> " ", "protocol" -> ""))).jwk mustBe
        Some(DefaultWSProxyServer("p", 80))
    }
  }

  "CleverCloudSettings" should {

    "round trip with its own format" in {
      roundTrip(CleverCloudSettings.format, CleverCloudSettings("ck", "cs", "t", "s", "o"))
    }

    "not read settings missing a value with its own format" in {
      readError(CleverCloudSettings.format, Json.obj("consumerKey" -> "ck"))
    }
  }
}
