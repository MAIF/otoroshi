package otoroshi.storage.stores

import otoroshi.actions.ApiActionContext
import otoroshi.env.Env
import otoroshi.events.Exporters.{
  CustomMetricsSettings,
  MetricSettings,
  MetricSettingsKind,
  OtlpLogsExporterSettings,
  OtlpMetricsExporterSettings,
  WasmExporterSettings
}
import otoroshi.events.{KafkaConfig, PulsarConfig}
import otoroshi.metrics.opentelemetry.OtlpSettings
import otoroshi.models._
import otoroshi.next.models.NgTlsConfig
import otoroshi.security.IdGenerator
import otoroshi.storage.{RedisLike, RedisLikeStore}
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.mailer._
import otoroshi.utils.syntax.implicits.BetterJsReadable
import play.api.libs.json.{Format, JsError, JsSuccess, Json}

import scala.concurrent.duration.{DurationInt, DurationLong}

class DataExporterConfigDataStore(redisCli: RedisLike, env: Env) extends RedisLikeStore[DataExporterConfig] {
  override def fmt: Format[DataExporterConfig] = DataExporterConfig.format

  override def redisLike(implicit env: Env): RedisLike = redisCli

  override def key(id: String): String = s"${env.storageRoot}:data-exporters:$id"

  override def extractId(value: DataExporterConfig): String = value.id

