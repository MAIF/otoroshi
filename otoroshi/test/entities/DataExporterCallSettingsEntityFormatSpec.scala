package entities

import org.apache.pekko.stream.connectors.s3.headers.CannedAcl
import otoroshi.events.Exporters.{CustomMetricsSettings, MetricSettings, MetricSettingsKind, OtlpLogsExporterSettings, OtlpMetricsExporterSettings, WasmExporterSettings}
import otoroshi.metrics.opentelemetry.OtlpSettings
import otoroshi.models.*
import otoroshi.next.analytics.exporter.UserAnalyticsExporterSettings
import otoroshi.next.models.NgTlsConfig
import otoroshi.storage.drivers.inmemory.S3Configuration
import play.api.libs.json.*
import play.api.libs.ws.DefaultWSCookie

import scala.concurrent.duration.*

class DataExporterCallSettingsEntityFormatSpec extends EntityFormatSpec {

  private val tls = NgTlsConfig(Seq("cert-1"), Seq("ca-1"), enabled = true, loose = true, trustAll = true)

  private val otlp = OtlpSettings(
    grpc = true,
    endpoint = "http://otlp.oto.tools:4317",
    timeout = 2.seconds,
    gzip = true,
    clientCert = None,
    trustedCert = None,
    headers = Map("X-A" -> "b"),
    maxBatch = 10,
    maxDuration = 3.seconds
  )

  "HttpCallSettings" should {

    val format = HttpCallSettings.format

    val cookie = DefaultWSCookie("session", "s3cr3t", Some("oto.tools"), Some("/"), Some(3600L), secure = true, httpOnly = true)

    val full = HttpCallSettings(
      url = "https://events.oto.tools",
      method = "PUT",
      headers = Map("X-A" -> "b"),
      cookies = Seq(cookie, DefaultWSCookie("plain", "value")),
      body = "${events}",
      timeout = 10.seconds,
      tlsConfig = tls
    )

    "write settings where every field is set" in {
      format.writes(full.copy(cookies = Seq(cookie))) mustBe Json.obj(
        "url"        -> "https://events.oto.tools",
        "method"     -> "PUT",
        "headers"    -> Json.obj("X-A" -> "b"),
        "cookies"    -> Json.arr(
          Json.obj(
            "name"      -> "session",
            "value"     -> "s3cr3t",
            "path"      -> "/",
            "domain"    -> "oto.tools",
            "http_only" -> true,
            "max_age"   -> 3600,
            "secure"    -> true
          )
        ),
        "body"       -> "${events}",
        "timeout"    -> 10000,
        "tls_config" -> tls.json
      )
    }

    "round trip settings where every field is set" in {
      roundTrip(format, full)
    }

    "read a lone url with the defaults" in {
      readOk(format, Json.obj("url" -> "http://a")) mustBe HttpCallSettings("http://a", "GET", Map.empty, Seq.empty, "", 60.seconds, NgTlsConfig())
    }

    "not read settings without url" in {
      readError(format, Json.obj("method" -> "POST"))
    }

    "not read settings with a cookie without value" in {
      readError(format, Json.obj("url" -> "http://a", "cookies" -> Json.arr(Json.obj("name" -> "c"))))
    }

    "read the tls config from its legacy single cert key" in {
      readOk(format, Json.obj("url" -> "http://a", "tls_config" -> Json.obj("cert_id" -> "cert-1", "enabled" -> true))).tlsConfig mustBe
        NgTlsConfig(certs = Seq("cert-1"), enabled = true)
    }
  }

