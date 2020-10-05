package models

import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Sink, Source}
import env.Env
import events.Exporters.{WebhookExporter, _}
import events.{DataExporter, KafkaConfig, PulsarConfig}
import models.DataExporterConfig._
import otoroshi.models.{EntityLocation, EntityLocationSupport}
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import security.IdGenerator
import utils._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}


trait Exporter {
  def toJson: JsValue
}

object Exporter {

  case object NoneExporter extends Exporter {
    def toJson: JsValue = JsNull
  }

}

case class DataExporterConfigFiltering(include: Seq[JsObject] = Seq.empty, exclude: Seq[JsObject] = Seq.empty)

case class FileSettings(path: String, maxFileSize: Int = 10 * 1024 * 1024) extends Exporter {
  override def toJson: JsValue = Json.obj(
    "path" -> path,
    "maxFileSize" -> maxFileSize
  )
}

object DataExporterConfig {

  import scala.concurrent.duration._

  private val log = Logger("otoroshi-data-exporter-config")

  def fromJsons(value: JsValue): DataExporterConfig =
    try {
      format.reads(value).get
    } catch {
      case e: Throwable => {
        log.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }

  val format = new Format[DataExporterConfig] {
    override def writes(o: DataExporterConfig): JsValue = {
      o.location.jsonWithKey ++ Json.obj(
        "type" -> o.typ.name,
        "enabled" -> o.enabled,
        "id" -> o.id,
        "name" -> o.name,
        "desc" -> o.desc,
        "metadata" -> o.metadata,
        "bufferSize" -> o.bufferSize,
        "jsonWorkers" -> o.jsonWorkers,
        "sendWorkers" -> o.sendWorkers,
        "groupSize" -> o.groupSize,
        "groupDuration" -> o.groupDuration.toMillis,
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
        bufferSize = (json \ "bufferSize").asOpt[Int].getOrElse(5000),
        jsonWorkers = (json \ "jsonWorkers").asOpt[Int].getOrElse(1),
        sendWorkers = (json \ "sendWorkers").asOpt[Int].getOrElse(5),
        groupSize = (json \ "groupSize").asOpt[Int].getOrElse(100),
        groupDuration = (json \ "groupDuration").asOpt[Int].map(_.millis).getOrElse(30.seconds),
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

  case object Kafka extends DataExporterConfigType {
    def name: String = "kafka"
  }

  case object Pulsar extends DataExporterConfigType {
    def name: String = "pulsar"
  }

  case object Elastic extends DataExporterConfigType {
    def name: String = "elastic"
  }

  case object Webhook extends DataExporterConfigType {
    def name: String = "webhook"
  }

  case object File extends DataExporterConfigType {
    def name: String = "file"
  }

  case object Mailer extends DataExporterConfigType {
    def name: String = "mailer"
  }

  case object None extends DataExporterConfigType {
    def name: String = "none"
  }

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
                              bufferSize: Int = 5000,
                              jsonWorkers: Int = 1,
                              sendWorkers: Int = 5,
                              groupSize: Int = 100,
                              groupDuration: FiniteDuration = 30.seconds,
                              filtering: DataExporterConfigFiltering,
                              projection: JsObject,
                              config: Exporter) extends EntityLocationSupport {

  override def json: JsValue = DataExporterConfig.format.writes(this)

  override def internalId: String = id

  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    env.datastores.dataExporterConfigDataStore.set(this)
  }

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

class DataExporterConfigMigrationJob extends Job {

  private val logger = Logger("otoroshi-data-exporter-config-migration-job")

  override def uniqueId: JobId = JobId("io.otoroshi.core.models.DataExporterConfigMigrationJob")

  override def name: String = "Otoroshi data exporter config migration job"

  override def visibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledOnce

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation: JobInstantiation = JobInstantiation.OneInstancePerOtoroshiCluster

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    implicit val mat = env.otoroshiMaterializer

    val alertDataExporterConfigFiltering = DataExporterConfigFiltering(
      include = Seq(Json.obj("type" -> Json.obj("$regex" -> "Alert*")))
    )
    val analyticsDataExporterConfigFiltering = DataExporterConfigFiltering(
      include = Seq(Json.obj("type" -> Json.obj("$regex" -> "*Event")))
    )
    def toDataExporterConfig(ex: Exporter, typ: DataExporterConfigType,  filter: DataExporterConfigFiltering): DataExporterConfig =
      DataExporterConfig(
        enabled = true,
        id = IdGenerator.token,
        name = "--",
        desc = "--",
        metadata = Map.empty,
        location = EntityLocation(),
        filtering = filter,
        projection = Json.obj(),
        typ = typ,
        config = ex
      )

    env.datastores.globalConfigDataStore.findById("global").map {
      case Some(globalConfig) =>
        val analyticsWebhooksExporters = globalConfig.analyticsWebhooks.map(c => toDataExporterConfig(c, DataExporterConfigType.Webhook, analyticsDataExporterConfigFiltering))
        val alertsWebhooksExporters: Seq[DataExporterConfig] = globalConfig.alertsWebhooks.map(c => toDataExporterConfig(c, DataExporterConfigType.Webhook, alertDataExporterConfigFiltering))
        val analyticsElasticExporters: Seq[DataExporterConfig] = globalConfig.elasticWritesConfigs.map(c => toDataExporterConfig(c, DataExporterConfigType.Elastic, analyticsDataExporterConfigFiltering))
        val kafkaExporter: Option[DataExporterConfig] = globalConfig.kafkaConfig
          .filter(c => c.servers.nonEmpty)
          .map(c => toDataExporterConfig(c, DataExporterConfigType.Kafka, analyticsDataExporterConfigFiltering))
        val alertMailerExporter: Option[DataExporterConfig] = globalConfig.mailerSettings
          .filter(setting => setting.typ != "none")
          .map(c => toDataExporterConfig(c, DataExporterConfigType.Mailer, alertDataExporterConfigFiltering))

        analyticsWebhooksExporters ++
          alertsWebhooksExporters ++
          analyticsElasticExporters ++
          kafkaExporter.fold(Seq.empty[DataExporterConfig])(e => Seq(e)) ++
          alertMailerExporter.fold(Seq.empty[DataExporterConfig])(e => Seq(e))
    }
      .flatMap(configs => {
        Source(configs.toList)
          .mapAsync(1)(ex => {
            env.datastores.dataExporterConfigDataStore.set(ex)
          })
          .runWith(Sink.ignore)
          .map(_ => ())
      })
      .andThen {
        case Success(_) =>
          env.datastores.globalConfigDataStore.findById("global").map {
            case Some(config) => env.datastores.globalConfigDataStore.set(config.copy(
              analyticsWebhooks = Seq.empty,
              alertsWebhooks = Seq.empty,
              elasticWritesConfigs = Seq.empty,
              kafkaConfig = None,
              mailerSettings = None
            ))
          }
        case Failure(e) => logger.error("Data exporter migration job failed", e)
      }
  }
}

