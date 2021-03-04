package otoroshi.events

import java.io.File
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.util.concurrent.atomic.AtomicReference
import akka.Done
import akka.actor.{Actor, Props}
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{OverflowStrategy, QueueOfferResult}
import com.sksamuel.pulsar4s.Producer
import com.spotify.metrics.core.MetricId
import otoroshi.env.Env
import otoroshi.events.DataExporter.DefaultDataExporter
import otoroshi.events.impl.{ElasticWritesAnalytics, WebHookAnalytics}
import models._
import org.joda.time.DateTime
import otoroshi.models.{DataExporterConfig, Exporter, ExporterRef, FileSettings}
import otoroshi.script._
import otoroshi.utils.mailer.{EmailLocation, MailerSettings}
import play.api.Logger
import play.api.libs.json.{JsArray, JsBoolean, JsNull, JsNumber, JsObject, JsString, JsValue, Json}
import otoroshi.utils.mailer.EmailLocation

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}
import otoroshi.utils.syntax.implicits._

import scala.annotation.tailrec
import scala.collection.JavaConverters._

object OtoroshiEventsActorSupervizer {
  def props(implicit env: Env) = Props(new OtoroshiEventsActorSupervizer(env))
}

case object StartExporters
case object UpdateExporters

class OtoroshiEventsActorSupervizer(env: Env) extends Actor {

  lazy val logger = Logger("otoroshi-events-actor-supervizer")

  implicit val e = env
  implicit val ec = env.analyticsExecutionContext

  val dataExporters: TrieMap[String, DataExporter] = new TrieMap[String, DataExporter]()
  val lastUpdate = new AtomicReference[Long](0L)

  override def receive: Receive = {
    case StartExporters => start()
    case UpdateExporters => updateExporters()
    case evt: OtoroshiEvent =>
      dataExporters.foreach { case (_, exporter) => exporter.publish(evt) }
    case _ =>
  }

  def updateExporters(): Future[Unit] = {
    env.datastores.dataExporterConfigDataStore.findAll().fast.map { exporters =>
      for {
        _ <- Future.sequence(dataExporters.map {
          case (key, c) if !exporters.exists(_.id == key) =>
            logger.debug(s"[OtoroshiEventActor] - Stop exporter ${c.configOpt.map(_.name).getOrElse("no name")}")
            dataExporters.remove(key).map(_.stopExporter()).getOrElse(FastFuture.successful(()))
          case _ => FastFuture.successful(())
        })
        _ <- Future.sequence(exporters.map {
          case config if dataExporters.exists(e => e._1 == config.id && !e._2.configOpt.contains(config)) && !config.enabled =>
            logger.debug(s"[OtoroshiEventActor] - stop exporter ${config.name}")
            dataExporters.remove(config.id).map(_.stopExporter()).getOrElse(FastFuture.successful(()))
          case config if dataExporters.exists(e => e._1 == config.id && !e._2.configOpt.contains(config)) =>
            logger.debug(s"[OtoroshiEventActor] - Update exporter ${config.name}")
            dataExporters.get(config.id).map(_.update(config)).getOrElse(FastFuture.successful(()))
          case config if !dataExporters.contains(config.id) && config.enabled =>
            logger.debug(s"[OtoroshiEventActor] - Start exporter ${config.name}")
            val exporter = config.exporter()
            dataExporters.put(config.id, exporter)
            exporter.startExporter()
          case _ => FastFuture.successful(())
        })
      } yield ()
    }
  }

  def start(): Unit = {
    updateExporters()
  }
}

sealed trait ExportResult

object ExportResult {

  case object ExportResultSuccess extends ExportResult

  case class ExportResultFailure(error: String) extends ExportResult

}

sealed trait DataExporter {
  def exporter[T <: Exporter]: Option[T]

  def configUnsafe: DataExporterConfig

  def configOpt: Option[DataExporterConfig]

  def accept(event: JsValue): Boolean

