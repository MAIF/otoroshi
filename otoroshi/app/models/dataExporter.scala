package models

import env.Env
import events.Exporters._
import events.{DataExporter, KafkaConfig, PulsarConfig}
import otoroshi.models.{EntityLocation, EntityLocationSupport}
import play.api.libs.json._
import utils._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}


trait Exporter {
  def toJson: JsValue
}
object Exporter {
  case object NoneExporter extends Exporter {
    def toJson: JsValue = JsNull
  }
}

case class DataExporterConfigFiltering(include: Seq[JsObject] = Seq.empty, exclude: Seq[JsObject] = Seq.empty)

case class FileSettings(path: String) extends Exporter {
  override def toJson: JsValue = Json.obj(
    "path" -> path
  )
}

object DataExporterConfig {
  val format = new Format[DataExporterConfig] {
    override def writes(o: DataExporterConfig): JsValue = {
      Json.obj(
        "type" -> o.typ.name,
        "enabled" -> o.enabled,
        "id" -> o.id,
        "name" -> o.name,
        "desc" -> o.desc,
        "metadata" -> o.metadata,
        "projection" -> o.projection,
        "filtering" -> Json.obj(
          "include" -> JsArray(o.filtering.include),
          "exclude" -> JsArray(o.filtering.exclude),
        ),
        "config" -> o.config.toJson
      )
    }
    override def reads(json: JsValue): JsResult[DataExporterConfig] = Try {
      DataExporterConfig(
        typ = DataExporterConfigType.parse((json \ "type").as[String]),
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
        config = (json \ "type").as[String] match {
          case "elastic" => ElasticAnalyticsConfig.format.reads((json \ "config").as[JsObject]).get
          case "webhook" => Webhook.format.reads((json \ "config").as[JsObject]).get
          case "kafka" => KafkaConfig.format.reads((json \ "config").as[JsObject]).get
          case "pulsar" => PulsarConfig.format.reads((json \ "config").as[JsObject]).get
          case "file" => FileSettings((json \ "config" \ "path").as[String])
          case "mailer" => MailerSettings.format.reads((json \ "config").as[JsObject]).get
        }
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
  }
}

sealed trait DataExporterConfigType {
  def name: String
}
object DataExporterConfigType {
  case object Kafka extends DataExporterConfigType { def name: String = "kafka" }
  case object Pulsar extends DataExporterConfigType { def name: String = "pulsar" }
  case object Elastic extends DataExporterConfigType { def name: String = "elastic" }
  case object Webhook extends DataExporterConfigType { def name: String = "webhook" }
  case object File extends DataExporterConfigType { def name: String = "file" }
  case object Mailer extends DataExporterConfigType { def name: String = "mailer" }
  case object None extends DataExporterConfigType { def name: String = "none" }
  def parse(str: String): DataExporterConfigType = str.toLowerCase() match {
    case "kafka" => Kafka
    case "pulsar" => Pulsar
    case "elastic" => Elastic
    case "webhook" => Webhook
    case "file" => File
    case "mailer" => Mailer
    case "none" => None
  }
}

case class DataExporterConfig(enabled: Boolean,
                              typ: DataExporterConfigType,
                              id: String,
                              name: String,
                              desc: String,
                              metadata: Map[String, String],
                              location: EntityLocation = EntityLocation(),
                              filtering: DataExporterConfigFiltering,
                              projection: JsObject,
                              config: Exporter) extends EntityLocationSupport {

  override def json: JsValue = DataExporterConfig.format.writes(this)
  override def internalId: String = id

  def exporter()(implicit ec: ExecutionContext, env: Env): DataExporter = {
    config match {
      case c: KafkaConfig => new KafkaExporter(this)
      case c: PulsarConfig => new PulsarExporter(this)
      case c: ElasticAnalyticsConfig => new ElasticExporter(this)
      case c: Webhook => new WebhookExporter(this)
      case c: FileSettings => new FileAppenderExporter(this)
      case c: NoneMailerSettings => new GenericMailerExporter(this)
      case c: ConsoleMailerSettings => new GenericMailerExporter(this)
      case c: MailjetSettings => new GenericMailerExporter(this)
      case c: MailgunSettings => new GenericMailerExporter(this)
      case c: SendgridSettings => new GenericMailerExporter(this)
      case c: GenericMailerSettings => new GenericMailerExporter(this)
      case _ => throw new RuntimeException("unsupported exporter type")
    }
  }
}

