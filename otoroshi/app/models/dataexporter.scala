package models

import env.Env
import events.Exporters.{ConsoleExporter, ElasticExporter, GenericMailerExporter, KafkaExporter, PulsarExporter, WebhookExporter}
import events.{DataExporter, KafkaConfig, PulsarConfig}
import play.api.libs.json.{Format, JsArray, JsObject, JsResult, JsString, JsValue, Json}
import utils.{ConsoleMailerSettings, GenericMailerSettings, MailerSettings, MailgunSettings, MailjetSettings, NoneMailerSettings}

import scala.concurrent.ExecutionContext


trait Exporter {
  def toJson: JsValue
}

trait DataExporterConfig {
  val enabled: Boolean
  val id: String
  val eventsFilters: Seq[String]
  val eventsFiltersNot: Seq[String]
  val config: Exporter
  def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter
}

case object DataExporterConfig {

  case class ElasticExporterConfig(enabled: Boolean, id: String, eventsFilters: Seq[String], eventsFiltersNot: Seq[String], config: ElasticAnalyticsConfig) extends DataExporterConfig {
    def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter = new ElasticExporter(this)(ec, env)
  }
  case class WebhookExporterConfig(enabled: Boolean, id: String, eventsFilters: Seq[String], eventsFiltersNot: Seq[String], config: Webhook) extends DataExporterConfig {
    def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter = new WebhookExporter(this)(ec, env)
  }
  case class KafkaExporterConfig(enabled: Boolean, id: String, eventsFilters: Seq[String], eventsFiltersNot: Seq[String], config: KafkaConfig) extends DataExporterConfig {
    def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter = new KafkaExporter(this)(ec, env)
  }
  case class PulsarExporterConfig(enabled: Boolean, id: String, eventsFilters: Seq[String], eventsFiltersNot: Seq[String], config: PulsarConfig) extends DataExporterConfig {
    def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter = new PulsarExporter(this)(ec, env)
  }
  case class ConsoleExporterConfig(enabled: Boolean, id: String, eventsFilters: Seq[String], eventsFiltersNot: Seq[String], config: ConsoleMailerSettings) extends DataExporterConfig {
    def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter = new ConsoleExporter(this)(ec, env)
  }
  case class GenericMailerExporterConfig(enabled: Boolean, id: String, eventsFilters: Seq[String], eventsFiltersNot: Seq[String], config: GenericMailerSettings) extends DataExporterConfig {
    def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter = new GenericMailerExporter(this)(ec, env)
  }
  case class MailgunExporterConfig(enabled: Boolean, id: String, eventsFilters: Seq[String], eventsFiltersNot: Seq[String], config: MailgunSettings ) extends DataExporterConfig {
    def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter = new GenericMailerExporter(this)(ec, env)
  }
  case class MailjetExporterConfig(enabled: Boolean, id: String, eventsFilters: Seq[String], eventsFiltersNot: Seq[String], config: MailjetSettings) extends DataExporterConfig {
    def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter = new GenericMailerExporter(this)(ec, env)
  }
  case class NoneMailerExporterConfig(enabled: Boolean, id: String, eventsFilters: Seq[String], eventsFiltersNot: Seq[String], config: NoneMailerSettings) extends DataExporterConfig {
    def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter = new GenericMailerExporter(this)(ec, env)
  }

  // TODO: fix enabled
  val format: Format[DataExporterConfig] = new Format[DataExporterConfig] {
    override def reads(json: JsValue): JsResult[DataExporterConfig] = (json \ "type").as[String] match {
      case "elastic" => ElasticAnalyticsConfig.format.reads((json \ "config").as[JsObject])
        .map(config => ElasticExporterConfig(
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          id = (json \ "id").as[String],
          eventsFilters = (json \ "eventsFilters").as[Seq[String]],
          eventsFiltersNot = (json \ "eventsFiltersNot").as[Seq[String]],
          config = config
        ))
      case "webhook" => Webhook.format.reads((json \ "config").as[JsObject])
        .map(config => WebhookExporterConfig(
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          id = (json \ "id").as[String],
          eventsFilters = (json \ "eventsFilters").as[Seq[String]],
          eventsFiltersNot = (json \ "eventsFiltersNot").as[Seq[String]],
          config = config
        ))
      case "kafka" => KafkaConfig.format.reads((json \ "config").as[JsObject])
        .map(config => KafkaExporterConfig(
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          id = (json \ "id").as[String],
          eventsFilters = (json \ "eventsFilters").as[Seq[String]],
          eventsFiltersNot = (json \ "eventsFiltersNot").as[Seq[String]],
          config = config
        ))
      case "pulsar" => PulsarConfig.format.reads((json \ "config").as[JsObject])
        .map(config => PulsarExporterConfig(
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          id = (json \ "id").as[String],
          eventsFilters = (json \ "eventsFilters").as[Seq[String]],
          eventsFiltersNot = (json \ "eventsFiltersNot").as[Seq[String]],
          config = config
        ))
      case "mailer" => MailerSettings.format.reads((json \ "config").as[JsObject])
        .map {
          case config: ConsoleMailerSettings => ConsoleExporterConfig(
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            id = (json \ "id").as[String],
            eventsFilters = (json \ "eventsFilters").as[Seq[String]],
            eventsFiltersNot = (json \ "eventsFiltersNot").as[Seq[String]],
            config = config
          )
          case config: GenericMailerSettings => GenericMailerExporterConfig(
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            id = (json \ "id").as[String],
            eventsFilters = (json \ "eventsFilters").as[Seq[String]],
            eventsFiltersNot = (json \ "eventsFiltersNot").as[Seq[String]],
            config = config
          )
          case config: MailgunSettings => MailgunExporterConfig(
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            id = (json \ "id").as[String],
            eventsFilters = (json \ "eventsFilters").as[Seq[String]],
            eventsFiltersNot = (json \ "eventsFiltersNot").as[Seq[String]],
            config = config
          )
          case config: MailjetSettings => MailjetExporterConfig(
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            id = (json \ "id").as[String],
            eventsFilters = (json \ "eventsFilters").as[Seq[String]],
            eventsFiltersNot = (json \ "eventsFiltersNot").as[Seq[String]],
            config = config
          )
          case config: NoneMailerSettings => NoneMailerExporterConfig(
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            id = (json \ "id").as[String],
            eventsFilters = (json \ "eventsFilters").as[Seq[String]],
            eventsFiltersNot = (json \ "eventsFiltersNot").as[Seq[String]],
            config = config
          )
        }
    }

    override def writes(o: DataExporterConfig): JsValue = o match {
      case e: ElasticExporterConfig => Json.obj(
        "type" -> "elastic",
        "id" -> o.id,
        "eventsFilters" -> JsArray(o.eventsFilters.map(JsString.apply)),
        "eventsFiltersNot" -> JsArray(o.eventsFiltersNot.map(JsString.apply)),
        "config" -> ElasticAnalyticsConfig.format.writes(e.config).as[JsObject]
      )
      case e: WebhookExporterConfig => Json.obj(
        "type" -> "webhook",
        "id" -> o.id,
        "eventsFilters" -> JsArray(o.eventsFilters.map(JsString.apply)),
        "eventsFiltersNot" -> JsArray(o.eventsFiltersNot.map(JsString.apply)),
        "config" -> Webhook.format.writes(e.config).as[JsObject]
      )
      case e: KafkaExporterConfig => Json.obj(
        "type" -> "kafka",
        "id" -> o.id,
        "eventsFilters" -> JsArray(o.eventsFilters.map(JsString.apply)),
        "eventsFiltersNot" -> JsArray(o.eventsFiltersNot.map(JsString.apply)),
        "config" -> KafkaConfig.format.writes(e.config).as[JsObject]
      )
      case e: PulsarExporterConfig => Json.obj(
        "type" -> "pulsar",
        "id" -> o.id,
        "eventsFilters" -> JsArray(o.eventsFilters.map(JsString.apply)),
        "eventsFiltersNot" -> JsArray(o.eventsFiltersNot.map(JsString.apply)),
        "config" -> PulsarConfig.format.writes(e.config).as[JsObject]
      )
      case e: ConsoleExporterConfig => Json.obj(
        "type" -> "mailer",
        "id" -> o.id,
        "eventsFilters" -> JsArray(o.eventsFilters.map(JsString.apply)),
        "eventsFiltersNot" -> JsArray(o.eventsFiltersNot.map(JsString.apply)),
        "config" -> (ConsoleMailerSettings.format.writes(e.config) ++ Json.obj("type" -> "console"))
      )
      case e: GenericMailerExporterConfig => Json.obj(
        "type" -> "mailer",
        "id" -> o.id,
        "eventsFilters" -> JsArray(o.eventsFilters.map(JsString.apply)),
        "eventsFiltersNot" -> JsArray(o.eventsFiltersNot.map(JsString.apply)),
        "config" -> (GenericMailerSettings.format.writes(e.config) ++ Json.obj("type" -> "generic"))
      )
      case e: MailgunExporterConfig => Json.obj(
        "type" -> "mailer",
        "id" -> o.id,
        "eventsFilters" -> JsArray(o.eventsFilters.map(JsString.apply)),
        "eventsFiltersNot" -> JsArray(o.eventsFiltersNot.map(JsString.apply)),
        "config" -> (MailgunSettings.format.writes(e.config) ++ Json.obj("type" -> "mailgun"))
      )
      case e: MailjetExporterConfig => Json.obj(
        "type" -> "mailer",
        "id" -> o.id,
        "eventsFilters" -> JsArray(o.eventsFilters.map(JsString.apply)),
        "eventsFiltersNot" -> JsArray(o.eventsFiltersNot.map(JsString.apply)),
        "config" -> (MailjetSettings.format.writes(e.config) ++ Json.obj("type" -> "mailjet"))
      )
      case e: NoneMailerExporterConfig => Json.obj(
        "type" -> "mailer",
        "id" -> o.id,
        "eventsFilters" -> JsArray(o.eventsFilters.map(JsString.apply)),
        "eventsFiltersNot" -> JsArray(o.eventsFiltersNot.map(JsString.apply)),
        "config" -> (NoneMailerSettings.format.writes(e.config) ++ Json.obj("type" -> "none"))
      )
    }

  }
}

