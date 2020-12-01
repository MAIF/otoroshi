package events

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import akka.actor.{Actor, ActorRef, PoisonPill, Props, Terminated}
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{OverflowStrategy, QueueOfferResult}
import cluster.ClusterMode
import env.Env
import events.impl.{ElasticReadsAnalytics, ElasticWritesAnalytics, WebHookAnalytics}
import models._
import org.joda.time.DateTime
import otoroshi.plugins.useragent.UserAgentHelper
import otoroshi.tcp.TcpService
import play.api.Logger
import play.api.libs.json._
import utils.JsonImplicits._

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case object SendToAnalytics

/*
object AnalyticsActor {
  def props(exporter: DataExporterConfig)(implicit env: Env) = Props(new AnalyticsActor(exporter))
}

class AnalyticsActor(exporter: DataExporterConfig)(implicit env: Env) extends Actor {

  implicit lazy val ec = env.analyticsExecutionContext

  lazy val logger = Logger("otoroshi-analytics-actor")

  lazy val kafkaWrapperAnalytics = new KafkaWrapper(env.analyticsActorSystem, env, _.topic)
  lazy val kafkaWrapperAudit     = new KafkaWrapper(env.analyticsActorSystem, env, _.topic)

  lazy val stream = Source
    .queue[AnalyticEvent](50000, OverflowStrategy.dropHead)
    .mapAsync(5)(evt => evt.toEnrichedJson)
    .groupedWithin(env.maxWebhookSize, FiniteDuration(env.analyticsWindow, TimeUnit.SECONDS))
    .filter(_.nonEmpty)
    .mapAsync(5) { evts =>
      logger.debug(s"SEND_TO_ANALYTICS_HOOK: will send ${evts.size} evts")
      env.datastores.globalConfigDataStore.singleton().fast.map { config =>
        logger.debug("SEND_TO_ANALYTICS_HOOK: " + config.analyticsWebhooks)
        config.kafkaConfig.foreach { kafkaConfig =>
          evts.foreach {
            case evt: AuditEvent => kafkaWrapperAudit.publish(evt)(env, kafkaConfig)
            case evt             => kafkaWrapperAnalytics.publish(evt)(env, kafkaConfig)
          }
          if (config.kafkaConfig.isEmpty) {
            kafkaWrapperAnalytics.close()
            kafkaWrapperAudit.close()
          }
        }

        val service: AnalyticsWritesService = exporter.config match {
          case c: ElasticAnalyticsConfig => new ElasticWritesAnalytics(c, env)
          case c: Webhook => new WebHookAnalytics(c, config)
        }

        service.publish(evts)
      }
    }

  lazy val (queue, done) = stream.toMat(Sink.ignore)(Keep.both).run()(env.analyticsMaterializer)

  override def receive: Receive = {
    case ge: AnalyticEvent => {
      logger.debug("SEND_TO_ANALYTICS: Event sent to stream")
      val myself = self
      queue.offer(ge).andThen {
        case Success(QueueOfferResult.Enqueued) => logger.debug("SEND_TO_ANALYTICS: Event enqueued")
        case Success(QueueOfferResult.Dropped) =>
          logger.error("SEND_TO_ANALYTICS_ERROR: Enqueue Dropped AnalyticEvent :(")
        case Success(QueueOfferResult.QueueClosed) =>
          logger.error("SEND_TO_ANALYTICS_ERROR: Queue closed :(")
          context.stop(myself)
        case Success(QueueOfferResult.Failure(t)) =>
          logger.error("SEND_TO_ANALYTICS_ERROR: Enqueue Failure AnalyticEvent :(", t)
          context.stop(myself)
        case Failure(e: akka.stream.StreamDetachedException) if env.liveJs =>
          // silently ignore in dev
        case e =>
          logger.error(s"SEND_TO_ANALYTICS_ERROR: analytics actor error : ${e}")
          context.stop(myself)
      }
      env.datastores.globalConfigDataStore.latestSafe.filter(_.logAnalyticsOnServer).foreach(_ => ge.log())
    }
    case _ =>
  }
}

class AnalyticsActorSupervizer(env: Env) extends Actor {

  lazy val logger    = Logger("otoroshi-analytics-actor-supervizer")

  implicit val e = env
  implicit val ec  = env.analyticsExecutionContext

  val namesAndRefs: Map[ActorRef, Tuple2[String, DataExporterConfig]] = Map.empty

  // override def supervisorStrategy: SupervisorStrategy =
  //   OneForOneStrategy() {
  //     case e =>
  //       Restart
  //   }

  override def receive: Receive = {
    case Terminated(ref) =>
      logger.debug("Restarting analytics actor child")
      context.watch(context.actorOf(AnalyticsActor.props(namesAndRefs(ref)._2)(env), namesAndRefs(ref)._1))
    case evt => context.children.map(_ ! evt)
  }

  override def preStart(): Unit = {
    env.datastores.dataExporterConfigDataStore.findAll().fast.map { dataExporters =>
      dataExporters.foreach(exporter => {
        val childName = s"analytics-actor-${exporter.id}"
        if (context.child(childName).isEmpty) {
          logger.debug(s"Starting new child $childName")
          val ref = context.actorOf(AnalyticsActor.props(exporter)(env), childName)
          namesAndRefs + (ref -> (childName -> exporter))
          context.watch(ref)
        }
      })

    }
  }

  override def postStop(): Unit =
    context.children.foreach(_ ! PoisonPill)
}

object AnalyticsActorSupervizer {
  def props(implicit env: Env) = Props(new AnalyticsActorSupervizer(env))
}
*/