  "SplunkCallSettings" should {

    val format = SplunkCallSettings.format

    "write minimal settings with a null token and without the optional fields" in {
      format.writes(SplunkCallSettings("http://splunk", Map.empty, None, None, None, Map.empty, 1.second, NgTlsConfig())) mustBe Json.obj(
        "token"      -> JsNull,
        "url"        -> "http://splunk",
        "headers"    -> Json.obj(),
        "timeout"    -> 1000,
        "tls_config" -> NgTlsConfig().json
      )
    }

    "write the token, the index and the fields when they are set" in {
      val json = written(format, SplunkCallSettings("http://splunk", Map.empty, Some("t"), None, Some("main"), Map("env" -> "prod"), 1.second, tls))
      (json \ "token").as[String] mustBe "t"
      (json \ "index").as[String] mustBe "main"
      (json \ "fields").as[JsValue] mustBe Json.obj("env" -> "prod")
    }

    "round trip settings without source type" in {
      roundTrip(format, SplunkCallSettings("http://splunk", Map("X-A" -> "b"), Some("t"), None, Some("main"), Map("env" -> "prod"), 2.seconds, tls))
    }

    "round trip minimal settings" in {
      roundTrip(format, SplunkCallSettings("http://splunk", Map.empty, None, None, None, Map.empty, 60.seconds, NgTlsConfig()))
    }

    "read the source type from its snake case key" in {
      readOk(format, Json.obj("url" -> "http://splunk", "source_type" -> "_json")).sourceType mustBe Some("_json")
    }

    "read a lone url with the defaults" in {
      readOk(format, Json.obj("url" -> "http://splunk")) mustBe
        SplunkCallSettings("http://splunk", Map.empty, None, None, None, Map.empty, 60.seconds, NgTlsConfig())
    }

    "not read settings without url" in {
      readError(format, Json.obj("token" -> "t"))
    }
  }

  "DatadogCallSettings" should {

    val format = DatadogCallSettings.format

    val full = DatadogCallSettings("https://dd", Map("X-A" -> "b"), Some("t"), Some("src"), Some("env:prod"), Some("gw"), Some("oto-1"), 5.seconds, tls)

    "write minimal settings with a null token and without the optional fields" in {
      format.writes(full.copy(token = None, ddsource = None, ddtags = None, service = None, hostname = None)) mustBe Json.obj(
        "token"      -> JsNull,
        "url"        -> "https://dd",
        "headers"    -> Json.obj("X-A" -> "b"),
        "timeout"    -> 5000,
        "tls_config" -> tls.json
      )
    }

    "write the optional fields when they are set" in {
      val json = written(format, full)
      (json \ "token").as[String] mustBe "t"
      (json \ "ddsource").as[String] mustBe "src"
      (json \ "ddtags").as[String] mustBe "env:prod"
      (json \ "service").as[String] mustBe "gw"
      (json \ "hostname").as[String] mustBe "oto-1"
    }

    "round trip settings where every field is set" in {
      roundTrip(format, full)
    }

    "read an empty object with the european intake url" in {
      readOk(format, Json.obj()) mustBe DatadogCallSettings(
        "https://http-intake.logs.datadoghq.eu/api/v2/logs",
        Map.empty,
        None,
        None,
        None,
        None,
        None,
        60.seconds,
        NgTlsConfig()
      )
    }
  }

  "NewRelicCallSettings" should {

    val format = NewRelicCallSettings.format

    val full = NewRelicCallSettings("https://nr", Map("X-A" -> "b"), Some("t"), Some("accesslogs"), Some("gw"), Some("oto-1"), 5.seconds, tls)

    "write the optional fields only when they are set" in {
      writtenKeys(format, full.copy(logtype = None, service = None, hostname = None)) mustBe Set("token", "url", "headers", "timeout", "tls_config")
      writtenKeys(format, full) mustBe Set("token", "url", "headers", "timeout", "tls_config", "logtype", "service", "hostname")
    }

    "round trip settings where every field is set" in {
      roundTrip(format, full)
    }

    "read an empty object with the european log api url" in {
      readOk(format, Json.obj()) mustBe NewRelicCallSettings("https://log-api.eu.newrelic.com/log/v1", Map.empty, None, None, None, None, 60.seconds, NgTlsConfig())
    }
  }

  "WorkflowCallSettings" should {

    val format = WorkflowCallSettings.format

    "write and round trip its ref" in {
      format.writes(WorkflowCallSettings("workflow-1")) mustBe Json.obj("ref" -> "workflow-1")
      roundTrip(format, WorkflowCallSettings("workflow-1"))
    }

    "read an empty object as an empty ref" in {
      readOk(format, Json.obj()) mustBe WorkflowCallSettings("")
    }
  }

  "TCPExporterSettings" should {

    val format = TCPExporterSettings.format

    "write its fields" in {
      format.writes(TCPExporterSettings("logs", 6000, unixSocket = true, 2.seconds, tls)) mustBe Json.obj(
        "host"            -> "logs",
        "port"            -> 6000,
        "unix_socket"     -> true,
        "connect_timeout" -> 2000,
        "tls"             -> tls.json
      )
    }

    "round trip settings" in {
      roundTrip(format, TCPExporterSettings("logs", 6000, unixSocket = true, 2.seconds, tls))
    }

    "read an empty object with the defaults" in {
      readOk(format, Json.obj()) mustBe TCPExporterSettings("127.0.0.1", 6514, unixSocket = false, 10.seconds, NgTlsConfig.default)
    }
  }