  def project(event: JsValue): JsValue

  def send(events: Seq[JsValue]): Future[ExportResult]

  def publish(event: OtoroshiEvent): Unit

  def update(config: DataExporterConfig): Future[Unit]

  def startExporter(): Future[Unit]

  def stopExporter(): Future[Unit]

  def start(): Future[Unit] = FastFuture.successful(())

  def stop(): Future[Unit] = FastFuture.successful(())
}

case class CustomDataExporterContext(config: JsValue, exporter: DataExporter)

trait CustomDataExporter extends NamedPlugin with StartableAndStoppable {

  override def pluginType: PluginType = DataExporterType

  def accept(event: JsValue, ctx: CustomDataExporterContext)(implicit env: Env): Boolean

  def project(event: JsValue, ctx: CustomDataExporterContext)(implicit env: Env): JsValue

  def send(events: Seq[JsValue], ctx: CustomDataExporterContext)(implicit ec: ExecutionContext, env: Env): Future[ExportResult]

  def startExporter(ctx: CustomDataExporterContext)(implicit ec: ExecutionContext, env: Env): Future[Unit]

  def stopExporter(ctx: CustomDataExporterContext)(implicit ec: ExecutionContext, env: Env): Future[Unit]
}

object DataExporter {

  abstract class DefaultDataExporter(originalConfig: DataExporterConfig)(implicit ec: ExecutionContext, env: Env) extends DataExporter {

    lazy val ref = new AtomicReference[DataExporterConfig](originalConfig)

    lazy val id = originalConfig.id

    lazy val logger = Logger("otoroshi-data-exporter")

    private val internalQueue = new AtomicReference[(Source[ExportResult, SourceQueueWithComplete[OtoroshiEvent]], SourceQueueWithComplete[OtoroshiEvent], Future[Done])]()

    def setupQueue(): (Source[ExportResult, SourceQueueWithComplete[OtoroshiEvent]], SourceQueueWithComplete[OtoroshiEvent], Future[Done]) = {
      val stream = Source
        .queue[OtoroshiEvent](configUnsafe.bufferSize, OverflowStrategy.dropHead)
        .filter(_ => configOpt.exists(_.enabled))
        .mapAsync(configUnsafe.jsonWorkers)(event => event.toEnrichedJson)
        .filter(event => accept(event))
        .map(event => project(event))
        .groupedWithin(configUnsafe.groupSize, configUnsafe.groupDuration)
        .filterNot(_.isEmpty)
        .mapAsync(configUnsafe.sendWorkers)(events => send(events))

      val (queue, done) = stream.toMat(Sink.ignore)(Keep.both).run()(env.analyticsMaterializer)

      (stream, queue, done)
    }

    def withQueue[A](f: SourceQueueWithComplete[OtoroshiEvent] => A): Unit = {
      Option(internalQueue.get()).foreach(t => f(t._2))
    }

    override def startExporter(): Future[Unit] = {
      val oldQueue = internalQueue.get()
      val newQueue = setupQueue()
      internalQueue.set(newQueue)
      val fuStart = start()
      val endOfOldQueue = Promise[Unit]
      Option(oldQueue) match {
        case None => endOfOldQueue.trySuccess(())
        case Some((_, queue, _)) => {
          queue.watchCompletion().map { _ =>
            endOfOldQueue.trySuccess(())
          }
          queue.complete()
        }
      }
      for {
        _ <- fuStart
        _ <- endOfOldQueue.future
      } yield ()
    }

    override def stopExporter(): Future[Unit] = {
      stop()
    }

    def exporter[T <: Exporter]: Option[T] = Try(ref.get()).map(_.config.asInstanceOf[T]).toOption

    def configUnsafe: DataExporterConfig = ref.get()

    def configOpt: Option[DataExporterConfig] = Option(ref.get())

    def update(config: DataExporterConfig): Future[Unit] = {
      for {
        _ <- stop()
        _ = ref.set(config)
        _ <- start()
      } yield ()
    }