object AnalyticEvent {
  lazy val logger = Logger("otoroshi-analytics-event")
}

trait OtoroshiEvent {
  def `@id`: String
  def `@timestamp`: DateTime
  def toJson(implicit _env: Env): JsValue
  def toEnrichedJson(implicit _env: Env, ec: ExecutionContext): Future[JsValue] = FastFuture.successful(toJson(_env))
  def dispatch()(implicit env: Env): Unit = {
    env.scriptManager.dispatchEvent(this)(env.analyticsExecutionContext)
  }
}

trait AnalyticEvent extends OtoroshiEvent {

  def `@type`: String
  def `@id`: String
  def `@timestamp`: DateTime
  def `@service`: String
  def `@serviceId`: String
  def fromOrigin: Option[String]
  def fromUserAgent: Option[String]

  def toJson(implicit _env: Env): JsValue
  override def toEnrichedJson(implicit _env: Env, ec: ExecutionContext): Future[JsValue] = {
    val jsonObject = toJson(_env).as[JsObject]
    val uaDetails = (jsonObject \ "userAgentInfo").asOpt[JsValue] match {
      case Some(details) => details
      case None =>
        fromUserAgent match {
          case None => JsNull
          case Some(ua) =>
            _env.datastores.globalConfigDataStore.latestSafe match {
              case None                                              => JsNull
              case Some(config) if !config.userAgentSettings.enabled => JsNull
              case Some(config) =>
                config.userAgentSettings.find(ua) match {
                  case None          => JsNull
                  case Some(details) => details
                }
            }
        }
    }
    val fOrigin = (jsonObject \ "geolocationInfo").asOpt[JsValue] match {
      case Some(details) => FastFuture.successful(details)
      case None =>
        fromOrigin match {
          case None => FastFuture.successful(JsNull)
          case Some(ipAddress) => {
            _env.datastores.globalConfigDataStore.latestSafe match {
              case None                                                => FastFuture.successful(JsNull)
              case Some(config) if !config.geolocationSettings.enabled => FastFuture.successful(JsNull)
              case Some(config) =>
                config.geolocationSettings.find(ipAddress).map {
                  case None          => JsNull
                  case Some(details) => details
                }
            }
          }
        }
    }
    fOrigin.map(
      originDetails =>
        jsonObject ++ Json.obj(
          "user-agent-details" -> uaDetails,
          "origin-details"     -> originDetails,
          "instance-number"    -> _env.number,
          "instance-name"      -> _env.name,
          "instance-zone"      -> _env.zone,
          "instance-region"    -> _env.region,
          "instance-dc"        -> _env.dataCenter,
          "instance-provider"  -> _env.infraProvider,
          "instance-rack"      -> _env.rack,
          "cluster-mode"       -> _env.clusterConfig.mode.name,
          "cluster-name" -> (_env.clusterConfig.mode match {
            case ClusterMode.Worker => _env.clusterConfig.worker.name
            case ClusterMode.Leader => _env.clusterConfig.leader.name
            case _                  => "none"
          })
      )
    )
  }

