package storage.stores

import env.Env
import events.{KafkaConfig, PulsarConfig}
import models.DataExporterConfig
import models._
import otoroshi.models.EntityLocation
import otoroshi.storage.{RedisLike, RedisLikeStore}
import play.api.libs.json.{Format, Json}
import security.IdGenerator
import utils.{ConsoleMailerSettings, GenericMailerSettings, MailgunSettings, MailjetSettings, NoneMailerSettings, SendgridSettings}
import utils.http.MtlsConfig

import scala.concurrent.duration.DurationInt

class DataExporterConfigDataStore(redisCli: RedisLike, env: Env) extends RedisLikeStore[DataExporterConfig] {
  override def fmt: Format[DataExporterConfig] = DataExporterConfig.format

  override def redisLike(implicit env: Env): RedisLike = redisCli

  override def key(id: String): Key = Key(s"${env.storageRoot}:data-exporters:$id")

  override def extractId(value: DataExporterConfig): String = value.id

  def template(modType: Option[String]): DataExporterConfig = {
    modType match {
      case Some("webhook") =>
        DataExporterConfig(
          typ = DataExporterConfigType.Webhook,
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
            mtlsConfig = MtlsConfig.default
          )
        )
      case Some("elastic") =>
        DataExporterConfig(
          typ = DataExporterConfigType.Elastic,
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
        DataExporterConfig(
          typ = DataExporterConfigType.Pulsar,
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
        DataExporterConfig(
          typ = DataExporterConfigType.Kafka,
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
        DataExporterConfig(
          typ = DataExporterConfigType.Mailer,
          id = IdGenerator.token,
          name = "New console exporter config",
          desc = "New console exporter config",
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
          id = IdGenerator.token,
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
      case Some("mailgun") =>
        DataExporterConfig(
          typ = DataExporterConfigType.Mailer,
          id = IdGenerator.token,
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
      case Some("mailjet") =>
        DataExporterConfig(
          typ = DataExporterConfigType.Mailer,
          id = IdGenerator.token,
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
      case Some("none-mailer") =>
        DataExporterConfig(
          typ = DataExporterConfigType.Mailer,
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
        DataExporterConfig(
          typ = DataExporterConfigType.Mailer,
          id = IdGenerator.token,
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
      case Some("file") =>
        DataExporterConfig(
          typ = DataExporterConfigType.File,
          id = IdGenerator.token,
          name = "New file exporter config",
          desc = "New file exporter config",
          metadata = Map.empty,
          enabled = false,
          sendWorkers = 1,
          location = EntityLocation(),
          projection = Json.obj(),
          filtering = DataExporterConfigFiltering(),
          config = FileSettings(path = "/tmp/otoroshi-events.log")
        )
      case _ => DataExporterConfig(
        typ = DataExporterConfigType.Mailer,
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