package events

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, PoisonPill, Props, Terminated}
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{OverflowStrategy, QueueOfferResult}
import cluster.ClusterMode
import env.Env
import events.impl.{ElasticReadsAnalytics, ElasticWritesAnalytics, WebHookAnalytics}
import models._
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json._
import utils.JsonImplicits._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

case object SendToAnalytics

object AnalyticsActor {
  def props(implicit env: Env) = Props(new AnalyticsActor())
}

class AnalyticsActor(implicit env: Env) extends Actor {

  implicit lazy val ec = env.otoroshiExecutionContext

  lazy val logger = Logger("otoroshi-analytics-actor")

  lazy val kafkaWrapperAnalytics = new KafkaWrapper(env.otoroshiActorSystem, env, _.analyticsTopic)
  lazy val kafkaWrapperAudit     = new KafkaWrapper(env.otoroshiActorSystem, env, _.auditTopic)

  lazy val stream = Source
    .queue[AnalyticEvent](50000, OverflowStrategy.dropHead)
    .groupedWithin(env.maxWebhookSize, FiniteDuration(env.analyticsWindow, TimeUnit.SECONDS))
    .mapAsync(5) { evts =>
      logger.debug(s"SEND_TO_ANALYTICS_HOOK: will send ${evts.size} evts")
      env.datastores.globalConfigDataStore.singleton().fast.map { config =>
        logger.debug("SEND_TO_ANALYTICS_HOOK: " + config.analyticsWebhooks)
        config.kafkaConfig.foreach { kafkaConfig =>
          evts.foreach {
            case evt: AuditEvent => kafkaWrapperAudit.publish(evt.toEnrichedJson)(env, kafkaConfig)
            case evt             => kafkaWrapperAnalytics.publish(evt.toEnrichedJson)(env, kafkaConfig)
          }
          if (config.kafkaConfig.isEmpty) {
            kafkaWrapperAnalytics.close()
            kafkaWrapperAudit.close()
          }
        }
        Future.traverse(
          config.analyticsWebhooks.map(c => new WebHookAnalytics(c)) ++
          config.elasticWritesConfigs.map(
            c =>
              new ElasticWritesAnalytics(c,
                                         env.environment,
                                         env.Ws,
                                         env.otoroshiExecutionContext,
                                         env.otoroshiActorSystem)
          )
        ) {
          _.publish(evts)
        }
      }
    }

  lazy val (queue, done) = stream.toMat(Sink.ignore)(Keep.both).run()(env.otoroshiMaterializer)

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
          logger.error("SEND_TO_ANALYTICS_ERROR: Enqueue Failre AnalyticEvent :(", t)
          context.stop(myself)
        case e =>
          logger.error(s"SEND_TO_ANALYTICS_ERROR: analytics actor error : ${e}")
          context.stop(myself)
      }
    }
    case _ =>
  }
}

class AnalyticsActorSupervizer(env: Env) extends Actor {

  lazy val childName = "analytics-actor"
  lazy val logger    = Logger("otoroshi-analytics-actor-supervizer")

  // override def supervisorStrategy: SupervisorStrategy =
  //   OneForOneStrategy() {
  //     case e =>
  //       Restart
  //   }

  override def receive: Receive = {
    case Terminated(ref) =>
      logger.debug("Restarting analytics actor child")
      context.watch(context.actorOf(AnalyticsActor.props(env), childName))
    case evt => context.child(childName).map(_ ! evt)
  }

  override def preStart(): Unit =
    if (context.child(childName).isEmpty) {
      logger.debug(s"Starting new child $childName")
      val ref = context.actorOf(AnalyticsActor.props(env), childName)
      context.watch(ref)
    }

  override def postStop(): Unit =
    context.children.foreach(_ ! PoisonPill)
}

object AnalyticsActorSupervizer {
  def props(implicit env: Env) = Props(new AnalyticsActorSupervizer(env))
}

trait AnalyticEvent {

