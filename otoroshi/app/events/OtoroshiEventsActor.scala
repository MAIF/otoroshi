package otoroshi.events

import java.io.{File, FilenameFilter}
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import akka.Done
import akka.actor.{Actor, Props}
import akka.http.scaladsl.model.{ContentType, ContentTypes}
import akka.http.scaladsl.util.FastFuture
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.alpakka.s3.{
  ApiVersion,
  ListBucketResultContents,
  MemoryBufferType,
  MetaHeaders,
  S3Attributes,
  S3Settings
}
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{Attributes, OverflowStrategy, QueueOfferResult}
import com.sksamuel.pulsar4s.Producer
import com.spotify.metrics.core.MetricId
import otoroshi.env.Env
import otoroshi.events.DataExporter.DefaultDataExporter
import otoroshi.events.impl.{ElasticWritesAnalytics, WebHookAnalytics}
import otoroshi.models._
import org.joda.time.DateTime
import otoroshi.models.{DataExporterConfig, Exporter, ExporterRef, FileSettings}
import otoroshi.next.events.TrafficCaptureEvent
import otoroshi.next.plugins.FakeWasmContext
import otoroshi.next.plugins.api.NgPluginCategory
import otoroshi.script._
import otoroshi.security.IdGenerator
import otoroshi.storage.drivers.inmemory.S3Configuration
import otoroshi.utils.TypedMap
import otoroshi.utils.cache.types.LegitTrieMap
import otoroshi.utils.json.JsonOperationsHelper
import otoroshi.utils.mailer.{EmailLocation, MailerSettings}
import play.api.Logger
import play.api.libs.json.{
  Format,
  JsArray,
  JsBoolean,
  JsError,
  JsNull,
  JsNumber,
  JsObject,
  JsResult,
  JsString,
  JsSuccess,
  JsValue,
  Json
}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm.{WasmConfig, WasmUtils}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.providers.AwsRegionProvider

import java.util.concurrent.{Executors, TimeUnit}
import scala.collection.JavaConverters._

object OtoroshiEventsActorSupervizer {
  def props(implicit env: Env) = Props(new OtoroshiEventsActorSupervizer(env))
}

case object StartExporters
case object StopExporters
case object UpdateExporters

class OtoroshiEventsActorSupervizer(env: Env) extends Actor {

  lazy val logger = Logger("otoroshi-events-actor-supervizer")

  implicit val e  = env
  implicit val ec = env.analyticsExecutionContext

  val dataExporters: TrieMap[String, DataExporter] = new LegitTrieMap[String, DataExporter]()
  val lastUpdate                                   = new AtomicReference[Long](0L)

  override def receive: Receive = {
    case StartExporters     => start()
    case StopExporters      => stop()
    case UpdateExporters    => updateExporters()
    case evt: OtoroshiEvent =>
      dataExporters.foreach { case (_, exporter) => exporter.publish(evt) }
    case _                  =>
  }

  def updateExporters(): Future[Unit] = {
    env.proxyState.allDataExporters().vfuture.map { exporters =>
      for {
        _ <- Future.sequence(dataExporters.map {
               case (key, c) if !exporters.exists(e => e.id == c.configUnsafe.id || e.id == key) =>
                 if (logger.isDebugEnabled)
                   logger.debug(s"[OtoroshiEventActor] - Stop exporter ${c.configOpt.map(_.name).getOrElse("no name")}")
                 dataExporters.remove(key).map(_.stopExporter()).getOrElse(FastFuture.successful(()))
               case _                                                                            => FastFuture.successful(())
             })
        _ <- Future.sequence(exporters.map {
               case config
                   if dataExporters
                     .exists(e => e._1 == config.id && !e._2.configOpt.contains(config)) && !config.enabled =>
                 if (logger.isDebugEnabled)
                   logger.debug(s"[OtoroshiEventActor] - stop exporter ${config.name} - ${config.id}")
                 dataExporters.remove(config.id).map(_.stopExporter()).getOrElse(FastFuture.successful(()))
               case config if dataExporters.exists(e => e._1 == config.id && !e._2.configOpt.contains(config)) =>
                 if (logger.isDebugEnabled)
                   logger.debug(s"[OtoroshiEventActor] - update exporter ${config.name} - ${config.id}")
                 dataExporters.get(config.id).map(_.update(config)).getOrElse(FastFuture.successful(()))
               case config if !dataExporters.contains(config.id) && config.enabled                             =>
                 if (logger.isDebugEnabled)
                   logger.debug(s"[OtoroshiEventActor] - start exporter ${config.name} - ${config.id}")
                 val exporter = config.exporter()
                 dataExporters.put(config.id, exporter)
                 exporter.startExporter()
               case _                                                                                          => FastFuture.successful(())
             })
      } yield ()
    }
  }

  def start(): Unit = {
    updateExporters()
  }

  def stop(): Unit = {
    env.proxyState.allDataExporters().vfuture.map { exporters =>
      for {
        _ <- Future.sequence(dataExporters.map { case (key, c) =>
               c.stopExporter()
             })
      } yield ()
    }
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

  def sendWithSource(events: Seq[JsValue], rawEvents: Seq[OtoroshiEvent]): Future[ExportResult] = send(events)

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

  override def pluginType: PluginType = PluginType.DataExporterType

  def accept(event: JsValue, ctx: CustomDataExporterContext)(implicit env: Env): Boolean

  def project(event: JsValue, ctx: CustomDataExporterContext)(implicit env: Env): JsValue

  def send(events: Seq[JsValue], ctx: CustomDataExporterContext)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[ExportResult]

  def startExporter(ctx: CustomDataExporterContext)(implicit ec: ExecutionContext, env: Env): Future[Unit]

  def stopExporter(ctx: CustomDataExporterContext)(implicit ec: ExecutionContext, env: Env): Future[Unit]
}

object DataExporter {

