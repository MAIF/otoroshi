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
object Exporter {
  case object NoneExporter extends Exporter {
    def toJson: JsValue = JsNull
  }
}


case class DataExporterConfigFiltering(include: Seq[JsObject] = Seq.empty, exclude: Seq[JsObject] = Seq.empty)

trait DataExporterConfig extends EntityLocationSupport  {
  def enabled: Boolean
  def id: String
  def name: String
  def desc: String
  def metadata: Map[String, String]
  def location: EntityLocation
  def filtering: DataExporterConfigFiltering
  def projection: JsObject
  def config: Exporter
  def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter

  override def internalId: String = id
  override def json: JsValue = DataExporterConfig.format.writes(this)

  def commonJson(typ: String): JsObject = Json.obj(
    "type" -> typ,
    "enabled" -> enabled,
    "id" -> id,
    "name" -> name,
    "desc" -> desc,
    "metadata" -> metadata,
    "projection" -> projection,
    "filtering" -> Json.obj(
      "include" -> JsArray(filtering.include),
      "exclude" -> JsArray(filtering.exclude),
    )
  )
}

case object DataExporterConfig {

  case class ElasticExporterConfig(
                                    enabled: Boolean,
                                    id: String,
                                    name: String,
                                    desc: String,
                                    metadata: Map[String, String],
                                    location: EntityLocation = EntityLocation(),
                                    filtering: DataExporterConfigFiltering,
                                    projection: JsObject,
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
                                    filtering: DataExporterConfigFiltering,
                                    projection: JsObject,
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
                                  filtering: DataExporterConfigFiltering,
                                  projection: JsObject,
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
                                   filtering: DataExporterConfigFiltering,
                                   projection: JsObject,
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
                                    filtering: DataExporterConfigFiltering,
                                    projection: JsObject,
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
                                          filtering: DataExporterConfigFiltering,
                                          projection: JsObject,
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
                                    filtering: DataExporterConfigFiltering,
                                    projection: JsObject,
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
                                    filtering: DataExporterConfigFiltering,
                                    projection: JsObject,
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
                                       filtering: DataExporterConfigFiltering,
                                       projection: JsObject,
                                       config: NoneMailerSettings) extends DataExporterConfig {
    def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter = new GenericMailerExporter(this)(ec, env)
  }

  case class SendgridExporterConfig(
                                       enabled: Boolean,
                                       id: String,
                                       name: String,
                                       desc: String,
                                       metadata: Map[String, String],
                                       location: EntityLocation = EntityLocation(),
                                       filtering: DataExporterConfigFiltering,
                                       projection: JsObject,
                                       config: SendgridSettings) extends DataExporterConfig {
    def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter = new GenericMailerExporter(this)(ec, env)
  }

  case class FileExporter(path: String) extends Exporter {
    override def toJson: JsValue = Json.obj(
      "path" -> path
    )
  }

  case class FileAppenderExporterConfig(
                                       enabled: Boolean,
                                       id: String,
                                       name: String,
                                       desc: String,
                                       metadata: Map[String, String],
                                       location: EntityLocation = EntityLocation(),
                                       filtering: DataExporterConfigFiltering,
                                       projection: JsObject,
                                       config: FileExporter) extends DataExporterConfig {
    def exporter()(implicit ec:ExecutionContext, env: Env): DataExporter = new FileAppenderExporter(this)(ec, env)
  }

  val format: Format[DataExporterConfig] = new Format[DataExporterConfig] {
    override def reads(json: JsValue): JsResult[DataExporterConfig] = (json \ "type").as[String] match {
      case "elastic" => ElasticAnalyticsConfig.format.reads((json \ "config").as[JsObject])
        .map(config => ElasticExporterConfig(
          location = EntityLocation.readFromKey(json),
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          desc = (json \ "desc").asOpt[String].getOrElse("--"),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          projection = (json \ "projection").asOpt[JsObject].getOrElse(Json.obj()),
          filtering = DataExporterConfigFiltering(
            include = (json \ "filtering" \ "include").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
            exclude = (json \ "filtering" \ "exclude").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
          ),
          config = config
        ))
      case "webhook" => Webhook.format.reads((json \ "config").as[JsObject])
        .map(config => WebhookExporterConfig(
          location = EntityLocation.readFromKey(json),
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          desc = (json \ "desc").asOpt[String].getOrElse("--"),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          projection = (json \ "projection").asOpt[JsObject].getOrElse(Json.obj()),
          filtering = DataExporterConfigFiltering(
            include = (json \ "filtering" \ "include").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
            exclude = (json \ "filtering" \ "exclude").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
          ),
          config = config
        ))
      case "kafka" => KafkaConfig.format.reads((json \ "config").as[JsObject])
        .map(config => KafkaExporterConfig(
          location = EntityLocation.readFromKey(json),
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          desc = (json \ "desc").asOpt[String].getOrElse("--"),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          projection = (json \ "projection").asOpt[JsObject].getOrElse(Json.obj()),
          filtering = DataExporterConfigFiltering(
            include = (json \ "filtering" \ "include").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
            exclude = (json \ "filtering" \ "exclude").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
          ),
          config = config
        ))
      case "pulsar" => PulsarConfig.format.reads((json \ "config").as[JsObject])
        .map(config => PulsarExporterConfig(
          location = EntityLocation.readFromKey(json),
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          desc = (json \ "desc").asOpt[String].getOrElse("--"),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          projection = (json \ "projection").asOpt[JsObject].getOrElse(Json.obj()),
          filtering = DataExporterConfigFiltering(
            include = (json \ "filtering" \ "include").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
            exclude = (json \ "filtering" \ "exclude").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
          ),
          config = config
        ))
      case "file" => JsSuccess(FileAppenderExporterConfig(
        location = EntityLocation.readFromKey(json),
        enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        desc = (json \ "desc").asOpt[String].getOrElse("--"),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        projection = (json \ "projection").asOpt[JsObject].getOrElse(Json.obj()),
        filtering = DataExporterConfigFiltering(
          include = (json \ "filtering" \ "include").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
          exclude = (json \ "filtering" \ "exclude").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
        ),
        config = FileExporter((json \ "config" \ "path").as[String])
      ))
      case "mailer" => MailerSettings.format.reads((json \ "config").as[JsObject])
        .map {
          case config: ConsoleMailerSettings => ConsoleExporterConfig(
            location = EntityLocation.readFromKey(json),
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            id = (json \ "id").as[String],
            name = (json \ "name").as[String],
            desc = (json \ "desc").asOpt[String].getOrElse("--"),
            metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
            projection = (json \ "projection").asOpt[JsObject].getOrElse(Json.obj()),
            filtering = DataExporterConfigFiltering(
              include = (json \ "filtering" \ "include").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
              exclude = (json \ "filtering" \ "exclude").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
            ),
            config = config
          )
          case config: GenericMailerSettings => GenericMailerExporterConfig(
            location = EntityLocation.readFromKey(json),
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            id = (json \ "id").as[String],
            name = (json \ "name").as[String],
            desc = (json \ "desc").asOpt[String].getOrElse("--"),
            metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
            projection = (json \ "projection").asOpt[JsObject].getOrElse(Json.obj()),
            filtering = DataExporterConfigFiltering(
              include = (json \ "filtering" \ "include").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
              exclude = (json \ "filtering" \ "exclude").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
            ),
            config = config
          )
          case config: MailgunSettings => MailgunExporterConfig(
            location = EntityLocation.readFromKey(json),
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            id = (json \ "id").as[String],
            name = (json \ "name").as[String],
            desc = (json \ "desc").asOpt[String].getOrElse("--"),
            metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
            projection = (json \ "projection").asOpt[JsObject].getOrElse(Json.obj()),
            filtering = DataExporterConfigFiltering(
              include = (json \ "filtering" \ "include").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
              exclude = (json \ "filtering" \ "exclude").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
            ),
            config = config
          )
          case config: MailjetSettings => MailjetExporterConfig(
            location = EntityLocation.readFromKey(json),
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            id = (json \ "id").as[String],
            name = (json \ "name").as[String],
            desc = (json \ "desc").asOpt[String].getOrElse("--"),
            metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
            projection = (json \ "projection").asOpt[JsObject].getOrElse(Json.obj()),
            filtering = DataExporterConfigFiltering(
              include = (json \ "filtering" \ "include").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
              exclude = (json \ "filtering" \ "exclude").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
            ),
            config = config
          )
          case config: NoneMailerSettings => NoneMailerExporterConfig(
            location = EntityLocation.readFromKey(json),
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            id = (json \ "id").as[String],
            name = (json \ "name").as[String],
            desc = (json \ "desc").asOpt[String].getOrElse("--"),
            metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
            projection = (json \ "projection").asOpt[JsObject].getOrElse(Json.obj()),
            filtering = DataExporterConfigFiltering(
              include = (json \ "filtering" \ "include").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
              exclude = (json \ "filtering" \ "exclude").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
            ),
            config = config
          )
          case config: SendgridSettings => SendgridExporterConfig(
            location = EntityLocation.readFromKey(json),
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            id = (json \ "id").as[String],
            name = (json \ "name").as[String],
            desc = (json \ "desc").asOpt[String].getOrElse("--"),
            metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
            projection = (json \ "projection").asOpt[JsObject].getOrElse(Json.obj()),
            filtering = DataExporterConfigFiltering(
              include = (json \ "filtering" \ "include").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
              exclude = (json \ "filtering" \ "exclude").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
            ),
            config = config
          )
        }
      case eeee =>
        println(eeee)
        JsError("fffffffuuuu")
    }

    override def writes(o: DataExporterConfig): JsValue = o match {
      case e: ElasticExporterConfig => o.location.jsonWithKey ++ e.commonJson("elastic") ++ Json.obj(
        "config" -> ElasticAnalyticsConfig.format.writes(e.config).as[JsObject]
      )
      case e: WebhookExporterConfig => o.location.jsonWithKey ++ e.commonJson("webhook") ++ Json.obj(
        "config" -> Webhook.format.writes(e.config).as[JsObject]
      )
      case e: KafkaExporterConfig => o.location.jsonWithKey ++ e.commonJson("kafka") ++ Json.obj(
        "config" -> KafkaConfig.format.writes(e.config).as[JsObject]
      )
      case e: PulsarExporterConfig => o.location.jsonWithKey ++ e.commonJson("pulsar") ++ Json.obj(
        "config" -> PulsarConfig.format.writes(e.config).as[JsObject]
      )
      case e: ConsoleExporterConfig => o.location.jsonWithKey ++ e.commonJson("mailer") ++ Json.obj(
        "config" -> (ConsoleMailerSettings.format.writes(e.config) ++ Json.obj("type" -> "console"))
      )
      case e: GenericMailerExporterConfig => o.location.jsonWithKey ++ e.commonJson("mailer") ++ Json.obj(
        "config" -> (GenericMailerSettings.format.writes(e.config) ++ Json.obj("type" -> "generic"))
      )
      case e: MailgunExporterConfig => o.location.jsonWithKey ++ e.commonJson("mailer") ++ Json.obj(
        "config" -> (MailgunSettings.format.writes(e.config) ++ Json.obj("type" -> "mailgun"))
      )
      case e: MailjetExporterConfig => o.location.jsonWithKey ++ e.commonJson("mailer") ++ Json.obj(
        "config" -> (MailjetSettings.format.writes(e.config) ++ Json.obj("type" -> "mailjet"))
      )
      case e: NoneMailerExporterConfig => o.location.jsonWithKey ++ e.commonJson("mailer") ++ Json.obj(
        "config" -> (NoneMailerSettings.format.writes(e.config) ++ Json.obj("type" -> "none"))
      )
      case e: FileAppenderExporterConfig => o.location.jsonWithKey ++ e.commonJson("file") ++ Json.obj("config" -> e.config.toJson) ++ Json.obj("type" -> "file")
    }

  }
}

