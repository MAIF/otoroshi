package otoroshi.storage.stores

import otoroshi.env.Env
import otoroshi.events.Exporters.{CustomMetricsSettings, MetricSettings, MetricSettingsKind, OtlpLogsExporterSettings, OtlpMetricsExporterSettings, OtlpSettings, WasmExporterSettings}
import otoroshi.events.{KafkaConfig, PulsarConfig}
import otoroshi.models._
import otoroshi.security.IdGenerator
import otoroshi.storage.{RedisLike, RedisLikeStore}
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.mailer._
import otoroshi.utils.syntax.implicits.BetterJsReadable
import play.api.libs.json.{Format, Json}

import scala.concurrent.duration.{DurationInt, DurationLong}

class DataExporterConfigDataStore(redisCli: RedisLike, env: Env) extends RedisLikeStore[DataExporterConfig] {
  override def fmt: Format[DataExporterConfig] = DataExporterConfig.format

  override def redisLike(implicit env: Env): RedisLike = redisCli

  override def key(id: String): String = s"${env.storageRoot}:data-exporters:$id"

  override def extractId(value: DataExporterConfig): String = value.id

  def template(modType: Option[String]): DataExporterConfig = {
    val defaultTemplate = modType match {
      case Some("webhook")        =>
        DataExporterConfig(
          typ = DataExporterConfigType.Webhook,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New webhook exporter config",
          desc = "New webhook exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation(),
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
          location = EntityLocation(),
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
          location = EntityLocation(),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = PulsarConfig(
            uri = "http://localhost:6650",
            tlsTrustCertsFilePath = None,
            tenant = "public",
            namespace = "default",
            topic = "otoroshi"
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
          location = EntityLocation(),
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
          location = EntityLocation(),
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
          location = EntityLocation(),
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
          location = EntityLocation(),
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
          location = EntityLocation(),
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
          location = EntityLocation(),
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
          location = EntityLocation(),
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
          location = EntityLocation(),
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
          location = EntityLocation(),
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
          location = EntityLocation(),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = CustomMetricsSettings()
        )
      case Some("wasm") =>
        DataExporterConfig(
          typ = DataExporterConfigType.Wasm,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New wasm exporter config",
          desc = "New wasm exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation(),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = WasmExporterSettings(Json.obj(), None)
        )
      case Some("otlp-logs") =>
        DataExporterConfig(
          typ = DataExporterConfigType.OtlpLogs,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New OTLP logs exporter config",
          desc = "New OTLP logs exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation(),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = OtlpLogsExporterSettings(OtlpSettings.defaultLogs)
        )
      case Some("otlp-metrics") =>
        DataExporterConfig(
          typ = DataExporterConfigType.OtlpMetrics,
          id = IdGenerator.namedId("data_exporter", env),
          name = "New OTLP metrics exporter config",
          desc = "New OTLP metrics exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation(),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = OtlpMetricsExporterSettings(
            otlp = OtlpSettings.defaultMetrics,
            tags = Map.empty,
            metrics = Seq(MetricSettings(
              id = "calls_duration",
              selector = Some("duration"),
              kind = MetricSettingsKind.Counter,
              eventType = Some("GatewayEvent"),
              labels = Map.empty,
            ))
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
          location = EntityLocation(),
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
