package entities

import org.apache.pekko.stream.connectors.s3.headers.CannedAcl
import otoroshi.events.Exporters.{CustomMetricsSettings, MetricSettings, MetricSettingsKind, OtlpLogsExporterSettings, OtlpMetricsExporterSettings, WasmExporterSettings}
import otoroshi.events.{KafkaConfig, PulsarConfig, SaslConfig}
import otoroshi.metrics.opentelemetry.OtlpSettings
import otoroshi.models.*
import otoroshi.next.analytics.exporter.UserAnalyticsExporterSettings
import otoroshi.next.models.NgTlsConfig
import otoroshi.storage.drivers.inmemory.S3Configuration
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.mailer.*
import play.api.libs.json.*
import play.api.libs.ws.DefaultWSCookie

import scala.concurrent.duration.*

class DataExporterEntityFormatSpec extends EntityFormatSpec {

  private val format = DataExporterConfig.format

  private val minimal = DataExporterConfig(
    enabled = false,
    typ = DataExporterConfigType.Console,
    id = "exporter-1",
    name = "Exporter 1",
    desc = "",
    filtering = DataExporterConfigFiltering(),
    projection = Json.obj(),
    config = ConsoleSettings()
  )

  private val webhook = Webhook(
    "http://events.oto.tools",
    Map("Authorization" -> "Bearer token"),
    MtlsConfig(Seq("cert-1"), Seq("ca-1"), mtls = true, loose = true, trustAll = true)
  )

  private val full = DataExporterConfig(
    enabled = true,
    typ = DataExporterConfigType.Webhook,
    id = "exporter-2",
    name = "Exporter 2",
    desc = "the webhook exporter",
    metadata = Map("owner" -> "ops"),
    tags = Seq("prod", "eu"),
    location = customLocation,
    bufferSize = 100,
    jsonWorkers = 2,
    sendWorkers = 3,
    groupSize = 10,
    groupDuration = 5.seconds,
    filtering = DataExporterConfigFiltering(
      include = Seq(Json.obj("@type" -> "AlertEvent")),
      exclude = Seq(Json.obj("@type" -> Json.obj("$regex" -> "Gateway.*")), Json.obj("status" -> 200))
    ),
    projection = Json.obj("@type" -> true, "@timestamp" -> true),
    customFilter = Some(CustomDataExporterRefConfig("wasm", "wasm-plugin-1", Json.obj("a" -> 1))),
    customTransform = Some(CustomDataExporterRefConfig("plugin", "cp:transform", Json.obj())),
    config = webhook
  )

  private val tls = NgTlsConfig(Seq("cert-1"), Seq("ca-1"), enabled = true, loose = true, trustAll = true)

  private val s3 = S3Configuration(
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

  private def exporter(typ: DataExporterConfigType, config: Exporter): DataExporterConfig =
    minimal.copy(typ = typ, config = config)

  private def configOf(typ: String, config: JsValue): JsObject =
    written(format, minimal) ++ Json.obj("type" -> typ, "config" -> config)

  "DataExporterConfig" should {

    "write a minimal exporter" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"            -> defaultLocationJson,
        "type"            -> "console",
        "enabled"         -> false,
        "id"              -> "exporter-1",
        "name"            -> "Exporter 1",
        "desc"            -> "",
        "metadata"        -> Json.obj(),
        "tags"            -> Json.arr(),
        "bufferSize"      -> 5000,
        "jsonWorkers"     -> 1,
        "sendWorkers"     -> 5,
        "groupSize"       -> 100,
        "groupDuration"   -> 30000,
        "projection"      -> Json.obj(),
        "filtering"       -> Json.obj("include" -> Json.arr(), "exclude" -> Json.arr()),
        "customFilter"    -> JsNull,
        "customTransform" -> JsNull,
        "config"          -> Json.obj()
      )
    }

