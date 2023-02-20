package otoroshi.jobs

import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.AnalyticEvent
import otoroshi.next.plugins.api.NgPluginCategory
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

import java.util.UUID
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

class StateExporter extends Job {

  override def categories: Seq[NgPluginCategory] = Seq.empty

  override def uniqueId: JobId = JobId("io.otoroshi.core.jobs.StateExporter")

  override def name: String = "Otoroshi state exporter job"

  override def defaultConfig: Option[JsObject] = Json.obj(
    "StateExporter" -> Json.obj(
      "every_sec" -> 60.minutes.toSeconds,
      "format" -> "json",
    )
  ).some

  override def configFlow: Seq[String] = Seq("every_sec")

  override def configSchema: Option[JsObject] = Json.obj(
    "every_sec" -> Json.obj(
      "type" -> "number",
      "props" -> Json.obj(
        "label" -> "Run every",
        "suffix" -> "seconds",
      ),
    ),
    "format" -> Json.obj(
      "type" -> "string",
      "props" -> Json.obj(
        "label" -> "Export format",
      )
    ),
  ).some

  override def description: Option[String] =
    s"""This job send an event containing the full otoroshi export every n seconds""".stripMargin.some

  override def jobVisibility: JobVisibility = JobVisibility.UserLand

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.FromConfiguration

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation = JobInstantiation.OneInstancePerOtoroshiCluster

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = {
    env.datastores.globalConfigDataStore.latest()(env.otoroshiExecutionContext, env).plugins.config.select("StateExporter").asOpt[JsObject] match {
      case None => None
      case Some(config) => config.select("every_sec").asOpt[Int] match {
        case None => None
        case Some(every) => every.seconds.some
      }
    }
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val format = env.datastores.globalConfigDataStore.latest()(env.otoroshiExecutionContext, env)
      .plugins
      .config
      .select("StateExporter").asOpt[JsObject]
      .flatMap(_.select("format").asOpt[String])
      .getOrElse("json")
    format match {
      case "raw" => env.datastores.fullNdJsonExport(100, 1, 4).flatMap { source =>
        source.runFold(Seq.empty[JsValue])(_ :+ _)(env.otoroshiMaterializer).map { raw =>
          FullStateExport(UUID.randomUUID().toString, DateTime.now(), "raw", JsArray(raw)).toAnalytics()
        }
      }
      case _ => env.datastores.globalConfigDataStore.fullExport().map { export =>
        FullStateExport(UUID.randomUUID().toString, DateTime.now(), "json", export).toAnalytics()
      }
    }
  }
}

case class FullStateExport(id: String, timestamp: DateTime, format: String, export: JsValue) extends AnalyticEvent {

  override def `@type`: String = "FullStateExport"
  override def `@id`: String = id
  override def `@timestamp`: DateTime = timestamp
  override def `@service`: String = "Otoroshi"
  override def `@serviceId`: String = "--"
  override def fromOrigin: Option[String] = None
  override def fromUserAgent: Option[String] = None

  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id" -> `@id`,
    "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type" -> `@type`,
    "@product" -> _env.eventsName,
    "@serviceId" -> `@serviceId`,
    "@service" -> `@service`,
    "@env" -> "prod",
    "format" -> format,
    "export" -> export
  )
}