  def acceptEvent(event: JsValue, configUnsafe: DataExporterConfig, logger: Logger): Boolean = {
    try {
      (configUnsafe.filtering.include.isEmpty || configUnsafe.filtering.include.exists(i =>
        otoroshi.utils.Match.matches(event, i)
      )) &&
      (configUnsafe.filtering.exclude.isEmpty || !configUnsafe.filtering.exclude.exists(i =>
        otoroshi.utils.Match.matches(event, i)
      ))
    } catch {
      case t: Throwable =>
        logger.error("error while accepting event", t)
        false
    }
  }

  case class RetryEvent(val raw: JsValue) extends OtoroshiEvent {
    override def `@id`: String                       = raw.select("@id").asOpt[String].getOrElse(IdGenerator.uuid)
    override def `@timestamp`: DateTime              =
      raw.select("@timestamp").asOpt[String].map(DateTime.parse).getOrElse(DateTime.now())
    override def toJson(implicit _env: Env): JsValue = raw
  }

  abstract class DefaultDataExporter(originalConfig: DataExporterConfig)(implicit ec: ExecutionContext, env: Env)
      extends DataExporter {

    lazy val ref = new AtomicReference[DataExporterConfig](originalConfig)

    lazy val id = originalConfig.id

    lazy val logger = Logger("otoroshi-data-exporter")

    private val internalQueue = new AtomicReference[
      (
          Source[ExportResult, SourceQueueWithComplete[OtoroshiEvent]],
          SourceQueueWithComplete[OtoroshiEvent],
          Future[Done]
      )
    ]()

    def setupQueue(): (
        Source[ExportResult, SourceQueueWithComplete[OtoroshiEvent]],
        SourceQueueWithComplete[OtoroshiEvent],
        Future[Done]
    ) = {
      val stream = Source
        .queue[OtoroshiEvent](configUnsafe.bufferSize, OverflowStrategy.dropHead)
        .filter(_ => configOpt.exists(_.enabled))
        .mapAsync(configUnsafe.jsonWorkers)(event => event.toEnrichedJson.map(js => (js, event)))
        .filter { case (event, _) => accept(event) }
        .map { case (event, rawEvent) => (project(event), rawEvent) }
        .groupedWithin(configUnsafe.groupSize, configUnsafe.groupDuration)
        .filterNot(_.isEmpty)
        .mapAsync(configUnsafe.sendWorkers) { items =>
          val events    = items.map(_._1)
          val rawEvents = items.map(_._2)
          Try(sendWithSource(events, rawEvents).recover { case e: Throwable =>
            val message = s"error while sending events on ${id} of kind ${this.getClass.getName}"
            logger.error(message, e)
            withQueue { queue => events.foreach(e => queue.offer(RetryEvent(e))) }
            ExportResult.ExportResultFailure(s"$message: ${e.getMessage}")
          }) match {
            case Failure(e) =>
              val message = s"error while sending events on ${id} of kind ${this.getClass.getName}"
              logger.error(message, e)
              withQueue { queue => events.foreach(e => queue.offer(RetryEvent(e))) }
              ExportResult.ExportResultFailure(s"$message: ${e.getMessage}").vfuture
            case Success(f) => f
          }
        }

      val (queue, done) = stream.toMat(Sink.ignore)(Keep.both).run()(env.analyticsMaterializer)

      (stream, queue, done)
    }

    def withQueue[A](f: SourceQueueWithComplete[OtoroshiEvent] => A): Unit = {
      Option(internalQueue.get()).foreach(t => f(t._2))
    }

    override def startExporter(): Future[Unit] = {
      val oldQueue      = internalQueue.get()
      val newQueue      = setupQueue()
      internalQueue.set(newQueue)
      val fuStart       = start()
      val endOfOldQueue = Promise[Unit]
      Option(oldQueue) match {
        case None                => endOfOldQueue.trySuccess(())
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
        _  = ref.set(config)
        _ <- start()
      } yield ()
    }

    def accept(event: JsValue): Boolean = {
      acceptEvent(event, configUnsafe, logger)
      // try {
      //   (configUnsafe.filtering.include.isEmpty || configUnsafe.filtering.include.exists(i =>
      //     otoroshi.utils.Match.matches(event, i)
      //   )) &&
      //   (configUnsafe.filtering.exclude.isEmpty || configUnsafe.filtering.exclude.exists(i =>
      //     !otoroshi.utils.Match.matches(event, i)
      //   ))
      // } catch {
      //   case t: Throwable =>
      //     logger.error("error while accepting event", t)
      //     false
      // }
    }

    def project(event: JsValue): JsValue = {
      try {
        if (configUnsafe.projection.value.isEmpty) {
          event
        } else {
          otoroshi.utils.Projection.project(event, configUnsafe.projection, identity)
        }
      } catch {
        case t: Throwable =>
          logger.error("error while projecting event", t)
          event
      }
    }

    def publish(event: OtoroshiEvent): Unit = {
      if (configOpt.exists(_.enabled)) {
        withQueue { queue =>
          queue.offer(event).andThen {
            case Success(QueueOfferResult.Enqueued)    =>
              if (logger.isDebugEnabled) logger.debug("OTOROSHI_EVENT: Event enqueued")
            case Success(QueueOfferResult.Dropped)     =>
              logger.error("OTOROSHI_EVENTS_ERROR: Enqueue Dropped otoroshiEvents :(")
            case Success(QueueOfferResult.QueueClosed) =>
              logger.error("OTOROSHI_EVENTS_ERROR: Queue closed :(")
            // TODO:
            case Success(QueueOfferResult.Failure(t))  =>
              logger.error("OTOROSHI_EVENTS_ERROR: Enqueue Failure otoroshiEvents :(", t)
            // TODO:
            case e                                     =>
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

  class ElasticExporter(config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env)
      extends DefaultDataExporter(config)(ec, env) {

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
      if (logger.isDebugEnabled) logger.debug(s"sending ${events.size} events to elastic !!!")
      Option(clientRef.get()).map { client =>
        client.publish(events).map(_ => ExportResult.ExportResultSuccess)
      } getOrElse {
        FastFuture.successful(ExportResult.ExportResultFailure("Bad config type !"))
      }
    }
  }

  class WebhookExporter(config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env)
      extends DefaultDataExporter(config)(ec, env) {
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

  class KafkaExporter(config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env)
      extends DefaultDataExporter(config)(ec, env) {

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
        Option(clientRef.get()).flatMap(cli => exporter[KafkaConfig].map(conf => (cli, conf))).map { case (cli, conf) =>
          Source(events.toList)
            .mapAsync(10)(evt => cli.publish(evt)(env, conf.copy(sendEvents = true)))
            .runWith(Sink.ignore)(env.analyticsMaterializer)
            .map(_ => ExportResult.ExportResultSuccess)
        } getOrElse {
          FastFuture.successful(ExportResult.ExportResultFailure("Bad config type !"))
        }
      }
    }
  }

  class PulsarExporter(config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env)
      extends DefaultDataExporter(config)(ec, env) {

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

  class ConsoleExporter(config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env)
      extends DefaultDataExporter(config)(ec, env) {
    override def send(events: Seq[JsValue]): Future[ExportResult] = {
      events.foreach(e => logger.info(Json.stringify(e)))
      FastFuture.successful(ExportResult.ExportResultSuccess)
    }
  }

  class MetricsExporter(_config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env)
      extends DefaultDataExporter(_config)(ec, env) {

    private def incGlobalOtoroshiMetrics(
        duration: Long,
        overheadWoCb: Long,
        cbDuration: Long,
        overhead: Long,
        dataIn: Long,
        dataOut: Long
    ): Unit = {
      env.metrics.counterInc(MetricId.build("otoroshi.requests.count").tagged("serviceName", "otoroshi"))
      env.metrics
        .histogramUpdate(
          MetricId.build("otoroshi.requests.duration.millis").tagged("serviceName", "otoroshi"),
          duration
        )
      env.metrics
        .histogramUpdate(
          MetricId.build("otoroshi.requests.overheadWoCb.millis").tagged("serviceName", "otoroshi"),
          overheadWoCb
        )
      env.metrics
        .histogramUpdate(
          MetricId.build("otoroshi.requests.cbDuration.millis").tagged("serviceName", "otoroshi"),
          cbDuration
        )
      env.metrics
        .histogramUpdate(
          MetricId.build("otoroshi.requests.overhead.millis").tagged("serviceName", "otoroshi"),
          overhead
        )
      env.metrics
        .histogramUpdate(MetricId.build("otoroshi.requests.data.in.bytes").tagged("serviceName", "otoroshi"), dataIn)
      env.metrics
        .histogramUpdate(MetricId.build("otoroshi.requests.data.out.bytes").tagged("serviceName", "otoroshi"), dataOut)
      val perSec = env.metrics.getMeanCallsOf(s"otoroshi.requests.per.sec.technical")
      env.metrics
        .histogramUpdate(MetricId.build(s"otoroshi.requests.per.sec").tagged("serviceName", "otoroshi"), perSec.toInt)
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
        case Some(JsString(value))     => value
        case Some(JsBoolean(value))    => value.toString
        case Some(JsNumber(value))     => value.toString()
        case Some(value @ JsObject(_)) => value.stringify
        case Some(value @ JsArray(_))  => value.stringify
        case Some(JsNull)              => "null"
        case _                         => "--"
      }
    }

    override def send(events: Seq[JsValue]): Future[ExportResult] = exporter[MetricsSettings]
      .map { exporterConfig =>
        val labels       = exporterConfig.labels // (config.config.toJson \ "labels").as[Map[String, String]]
        val sortedLabels = labels.partition(_._1.contains("."))

        events.foreach { event =>
          if ((event \ "@type").as[String] == "GatewayEvent") {
            Try {
              val duration     = (event \ "duration").asOpt[Long].getOrElse(0L)
              val dataIn       = (event \ "data" \ "dataIn").asOpt[Long].getOrElse(0L)
              val dataOut      = (event \ "data" \ "dataOut").asOpt[Long].getOrElse(0L)
              val overheadWoCb = (event \ "overheadWoCb").asOpt[Long].getOrElse(0L)
              val cbDuration   = (event \ "cbDuration").asOpt[Long].getOrElse(0L)
              val overhead     = (event \ "overhead").asOpt[Long].getOrElse(0L)
              val serviceId    = (event \ "@serviceId").asOpt[String].getOrElse("global")

              var tags: Map[String, String] = Map()

              sortedLabels._1.foreach(objectlabel => {
                tags += (objectlabel._2.trim -> getValueAt(
                  event,
                  objectlabel._1.trim.replace("$at", "@")
                )) // getValueWithPath(objectlabel._1.trim.replace("$at", "@"), event))
              })

              sortedLabels._2.foreach(primitiveLabel => {
                tags += (primitiveLabel._2.trim -> getValueAt(
                  event,
                  primitiveLabel._1.trim.replace("$at", "@")
                )) // getStringOrJsObject(event, primitiveLabel._1.trim.replace("$at", "@")))
              })

              incGlobalOtoroshiMetrics(duration, overheadWoCb, cbDuration, overhead, dataIn, dataOut)
              env.metrics.counterInc(MetricId.build(s"otoroshi.service.requests.count").tagged(tags.asJava))
              env.metrics
                .histogramUpdate(
                  MetricId.build(s"otoroshi.service.requests.duration.millis").tagged(tags.asJava),
                  duration
                )
              env.metrics
                .histogramUpdate(
                  MetricId.build(s"otoroshi.service.requests.overheadWoCb.millis").tagged(tags.asJava),
                  overheadWoCb
                )
              env.metrics
                .histogramUpdate(
                  MetricId.build(s"otoroshi.service.requests.cbDuration.millis").tagged(tags.asJava),
                  cbDuration
                )
              env.metrics
                .histogramUpdate(
                  MetricId.build(s"otoroshi.service.requests.overhead.millis").tagged(tags.asJava),
                  overhead
                )
              env.metrics
                .histogramUpdate(MetricId.build(s"otoroshi.service.requests.data.in.bytes").tagged(tags.asJava), dataIn)
              env.metrics
                .histogramUpdate(
                  MetricId.build(s"otoroshi.service.requests.data.out.bytes").tagged(tags.asJava),
                  dataOut
                )
              val perSec = env.metrics.getMeanCallsOf(s"otoroshi.service.requests.per.sec.${serviceId}")
              env.metrics
                .histogramUpdate(MetricId.build(s"otoroshi.service.requests.per.sec").tagged(tags.asJava), perSec.toInt)
            } match {
              case Failure(e) => logger.error("error while collection tags", e)
              case _          =>
            }
          }
        }

        FastFuture.successful(ExportResult.ExportResultSuccess)
      }
      .getOrElse(ExportResult.ExportResultFailure("Bad config.").vfuture)
  }

  class CustomExporter(config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env)
      extends DefaultDataExporter(config)(ec, env) {

    def withCurrentExporter[A](f: CustomDataExporter => A): Option[A] = {
      val ref = exporter[ExporterRef].get.ref
      env.scriptManager.getAnyScript[CustomDataExporter](ref) match {
        case Left(err)  => None
        case Right(exp) => f(exp).some
      }
    }

    override def accept(event: JsValue): Boolean =
      withCurrentExporter(_.accept(event, CustomDataExporterContext(exporter[ExporterRef].get.config, this)))
        .getOrElse(false)

    override def project(event: JsValue): JsValue =
      withCurrentExporter(_.project(event, CustomDataExporterContext(exporter[ExporterRef].get.config, this)))
        .getOrElse(JsNull)

    override def send(events: Seq[JsValue]): Future[ExportResult] =
      withCurrentExporter(_.send(events, CustomDataExporterContext(exporter[ExporterRef].get.config, this)))
        .getOrElse(ExportResult.ExportResultFailure("exporter not found !").future)

    override def start(): Future[Unit] =
      withCurrentExporter(_.startExporter(CustomDataExporterContext(exporter[ExporterRef].get.config, this)))
        .getOrElse(().future)

    override def stop(): Future[Unit] =
      withCurrentExporter(_.stopExporter(CustomDataExporterContext(exporter[ExporterRef].get.config, this)))
        .getOrElse(().future)
  }

  class GenericMailerExporter(config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env)
      extends DefaultDataExporter(config)(ec, env) {
    override def send(events: Seq[JsValue]): Future[ExportResult] = {
      def sendEmail(gms: MailerSettings, globalConfig: GlobalConfig): Future[Unit] = {
        val titles = events
          .map { jsonEvt =>
            val date = new DateTime((jsonEvt \ "@timestamp").as[Long])
            val id   = (jsonEvt \ "@id").as[String]
            s"""<li><a href="#$id">""" + (jsonEvt \ "alert")
              .asOpt[String]
              .getOrElse("Unkown alert") + s" - ${date.toString()}</a></li>"
          }
          .mkString("<ul>", "\n", "</ul>")

        val email = events
          .map { jsonEvt =>
            val alert = (jsonEvt \ "alert").asOpt[String].getOrElse("Unkown alert")
            val date  = new DateTime((jsonEvt \ "@timestamp").as[Long])
            val id    = (jsonEvt \ "@id").as[String]
            s"""<h3 id="$id">$alert - ${date.toString()}</h3><pre>${Json.prettyPrint(jsonEvt)}</pre><br/>"""
          }
          .mkString("\n")

        val emailBody =
          s"""<p>${events.size} new events occured on Otoroshi, you can visualize it on the <a href="${env.rootScheme}${env.backOfficeHost}/">Otoroshi Dashboard</a></p>
             |$titles
             |$email
                 """
        gms
          .asMailer(globalConfig, env)
          .send(
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

  object FileWriting {
    val blockingEc = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))
  }

  class GoReplayFileAppenderExporter(config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env)
      extends DefaultDataExporter(config)(ec, env) {

    override def send(events: Seq[JsValue]): Future[ExportResult] = throw new RuntimeException(
      "send is not supported !!!"
    )

    override def sendWithSource(__events: Seq[JsValue], rawEvents: Seq[OtoroshiEvent]): Future[ExportResult] = {
      exporter[GoReplayFileSettings].map { exporterConfig =>
        val path = Paths.get(
          exporterConfig.path
            .replace("{date}", DateTime.now().toString("yyyy-MM-dd"))
            .replace("{year}", DateTime.now().toString("yyyy"))
            .replace("{month}", DateTime.now().toString("MM"))
            .replace("{day}", DateTime.now().toString("dd"))
            .replace("{hour}", DateTime.now().toString("HH"))
            .replace("{time}", DateTime.now().toString("hh:mm:ss.SSS"))
        )
        val file = path.toFile
        if (!file.exists()) {
          file.getParentFile.mkdirs()
          file.createNewFile()
        } else {
          if (file.length() > exporterConfig.maxFileSize) {
            val parts    = file.getName.split("\\.")
            val filename = parts.head
            val ext      = parts.last
            file.renameTo(new File(file.getParent, filename + "." + System.currentTimeMillis() + "." + ext))
            file.createNewFile()
          }
        }

        val contentToAppend = rawEvents
          .collect {
            case evt: TrafficCaptureEvent
                if exporterConfig.methods.isEmpty || exporterConfig.methods.contains(evt.request.method) =>
              evt.toGoReplayFormat(
                exporterConfig.captureRequests,
                exporterConfig.captureResponses,
                exporterConfig.preferBackendRequest,
                exporterConfig.preferBackendResponse
              )
          }
          .mkString("")

        Future
          .apply(Files.write(path, contentToAppend.getBytes, StandardOpenOption.APPEND))(FileWriting.blockingEc)
          .map { _ =>
            ExportResult.ExportResultSuccess
          }
      } getOrElse {
        FastFuture.successful(ExportResult.ExportResultFailure("Bad config type !"))
      }
    }
  }

  class FileAppenderExporter(config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env)
      extends DefaultDataExporter(config)(ec, env) {
    override def send(events: Seq[JsValue]): Future[ExportResult] = {
      exporter[FileSettings].map { exporterConfig =>
        val path = Paths.get(
          exporterConfig.path
            .replace("{date}", DateTime.now().toString("yyyy-MM-dd"))
            .replace("{year}", DateTime.now().toString("yyyy"))
            .replace("{month}", DateTime.now().toString("MM"))
            .replace("{day}", DateTime.now().toString("dd"))
            .replace("{hour}", DateTime.now().toString("HH"))
            .replace("{time}", DateTime.now().toString("hh:mm:ss.SSS"))
        )
        val file = path.toFile
        if (!file.exists()) {
          file.getParentFile.mkdirs()
          file.createNewFile()
        } else {
          if (file.length() > exporterConfig.maxFileSize) {
            val parts    = file.getName.split("\\.")
            val filename = parts.head
            val ext      = parts.last
            file.renameTo(new File(file.getParent, filename + "." + System.currentTimeMillis() + "." + ext))
            file.getParentFile.mkdirs()
            file.createNewFile()
          }
        }

        val fileIsNotEmpty = file.length() > 0 && events.nonEmpty
        val prefix         = if (fileIsNotEmpty) "\r\n" else ""

        val contentToAppend = events.map(Json.stringify).mkString("\r\n")

        Future
          .apply(Files.write(path, (prefix + contentToAppend).getBytes, StandardOpenOption.APPEND))(
            FileWriting.blockingEc
          )
          .map { _ =>
            if (exporterConfig.maxNumberOfFile.nonEmpty) {
              val start = file.getName.split("\\.").toSeq.init.mkString(".")
              val files = file.getParentFile
                .listFiles(new FilenameFilter() {
                  override def accept(dir: File, name: String): Boolean = name.startsWith(start)
                })
                .toSeq
                .sortWith((f1, f2) => f1.lastModified().compareTo(f2.lastModified()) > 0)
              files.splitAt(exporterConfig.maxNumberOfFile.get)._2.map(_.delete())
            }
            ExportResult.ExportResultSuccess
          }
      } getOrElse {
        FastFuture.successful(ExportResult.ExportResultFailure("Bad config type !"))
      }
    }
  }

  object S3Support {
    val logger = Logger("otoroshi-s3-exporter")
  }

  trait S3Support {

    private val lastS3Write         = new AtomicLong(0L)
    private val suffix: String      = s"${System.currentTimeMillis()}${System.nanoTime()}"
    private val counter: AtomicLong = new AtomicLong(0L)
    private val ts: AtomicLong      = new AtomicLong(System.currentTimeMillis())
    // private val maxFileSize: Long = 10L * 1024L * 1024L

    def env: Env
    def extension: String
    def contentType: String

    def debug(message: String): Unit = if (S3Support.logger.isDebugEnabled) S3Support.logger.debug(message)

    def computeKeyAndPath(conf: S3Configuration): (String, java.nio.file.Path) = {
      val key  = s"${conf.key}-${env.clusterConfig.name}-${counter.get()}-${ts.get()}-${suffix}.${extension}"
        .replace("{date}", DateTime.now().toString("yyyy-MM-dd"))
        .replace("{year}", DateTime.now().toString("yyyy"))
        .replace("{month}", DateTime.now().toString("MM"))
        .replace("{day}", DateTime.now().toString("dd"))
        .replace("{hour}", DateTime.now().toString("HH"))
        .replace("{time}", DateTime.now().toString("hh:mm:ss.SSS"))
      val path = Paths.get(System.getProperty("java.io.tmpdir") + "/" + key)
      (key, path)
    }

    def s3ClientSettingsAttrs(conf: S3Configuration): Attributes = {
      val awsCredentials = StaticCredentialsProvider.create(
        AwsBasicCredentials.create(conf.access, conf.secret)
      )
      val settings       = S3Settings(
        bufferType = MemoryBufferType,
        credentialsProvider = awsCredentials,
        s3RegionProvider = new AwsRegionProvider {
          override def getRegion: Region = Region.of(conf.region)
        },
        listBucketApiVersion = ApiVersion.ListBucketVersion2
      ).withEndpointUrl(conf.endpoint)
      S3Attributes.settings(settings)
    }

    def writeToS3(conf: S3Configuration, maxNumberOfFile: Option[Int]): Future[Unit] = {
      val (key, path) = computeKeyAndPath(conf)
      writeToS3WithKeyAndPath(key, path, maxNumberOfFile, conf)
    }

    def writeToS3AndDelete(conf: S3Configuration, maxNumberOfFile: Option[Int]): Future[Unit] = {
      implicit val ec = FileWriting.blockingEc
      val (key, path) = computeKeyAndPath(conf)
      writeToS3WithKeyAndPath(key, path, maxNumberOfFile, conf).map { _ =>
        path.toFile.delete()
        path.toFile.deleteOnExit()
        debug(s"deleting file '${path}' after S3 upload !")
      }
    }

    def writeToS3WithKeyAndPath(
        key: String,
        path: java.nio.file.Path,
        maxNumberOfFile: Option[Int],
        conf: S3Configuration
    ): Future[Unit] = {
      implicit val ec  = env.otoroshiExecutionContext
      implicit val mat = env.otoroshiMaterializer
      val url          =
        s"${conf.endpoint}/${key}?v4=${conf.v4auth}&region=${conf.region}&acl=${conf.acl.value}&bucket=${conf.bucket}"
      val wholeContent = Files.readString(path).byteString
      val ctype        = ContentType.parse(contentType).getOrElse(ContentTypes.`application/json`)
      val meta         = MetaHeaders(Map("content-type" -> contentType, "lastUpdated" -> DateTime.now().toString()))
      val sink         = S3
        .multipartUpload(
          bucket = conf.bucket,
          key = key,
          contentType = ctype,
          metaHeaders = meta,
          cannedAcl = conf.acl,
          chunkingParallelism = 1
        )
        .withAttributes(s3ClientSettingsAttrs(conf))
      debug(s"writing file '${path}' to $url")
      lastS3Write.set(System.currentTimeMillis())
      Source(wholeContent.grouped(16 * 1024).toList)
        .toMat(sink)(Keep.right)
        .run()
        .map { _ =>
          if (maxNumberOfFile.isDefined) {
            S3
              .listBucket(conf.bucket, (conf.key.split("/").init.mkString("/") + "/").some)
              .withAttributes(s3ClientSettingsAttrs(conf))
              .runWith(Sink.seq[ListBucketResultContents])
              .map { contents =>
                contents
                  .sortWith((c1, c2) => c1.lastModified.compareTo(c2.lastModified) > 0)
                  .splitAt(maxNumberOfFile.get)
                  ._2
                  .map { content =>
                    debug(s"deleting ${content.key} - ${content.size} - ${content.lastModified}")
                    S3.deleteObject(conf.bucket, content.key)
                      .withAttributes(s3ClientSettingsAttrs(conf))
                      .runWith(Sink.ignore)
                  }
              }
          }
          ()
        }
    }

    def shouldWriteToS3(conf: S3Configuration) =
      (lastS3Write.get() + conf.writeEvery.toMillis) < System.currentTimeMillis()

    def ensureFileCreationAndRolling(conf: S3Configuration, maxFileSize: Long, maxNumberOfFile: Option[Int]): File = {
      implicit val ec = FileWriting.blockingEc
      val (key, path) = computeKeyAndPath(conf)
      val file        = path.toFile
      if (!file.exists()) {
        file.getParentFile.mkdirs()
        file.createNewFile()
        file
      } else {
        if (file.length() > maxFileSize) {
          debug(s"file '${path}' is too heavy, switching to next")
          counter.incrementAndGet()
          ts.set(System.currentTimeMillis())
          val (_, newpath) = computeKeyAndPath(conf)
          val newfile      = newpath.toFile
          newfile.getParentFile.mkdirs()
          newfile.createNewFile()
          writeToS3WithKeyAndPath(key, path, maxNumberOfFile, conf).map { _ =>
            path.toFile.delete()
            path.toFile.deleteOnExit()
            debug(s"deleting file '${path}' after S3 upload !")
          }
          newfile
        } else {
          file
        }
      }
    }

    def appendToCurrentFile(content: String, conf: S3Configuration, maxNumberOfFile: Option[Int]): Future[Unit] = {
      implicit val ec = FileWriting.blockingEc
      val (_, path)   = computeKeyAndPath(conf)
      debug(s"appending events to file '${path}'")
      if (shouldWriteToS3(conf)) {
        Future
          .apply(Files.write(path, content.getBytes, StandardOpenOption.APPEND))
          .andThen { case _ =>
            writeToS3(conf, maxNumberOfFile)
          }
          .map(_ => ())
      } else {
        Future.apply(Files.write(path, content.getBytes, StandardOpenOption.APPEND)).map(_ => ())
      }
    }
  }

  class S3Exporter(config: DataExporterConfig)(implicit ec: ExecutionContext, _env: Env)
      extends DefaultDataExporter(config)(ec, _env)
      with S3Support {

    def env: Env            = _env
    def extension: String   = "ndjson"
    def contentType: String = "application/x-ndjson"

    override def send(evts: Seq[JsValue]): Future[ExportResult] = {
      exporter[S3ExporterSettings].map { exporterConfig =>
        val conf            = exporterConfig.config
        val file            = ensureFileCreationAndRolling(conf, exporterConfig.maxFileSize, exporterConfig.maxNumberOfFile)
        val fileIsNotEmpty  = file.length() > 0 && evts.nonEmpty
        val prefix          = if (fileIsNotEmpty) "\r\n" else ""
        val contentToAppend = evts.map(Json.stringify).mkString("\r\n")
        appendToCurrentFile(prefix + contentToAppend, conf, exporterConfig.maxNumberOfFile).map { _ =>
          ExportResult.ExportResultSuccess
        }
      } getOrElse {
        FastFuture.successful(ExportResult.ExportResultFailure("Bad config type !"))
      }
    }

    override def stop(): Future[Unit] = {
      exporter[S3ExporterSettings].map { exporterConfig =>
        val conf = exporterConfig.config
        writeToS3AndDelete(conf, exporterConfig.maxNumberOfFile)
      } getOrElse ().vfuture
    }
  }

  class GoReplayS3Exporter(config: DataExporterConfig)(implicit ec: ExecutionContext, _env: Env)
      extends DefaultDataExporter(config)(ec, _env)
      with S3Support {

    def env: Env            = _env
    def extension: String   = "gor"
    def contentType: String = "application/x-goreplay"

    override def send(events: Seq[JsValue]): Future[ExportResult] = throw new RuntimeException(
      "send is not supported !!!"
    )

    override def sendWithSource(__events: Seq[JsValue], rawEvents: Seq[OtoroshiEvent]): Future[ExportResult] = {
      exporter[GoReplayS3Settings].map { exporterConfig =>
        val conf            = exporterConfig.s3
        ensureFileCreationAndRolling(conf, exporterConfig.maxFileSize, None)
        val contentToAppend = rawEvents
          .collect {
            case evt: TrafficCaptureEvent
                if exporterConfig.methods.isEmpty || exporterConfig.methods.contains(evt.request.method) =>
              evt.toGoReplayFormat(
                exporterConfig.captureRequests,
                exporterConfig.captureResponses,
                exporterConfig.preferBackendRequest,
                exporterConfig.preferBackendResponse
              )
          }
          .mkString("")
        appendToCurrentFile(contentToAppend, conf, None).map { _ =>
          ExportResult.ExportResultSuccess
        }
      } getOrElse {
        FastFuture.successful(ExportResult.ExportResultFailure("Bad config type !"))
      }
    }

    override def stop(): Future[Unit] = {
      exporter[GoReplayS3Settings].map { exporterConfig =>
        val conf = exporterConfig.s3
        writeToS3AndDelete(conf, None)
      } getOrElse ().vfuture
    }
  }

  sealed trait MetricSettingsKind

  object MetricSettingsKind {
    case object Counter extends MetricSettingsKind

    case object Histogram extends MetricSettingsKind

    case object Timer extends MetricSettingsKind
  }

  case class MetricSettings(
      id: String,
      selector: Option[String] = None,
      kind: MetricSettingsKind = MetricSettingsKind.Counter,
      eventType: Option[String] = None,
      labels: Map[String, String] = Map.empty
  ) extends Exporter {
    override def toJson: JsValue = Json.obj(
      "id"        -> id,
      "selector"  -> selector,
      "kind"      -> (kind match {
        case MetricSettingsKind.Counter   => "Counter"
        case MetricSettingsKind.Timer     => "Histogram"
        case MetricSettingsKind.Histogram => "Timer"
      }),
      "eventType" -> eventType,
      "labels"    -> labels
    )
  }

  object MetricSettings {
    val format = new Format[MetricSettings] {
      override def reads(json: JsValue): JsResult[MetricSettings] = Try {
        MetricSettings(
          id = (json \ "id").as[String],
          selector = (json \ "selector").asOpt[String],
          kind = (json \ "kind")
            .asOpt[String]
            .map {
              case "Counter"   => MetricSettingsKind.Counter
              case "Histogram" => MetricSettingsKind.Histogram
              case "Timer"     => MetricSettingsKind.Timer
            }
            .getOrElse(MetricSettingsKind.Counter),
          eventType = (json \ "eventType").asOpt[String].getOrElse("AlertEvent").some,
          labels = (json \ "labels").asOpt[Map[String, String]].getOrElse(Map.empty)
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(e) => JsSuccess(e)
      }

      override def writes(o: MetricSettings): JsValue = o.toJson
    }
  }

  case class CustomMetricsSettings(tags: Map[String, String] = Map.empty, metrics: Seq[MetricSettings] = Seq.empty)
      extends Exporter {
    def json: JsValue   = CustomMetricsSettings.format.writes(this)
    def toJson: JsValue = CustomMetricsSettings.format.writes(this)
  }

  object CustomMetricsSettings {
    val format = new Format[CustomMetricsSettings] {
      override def reads(json: JsValue): JsResult[CustomMetricsSettings] = Try {
        CustomMetricsSettings(
          tags = (json \ "tags").asOpt[Map[String, String]].getOrElse(Map.empty),
          metrics = (json \ "metrics")
            .asOpt[Seq[JsValue]]
            .map(metrics => metrics.map(metric => MetricSettings.format.reads(metric).get))
            .getOrElse(Seq.empty)
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(e) => JsSuccess(e)
      }

      override def writes(o: CustomMetricsSettings): JsValue = Json.obj(
        "tags"    -> o.tags,
        "metrics" -> JsArray(o.metrics.map(_.toJson))
      )
    }
  }

  class CustomMetricsExporter(_config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env)
      extends DefaultDataExporter(_config)(ec, env) {

    def withEventLongValue(event: JsValue, selector: Option[String])(f: Long => Unit): Unit = {

      selector match {
        case None       => ()
        case Some(path) =>
          JsonOperationsHelper.getValueAtPath(path, event)._2.asOpt[Long] match {
            case None        => f(1)
            case Some(value) => f(value)
          }
      }
    }

    def extractLabels(labels: Map[String, String], event: JsValue): Map[String, String] = {
      labels.foldLeft(Map.empty[String, String]) { case (acc, label) =>
        acc + (label._2 -> JsonOperationsHelper
          .getValueAtPath(label._1, event)
          ._2
          .asOpt[String]
          .getOrElse(
            JsonOperationsHelper.getValueAtPath(label._1.replace("$at", "@"), event)._2.asOpt[String].getOrElse("")
          ))
      }
    }

    override def send(events: Seq[JsValue]): Future[ExportResult] = {
      exporter[CustomMetricsSettings].foreach { exporterConfig =>
        events.foreach { event =>
          exporterConfig.metrics.map { metric =>
            val id =
              MetricId.build(metric.id).tagged((exporterConfig.tags ++ extractLabels(metric.labels, event)).asJava)
            if (
              (event \ "@type").asOpt[String] == metric.eventType ||
              (event \ "alert").asOpt[String] == metric.eventType
            ) {
              metric.kind match {
                case MetricSettingsKind.Counter if metric.selector.isEmpty   =>
                  env.metrics.counterInc(id)
                case MetricSettingsKind.Counter if metric.selector.isDefined =>
                  withEventLongValue(event, metric.selector) { v =>
                    env.metrics.counterIncOf(id, v)
                  }
                case MetricSettingsKind.Histogram                            =>
                  withEventLongValue(event, metric.selector) { v =>
                    env.metrics.histogramUpdate(id, v)
                  }
                case MetricSettingsKind.Timer                                =>
                  withEventLongValue(event, metric.selector) { v =>
                    env.metrics.timerUpdate(id, v, TimeUnit.MILLISECONDS)
                  }
              }
            }
          }
        }
      }
      FastFuture.successful(ExportResult.ExportResultSuccess)
    }
  }

  case class WasmExporterSettings(params: JsObject, wasmRef: Option[String]) extends Exporter {
    def json: JsValue   = WasmExporterSettings.format.writes(this)
    def toJson: JsValue = json
  }

  object WasmExporterSettings {
    val format = new Format[WasmExporterSettings] {
      override def reads(json: JsValue): JsResult[WasmExporterSettings] = Try {
        WasmExporterSettings(
          params = json.select("params").asOpt[JsObject].getOrElse(Json.obj()),
          wasmRef = json.select("wasm_ref").asOpt[String].filter(_.trim.nonEmpty)
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(e) => JsSuccess(e)
      }

      override def writes(o: WasmExporterSettings): JsValue = Json.obj(
        "params"   -> o.params,
        "wasm_ref" -> o.wasmRef.map(JsString.apply).getOrElse(JsNull).asValue
      )
    }
  }

  class WasmExporter(_config: DataExporterConfig)(implicit ec: ExecutionContext, env: Env)
      extends DefaultDataExporter(_config)(ec, env) {
    override def send(events: Seq[JsValue]): Future[ExportResult] = {
      exporter[WasmExporterSettings]
        .flatMap { exporterConfig =>
          exporterConfig.wasmRef
            .flatMap(id => env.proxyState.wasmPlugin(id))
            .map { plugin =>
              val attrs = TypedMap.empty.some
              val ctx   = FakeWasmContext(exporterConfig.params).some
              val input = Json.obj(
                "params" -> exporterConfig.params,
                "config" -> configUnsafe.json
              )
              // println(s"call send: ${events.size}")
              WasmUtils
                .execute(plugin.config, "export_events", input ++ Json.obj("events" -> JsArray(events)), attrs, None)
                .map {
                  case Left(err)  => ExportResult.ExportResultFailure(err.stringify)
                  case Right(res) =>
                    res.parseJson.select("error").asOpt[JsValue] match {
                      case None        => ExportResult.ExportResultSuccess
                      case Some(error) => ExportResult.ExportResultFailure(error.stringify)
                    }
                }
                .recover { case e =>
                  e.printStackTrace()
                  ExportResult.ExportResultFailure(e.getMessage)
                }
            }
        }
        .getOrElse(ExportResult.ExportResultSuccess.vfuture)
    }
  }
}

class DataExporterUpdateJob extends Job {

  private val logger = Logger("otoroshi-data-exporter-update-job")

  override def categories: Seq[NgPluginCategory] = Seq.empty

  override def uniqueId: JobId = JobId("io.otoroshi.core.events.DataExporterUpdateJob")

  override def name: String = "Otoroshi data exporter update job"

  override def jobVisibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiInstance

  override def predicate(ctx: JobContext, env: Env): Option[Boolean] = None

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    FastFuture.successful(env.otoroshiEventsActor ! UpdateExporters)
  }
}