    "write an exporter where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "_loc"            -> customLocationJson,
        "type"            -> "webhook",
        "enabled"         -> true,
        "id"              -> "exporter-2",
        "name"            -> "Exporter 2",
        "desc"            -> "the webhook exporter",
        "metadata"        -> Json.obj("owner" -> "ops"),
        "tags"            -> Json.arr("prod", "eu"),
        "bufferSize"      -> 100,
        "jsonWorkers"     -> 2,
        "sendWorkers"     -> 3,
        "groupSize"       -> 10,
        "groupDuration"   -> 5000,
        "projection"      -> Json.obj("@type" -> true, "@timestamp" -> true),
        "filtering"       -> Json.obj(
          "include" -> Json.arr(Json.obj("@type" -> "AlertEvent")),
          "exclude" -> Json.arr(Json.obj("@type" -> Json.obj("$regex" -> "Gateway.*")), Json.obj("status" -> 200))
        ),
        "customFilter"    -> Json.obj("kind" -> "wasm", "ref" -> "wasm-plugin-1", "config" -> Json.obj("a" -> 1)),
        "customTransform" -> Json.obj("kind" -> "plugin", "ref" -> "cp:transform", "config" -> Json.obj()),
        "config"          -> Webhook.format.writes(webhook)
      )
    }

    "round trip a minimal exporter" in {
      roundTrip(format, minimal)
    }

    "round trip an exporter where every field is set" in {
      roundTrip(format, full)
    }

    "round trip an elastic exporter" in {
      val elastic = ElasticAnalyticsConfig(
        uris = Seq("http://es1:9200", "http://es2:9200"),
        index = Some("events"),
        `type` = Some("event"),
        user = Some("elastic"),
        password = Some("changeme"),
        headers = Map("X-A" -> "b"),
        indexSettings = IndexSettings(false, 2, 3, "create", IndexSettingsInterval.Week),
        mtlsConfig = MtlsConfig(mtls = true, trustAll = true),
        applyTemplate = false,
        version = Some("7.10.0"),
        maxBulkSize = 10,
        sendWorkers = 1
      )
      roundTrip(format, exporter(DataExporterConfigType.Elastic, elastic)).config mustBe elastic
    }

    "round trip a webhook exporter" in {
      roundTrip(format, exporter(DataExporterConfigType.Webhook, webhook)).config mustBe webhook
    }

    "round trip an http exporter" in {
      val http = HttpCallSettings(
        url = "https://events.oto.tools",
        method = "PUT",
        headers = Map("X-A" -> "b"),
        cookies = Seq(DefaultWSCookie("session", "s3cr3t", Some("oto.tools"), Some("/"), Some(3600L), secure = true, httpOnly = true)),
        body = "${events}",
        timeout = 10.seconds,
        tlsConfig = tls
      )
      roundTrip(format, exporter(DataExporterConfigType.Http, http)).config mustBe http
    }

    "round trip a splunk exporter" in {
      val splunk = SplunkCallSettings(
        url = "https://splunk.oto.tools",
        headers = Map("X-A" -> "b"),
        token = Some("token"),
        sourceType = None,
        index = Some("main"),
        fields = Map("env" -> "prod"),
        timeout = 10.seconds,
        tlsConfig = tls
      )
      roundTrip(format, exporter(DataExporterConfigType.Splunk, splunk)).config mustBe splunk
    }

    "round trip a datadog exporter" in {
      val datadog = DatadogCallSettings(
        url = "https://datadog.oto.tools",
        headers = Map("X-A" -> "b"),
        token = Some("token"),
        ddsource = Some("otoroshi"),
        ddtags = Some("env:prod"),
        service = Some("gateway"),
        hostname = Some("oto-1"),
        timeout = 10.seconds,
        tlsConfig = tls
      )
      roundTrip(format, exporter(DataExporterConfigType.Datadog, datadog)).config mustBe datadog
    }

    "round trip a new relic exporter" in {
      val newrelic = NewRelicCallSettings(
        url = "https://newrelic.oto.tools",
        headers = Map("X-A" -> "b"),
        token = Some("token"),
        logtype = Some("accesslogs"),
        service = Some("gateway"),
        hostname = Some("oto-1"),
        timeout = 10.seconds,
        tlsConfig = tls
      )
      roundTrip(format, exporter(DataExporterConfigType.NewRelic, newrelic)).config mustBe newrelic
    }

    "round trip a workflow exporter" in {
      roundTrip(format, exporter(DataExporterConfigType.Workflow, WorkflowCallSettings("workflow-1"))).config mustBe
        WorkflowCallSettings("workflow-1")
    }

    "round trip a kafka exporter" in {
      val kafka = KafkaConfig(
        servers = Seq("kafka:9092"),
        keyPass = Some("pass"),
        keystore = Some("/ks.jks"),
        truststore = Some("/ts.jks"),
        sendEvents = true,
        hostValidation = false,
        topic = "events",
        mtlsConfig = MtlsConfig(mtls = true),
        securityProtocol = "SASL_PLAINTEXT",
        saslConfig = Some(SaslConfig("user", "password", "SCRAM-SHA-256", None)),
        properties = Some(Map("acks" -> "all"))
      )
      roundTrip(format, exporter(DataExporterConfigType.Kafka, kafka)).config mustBe kafka
    }

    "round trip a pulsar exporter" in {
      val pulsar = PulsarConfig(
        uri = "pulsar://pulsar:6650",
        tlsTrustCertsFilePath = Some("/certs.pem"),
        tenant = "public",
        namespace = "default",
        topic = "events",
        token = Some("token"),
        username = Some("user"),
        password = Some("password"),
        mtlsConfig = MtlsConfig(mtls = true)
      )
      roundTrip(format, exporter(DataExporterConfigType.Pulsar, pulsar)).config mustBe pulsar
    }

    "round trip a file exporter" in {
      val file = FileSettings("/var/log/otoroshi.log", Some(10), 1024L)
      roundTrip(format, exporter(DataExporterConfigType.File, file)).config mustBe file
    }

    "round trip a file exporter without a maximum number of files" in {
      val file = FileSettings("/var/log/otoroshi.log", None)
      roundTrip(format, exporter(DataExporterConfigType.File, file)).config mustBe file
    }

    "round trip an s3 exporter" in {
      val settings = S3ExporterSettings(2048L, Some(3), s3)
      roundTrip(format, exporter(DataExporterConfigType.S3File, settings)).config mustBe settings
    }

    "round trip a goreplay file exporter" in {
      val goreplay = GoReplayFileSettings("/var/log/goreplay.gor", 2048L, false, true, true, true, Seq("GET", "POST"))
      roundTrip(format, exporter(DataExporterConfigType.GoReplayFile, goreplay)).config mustBe goreplay
    }

    "round trip a goreplay s3 exporter with the default maximum file size" in {
      val goreplay = GoReplayS3Settings(s3, 10L * 1024L * 1024L, false, true, true, true, Seq("GET"))
      roundTrip(format, exporter(DataExporterConfigType.GoReplayS3, goreplay)).config mustBe goreplay
    }

    "round trip each kind of mailer exporter" in {
      val to = Seq(EmailLocation("Ops", "ops@oto.tools"))
      Seq(
        NoneMailerSettings(),
        ConsoleMailerSettings(),
        GenericMailerSettings("https://mailer.oto.tools", Map("X-A" -> "b"), to),
        MailgunSettings(eu = true, "mg-key", "mg.oto.tools", to),
        MailjetSettings("public", "private", to),
        SendgridSettings("sg-key", to),
        ScalewayTEMSettings("secret", "project", "nl-ams", to),
        MailPaceSettings("token", to)
      ).foreach { mailer =>
        roundTrip(format, exporter(DataExporterConfigType.Mailer, mailer)).config mustBe mailer
      }
    }

    "round trip a custom exporter" in {
      val custom = ExporterRef("cp:my.exporter", Json.obj("a" -> 1))
      roundTrip(format, exporter(DataExporterConfigType.Custom, custom)).config mustBe custom
    }

    "round trip a metrics exporter" in {
      val metrics = MetricsSettings(Map("route.name" -> "route"))
      roundTrip(format, exporter(DataExporterConfigType.Metrics, metrics)).config mustBe metrics
    }

    "round trip a custom metrics exporter with counters" in {
      val metrics = CustomMetricsSettings(
        tags = Map("env" -> "prod"),
        metrics = Seq(MetricSettings("calls", Some("duration"), MetricSettingsKind.Counter, Some("GatewayEvent"), Map("route.name" -> "route")))
      )
      roundTrip(format, exporter(DataExporterConfigType.CustomMetrics, metrics)).config mustBe metrics
    }

    "round trip a wasm exporter" in {
      val wasm = WasmExporterSettings(Json.obj("a" -> 1), Some("wasm-plugin-1"))
      roundTrip(format, exporter(DataExporterConfigType.Wasm, wasm)).config mustBe wasm
    }

    "round trip an otlp metrics exporter" in {
      val metrics = OtlpMetricsExporterSettings(
        otlp,
        Map("env" -> "prod"),
        Seq(MetricSettings("calls", None, MetricSettingsKind.Counter, Some("GatewayEvent"), Map.empty))
      )
      roundTrip(format, exporter(DataExporterConfigType.OtlpMetrics, metrics)).config mustBe metrics
    }

    "round trip an otlp logs exporter" in {
      val logs = OtlpLogsExporterSettings(otlp)
      roundTrip(format, exporter(DataExporterConfigType.OtlpLogs, logs)).config mustBe logs
    }

    "round trip a tcp exporter" in {
      val tcp = TCPExporterSettings("logs.oto.tools", 6000, unixSocket = false, 2.seconds, tls)
      roundTrip(format, exporter(DataExporterConfigType.TCP, tcp)).config mustBe tcp
    }

    "round trip an udp exporter" in {
      val udp = UDPExporterSettings("logs.oto.tools", 6001, unixSocket = true, 3.seconds)
      roundTrip(format, exporter(DataExporterConfigType.UDP, udp)).config mustBe udp
    }

    "round trip a syslog exporter" in {
      val syslog = SyslogExporterSettings(tcp = true, udp = false, "syslog.oto.tools", 601, unixSocket = false, 4.seconds, tls)
      roundTrip(format, exporter(DataExporterConfigType.Syslog, syslog)).config mustBe syslog
    }

    "round trip a jms exporter" in {
      val jms = JMSExporterSettings("tcp://activemq:61616", "events", topic = false, Some("user"), Some("password"))
      roundTrip(format, exporter(DataExporterConfigType.JMS, jms)).config mustBe jms
    }

    "round trip a postgresql exporter" in {
      val pg = PostgresExporterSettings(Some("postgres://pg/otoroshi"), "pg", 5433, "db", "user", "password", "events", "logs", 2, ssl = true, 7)
      roundTrip(format, exporter(DataExporterConfigType.Postgres, pg)).config mustBe pg
    }

    "round trip a user analytics exporter" in {
      val analytics = UserAnalyticsExporterSettings(
        uri = Some("postgres://pg/analytics"),
        tablePrefix = "my_events",
        retentionDays = 90,
        rollupEnabled = true,
        excludedProjections = Seq("security")
      )
      roundTrip(format, exporter(DataExporterConfigType.UserAnalytics, analytics)).config mustBe analytics
    }

    "read the config according to the exporter type, whatever config was written" in {
      readOk(format, written(format, minimal.copy(config = webhook))).config mustBe ConsoleSettings()
    }

    "round trip a custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read an exporter without _loc in the default location" in {
      readOk(format, written(format, full) - "_loc").location mustBe EntityLocation()
    }

    "throw instead of failing on an exporter without type" in {
      a[JsResultException] must be thrownBy format.reads(written(format, minimal) - "type")
    }

    "throw instead of failing on an empty object or a json null" in {
      a[JsResultException] must be thrownBy format.reads(Json.obj())
      a[JsResultException] must be thrownBy format.reads(JsNull)
    }

    "throw instead of failing on a type that is not a string" in {
      a[JsResultException] must be thrownBy format.reads(written(format, minimal) ++ Json.obj("type" -> 1))
    }

    "not read an exporter without id" in {
      readError(format, written(format, minimal) - "id")
    }

    "not read an exporter without name" in {
      readError(format, written(format, minimal) - "name")
    }

    "not read an exporter whose config is missing" in {
      readError(format, written(format, full) - "config")
    }

    "not read an exporter whose config does not match its type" in {
      readError(format, configOf("elastic", Json.obj("index" -> "events")))
      readError(format, configOf("webhook", Json.obj("headers" -> Json.obj())))
    }

    "not read an unknown exporter type" in {
      readError(format, configOf("graphite", Json.obj()))
    }

    "not read the none exporter type" in {
      DataExporterConfigType.parse("none") mustBe DataExporterConfigType.None
      readError(format, configOf("none", Json.obj()))
    }

    "not read an exporter type that is not lower cased" in {
      DataExporterConfigType.parse("CONSOLE") mustBe DataExporterConfigType.Console
      readError(format, configOf("CONSOLE", Json.obj()))
    }

    "read a console exporter whatever its config" in {
      readOk(format, configOf("console", Json.obj("anything" -> true))).config mustBe ConsoleSettings()
    }

    "read the defaults of every missing optional field" in {
      val back = readOk(format, Json.obj("type" -> "console", "id" -> "e", "name" -> "E"))
      back mustBe DataExporterConfig(
        enabled = false,
        typ = DataExporterConfigType.Console,
        id = "e",
        name = "E",
        desc = "--",
        metadata = Map.empty,
        tags = Seq.empty,
        location = EntityLocation(),
        bufferSize = 5000,
        jsonWorkers = 1,
        sendWorkers = 5,
        groupSize = 100,
        groupDuration = 30.seconds,
        filtering = DataExporterConfigFiltering(),
        projection = Json.obj(),
        customFilter = None,
        customTransform = None,
        config = ConsoleSettings()
      )
    }

    "fall back to the defaults for fields of the wrong type" in {
      val back = readOk(
        format,
        written(format, full) ++ Json.obj(
          "enabled"       -> "true",
          "desc"          -> 42,
          "bufferSize"    -> "100",
          "jsonWorkers"   -> true,
          "sendWorkers"   -> 1.5,
          "groupSize"     -> Json.arr(),
          "groupDuration" -> "5s",
          "projection"    -> Json.arr("@type"),
          "tags"          -> "prod"
        )
      )
      back.enabled mustBe false
      back.desc mustBe "--"
      back.bufferSize mustBe 5000
      back.jsonWorkers mustBe 1
      back.sendWorkers mustBe 5
      back.groupSize mustBe 100
      back.groupDuration mustBe 30.seconds
      back.projection mustBe Json.obj()
      back.tags mustBe empty
    }

    "read a group duration that does not fit an int as 30 seconds" in {
      val json = written(format, minimal.copy(groupDuration = 30.days))
      (json \ "groupDuration").as[Long] mustBe 30L * 24L * 3600L * 1000L
      readOk(format, json).groupDuration mustBe 30.seconds
    }

    "drop the whole include filter when one of its entries is not an object" in {
      val back = readOk(format, written(format, minimal) ++ Json.obj("filtering" -> Json.obj("include" -> Json.arr(Json.obj("a" -> 1), "b"))))
      back.filtering mustBe DataExporterConfigFiltering()
    }

    "read an include filter alone" in {
      val back = readOk(format, written(format, minimal) ++ Json.obj("filtering" -> Json.obj("include" -> Json.arr(Json.obj("a" -> 1)))))
      back.filtering mustBe DataExporterConfigFiltering(include = Seq(Json.obj("a" -> 1)), exclude = Seq.empty)
    }

    "ignore a custom filter or transform with a blank ref" in {
      val back = readOk(
        format,
        written(format, minimal) ++ Json.obj(
          "customFilter"    -> Json.obj("kind" -> "wasm", "ref" -> "  "),
          "customTransform" -> Json.obj("kind" -> "plugin")
        )
      )
      back.customFilter mustBe None
      back.customTransform mustBe None
    }

    "ignore a custom filter that is not an object" in {
      readOk(format, written(format, minimal) ++ Json.obj("customFilter" -> "cp:filter")).customFilter mustBe None
    }

    "read a custom filter kind lower cased" in {
      readOk(format, written(format, minimal) ++ Json.obj("customFilter" -> Json.obj("kind" -> "WASM", "ref" -> "w"))).customFilter mustBe
        Some(CustomDataExporterRefConfig("wasm", "w"))
    }

    "ignore metadata holding a non string value" in {
      readOk(format, written(format, full) ++ Json.obj("metadata" -> Json.obj("a" -> "b", "n" -> 1))).metadata mustBe empty
    }

    "keep the order of the tags and of the filters" in {
      val back = roundTrip(
        format,
        full.copy(tags = Seq("z", "a"), filtering = DataExporterConfigFiltering(Seq(Json.obj("b" -> 1), Json.obj("a" -> 1)), Seq.empty))
      )
      back.tags mustBe Seq("z", "a")
      back.filtering.include mustBe Seq(Json.obj("b" -> 1), Json.obj("a" -> 1))
    }

    "not read a file exporter without path or maximum file size" in {
      readError(format, configOf("file", Json.obj("maxFileSize" -> 1024)))
      readError(format, configOf("file", Json.obj("path" -> "/tmp/events.log")))
    }

    "read a maximum number of files that is not positive as none" in {
      readOk(format, configOf("file", Json.obj("path" -> "/f", "maxFileSize" -> 1, "maxNumberOfFile" -> 0))).config mustBe
        FileSettings("/f", None, 1L)
    }

    "read a goreplay file exporter with its defaults" in {
      readOk(format, configOf("goreplayfile", Json.obj("path" -> "/f.gor"))).config mustBe
        GoReplayFileSettings("/f.gor", 10L * 1024L * 1024L, true, false, false, false, Seq.empty)
    }

    "not read a goreplay file exporter without path" in {
      readError(format, configOf("goreplayfile", Json.obj()))
    }

    "not read a goreplay s3 exporter without s3 config" in {
      readError(format, configOf("goreplays3", Json.obj("methods" -> Json.arr("GET"))))
    }

    "not read a metrics exporter without labels" in {
      readError(format, configOf("metrics", Json.obj()))
    }

    "not read a custom exporter without ref or config" in {
      readError(format, configOf("custom", Json.obj("config" -> Json.obj())))
      readError(format, configOf("custom", Json.obj("ref" -> "cp:exporter")))
    }

    "read a mailer exporter of an unknown kind as a console mailer" in {
      readOk(format, configOf("mailer", Json.obj("type" -> "smtp"))).config mustBe ConsoleMailerSettings()
    }

    "read a mailer exporter without kind as no mailer" in {
      readOk(format, configOf("mailer", Json.obj())).config mustBe NoneMailerSettings()
    }

    "not read an otlp exporter without otlp settings" in {
      readError(format, configOf("otlp-logs", Json.obj()))
      readError(format, configOf("otlp-metrics", Json.obj("tags" -> Json.obj())))
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "exporter-2", "Exporter 2", "the webhook exporter", Seq("prod", "eu"), Map("owner" -> "ops"))
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "be built from its json" in {
      DataExporterConfig.fromJsons(full.json) mustBe full
    }

    "fail to be built from a json without id" in {
      a[Throwable] must be thrownBy DataExporterConfig.fromJsons(written(format, full) - "id")
    }
  }

  "DataExporterConfigType" should {

    val all = Seq(
      DataExporterConfigType.Kafka,
      DataExporterConfigType.Pulsar,
      DataExporterConfigType.Elastic,
      DataExporterConfigType.Webhook,
      DataExporterConfigType.Http,
      DataExporterConfigType.Splunk,
      DataExporterConfigType.Datadog,
      DataExporterConfigType.NewRelic,
      DataExporterConfigType.Workflow,
      DataExporterConfigType.File,
      DataExporterConfigType.GoReplayFile,
      DataExporterConfigType.GoReplayS3,
      DataExporterConfigType.S3File,
      DataExporterConfigType.Mailer,
      DataExporterConfigType.Custom,
      DataExporterConfigType.Console,
      DataExporterConfigType.Metrics,
      DataExporterConfigType.CustomMetrics,
      DataExporterConfigType.Wasm,
      DataExporterConfigType.OtlpMetrics,
      DataExporterConfigType.OtlpLogs,
      DataExporterConfigType.None,
      DataExporterConfigType.TCP,
      DataExporterConfigType.UDP,
      DataExporterConfigType.Syslog,
      DataExporterConfigType.JMS,
      DataExporterConfigType.Postgres,
      DataExporterConfigType.UserAnalytics
    )

    "parse back the name of every type" in {
      all.foreach { typ =>
        DataExporterConfigType.parse(typ.name) mustBe typ
      }
    }

    "have distinct names" in {
      all.map(_.name).distinct.size mustBe all.size
    }

    "name the types as the exporter json does" in {
      all.map(_.name) mustBe Seq(
        "kafka",
        "pulsar",
        "elastic",
        "webhook",
        "http",
        "splunk",
        "datadog",
        "newrelic",
        "workflow",
        "file",
        "goreplayfile",
        "goreplays3",
        "s3",
        "mailer",
        "custom",
        "console",
        "metrics",
        "custommetrics",
        "wasm",
        "otlp-metrics",
        "otlp-logs",
        "none",
        "tcp",
        "udp",
        "syslog",
        "jms",
        "postgresql",
        "user-analytics"
      )
    }

    "parse a type whatever its case" in {
      DataExporterConfigType.parse("Kafka") mustBe DataExporterConfigType.Kafka
      DataExporterConfigType.parse("OTLP-LOGS") mustBe DataExporterConfigType.OtlpLogs
    }

    "parse an unknown type as none" in {
      DataExporterConfigType.parse("graphite") mustBe DataExporterConfigType.None
      DataExporterConfigType.parse("") mustBe DataExporterConfigType.None
    }
  }

  "CustomDataExporterRefConfig" should {

    val refFormat = CustomDataExporterRefConfig.format

    "write its fields" in {
      refFormat.writes(CustomDataExporterRefConfig("wasm", "w", Json.obj("a" -> 1))) mustBe Json.obj(
        "kind"   -> "wasm",
        "ref"    -> "w",
        "config" -> Json.obj("a" -> 1)
      )
    }

    "expose through json what the format writes" in {
      val ref = CustomDataExporterRefConfig("plugin", "cp:p")
      ref.json mustBe refFormat.writes(ref)
    }

    "round trip a ref" in {
      roundTrip(refFormat, CustomDataExporterRefConfig("wasm", "w", Json.obj("a" -> 1)))
    }

    "read an empty object as an empty plugin ref" in {
      readOk(refFormat, Json.obj()) mustBe CustomDataExporterRefConfig("plugin", "", Json.obj())
    }

    "read the kind lower cased" in {
      readOk(refFormat, Json.obj("kind" -> "Plugin", "ref" -> "p")).kind mustBe "plugin"
    }

    "read a config that is not an object as an empty one" in {
      readOk(refFormat, Json.obj("ref" -> "p", "config" -> Json.arr(1))).config mustBe Json.obj()
    }
  }

  "the exporter configs" should {

    "write a file config with a null maximum number of files" in {
      FileSettings("/f", None, 1L).toJson mustBe Json.obj("path" -> "/f", "maxFileSize" -> 1, "maxNumberOfFile" -> JsNull)
    }

    "write a goreplay file config" in {
      GoReplayFileSettings("/f.gor", 1L, true, false, true, false, Seq("GET")).toJson mustBe Json.obj(
        "path"                  -> "/f.gor",
        "maxFileSize"           -> 1,
        "captureRequests"       -> true,
        "captureResponses"      -> false,
        "preferBackendRequest"  -> true,
        "preferBackendResponse" -> false,
        "methods"               -> Json.arr("GET")
      )
    }

    "write a custom exporter ref" in {
      ExporterRef("cp:e", Json.obj("a" -> 1)).toJson mustBe Json.obj("ref" -> "cp:e", "config" -> Json.obj("a" -> 1))
    }

    "write the console config as an empty object" in {
      ConsoleSettings().toJson mustBe Json.obj()
    }

    "write the metrics labels" in {
      MetricsSettings(Map("a" -> "b")).toJson mustBe Json.obj("labels" -> Json.obj("a" -> "b"))
      MetricsSettings().toJson mustBe Json.obj("labels" -> Json.obj())
    }

    "write no exporter as null" in {
      NoneExporter.toJson mustBe JsNull
    }
  }
}