  def template(modType: Option[String], ctx: Option[ApiActionContext[_]] = None): DataExporterConfig = {
    val defaultTemplate = modType match {
      case Some("webhook")        =>
        DataExporterConfig(
          typ = DataExporterConfigType.Webhook,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New webhook exporter config",
          desc = "New webhook exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = Webhook(
            url = "http://localhost:8080",
            headers = Map.empty[String, String],
            mtlsConfig = MtlsConfig.default
          )
        )
      case Some("elastic")        =>
        DataExporterConfig(
          typ = DataExporterConfigType.Elastic,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New elastic exporter config",
          desc = "New elastic exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = ElasticAnalyticsConfig(
            uris = Seq("http://localhost:9200")
          )
        )
      case Some("pulsar")         =>
        DataExporterConfig(
          typ = DataExporterConfigType.Pulsar,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New pulsar exporter config",
          desc = "New pulsar exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = PulsarConfig(
            uri = "http://localhost:6650",
            tlsTrustCertsFilePath = None,
            tenant = "public",
            namespace = "default",
            topic = "otoroshi",
            token = None,
            username = None,
            password = None
          )
        )
      case Some("kafka")          =>
        DataExporterConfig(
          typ = DataExporterConfigType.Kafka,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New kafka exporter config",
          desc = "New kafka exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = KafkaConfig(
            servers = Seq("http://localhost:9092")
          )
        )
      case Some("mailer")         =>
        DataExporterConfig(
          typ = DataExporterConfigType.Mailer,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New mailer exporter config",
          desc = "New mailer exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          projection = Json.obj(),
          groupSize = 25,
          groupDuration = 60.seconds,
          filtering = DataExporterConfigFiltering(),
          config = ConsoleMailerSettings()
        )
      case Some("generic-mailer") =>
        DataExporterConfig(
          typ = DataExporterConfigType.Mailer,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New generic mailer exporter config",
          desc = "New generic mailer exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          groupSize = 25,
          groupDuration = 60.seconds,
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = GenericMailerSettings(
            url = "http://localhost:8080",
            headers = Map.empty,
            to = Seq.empty
          )
        )
      case Some("mailgun")        =>
        DataExporterConfig(
          typ = DataExporterConfigType.Mailer,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New mailgun exporter config",
          desc = "New mailgun exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          groupSize = 25,
          groupDuration = 60.seconds,
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = MailgunSettings(
            eu = true,
            apiKey = "key",
            domain = "domain",
            to = Seq.empty
          )
        )
      case Some("mailjet")        =>
        DataExporterConfig(
          typ = DataExporterConfigType.Mailer,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New mailjet exporter config",
          desc = "New mailjet exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          groupSize = 25,
          groupDuration = 60.seconds,
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = MailjetSettings(
            apiKeyPublic = "key-public",
            apiKeyPrivate = "key-private",
            to = Seq.empty
          )
        )
      case Some("none-mailer")    =>
        DataExporterConfig(
          typ = DataExporterConfigType.Mailer,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New none mailer exporter config",
          desc = "New none mailer exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = NoneMailerSettings()
        )
      case Some("sendgrid")       =>
        DataExporterConfig(
          typ = DataExporterConfigType.Mailer,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New sendgrid mailer exporter config",
          desc = "New sendgrid mailer exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          groupSize = 25,
          groupDuration = 60.seconds,
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = SendgridSettings(
            apiKey = "apikey",
            to = Seq.empty
          )
        )
      case Some("file")           =>
        DataExporterConfig(
          typ = DataExporterConfigType.File,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New file exporter config",
          desc = "New file exporter config",
          metadata = Map.empty,
          enabled = false,
          sendWorkers = 1,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = FileSettings(path = "/tmp/otoroshi-events.log", None)
        )
      case Some("metrics")        =>
        DataExporterConfig(
          typ = DataExporterConfigType.Metrics,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New metrics exporter config",
          desc = "New metrics exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = MetricsSettings()
        )
      case Some("custommetrics")  =>
        DataExporterConfig(
          typ = DataExporterConfigType.CustomMetrics,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New custom metrics exporter config",
          desc = "New custom metrics exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = CustomMetricsSettings()
        )
      case Some("custom")         =>
        DataExporterConfig(
          typ = DataExporterConfigType.Custom,
          id = IdGenerator.namedId("data-exporter", env),
          name = "New custom exporter config",
          desc = "New custom exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = ExporterRef("", Json.obj())
        )
      case Some("wasm")           =>
        DataExporterConfig(
          typ = DataExporterConfigType.Wasm,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New wasm exporter config",
          desc = "New wasm exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = WasmExporterSettings(Json.obj(), None)
        )
      case Some("otlp-logs")      =>
        DataExporterConfig(
          typ = DataExporterConfigType.OtlpLogs,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New OTLP logs exporter config",
          desc = "New OTLP logs exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = OtlpLogsExporterSettings(OtlpSettings.defaultLogs)
        )
      case Some("otlp-metrics")   =>
        DataExporterConfig(
          typ = DataExporterConfigType.OtlpMetrics,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New OTLP metrics exporter config",
          desc = "New OTLP metrics exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = OtlpMetricsExporterSettings(
            otlp = OtlpSettings.defaultMetrics,
            tags = Map.empty,
            metrics = Seq(
              MetricSettings(
                id = "calls_duration",
                selector = Some("duration"),
                kind = MetricSettingsKind.Counter,
                eventType = Some("GatewayEvent"),
                labels = Map.empty
              )
            )
          )
        )
      case Some("splunk")         =>
        DataExporterConfig(
          typ = DataExporterConfigType.Splunk,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New Splunk exporter config",
          desc = "New Splunk metrics exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = SplunkCallSettings(
            url = "",
            headers = Map.empty,
            token = Some("secret"),
            sourceType = None,
            index = None,
            fields = Map.empty,
            timeout = 30000.millis,
            tlsConfig = NgTlsConfig()
          )
        )
      case Some("http")           =>
        DataExporterConfig(
          typ = DataExporterConfigType.Http,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New Http exporter config",
          desc = "New Http exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = HttpCallSettings(
            url = "http://localhost:3465/logs",
            method = "POST",
            headers = Map.empty,
            cookies = Seq.empty,
            body = "${events.nd_stringify}",
            timeout = 3000.millis,
            tlsConfig = NgTlsConfig()
          )
        )
      case Some("workflow")       =>
        DataExporterConfig(
          typ = DataExporterConfigType.Workflow,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New Workflow exporter config",
          desc = "New Workflow exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = WorkflowCallSettings(
            ref = ""
          )
        )
      case Some("datadog")        =>
        DataExporterConfig(
          typ = DataExporterConfigType.Datadog,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New Datadog exporter config",
          desc = "New Datadog exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = DatadogCallSettings(
            url = "https://http-intake.logs.datadoghq.eu/api/v2/logs",
            headers = Map.empty,
            token = Some("secret"),
            ddsource = None,
            ddtags = None,
            service = None,
            hostname = None,
            timeout = 30000.millis,
            tlsConfig = NgTlsConfig()
          )
        )
      case Some("newrelic")       =>
        DataExporterConfig(
          typ = DataExporterConfigType.NewRelic,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New New Relic exporter config",
          desc = "New New Relic exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = NewRelicCallSettings(
            url = "https://log-api.eu.newrelic.com/log/v1",
            headers = Map.empty,
            token = Some("secret"),
            logtype = None,
            service = None,
            hostname = None,
            timeout = 30000.millis,
            tlsConfig = NgTlsConfig()
          )
        )
      case _                      =>
        DataExporterConfig(
          typ = DataExporterConfigType.Mailer,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New console exporter config",
          desc = "New console exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation.ownEntityLocation(ctx)(env),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = ConsoleMailerSettings()
        )
    }
    env.datastores.globalConfigDataStore
      .latest()(env.otoroshiExecutionContext, env)
      .templates
      .dataExporter
      .map { template =>
        DataExporterConfig.format.reads(defaultTemplate.json.asObject.deepMerge(template)).get
      }
      .getOrElse {
        defaultTemplate
      }
  }
}