  def toAnalytics()(implicit env: Env): Unit = {
    dispatch()(env)
    env.otoroshiEventsActor ! this
  }

  def log()(implicit _env: Env, ec: ExecutionContext): Unit = {
    toEnrichedJson.map(e => AnalyticEvent.logger.info(Json.stringify(e)))
  }
}

case class Identity(identityType: String, identity: String, label: String)

object Identity {
  implicit val format = Json.format[Identity]
}

case class Location(host: String, scheme: String, uri: String)

object Location {
  implicit val format = Json.format[Location]
}

case class Header(key: String, value: String)

object Header {
  implicit val format                        = Json.format[Header]
  def apply(tuple: (String, String)): Header = Header(tuple._1, tuple._2)
}

case class DataInOut(dataIn: Long, dataOut: Long)

object DataInOut {
  implicit val fmt = Json.format[DataInOut]
}

case class OtoroshiViz(fromTo: String, from: String, to: String, fromLbl: String, toLbl: String) {
  def toJson = OtoroshiViz.format.writes(this)
}

object OtoroshiViz {
  implicit val format = Json.format[OtoroshiViz]
}

case class GatewayEvent(
    `@type`: String = "GatewayEvent",
    `@id`: String,
    `@timestamp`: DateTime,
    `@calledAt`: DateTime,
    reqId: String,
    parentReqId: Option[String],
    protocol: String,
    to: Location,
    target: Location,
    url: String,
    method: String,
    from: String,
    env: String,
    duration: Long,
    overhead: Long,
    cbDuration: Long,
    overheadWoCb: Long,
    callAttempts: Int,
    data: DataInOut,
    status: Int,
    headers: Seq[Header],
    headersOut: Seq[Header],
    responseChunked: Boolean,
    identity: Option[Identity] = None,
    gwError: Option[String] = None,
    err: Boolean, // = false,
    `@serviceId`: String,
    `@service`: String,
    descriptor: Option[ServiceDescriptor],
    `@product`: String = "--",
    remainingQuotas: RemainingQuotas,
    viz: Option[OtoroshiViz],
    clientCertChain: Seq[String] = Seq.empty[String],
    userAgentInfo: Option[JsValue],
    geolocationInfo: Option[JsValue],
    extraAnalyticsData: Option[JsValue]
) extends AnalyticEvent {
  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = headers.find(h => h.key.toLowerCase() == "user-agent").map(_.value)
  def toJson(implicit _env: Env): JsValue    = GatewayEvent.writes(this, _env)
}

object GatewayEvent {
  def writes(o: GatewayEvent, env: Env): JsValue = Json.obj(
    "@type"           -> o.`@type`,
    "@id"             -> o.`@id`,
    "@timestamp"      -> o.`@timestamp`,
    "@callAt"         -> o.`@calledAt`,
    "reqId"           -> o.reqId,
    "parentReqId"     -> o.parentReqId.map(l => JsString(l)).getOrElse(JsNull).as[JsValue],
    "protocol"        -> o.protocol,
    "to"              -> Location.format.writes(o.to),
    "target"          -> Location.format.writes(o.target),
    "url"             -> o.url,
    "method"          -> o.method,
    "from"            -> o.from,
    "@env"            -> o.env,
    "duration"        -> o.duration,
    "overhead"        -> o.overhead,
    "data"            -> DataInOut.fmt.writes(o.data),
    "status"          -> o.status,
    "responseChunked" -> o.responseChunked,
    "headers"         -> o.headers.map(Header.format.writes),
    "headersOut"      -> o.headersOut.map(Header.format.writes),
    "identity"        -> o.identity.map(Identity.format.writes).getOrElse(JsNull).as[JsValue],
    "gwError"         -> o.gwError.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    "err"             -> o.err,
    "@serviceId"      -> o.`@serviceId`,
    "@service"        -> o.`@service`,
    "descriptor"      -> o.descriptor.map(d => ServiceDescriptor.toJson(d)).getOrElse(JsNull).as[JsValue],
    "@product"        -> o.`@product`,
    "remainingQuotas" -> o.remainingQuotas,
    "viz"             -> o.viz.map(_.toJson).getOrElse(JsNull).as[JsValue],
    "cbDuration"      -> o.cbDuration,
    "overheadWoCb"    -> o.overheadWoCb,
    "callAttempts"    -> o.callAttempts,
    "clientCertChain" -> o.clientCertChain,
    "userAgentInfo"   -> o.userAgentInfo.getOrElse(JsNull).as[JsValue],
    "geolocationInfo" -> o.geolocationInfo.getOrElse(JsNull).as[JsValue],
    "extrasData"      -> o.extraAnalyticsData.getOrElse(JsNull).as[JsValue],
  )
}

