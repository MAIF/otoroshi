package events

import java.io.File
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import akka.Done
import akka.actor.{Actor, Props}
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture.EnhancedFuture
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{OverflowStrategy, QueueOfferResult, scaladsl}
import env.Env
import events.DataExporter.DefaultDataExporter
import events.impl.{ElasticWritesAnalytics, WebHookAnalytics}
import models._
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import utils.{EmailLocation, MailerSettings}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Success, Try}

object OtoroshiEventsActorSupervizer {
  def props(implicit env: Env) = Props(new OtoroshiEventsActorSupervizer(env))
}

case class StartExporters()

class OtoroshiEventsActorSupervizer(env: Env) extends Actor {

  lazy val logger = Logger("otoroshi-events-actor-supervizer")

  implicit val e = env
  implicit val ec = env.analyticsExecutionContext

  val dataExporters: TrieMap[String, DataExporter] = new TrieMap[String, DataExporter]()
  val lastUpdate = new AtomicReference[Long](0L)

  override def receive: Receive = {
    case StartExporters() => start()
    case evt: OtoroshiEvent =>
      dataExporters.foreach { case (_, exporter) => exporter.publish(evt) }
      if ((lastUpdate.get() + 10000) < System.currentTimeMillis()) { // TODO: from config
        updateExporters() // TODO: move to a job ????
      }
    case _ =>
  }

  def updateExporters(): Future[Unit] = {
    env.datastores.dataExporterConfigDataStore.findAll().fast.map { exporters =>
      for {
        _ <- Future.sequence(dataExporters.map {
          case (key, _) if exporters.exists(_.id == key) =>
            dataExporters.remove(key).map(_.stopExporter()).getOrElse(FastFuture.successful(()))
          case _ => FastFuture.successful(())
        })
        _ <- Future.sequence(exporters.map {
          case config if dataExporters.exists(e => e._1 == config.id && e._2.configOpt.contains(config)) =>
            dataExporters.get(config.id).map(_.update(config)).getOrElse(FastFuture.successful(()))
          case config if !dataExporters.contains(config.id) =>
            val exporter = config.exporter()
            dataExporters.put(config.id, exporter)
            exporter.startExporter()
          case _ => FastFuture.successful(())
        })
        _ = lastUpdate.set(System.currentTimeMillis())
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

    //lazy val stream: Source[ExportResult, SourceQueueWithComplete[OtoroshiEvent]] = Source
    //  .queue[OtoroshiEvent](configUnsafe.bufferSize, OverflowStrategy.dropHead)
    //  .filter(_ => configOpt.exists(_.enabled))
    //  .mapAsync(configUnsafe.jsonWorkers)(event => event.toEnrichedJson)
    //  .filter(event => accept(event))
    //  .map(event => project(event))
    //  .groupedWithin(configUnsafe.groupSize, configUnsafe.groupDuration)
    //  .filterNot(_.isEmpty)
    //  .mapAsync(configUnsafe.sendWorkers)(events => send(events))

    //lazy val (queue, done) = stream.toMat(Sink.ignore)(Keep.both).run()(env.analyticsMaterializer)

    override def startExporter(): Future[Unit] = {
      val newQueue = setupQueue()
      val endOfOldQueue = Promise[Unit]
      Option(internalQueue.get()) match {
        case None => endOfOldQueue.trySuccess(())
        case Some((_, queue, _)) => {
          queue.watchCompletion().map { _ =>
            endOfOldQueue.trySuccess(())
          }
          queue.complete()
        }
      }
      endOfOldQueue.future.flatMap { _ =>
        internalQueue.set(newQueue)
        start()
      }
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
      configUnsafe.filtering.include.exists(i => otoroshi.utils.Match.matches(event, i)) &&
        configUnsafe.filtering.exclude.exists(i => !otoroshi.utils.Match.matches(event, i))
    }

    def project(event: JsValue): JsValue = {
      if (configUnsafe.projection.value.isEmpty) {
        event
      } else {
        otoroshi.utils.Project.project(event, configUnsafe.projection)
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
    override def send(events: Seq[JsValue]): Future[ExportResult] = {
      exporter[ElasticAnalyticsConfig].map { eec =>
        new ElasticWritesAnalytics(eec, env).publish(events).map(_ => ExportResult.ExportResultSuccess)
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
    override def send(events: Seq[JsValue]): Future[ExportResult] = {
      env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
        exporter[KafkaConfig].map { eec =>
          lazy val kafkaWrapper = new KafkaWrapper(env.analyticsActorSystem, env, c => c.topic)

          Source(events.toList)
            .mapAsync(10)(evt => kafkaWrapper.publish(evt)(env, eec))
            .runWith(Sink.ignore)(env.analyticsMaterializer)
            .map(_ => ExportResult.ExportResultSuccess)
        } getOrElse {
          FastFuture.successful(ExportResult.ExportResultFailure("Bad config type !"))
        }
      }
    }
  }

  class PulsarExporter(config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env) extends DefaultDataExporter(config)(ec, env) {
    override def send(events: Seq[JsValue]): Future[ExportResult] = {
      exporter[PulsarConfig].map { eec =>
        lazy val pulsarProducer = PulsarSetting.producer(env, eec)

        Source(events.toList)
          .mapAsync(10)(evt => pulsarProducer.sendAsync(evt))
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