  "UDPExporterSettings" should {

    val format = UDPExporterSettings.format

    "write its fields" in {
      format.writes(UDPExporterSettings("logs", 6001, unixSocket = false, 3.seconds)) mustBe Json.obj(
        "host"            -> "logs",
        "port"            -> 6001,
        "unix_socket"     -> false,
        "connect_timeout" -> 3000
      )
    }

    "round trip settings" in {
      roundTrip(format, UDPExporterSettings("logs", 6001, unixSocket = true, 3.seconds))
    }

    "read an empty object with the defaults" in {
      readOk(format, Json.obj()) mustBe UDPExporterSettings("127.0.0.1", 514, unixSocket = false, 10.seconds)
    }
  }

  "SyslogExporterSettings" should {

    val format = SyslogExporterSettings.format

    "write its fields" in {
      format.writes(SyslogExporterSettings(tcp = true, udp = false, "syslog", 601, unixSocket = false, 4.seconds, tls)) mustBe Json.obj(
        "tcp"             -> true,
        "udp"             -> false,
        "host"            -> "syslog",
        "port"            -> 601,
        "unix_socket"     -> false,
        "connect_timeout" -> 4000,
        "tls"             -> tls.json
      )
    }

    "round trip settings" in {
      roundTrip(format, SyslogExporterSettings(tcp = true, udp = false, "syslog", 601, unixSocket = false, 4.seconds, tls))
    }

    "read an empty object as the local udp syslog socket" in {
      readOk(format, Json.obj()) mustBe SyslogExporterSettings(
        tcp = false,
        udp = true,
        "/var/run/syslog",
        514,
        unixSocket = true,
        10.seconds,
        NgTlsConfig.default
      )
    }
  }

  "JMSExporterSettings" should {

    val format = JMSExporterSettings.format

    "write its fields with null credentials" in {
      format.writes(JMSExporterSettings("tcp://mq:61616", "events", topic = false, None, None)) mustBe Json.obj(
        "url"      -> "tcp://mq:61616",
        "name"     -> "events",
        "topic"    -> false,
        "username" -> JsNull,
        "password" -> JsNull
      )
    }

    "round trip settings with and without credentials" in {
      roundTrip(format, JMSExporterSettings("tcp://mq:61616", "events", topic = false, Some("user"), Some("password")))
      roundTrip(format, JMSExporterSettings("tcp://mq:61616", "events", topic = true, None, None))
    }

    "read an empty object with the defaults" in {
      readOk(format, Json.obj()) mustBe JMSExporterSettings("tcp://localhost:61616", "otoroshi-events", topic = true, None, None)
    }

    "ignore blank credentials" in {
      val back = readOk(format, Json.obj("username" -> "", "password" -> "  "))
      back.username mustBe None
      back.password mustBe None
    }
  }

  "PostgresExporterSettings" should {

    val format = PostgresExporterSettings.format

    "write the default settings" in {
      format.writes(PostgresExporterSettings()) mustBe Json.obj(
        "uri"            -> JsNull,
        "host"           -> "localhost",
        "port"           -> 5432,
        "database"       -> "otoroshi",
        "user"           -> "otoroshi",
        "password"       -> "otoroshi",
        "schema"         -> "otoroshi",
        "table"          -> "otoroshi_events",
        "pool_size"      -> 5,
        "ssl"            -> false,
        "retention_days" -> 0
      )
    }

    "round trip settings where every field is set" in {
      roundTrip(format, PostgresExporterSettings(Some("postgres://pg/db"), "pg", 5433, "db", "u", "p", "s", "t", 2, ssl = true, 7))
    }

    "read an empty object as the default settings" in {
      readOk(format, Json.obj()) mustBe PostgresExporterSettings()
    }

    "ignore an empty uri" in {
      readOk(format, Json.obj("uri" -> "")).uri mustBe None
    }
  }