    def accept(event: JsValue): Boolean = {
      (configUnsafe.filtering.include.isEmpty || configUnsafe.filtering.include.exists(i => otoroshi.utils.Match.matches(event, i))) &&
        (configUnsafe.filtering.exclude.isEmpty || configUnsafe.filtering.exclude.exists(i => !otoroshi.utils.Match.matches(event, i)))
    }

    def project(event: JsValue): JsValue = {
      if (configUnsafe.projection.value.isEmpty) {
        event
      } else {
        otoroshi.utils.Projection.project(event, configUnsafe.projection, identity)
      }
    }

    def publish(event: OtoroshiEvent): Unit = {
      if (configOpt.exists(_.enabled)) {
        withQueue { queue =>
          queue.offer(event).andThen {
            case Success(QueueOfferResult.Enqueued) => logger.debug("OTOROSHI_EVENT: Event enqueued")
            case Success(QueueOfferResult.Dropped) =>
              logger.error("OTOROSHI_EVENTS_ERROR: Enqueue Dropped otoroshiEvents :(")
            case Success(QueueOfferResult.QueueClosed) =>
              logger.error("OTOROSHI_EVENTS_ERROR: Queue closed :(")
            // TODO:
            case Success(QueueOfferResult.Failure(t)) =>
              logger.error("OTOROSHI_EVENTS_ERROR: Enqueue Failure otoroshiEvents :(", t)
            // TODO:
            case e =>
              logger.error(s"OTOROSHI_EVENTS_ERROR: otoroshiEvents actor error : ${e}")
            // TODO:
          }
        }
      } else {
        ()
      }
    }
  }

}

object Exporters {

  class ElasticExporter(config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env) extends DefaultDataExporter(config)(ec, env) {

    val clientRef = new AtomicReference[ElasticWritesAnalytics]()

    override def start(): Future[Unit] = {
      exporter[ElasticAnalyticsConfig].foreach { eec =>
        clientRef.set(new ElasticWritesAnalytics(eec, env))
      }
      FastFuture.successful(())
    }

    override def stop(): Future[Unit] = {
      // Option(clientRef.get()).foreach(_.close())
      FastFuture.successful(())
    }

    override def send(events: Seq[JsValue]): Future[ExportResult] = {
      logger.debug(s"sending ${events.size} events to elastic !!!")
      Option(clientRef.get()).map { client =>
        client.publish(events).map(_ => ExportResult.ExportResultSuccess)
      } getOrElse {
        FastFuture.successful(ExportResult.ExportResultFailure("Bad config type !"))
      }
    }
  }

