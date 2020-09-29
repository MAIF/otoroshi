package storage.stores

import env.Env
import events.{KafkaConfig, PulsarConfig}
import models.DataExporterConfig.{ConsoleExporterConfig, ElasticExporterConfig, GenericMailerExporterConfig, KafkaExporterConfig, MailgunExporterConfig, MailjetExporterConfig, NoneMailerExporterConfig, PulsarExporterConfig, WebhookExporterConfig}
import models.{DataExporterConfig, ElasticAnalyticsConfig, Key, Webhook}
import otoroshi.models.EntityLocation
import otoroshi.storage.{RedisLike, RedisLikeStore}
import play.api.libs.json.Format
import security.IdGenerator
import utils.{ConsoleMailerSettings, GenericMailerSettings, MailgunSettings, MailjetSettings, NoneMailerSettings}
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
          eventsFilters = Seq.empty,
          eventsFiltersNot = Seq.empty,
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
          eventsFilters = Seq.empty,
          eventsFiltersNot = Seq.empty,
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
          eventsFilters = Seq.empty,
          eventsFiltersNot = Seq.empty,
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
          eventsFilters = Seq.empty,
          eventsFiltersNot = Seq.empty,
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
          eventsFilters = Seq.empty,
          eventsFiltersNot = Seq.empty,
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
          eventsFilters = Seq.empty,
          eventsFiltersNot = Seq.empty,
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
          eventsFilters = Seq.empty,
          eventsFiltersNot = Seq.empty,
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
          eventsFilters = Seq.empty,
          eventsFiltersNot = Seq.empty,
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
          eventsFilters = Seq.empty,
          eventsFiltersNot = Seq.empty,
          config = NoneMailerSettings()
        )
      case _ => ConsoleExporterConfig(
        id = IdGenerator.token,
        name = "New console exporter config",
        desc = "New console exporter config",
        metadata = Map.empty,
        enabled = false,
        location = EntityLocation(),
        eventsFilters = Seq.empty,
        eventsFiltersNot = Seq.empty,
        config = ConsoleMailerSettings()
      )
    }
  }
}