  "UserAnalyticsExporterSettings" should {

    val format = UserAnalyticsExporterSettings.format

    val full = UserAnalyticsExporterSettings(
      uri = Some("postgres://pg/analytics"),
      host = "pg",
      port = 5433,
      database = "analytics",
      user = "u",
      password = "p",
      schema = "events",
      tablePrefix = "my_events",
      poolSize = 3,
      ssl = true,
      retentionDays = 90,
      statementTimeoutMs = 1000,
      rollupEnabled = true,
      excludedProjections = Seq("security", "llm")
    )

    "write the table prefix under its legacy table key too" in {
      val json = written(format, full)
      (json \ "table_prefix").as[String] mustBe "my_events"
      (json \ "table").as[String] mustBe "my_events"
    }

    "write the default settings" in {
      format.writes(UserAnalyticsExporterSettings()) mustBe Json.obj(
        "uri"                  -> JsNull,
        "host"                 -> "localhost",
        "port"                 -> 5432,
        "database"             -> "otoroshi",
        "user"                 -> "otoroshi",
        "password"             -> "otoroshi",
        "schema"               -> "public",
        "table_prefix"         -> "otoroshi_analytics_events",
        "table"                -> "otoroshi_analytics_events",
        "pool_size"            -> 10,
        "ssl"                  -> false,
        "retention_days"       -> 30,
        "statement_timeout_ms" -> 30000,
        "rollup_enabled"       -> false,
        "excluded_projections" -> Json.arr()
      )
    }

    "round trip settings where every field is set" in {
      roundTrip(format, full)
    }

    "read an empty object as the default settings" in {
      readOk(format, Json.obj()) mustBe UserAnalyticsExporterSettings()
    }

    "read the table prefix from the legacy table key" in {
      readOk(format, Json.obj("table" -> "legacy_events")).tablePrefix mustBe "legacy_events"
    }

    "prefer the table prefix to the legacy table key" in {
      readOk(format, Json.obj("table_prefix" -> "new_events", "table" -> "legacy_events")).tablePrefix mustBe "new_events"
    }

    "read an empty table prefix from the legacy table key" in {
      readOk(format, Json.obj("table_prefix" -> "", "table" -> "legacy_events")).tablePrefix mustBe "legacy_events"
    }

    "name the tables of the projections after the schema and the prefix" in {
      full.prefixedTable("security") mustBe "events.my_events_security"
    }
  }

  "S3Configuration" should {

    val format = S3Configuration.format

    val full = S3Configuration(
      bucket = "events",
      endpoint = "https://s3.oto.tools",
      region = "eu-west-3",
      access = "access",
      secret = "secret",
      key = "otoroshi/events",
      chunkSize = 1024,
      v4auth = false,
      writeEvery = 30.seconds,
      acl = CannedAcl.PublicRead,
      pathStyleAccess = true
    )

    "write a config with the s3 value of its acl" in {
      format.writes(full) mustBe Json.obj(
        "bucket"          -> "events",
        "endpoint"        -> "https://s3.oto.tools",
        "region"          -> "eu-west-3",
        "access"          -> "access",
        "secret"          -> "secret",
        "key"             -> "otoroshi/events",
        "chunkSize"       -> 1024,
        "v4auth"          -> false,
        "writeEvery"      -> 30000,
        "acl"             -> "public-read",
        "pathStyleAccess" -> true
      )
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip every acl" in {
      Seq(
        CannedAcl.AuthenticatedRead,
        CannedAcl.AwsExecRead,
        CannedAcl.BucketOwnerFullControl,
        CannedAcl.BucketOwnerRead,
        CannedAcl.Private,
        CannedAcl.PublicRead,
        CannedAcl.PublicReadWrite
      ).foreach { acl =>
        roundTrip(format, full.copy(acl = acl)).acl mustBe acl
      }
    }

    "read the acls from their enum name too" in {
      readOk(format, Json.obj("acl" -> "BucketOwnerRead")).acl mustBe CannedAcl.BucketOwnerRead
      readOk(format, Json.obj("acl" -> "PublicReadWrite")).acl mustBe CannedAcl.PublicReadWrite
    }

    "read an unknown acl as private" in {
      readOk(format, Json.obj("acl" -> "everyone")).acl mustBe CannedAcl.Private
    }

    "read an empty object with a default access key that differs from the default config" in {
      readOk(format, Json.obj()) mustBe S3Configuration.default.copy(access = "secret")
    }

    "drop the leading slash of the key" in {
      readOk(format, Json.obj("key" -> "/otoroshi/events")).key mustBe "otoroshi/events"
    }
  }