case class TcpEvent(
    `@type`: String = "TcpEvent",
    `@id`: String,
    `@timestamp`: DateTime,
    reqId: String,
    protocol: String,
    port: Int,
    to: Location,
    target: Location,
    remote: String,
    local: String,
    duration: Long,
    overhead: Long,
    data: DataInOut,
    gwError: Option[String] = None,
    `@serviceId`: String,
    `@service`: String,
    service: Option[TcpService],
) extends AnalyticEvent {
  override def fromOrigin: Option[String]    = Some(remote)
  override def fromUserAgent: Option[String] = None
  def toJson(implicit _env: Env): JsValue    = TcpEvent.writes(this, _env)
}

object TcpEvent {
  def writes(o: TcpEvent, env: Env): JsValue = Json.obj(
    "@type"      -> o.`@type`,
    "@id"        -> o.`@id`,
    "@timestamp" -> o.`@timestamp`,
    "reqId"      -> o.reqId,
    "protocol"   -> o.protocol,
    "port"       -> o.port,
    "to"         -> Location.format.writes(o.to),
    "target"     -> Location.format.writes(o.target),
    "remote"     -> o.remote,
    "local"      -> o.local,
    "duration"   -> o.duration,
    "overhead"   -> o.overhead,
    "data"       -> DataInOut.fmt.writes(o.data),
    "gwError"    -> o.gwError.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    "@serviceId" -> o.`@serviceId`,
    "@service"   -> o.`@service`,
    "service"    -> o.service.map(_.json).getOrElse(JsNull).as[JsValue],
  )
}

case class HealthCheckEvent(
    `@type`: String = "HealthCheckEvent",
    `@id`: String,
    `@timestamp`: DateTime,
    `@service`: String,
    `@serviceId`: String,
    `@product`: String = "default",
    url: String,
    duration: Long,
    status: Int,
    logicCheck: Boolean,
    error: Option[String] = None,
    health: Option[String] = None
) extends AnalyticEvent {
  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None
  def toJson(implicit _env: Env): JsValue    = HealthCheckEvent.format.writes(this)
  def pushToRedis()(implicit ec: ExecutionContext, env: Env): Future[Long] =
    toEnrichedJson.flatMap(e => env.datastores.healthCheckDataStore.push(e))
  def isUp: Boolean =
    if (error.isDefined) {
      false
    } else {
      if (status > 499) {
        false
      } else {
        true
      }
    }
}

object HealthCheckEvent {
  implicit val format = Json.format[HealthCheckEvent]
}

trait HealthCheckDataStore {
  def findAll(serviceDescriptor: ServiceDescriptor)(implicit ec: ExecutionContext,
                                                    env: Env): Future[Seq[HealthCheckEvent]]
  def findLast(serviceDescriptor: ServiceDescriptor)(implicit ec: ExecutionContext,
                                                     env: Env): Future[Option[HealthCheckEvent]]
  def push(event: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Long]
}

sealed trait Filterable
case class ServiceDescriptorFilterable(service: ServiceDescriptor) extends Filterable
case class ApiKeyFilterable(apiKey: ApiKey)                        extends Filterable
case class ServiceGroupFilterable(group: ServiceGroup)             extends Filterable