  def `@type`: String
  def `@id`: String
  def `@timestamp`: DateTime
  def `@service`: String
  def `@serviceId`: String

  def toJson(implicit _env: Env): JsValue
  def toEnrichedJson(implicit _env: Env): JsValue = {
    toJson(_env).as[JsObject] ++ Json.obj(
      "cluster-mode" -> _env.clusterConfig.mode.name,
      "cluster-name" -> (_env.clusterConfig.mode match {
        case ClusterMode.Worker => _env.clusterConfig.worker.name
        case ClusterMode.Leader => _env.clusterConfig.leader.name
        case _                  => "none"
      })
    )
  }

  def toAnalytics()(implicit env: Env): Unit = {
    if (true) env.analyticsActor ! this
    // Logger("otoroshi-analytics").debug(s"${this.`@type`} ${Json.stringify(toJson)}")
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
    identity: Option[Identity] = None,
    gwError: Option[String] = None,
    `@serviceId`: String,
    `@service`: String,
    descriptor: ServiceDescriptor,
    `@product`: String = "--",
    remainingQuotas: RemainingQuotas,
    viz: Option[OtoroshiViz]
) extends AnalyticEvent {
  def toJson(implicit _env: Env): JsValue = GatewayEvent.writes(this, _env)
}

object GatewayEvent {
  def writes(o: GatewayEvent, env: Env): JsValue = Json.obj(
    "@type"           -> o.`@type`,
    "@id"             -> o.`@id`,
    "@timestamp"      -> o.`@timestamp`,
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
    "headers"         -> o.headers.map(Header.format.writes),
    "headersOut"      -> o.headersOut.map(Header.format.writes),
    "identity"        -> o.identity.map(Identity.format.writes).getOrElse(JsNull).as[JsValue],
    "gwError"         -> o.gwError.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    "@serviceId"      -> o.`@serviceId`,
    "@service"        -> o.`@service`,
    "descriptor"      -> ServiceDescriptor.toJson(o.descriptor),
    "@product"        -> o.`@product`,
    "remainingQuotas" -> o.remainingQuotas,
    "viz"             -> o.viz.map(_.toJson).getOrElse(JsNull).as[JsValue],
    "cbDuration"      -> o.cbDuration,
    "overheadWoCb"    -> o.overheadWoCb,
    "callAttempts"    -> o.callAttempts
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
  def toJson(implicit _env: Env): JsValue = HealthCheckEvent.format.writes(this)
  def pushToRedis()(implicit ec: ExecutionContext, env: Env): Future[Long] =
    env.datastores.healthCheckDataStore.push(this)
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
  def push(event: HealthCheckEvent)(implicit ec: ExecutionContext, env: Env): Future[Long]
}

trait AnalyticsReadsService {
  def events(eventType: String,
             service: Option[ServiceDescriptor],
             from: Option[DateTime],
             to: Option[DateTime],
             page: Int = 1,
             size: Int = 50)(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]]
  def fetchHits(service: Option[ServiceDescriptor], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchDataIn(service: Option[ServiceDescriptor], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchDataOut(service: Option[ServiceDescriptor], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchAvgDuration(service: Option[ServiceDescriptor], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchAvgOverhead(service: Option[ServiceDescriptor], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchStatusesPiechart(service: Option[ServiceDescriptor], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchStatusesHistogram(service: Option[ServiceDescriptor], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchDataInStatsHistogram(service: Option[ServiceDescriptor], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchDataOutStatsHistogram(service: Option[ServiceDescriptor], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchDurationStatsHistogram(service: Option[ServiceDescriptor], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchDurationPercentilesHistogram(service: Option[ServiceDescriptor],
                                        from: Option[DateTime],
                                        to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchOverheadPercentilesHistogram(service: Option[ServiceDescriptor],
                                        from: Option[DateTime],
                                        to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchOverheadStatsHistogram(service: Option[ServiceDescriptor], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchProductPiechart(service: Option[ServiceDescriptor], from: Option[DateTime], to: Option[DateTime], size: Int)(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchApiKeyPiechart(service: Option[ServiceDescriptor], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchUserPiechart(service: Option[ServiceDescriptor], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
  def fetchServicePiechart(service: Option[ServiceDescriptor], from: Option[DateTime], to: Option[DateTime], size: Int)(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]]
}

trait AnalyticsWritesService {
  def init(): Unit
  def publish(event: Seq[AnalyticEvent])(implicit env: Env, ec: ExecutionContext): Future[Unit]
}

class AnalyticsReadsServiceImpl(globalConfig: GlobalConfig, env: Env) extends AnalyticsReadsService {

  private def underlyingService()(implicit env: Env, ec: ExecutionContext): Future[Option[AnalyticsReadsService]] = {
    FastFuture.successful(
      globalConfig.elasticReadsConfig.map(
        c =>
          new ElasticReadsAnalytics(c, env.environment, env.Ws, env.otoroshiExecutionContext, env.otoroshiActorSystem)
      )
    )
  }

  override def events(eventType: String,
                      service: Option[ServiceDescriptor],
                      from: Option[DateTime],
                      to: Option[DateTime],
                      page: Int,
                      size: Int)(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.events(eventType, service, from, to, page, size))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchHits(
      service: Option[ServiceDescriptor],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchHits(service, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchDataIn(
      service: Option[ServiceDescriptor],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchDataIn(service, from, to))
        .getOrElse(FastFuture.successful(None))
    )
  override def fetchDataOut(
      service: Option[ServiceDescriptor],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchDataOut(service, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchAvgDuration(
      service: Option[ServiceDescriptor],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchAvgDuration(service, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchAvgOverhead(
      service: Option[ServiceDescriptor],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchAvgOverhead(service, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchStatusesPiechart(
      service: Option[ServiceDescriptor],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchStatusesPiechart(service, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchStatusesHistogram(
      service: Option[ServiceDescriptor],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchStatusesHistogram(service, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchDataInStatsHistogram(
      service: Option[ServiceDescriptor],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchDataInStatsHistogram(service, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchDataOutStatsHistogram(
      service: Option[ServiceDescriptor],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchDataOutStatsHistogram(service, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchDurationStatsHistogram(
      service: Option[ServiceDescriptor],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchDurationStatsHistogram(service, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchDurationPercentilesHistogram(
      service: Option[ServiceDescriptor],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchDurationPercentilesHistogram(service, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchOverheadPercentilesHistogram(
      service: Option[ServiceDescriptor],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchOverheadPercentilesHistogram(service, from, to))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchOverheadStatsHistogram(
      service: Option[ServiceDescriptor],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchOverheadStatsHistogram(service, from, to))
        .getOrElse(FastFuture.successful(None))
    )
  override def fetchProductPiechart(service: Option[ServiceDescriptor],
                                    from: Option[DateTime],
                                    to: Option[DateTime],
                                    size: Int)(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchProductPiechart(service, from, to, size))
        .getOrElse(FastFuture.successful(None))
    )

  override def fetchApiKeyPiechart(service: Option[ServiceDescriptor], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] = underlyingService().flatMap(
    _.map(_.fetchApiKeyPiechart(service, from, to))
      .getOrElse(FastFuture.successful(None))
  )

  override def fetchUserPiechart(service: Option[ServiceDescriptor], from: Option[DateTime], to: Option[DateTime])(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] = underlyingService().flatMap(
    _.map(_.fetchUserPiechart(service, from, to))
      .getOrElse(FastFuture.successful(None))
  )

  override def fetchServicePiechart(service: Option[ServiceDescriptor],
                                    from: Option[DateTime],
                                    to: Option[DateTime],
                                    size: Int)(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] =
    underlyingService().flatMap(
      _.map(_.fetchServicePiechart(service, from, to, size))
        .getOrElse(FastFuture.successful(None))
    )
}
