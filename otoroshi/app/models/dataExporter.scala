package models

import env.Env
import events.Exporters._
import events.{DataExporter, KafkaConfig, PulsarConfig}
import otoroshi.models.{EntityLocation, EntityLocationSupport}
import play.api.libs.json._
import utils._

import scala.concurrent.ExecutionContext


trait Exporter {
  def toJson: JsValue
}

trait DataExporterConfig extends EntityLocationSupport  {
  val enabled: Boolean
  val id: String
  val name: String
  val desc: String
  val metadata: Map[String, String]
  val location: EntityLocation
  val eventsFilters: Seq[String]
  val eventsFiltersNot: Seq[String]
  val config: Exporter
  def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter

  override def internalId: String = id
  override def json: JsValue = DataExporterConfig.format.writes(this)
}

case object DataExporterConfig {

  case class ElasticExporterConfig(
                                    enabled: Boolean,
                                    id: String,
                                    name: String,
                                    desc: String,
                                    metadata: Map[String, String],
                                    location: EntityLocation = EntityLocation(),
                                    eventsFilters: Seq[String],
                                    eventsFiltersNot: Seq[String],
                                    config: ElasticAnalyticsConfig) extends DataExporterConfig {
    def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter = new ElasticExporter(this)(ec, env)
  }
  case class WebhookExporterConfig(
                                    enabled: Boolean,
                                    id: String,
                                    name: String,
                                    desc: String,
                                    metadata: Map[String, String],
                                    location: EntityLocation = EntityLocation(),
                                    eventsFilters: Seq[String],
                                    eventsFiltersNot: Seq[String],
                                    config: Webhook) extends DataExporterConfig {
    def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter = new WebhookExporter(this)(ec, env)
  }
  case class KafkaExporterConfig(
                                  enabled: Boolean,
                                  id: String,
                                  name: String,
                                  desc: String,
                                  metadata: Map[String, String],
                                  location: EntityLocation = EntityLocation(),
                                  eventsFilters: Seq[String],
                                  eventsFiltersNot: Seq[String],
                                  config: KafkaConfig) extends DataExporterConfig {
    def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter = new KafkaExporter(this)(ec, env)
  }
  case class PulsarExporterConfig(
                                   enabled: Boolean,
                                   id: String,
                                   name: String,
                                   desc: String,
                                   metadata: Map[String, String],
                                   location: EntityLocation = EntityLocation(),
                                   eventsFilters: Seq[String],
                                   eventsFiltersNot: Seq[String],
                                   config: PulsarConfig) extends DataExporterConfig {
    def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter = new PulsarExporter(this)(ec, env)
  }
  case class ConsoleExporterConfig(
                                    enabled: Boolean,
                                    id: String,
                                    name: String,
                                    desc: String,
                                    metadata: Map[String, String],
                                    location: EntityLocation = EntityLocation(),
                                    eventsFilters: Seq[String],
                                    eventsFiltersNot: Seq[String],
                                    config: ConsoleMailerSettings) extends DataExporterConfig {
    def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter = new ConsoleExporter(this)(ec, env)
  }
  case class GenericMailerExporterConfig(
                                          enabled: Boolean,
                                          id: String,
                                          name: String,
                                          desc: String,
                                          metadata: Map[String, String],
                                          location: EntityLocation = EntityLocation(),
                                          eventsFilters: Seq[String],
                                          eventsFiltersNot: Seq[String],
                                          config: GenericMailerSettings) extends DataExporterConfig {
    def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter = new GenericMailerExporter(this)(ec, env)
  }
  case class MailgunExporterConfig(
                                    enabled: Boolean,
                                    id: String,
                                    name: String,
                                    desc: String,
                                    metadata: Map[String, String],
                                    location: EntityLocation = EntityLocation(),
                                    eventsFilters: Seq[String],
                                    eventsFiltersNot: Seq[String],
                                    config: MailgunSettings ) extends DataExporterConfig {
    def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter = new GenericMailerExporter(this)(ec, env)
  }
  case class MailjetExporterConfig(
                                    enabled: Boolean,
                                    id: String,
                                    name: String,
                                    desc: String,
                                    metadata: Map[String, String],
                                    location: EntityLocation = EntityLocation(),
                                    eventsFilters: Seq[String],
                                    eventsFiltersNot: Seq[String],
                                    config: MailjetSettings) extends DataExporterConfig {
    def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter = new GenericMailerExporter(this)(ec, env)
  }
  case class NoneMailerExporterConfig(
                                       enabled: Boolean,
                                       id: String,
                                       name: String,
                                       desc: String,
                                       metadata: Map[String, String],
                                       location: EntityLocation = EntityLocation(),
                                       eventsFilters: Seq[String],
                                       eventsFiltersNot: Seq[String],
                                       config: NoneMailerSettings) extends DataExporterConfig {
    def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter = new GenericMailerExporter(this)(ec, env)
  }

  val format: Format[DataExporterConfig] = new Format[DataExporterConfig] {
    override def reads(json: JsValue): JsResult[DataExporterConfig] = (json \ "type").as[String] match {
      case "elastic" => ElasticAnalyticsConfig.format.reads((json \ "config").as[JsObject])
        .map(config => ElasticExporterConfig(
          location = EntityLocation.readFromKey(json),
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          id = (json \ "id").as[String],
          name = (json \ "id").as[String],
          desc = (json \ "desc").asOpt[String].getOrElse("--"),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          eventsFilters = (json \ "eventsFilters").as[Seq[String]],
          eventsFiltersNot = (json \ "eventsFiltersNot").as[Seq[String]],
          config = config
        ))
      case "webhook" => Webhook.format.reads((json \ "config").as[JsObject])
        .map(config => WebhookExporterConfig(
          location = EntityLocation.readFromKey(json),
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          id = (json \ "id").as[String],
          name = (json \ "id").as[String],
          desc = (json \ "desc").asOpt[String].getOrElse("--"),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          eventsFilters = (json \ "eventsFilters").as[Seq[String]],
          eventsFiltersNot = (json \ "eventsFiltersNot").as[Seq[String]],
          config = config
        ))
      case "kafka" => KafkaConfig.format.reads((json \ "config").as[JsObject])
        .map(config => KafkaExporterConfig(
          location = EntityLocation.readFromKey(json),
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          id = (json \ "id").as[String],
          name = (json \ "id").as[String],
          desc = (json \ "desc").asOpt[String].getOrElse("--"),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          eventsFilters = (json \ "eventsFilters").as[Seq[String]],
          eventsFiltersNot = (json \ "eventsFiltersNot").as[Seq[String]],
          config = config
        ))
      case "pulsar" => PulsarConfig.format.reads((json \ "config").as[JsObject])
        .map(config => PulsarExporterConfig(
          location = EntityLocation.readFromKey(json),
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          id = (json \ "id").as[String],
          name = (json \ "id").as[String],
          desc = (json \ "desc").asOpt[String].getOrElse("--"),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          eventsFilters = (json \ "eventsFilters").as[Seq[String]],
          eventsFiltersNot = (json \ "eventsFiltersNot").as[Seq[String]],
          config = config
        ))
      case "mailer" => MailerSettings.format.reads((json \ "config").as[JsObject])
        .map {
          case config: ConsoleMailerSettings => ConsoleExporterConfig(
            location = EntityLocation.readFromKey(json),
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            id = (json \ "id").as[String],
            name = (json \ "id").as[String],
            desc = (json \ "desc").asOpt[String].getOrElse("--"),
            metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
            eventsFilters = (json \ "eventsFilters").as[Seq[String]],
            eventsFiltersNot = (json \ "eventsFiltersNot").as[Seq[String]],
            config = config
          )
          case config: GenericMailerSettings => GenericMailerExporterConfig(
            location = EntityLocation.readFromKey(json),
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            id = (json \ "id").as[String],
            name = (json \ "id").as[String],
            desc = (json \ "desc").asOpt[String].getOrElse("--"),
            metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
            eventsFilters = (json \ "eventsFilters").as[Seq[String]],
            eventsFiltersNot = (json \ "eventsFiltersNot").as[Seq[String]],
            config = config
          )
          case config: MailgunSettings => MailgunExporterConfig(
            location = EntityLocation.readFromKey(json),
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            id = (json \ "id").as[String],
            name = (json \ "id").as[String],
            desc = (json \ "desc").asOpt[String].getOrElse("--"),
            metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
            eventsFilters = (json \ "eventsFilters").as[Seq[String]],
            eventsFiltersNot = (json \ "eventsFiltersNot").as[Seq[String]],
            config = config
          )
          case config: MailjetSettings => MailjetExporterConfig(
            location = EntityLocation.readFromKey(json),
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            id = (json \ "id").as[String],
            name = (json \ "id").as[String],
            desc = (json \ "desc").asOpt[String].getOrElse("--"),
            metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
            eventsFilters = (json \ "eventsFilters").as[Seq[String]],
            eventsFiltersNot = (json \ "eventsFiltersNot").as[Seq[String]],
            config = config
          )
          case config: NoneMailerSettings => NoneMailerExporterConfig(
            location = EntityLocation.readFromKey(json),
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            id = (json \ "id").as[String],
            name = (json \ "id").as[String],
            desc = (json \ "desc").asOpt[String].getOrElse("--"),
            metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
            eventsFilters = (json \ "eventsFilters").as[Seq[String]],
            eventsFiltersNot = (json \ "eventsFiltersNot").as[Seq[String]],
            config = config
          )
        }
    }

    override def writes(o: DataExporterConfig): JsValue = o match {
      case e: ElasticExporterConfig => o.location.jsonWithKey ++ Json.obj(
        "type" -> "elastic",
        "enabled" -> o.enabled,
        "id" -> o.id,
        "name" -> o.name,
        "desc" -> o.desc,
        "metadata" -> o.metadata,
        "eventsFilters" -> JsArray(o.eventsFilters.map(JsString.apply)),
        "eventsFiltersNot" -> JsArray(o.eventsFiltersNot.map(JsString.apply)),
        "config" -> ElasticAnalyticsConfig.format.writes(e.config).as[JsObject]
      )
      case e: WebhookExporterConfig => o.location.jsonWithKey ++ Json.obj(
        "type" -> "webhook",
        "enabled" -> o.enabled,
        "id" -> o.id,
        "name" -> o.name,
        "desc" -> o.desc,
        "metadata" -> o.metadata,
        "eventsFilters" -> JsArray(o.eventsFilters.map(JsString.apply)),
        "eventsFiltersNot" -> JsArray(o.eventsFiltersNot.map(JsString.apply)),
        "config" -> Webhook.format.writes(e.config).as[JsObject]
      )
      case e: KafkaExporterConfig => o.location.jsonWithKey ++ Json.obj(
        "type" -> "kafka",
        "enabled" -> o.enabled,
        "id" -> o.id,
        "name" -> o.name,
        "desc" -> o.desc,
        "metadata" -> o.metadata,
        "eventsFilters" -> JsArray(o.eventsFilters.map(JsString.apply)),
        "eventsFiltersNot" -> JsArray(o.eventsFiltersNot.map(JsString.apply)),
        "config" -> KafkaConfig.format.writes(e.config).as[JsObject]
      )
      case e: PulsarExporterConfig => o.location.jsonWithKey ++ Json.obj(
        "type" -> "pulsar",
        "enabled" -> o.enabled,
        "id" -> o.id,
        "name" -> o.name,
        "desc" -> o.desc,
        "metadata" -> o.metadata,
        "eventsFilters" -> JsArray(o.eventsFilters.map(JsString.apply)),
        "eventsFiltersNot" -> JsArray(o.eventsFiltersNot.map(JsString.apply)),
        "config" -> PulsarConfig.format.writes(e.config).as[JsObject]
      )
      case e: ConsoleExporterConfig => o.location.jsonWithKey ++ Json.obj(
        "type" -> "mailer",
        "enabled" -> o.enabled,
        "id" -> o.id,
        "name" -> o.name,
        "desc" -> o.desc,
        "metadata" -> o.metadata,
        "eventsFilters" -> JsArray(o.eventsFilters.map(JsString.apply)),
        "eventsFiltersNot" -> JsArray(o.eventsFiltersNot.map(JsString.apply)),
        "config" -> (ConsoleMailerSettings.format.writes(e.config) ++ Json.obj("type" -> "console"))
      )
      case e: GenericMailerExporterConfig => o.location.jsonWithKey ++ Json.obj(
        "type" -> "mailer",
        "enabled" -> o.enabled,
        "id" -> o.id,
        "name" -> o.name,
        "desc" -> o.desc,
        "metadata" -> o.metadata,
        "eventsFilters" -> JsArray(o.eventsFilters.map(JsString.apply)),
        "eventsFiltersNot" -> JsArray(o.eventsFiltersNot.map(JsString.apply)),
        "config" -> (GenericMailerSettings.format.writes(e.config) ++ Json.obj("type" -> "generic"))
      )
      case e: MailgunExporterConfig => o.location.jsonWithKey ++ Json.obj(
        "type" -> "mailer",
        "enabled" -> o.enabled,
        "id" -> o.id,
        "name" -> o.name,
        "desc" -> o.desc,
        "metadata" -> o.metadata,
        "eventsFilters" -> JsArray(o.eventsFilters.map(JsString.apply)),
        "eventsFiltersNot" -> JsArray(o.eventsFiltersNot.map(JsString.apply)),
        "config" -> (MailgunSettings.format.writes(e.config) ++ Json.obj("type" -> "mailgun"))
      )
      case e: MailjetExporterConfig => o.location.jsonWithKey ++ Json.obj(
        "type" -> "mailer",
        "enabled" -> o.enabled,
        "id" -> o.id,
        "name" -> o.name,
        "desc" -> o.desc,
        "metadata" -> o.metadata,
        "eventsFilters" -> JsArray(o.eventsFilters.map(JsString.apply)),
        "eventsFiltersNot" -> JsArray(o.eventsFiltersNot.map(JsString.apply)),
        "config" -> (MailjetSettings.format.writes(e.config) ++ Json.obj("type" -> "mailjet"))
      )
      case e: NoneMailerExporterConfig => o.location.jsonWithKey ++ Json.obj(
        "type" -> "mailer",
        "enabled" -> o.enabled,
        "id" -> o.id,
        "name" -> o.name,
        "desc" -> o.desc,
        "metadata" -> o.metadata,
        "eventsFilters" -> JsArray(o.eventsFilters.map(JsString.apply)),
        "eventsFiltersNot" -> JsArray(o.eventsFiltersNot.map(JsString.apply)),
        "config" -> (NoneMailerSettings.format.writes(e.config) ++ Json.obj("type" -> "none"))
      )
    }

  }
}