  "S3ExporterSettings" should {

    val format = S3ExporterSettings.format

    "write the s3 config with the file limits" in {
      format.writes(S3ExporterSettings(2048L, Some(3), S3Configuration.default)) mustBe
        S3Configuration.default.json.as[JsObject] ++ Json.obj("maxFileSize" -> 2048, "maxNumberOfFile" -> 3)
    }

    "write a missing maximum number of files as null" in {
      (written(format, S3ExporterSettings(2048L, None, S3Configuration.default)) \ "maxNumberOfFile").as[JsValue] mustBe JsNull
    }

    "round trip settings with and without a maximum number of files" in {
      roundTrip(format, S3ExporterSettings(2048L, Some(3), S3Configuration.default))
      roundTrip(format, S3ExporterSettings(2048L, None, S3Configuration.default))
    }

    "read an empty object with a 10 MB maximum file size" in {
      val back = readOk(format, Json.obj())
      back.maxFileSize mustBe 10L * 1024L * 1024L
      back.maxNumberOfFile mustBe None
    }

    "read a maximum number of files that is not positive as none" in {
      readOk(format, Json.obj("maxNumberOfFile" -> 0)).maxNumberOfFile mustBe None
    }
  }

  "OtlpSettings" should {

    val format = OtlpSettings.format

    "write settings without certificates" in {
      format.writes(otlp) mustBe Json.obj(
        "gzip"         -> true,
        "grpc"         -> true,
        "endpoint"     -> "http://otlp.oto.tools:4317",
        "timeout"      -> 2000,
        "client_cert"  -> JsNull,
        "trusted_cert" -> JsNull,
        "headers"      -> Json.obj("X-A" -> "b"),
        "max_batch"    -> 10,
        "max_duration" -> 3000
      )
    }

    "round trip settings without certificates" in {
      roundTrip(format, otlp)
    }

    "round trip the default logs settings" in {
      roundTrip(format, OtlpSettings.defaultLogs)
    }

    "read the trusted certificate from its key" in {
      readOk(format, Json.obj("endpoint" -> "http://otlp", "trusted_cert" -> "ca-1")).trustedCert mustBe Some("ca-1")
    }

    "read a lone endpoint with the defaults" in {
      readOk(format, Json.obj("endpoint" -> "http://otlp")) mustBe OtlpSettings(
        grpc = false,
        endpoint = "http://otlp",
        timeout = 30.seconds,
        gzip = false,
        clientCert = None,
        trustedCert = None,
        headers = Map.empty,
        maxBatch = 1000,
        maxDuration = 30.seconds
      )
    }

    "read zero durations as 30 seconds" in {
      val back = readOk(format, Json.obj("endpoint" -> "http://otlp", "timeout" -> 0, "max_duration" -> 0))
      back.timeout mustBe 30.seconds
      back.maxDuration mustBe 30.seconds
    }

    "not read settings without endpoint" in {
      readError(format, Json.obj("grpc" -> true))
    }

    "expose through json what the format writes" in {
      otlp.json mustBe format.writes(otlp)
    }
  }

  "MetricSettings" should {

    val format = MetricSettings.format

    "write a counter" in {
      format.writes(MetricSettings("calls", Some("duration"), MetricSettingsKind.Counter, Some("GatewayEvent"), Map("route.name" -> "route"))) mustBe
        Json.obj(
          "id"        -> "calls",
          "selector"  -> "duration",
          "kind"      -> "Counter",
          "eventType" -> "GatewayEvent",
          "labels"    -> Json.obj("route.name" -> "route")
        )
    }

    "round trip a counter with an event type" in {
      roundTrip(format, MetricSettings("calls", None, MetricSettingsKind.Counter, Some("GatewayEvent"), Map("a" -> "b")))
    }

    "read a lone id as a counter of alert events" in {
      readOk(format, Json.obj("id" -> "calls")) mustBe MetricSettings("calls", None, MetricSettingsKind.Counter, Some("AlertEvent"), Map.empty)
    }

    "read each kind from its name" in {
      readOk(format, Json.obj("id" -> "m", "kind" -> "Counter")).kind mustBe MetricSettingsKind.Counter
      readOk(format, Json.obj("id" -> "m", "kind" -> "Histogram")).kind mustBe MetricSettingsKind.Histogram
      readOk(format, Json.obj("id" -> "m", "kind" -> "Timer")).kind mustBe MetricSettingsKind.Timer
    }

    "not read an unknown kind" in {
      readError(format, Json.obj("id" -> "m", "kind" -> "Gauge"))
    }

    "not read a metric without id" in {
      readError(format, Json.obj("kind" -> "Counter"))
    }
  }