trait AnalyticsReadsService {
  def events(eventType: String,
             filterable: Option[Filterable],
             from: Option[DateTime],
             to: Option[DateTime],
             page: Int = 1,
             size: Int = 50,
             order: String = "desc")(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]]
  def fetchHits(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchDataIn(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchDataOut(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchAvgDuration(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchAvgOverhead(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchStatusesPiechart(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchStatusesHistogram(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchDataInStatsHistogram(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchDataOutStatsHistogram(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchDurationStatsHistogram(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchDurationPercentilesHistogram(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchOverheadPercentilesHistogram(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchOverheadStatsHistogram(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchProductPiechart(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime], size: Int)(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchApiKeyPiechart(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchUserPiechart(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchServicePiechart(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime], size: Int)(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchServicesStatus(servicesDescriptors: Seq[ServiceDescriptor], from: Option[DateTime], to: Option[DateTime])(
    implicit env: Env,
    ec: ExecutionContext
  ): Future[Option[JsValue]]
}

trait AnalyticsWritesService {
  def init(): Unit
  def publish(event: Seq[JsValue])(implicit env: Env, ec: ExecutionContext): Future[Unit]
}

class AnalyticsReadsServiceImpl(globalConfig: GlobalConfig, env: Env) extends AnalyticsReadsService {

  private def underlyingService()(implicit env: Env, ec: ExecutionContext): Future[Option[AnalyticsReadsService]] = {
    FastFuture.successful(
      globalConfig.elasticReadsConfig.map(
        c => new ElasticReadsAnalytics(c, env)
      )
    )
  }

  override def events(eventType: String,
                      filterable: Option[Filterable],
                      from: Option[DateTime],
                      to: Option[DateTime],
                      page: Int,
                      size: Int,
                      order: String = "desc")(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.events(eventType, filterable, from, to, page, size, order))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchHits(
      filterable: Option[Filterable],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchHits(filterable, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchDataIn(
      filterable: Option[Filterable],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchDataIn(filterable, from, to))
        .getOrElse(FastFuture.successful(None))
    )
  override def fetchDataOut(
      filterable: Option[Filterable],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchDataOut(filterable, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchAvgDuration(
      filterable: Option[Filterable],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchAvgDuration(filterable, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchAvgOverhead(
      filterable: Option[Filterable],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchAvgOverhead(filterable, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchStatusesPiechart(
      filterable: Option[Filterable],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchStatusesPiechart(filterable, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchStatusesHistogram(
      filterable: Option[Filterable],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchStatusesHistogram(filterable, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchDataInStatsHistogram(
      filterable: Option[Filterable],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchDataInStatsHistogram(filterable, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchDataOutStatsHistogram(
      filterable: Option[Filterable],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchDataOutStatsHistogram(filterable, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchDurationStatsHistogram(
      filterable: Option[Filterable],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchDurationStatsHistogram(filterable, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchDurationPercentilesHistogram(
      filterable: Option[Filterable],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchDurationPercentilesHistogram(filterable, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchOverheadPercentilesHistogram(
      filterable: Option[Filterable],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchOverheadPercentilesHistogram(filterable, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchOverheadStatsHistogram(
      filterable: Option[Filterable],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchOverheadStatsHistogram(filterable, from, to))
        .getOrElse(FastFuture.successful(None))
    )
  override def fetchProductPiechart(filterable: Option[Filterable],
                                    from: Option[DateTime],
                                    to: Option[DateTime],
                                    size: Int)(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchProductPiechart(filterable, from, to, size))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchApiKeyPiechart(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] = underlyingService().flatMap(
    _.map(_.fetchApiKeyPiechart(filterable, from, to))
      .getOrElse(FastFuture.successful(None))
  )

  override def fetchUserPiechart(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] = underlyingService().flatMap(
    _.map(_.fetchUserPiechart(filterable, from, to))
      .getOrElse(FastFuture.successful(None))
  )

  override def fetchServicePiechart(filterable: Option[Filterable],
                                    from: Option[DateTime],
                                    to: Option[DateTime],
                                    size: Int)(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchServicePiechart(filterable, from, to, size))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchServicesStatus(servicesDescriptors: Seq[ServiceDescriptor],
                                  from: Option[DateTime],
                                  to: Option[DateTime])(
    implicit env: Env,
    ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchServicesStatus(servicesDescriptors, from, to))
        .getOrElse(FastFuture.successful(None))
    )
}
