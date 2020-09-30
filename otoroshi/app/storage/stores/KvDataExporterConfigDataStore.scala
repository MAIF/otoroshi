package storage.stores

import env.Env
import events.{KafkaConfig, PulsarConfig}
import models.DataExporterConfig.{ConsoleExporterConfig, ElasticExporterConfig, FileAppenderExporterConfig, FileExporter, GenericMailerExporterConfig, KafkaExporterConfig, MailgunExporterConfig, MailjetExporterConfig, NoneMailerExporterConfig, PulsarExporterConfig, SendgridExporterConfig, WebhookExporterConfig}
import models.{DataExporterConfig, DataExporterConfigFiltering, ElasticAnalyticsConfig, Key, Webhook}
import otoroshi.models.EntityLocation
import otoroshi.storage.{RedisLike, RedisLikeStore}
import play.api.libs.json.{Format, Json}
import security.IdGenerator
import utils.{ConsoleMailerSettings, GenericMailerSettings, MailgunSettings, MailjetSettings, NoneMailerSettings, SendgridSettings}
import utils.http.MtlsConfig

class DataExporterConfigDataStore(redisCli: RedisLike, env: Env) extends RedisLikeStore[DataExporterConfig] {
  override def fmt: Format[DataExporterConfig] = DataExporterConfig.format

  override def redisLike(implicit env: Env): RedisLike = redisCli

  override def key(id: String): Key = Key(s"${env.storageRoot}:data-exporters:$id")

  override def extractId(value: DataExporterConfig): String = value.id

  def template(modType: Option[String]): DataExporterConfig = {
    modType match {
      case Some("webhook") =>
        WebhookExporterConfig(
          id = IdGenerator.token,
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
            mtlsConfig = MtlsConfig.default)
        )
      case Some("elastic") =>
        ElasticExporterConfig(
          id = IdGenerator.token,
          name = "New elastic exporter config",
          desc = "New elastic exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation(),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = ElasticAnalyticsConfig(
            clusterUri = "http://localhost:9200"
          )
        )
      case Some("pulsar") =>
        PulsarExporterConfig(
          id = IdGenerator.token,
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
      case Some("kafka") =>
        KafkaExporterConfig(
          id = IdGenerator.token,
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
      case Some("console") =>
        ConsoleExporterConfig(
          id = IdGenerator.token,
          name = "New console exporter config",
          desc = "New console exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation(),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = ConsoleMailerSettings()
        )
      case Some("generic-mailer") =>
        GenericMailerExporterConfig(
          id = IdGenerator.token,
          name = "New generic mailer exporter config",
          desc = "New generic mailer exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation(),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = GenericMailerSettings(
            url = "http://localhost:8080",
            headers = Map.empty
          )
        )
      case Some("mailgun") =>
        MailgunExporterConfig(
          id = IdGenerator.token,
          name = "New mailgun exporter config",
          desc = "New mailgun exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation(),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = MailgunSettings(
            eu = true,
            apiKey = "key",
            domain = "domain"
          )
        )
      case Some("mailjet") =>
        MailjetExporterConfig(
          id = IdGenerator.token,
          name = "New mailjet exporter config",
          desc = "New mailjet exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation(),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = MailjetSettings(
            apiKeyPublic = "key-public",
            apiKeyPrivate = "key-private"
          )
        )
      case Some("none-mailer") =>
        NoneMailerExporterConfig(
          id = IdGenerator.token,
          name = "New none mailer exporter config",
          desc = "New none mailer exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation(),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = NoneMailerSettings()
        )
      case Some("sendgrid") =>
        SendgridExporterConfig(
          id = IdGenerator.token,
          name = "New sendgrid mailer exporter config",
          desc = "New sendgrid mailer exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation(),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = SendgridSettings("apikey")
        )
      case Some("file") =>
        FileAppenderExporterConfig(
          id = IdGenerator.token,
          name = "New file exporter config",
          desc = "New file exporter config",
          metadata = Map.empty,
          enabled = false,
          location = EntityLocation(),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = FileExporter(path = "/tmp/otoroshi-events.log")
        )
      case _ => ConsoleExporterConfig(
        id = IdGenerator.token,
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
  }
}