  "CustomMetricsSettings" should {

    val format = CustomMetricsSettings.format

    val metrics = CustomMetricsSettings(
      tags = Map("env" -> "prod"),
      metrics = Seq(MetricSettings("calls", None, MetricSettingsKind.Counter, Some("GatewayEvent"), Map.empty))
    )

    "write its tags and metrics" in {
      format.writes(metrics) mustBe Json.obj(
        "tags"    -> Json.obj("env" -> "prod"),
        "metrics" -> Json.arr(metrics.metrics.head.toJson)
      )
    }

    "round trip settings" in {
      roundTrip(format, metrics)
    }

    "read an empty object as no metric" in {
      readOk(format, Json.obj()) mustBe CustomMetricsSettings()
    }

    "not read settings where one metric cannot be read" in {
      readError(format, Json.obj("metrics" -> Json.arr(Json.obj("id" -> "a"), Json.obj("kind" -> "Counter"))))
    }

    "expose through json and toJson what the format writes" in {
      metrics.json mustBe format.writes(metrics)
      metrics.toJson mustBe format.writes(metrics)
    }
  }

  "WasmExporterSettings" should {

    val format = WasmExporterSettings.format

    "write its params and its plugin ref" in {
      format.writes(WasmExporterSettings(Json.obj("a" -> 1), Some("wasm-1"))) mustBe Json.obj("params" -> Json.obj("a" -> 1), "wasm_ref" -> "wasm-1")
      format.writes(WasmExporterSettings(Json.obj(), None)) mustBe Json.obj("params" -> Json.obj(), "wasm_ref" -> JsNull)
    }

    "round trip settings with and without plugin ref" in {
      roundTrip(format, WasmExporterSettings(Json.obj("a" -> 1), Some("wasm-1")))
      roundTrip(format, WasmExporterSettings(Json.obj(), None))
    }

    "read an empty object as no params and no plugin" in {
      readOk(format, Json.obj()) mustBe WasmExporterSettings(Json.obj(), None)
    }

    "ignore a blank plugin ref" in {
      readOk(format, Json.obj("wasm_ref" -> " ")).wasmRef mustBe None
    }
  }

  "OtlpMetricsExporterSettings" should {

    val format = OtlpMetricsExporterSettings.format

    val settings = OtlpMetricsExporterSettings(
      otlp,
      Map("env" -> "prod"),
      Seq(MetricSettings("calls", None, MetricSettingsKind.Counter, Some("GatewayEvent"), Map.empty))
    )

    "write its type with its settings" in {
      format.writes(settings) mustBe Json.obj(
        "type"    -> "otlp-metrics",
        "tags"    -> Json.obj("env" -> "prod"),
        "metrics" -> Json.arr(settings.metrics.head.toJson),
        "otlp"    -> otlp.json
      )
    }

    "round trip settings" in {
      roundTrip(format, settings)
    }

    "skip the metrics that cannot be read" in {
      readOk(format, written(format, settings) ++ Json.obj("metrics" -> Json.arr(Json.obj("kind" -> "Counter"), settings.metrics.head.toJson))).metrics mustBe
        settings.metrics
    }

    "not read settings without otlp settings" in {
      readError(format, Json.obj("tags" -> Json.obj()))
    }
  }

  "OtlpLogsExporterSettings" should {

    val format = OtlpLogsExporterSettings.format

    "write its type with its settings" in {
      format.writes(OtlpLogsExporterSettings(otlp)) mustBe Json.obj("type" -> "otlp-logs", "otlp" -> otlp.json)
    }

    "round trip settings" in {
      roundTrip(format, OtlpLogsExporterSettings(otlp))
    }

    "not read settings without otlp settings or without endpoint" in {
      readError(format, Json.obj())
      readError(format, Json.obj("otlp" -> Json.obj("grpc" -> true)))
    }
  }
}