  class WebhookExporter(config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env) extends DefaultDataExporter(config)(ec, env) {
    override def send(events: Seq[JsValue]): Future[ExportResult] = {
      env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
        exporter[Webhook].map { eec =>
          new WebHookAnalytics(eec, globalConfig).publish(events).map(_ => ExportResult.ExportResultSuccess)
        } getOrElse {
          FastFuture.successful(ExportResult.ExportResultFailure("Bad config type !"))
        }
      }
    }
  }

  class KafkaExporter(config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env) extends DefaultDataExporter(config)(ec, env) {

    val clientRef = new AtomicReference[KafkaWrapper]()

    override def start(): Future[Unit] = {
      exporter[KafkaConfig].foreach { eec =>
        clientRef.set(new KafkaWrapper(env.analyticsActorSystem, env, c => c.topic))
      }
      FastFuture.successful(())
    }

    override def stop(): Future[Unit] = {
      Option(clientRef.get()).foreach(_.close())
      FastFuture.successful(())
    }

    override def send(events: Seq[JsValue]): Future[ExportResult] = {
      env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
        Option(clientRef.get()).flatMap(cli => exporter[KafkaConfig].map(conf => (cli, conf))).map {
          case (cli, conf) =>
            Source(events.toList)
              .mapAsync(10)(evt => cli.publish(evt)(env, conf))
              .runWith(Sink.ignore)(env.analyticsMaterializer)
              .map(_ => ExportResult.ExportResultSuccess)
          } getOrElse {
            FastFuture.successful(ExportResult.ExportResultFailure("Bad config type !"))
          }
      }
    }
  }

  class PulsarExporter(config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env) extends DefaultDataExporter(config)(ec, env) {

    val clientRef = new AtomicReference[Producer[JsValue]]()

    override def start(): Future[Unit] = {
      exporter[PulsarConfig].foreach { eec =>
        clientRef.set(PulsarSetting.producer(env, eec))
      }
      FastFuture.successful(())
    }

    override def stop(): Future[Unit] = {
      Option(clientRef.get()).map(_.closeAsync).getOrElse(FastFuture.successful(()))
    }

    override def send(events: Seq[JsValue]): Future[ExportResult] = {
      Option(clientRef.get()).map { cli =>
        Source(events.toList)
          .mapAsync(10)(evt => cli.sendAsync(evt))
          .runWith(Sink.ignore)(env.analyticsMaterializer)
          .map(_ => ExportResult.ExportResultSuccess)
      } getOrElse {
        FastFuture.successful(ExportResult.ExportResultFailure("Bad config type !"))
      }
    }
  }

  class ConsoleExporter(config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env) extends DefaultDataExporter(config)(ec, env) {
    override def send(events: Seq[JsValue]): Future[ExportResult] = {
      events.foreach(e => logger.info(Json.stringify(e)))
      FastFuture.successful(ExportResult.ExportResultSuccess)
    }
  }

  class MetricsExporter(config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env) extends DefaultDataExporter(config)(ec, env) {

    private def incGlobalOtoroshiMetrics(duration: Long, overheadWoCb: Long, cbDuration: Long, overhead: Long, dataIn: Long, dataOut: Long): Unit = {
      env.metrics.counter(MetricId.build("otoroshi.requests.count").tagged("serviceName", "otoroshi")).inc()
      env.metrics.histogram(MetricId.build("otoroshi.requests.duration.millis").tagged("serviceName", "otoroshi")).update(duration)
      env.metrics.histogram(MetricId.build("otoroshi.requests.overheadWoCb.millis").tagged("serviceName", "otoroshi")).update(overheadWoCb)
      env.metrics.histogram(MetricId.build("otoroshi.requests.cbDuration.millis").tagged("serviceName", "otoroshi")).update(cbDuration)
      env.metrics.histogram(MetricId.build("otoroshi.requests.overhead.millis").tagged("serviceName", "otoroshi")).update(overhead)
      env.metrics.histogram(MetricId.build("otoroshi.requests.data.in.bytes").tagged("serviceName", "otoroshi")).update(dataIn)
      env.metrics.histogram(MetricId.build("otoroshi.requests.data.out.bytes").tagged("serviceName", "otoroshi")).update(dataOut)
    }

    // @tailrec
    // private def getValueWithPath(path: String, value: JsValue): String = {
    //   val idx = path.indexOf(".")
    //   if(idx != -1) {
    //     getValueWithPath(path.substring(idx+1), (value \ path.substring(0, idx)).as[JsObject])
    //   } else {
    //     getStringOrJsObject(value, path)
    //   }
    // }

    // private def getStringOrJsObject(value: JsValue, path: String): String = {
    //   (value \ path).asOpt[String] match {
    //     case Some(value) => value
    //     case _ => (value \ path).as[JsObject].toString
    //   }
    // }

    private def getValueAt(value: JsValue, path: String): String = {
      value.at(path).asOpt[JsValue] match {
        case Some(JsString(value)) => value
        case Some(JsBoolean(value)) => value.toString
        case Some(JsNumber(value)) => value.toString()
        case Some(value @ JsObject(_)) => value.stringify
        case Some(value @ JsArray(_)) => value.stringify
        case Some(JsNull) => "null"
        case _ => "--"
      }
    }

    override def send(events: Seq[JsValue]): Future[ExportResult] = {

      val labels = (config.config.toJson \ "labels").as[Map[String, String]]
      val sortedLabels = labels.partition(_._1.contains("."))

      events.foreach { event =>
        if ((event \ "@type").as[String] == "GatewayEvent") {
          Try {
            val duration = (event \ "duration").asOpt[Long].getOrElse(0L)
            val dataIn = (event \ "data" \ "dataIn").asOpt[Long].getOrElse(0L)
            val dataOut = (event \ "data" \ "dataOut").asOpt[Long].getOrElse(0L)
            val overheadWoCb = (event \ "overheadWoCb").asOpt[Long].getOrElse(0L)
            val cbDuration = (event \ "cbDuration").asOpt[Long].getOrElse(0L)
            val overhead = (event \ "overhead").asOpt[Long].getOrElse(0L)

            var tags: Map[String, String] = Map()

            sortedLabels._1.foreach(objectlabel => {
              tags += (objectlabel._2.trim -> getValueAt(event, objectlabel._1.trim.replace("$at", "@"))) // getValueWithPath(objectlabel._1.trim.replace("$at", "@"), event))
            })

            sortedLabels._2.foreach(primitiveLabel => {
              tags += (primitiveLabel._2.trim -> getValueAt(event, primitiveLabel._1.trim.replace("$at", "@"))) // getStringOrJsObject(event, primitiveLabel._1.trim.replace("$at", "@")))
            })

            incGlobalOtoroshiMetrics(duration, overheadWoCb, cbDuration, overhead, dataIn, dataOut)
            env.metrics.counter(MetricId.build(s"otoroshi.service.requests.count").tagged(tags.asJava)).inc()
            env.metrics.histogram(MetricId.build(s"otoroshi.service.requests.duration.millis").tagged(tags.asJava)).update(duration)
            env.metrics.histogram(MetricId.build(s"otoroshi.service.requests.overheadWoCb.millis").tagged(tags.asJava)).update(overheadWoCb)
            env.metrics.histogram(MetricId.build(s"otoroshi.service.requests.cbDuration.millis").tagged(tags.asJava)).update(cbDuration)
            env.metrics.histogram(MetricId.build(s"otoroshi.service.requests.overhead.millis").tagged(tags.asJava)).update(overhead)
            env.metrics.histogram(MetricId.build(s"otoroshi.service.requests.data.in.bytes").tagged(tags.asJava)).update(dataIn)
            env.metrics.histogram(MetricId.build(s"otoroshi.service.requests.data.out.bytes").tagged(tags.asJava)).update(dataOut)
          } match {
            case Failure(e) => logger.error("error while collection tags", e)
            case _ =>
          }
        }
      }

      FastFuture.successful(ExportResult.ExportResultSuccess)
    }
  }

  class CustomExporter(config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env) extends DefaultDataExporter(config)(ec, env) {

    def withCurrentExporter[A](f: CustomDataExporter => A): Option[A] = {
      val ref = exporter[ExporterRef].get.ref
      env.scriptManager.getAnyScript[CustomDataExporter](ref) match {
        case Left(err) => None
        case Right(exp) => f(exp).some
      }
    }

    override def accept(event: JsValue): Boolean = withCurrentExporter(_.accept(event, CustomDataExporterContext(exporter[ExporterRef].get.config, this))).getOrElse(false)

    override def project(event: JsValue): JsValue = withCurrentExporter(_.project(event,CustomDataExporterContext(exporter[ExporterRef].get.config, this))).getOrElse(JsNull)

    override def send(events: Seq[JsValue]): Future[ExportResult] = withCurrentExporter(_.send(events, CustomDataExporterContext(exporter[ExporterRef].get.config, this))).getOrElse(ExportResult.ExportResultFailure("exporter not found !").future)

    override def start(): Future[Unit] = withCurrentExporter(_.startExporter(CustomDataExporterContext(exporter[ExporterRef].get.config, this))).getOrElse(().future)

    override def stop(): Future[Unit] = withCurrentExporter(_.stopExporter(CustomDataExporterContext(exporter[ExporterRef].get.config, this))).getOrElse(().future)
  }

  class GenericMailerExporter(config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env) extends DefaultDataExporter(config)(ec, env) {
    override def send(events: Seq[JsValue]): Future[ExportResult] = {
      def sendEmail(gms: MailerSettings, globalConfig: GlobalConfig): Future[Unit] = {
        val titles = events
          .map { jsonEvt =>
            val date = new DateTime((jsonEvt \ "@timestamp").as[Long])
            val id = (jsonEvt \ "@id").as[String]
            s"""<li><a href="#$id">""" + (jsonEvt \ "alert")
              .asOpt[String]
              .getOrElse("Unkown alert") + s" - ${date.toString()}</a></li>"
          }
          .mkString("<ul>", "\n", "</ul>")

        val email = events
          .map { jsonEvt =>
            val alert = (jsonEvt \ "alert").asOpt[String].getOrElse("Unkown alert")
            val date = new DateTime((jsonEvt \ "@timestamp").as[Long])
            val id = (jsonEvt \ "@id").as[String]
            s"""<h3 id="$id">$alert - ${date.toString()}</h3><pre>${Json.prettyPrint(jsonEvt)}</pre><br/>"""
          }
          .mkString("\n")

        val emailBody =
          s"""<p>${events.size} new events occured on Otoroshi, you can visualize it on the <a href="${env.rootScheme}${env.backOfficeHost}/">Otoroshi Dashboard</a></p>
             |$titles
             |$email
                 """
        gms.asMailer(globalConfig, env).send(
          from = EmailLocation("Otoroshi Alerts", s"otoroshi-alerts@${env.domain}"),
          to = gms.to,
          subject = s"Otoroshi Alert - ${events.size} new alerts",
          html = emailBody
        )
      }

      env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
        exporter[MailerSettings].map { eec =>
          sendEmail(eec, globalConfig).map(_ => ExportResult.ExportResultSuccess)
        } getOrElse {
          FastFuture.successful(ExportResult.ExportResultFailure("Bad config type !"))
        }
      }
    }
  }

  class FileAppenderExporter(config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env) extends DefaultDataExporter(config)(ec, env) {
    override def send(events: Seq[JsValue]): Future[ExportResult] = {
      exporter[FileSettings].map { exporterConfig =>
        val contentToAppend = events.map(Json.stringify).mkString("\r\n")
        val path = Paths.get(exporterConfig.path.replace("{day}", DateTime.now().toString("yyyy-MM-dd")))
        val file = path.toFile
        if (!file.exists()) {
          file.createNewFile()
        } else {
          if (file.length() > exporterConfig.maxFileSize) {
            val parts = file.getName.split("\\.")
            val filename = parts.head
            val ext = parts.last
            file.renameTo(new File(file.getParent, filename + "." + System.currentTimeMillis() + "." + ext))
            file.createNewFile()
          }
        }
        Files.write(path, contentToAppend.getBytes(), StandardOpenOption.APPEND)
        FastFuture.successful(ExportResult.ExportResultSuccess)
      } getOrElse {
        FastFuture.successful(ExportResult.ExportResultFailure("Bad config type !"))
      }
    }
  }
}

class DataExporterUpdateJob extends Job {

  private val logger = Logger("otoroshi-data-exporter-update-job")

  override def uniqueId: JobId = JobId("io.otoroshi.core.events.DataExporterUpdateJob")

  override def name: String = "Otoroshi data exporter update job"

  override def visibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation = JobInstantiation.OneInstancePerOtoroshiInstance

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    FastFuture.successful(env.otoroshiEventsActor ! UpdateExporters)
  }
}