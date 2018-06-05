package events

import java.util.concurrent.{Executors, TimeUnit}

import akka.actor.{Actor, PoisonPill, Props, Terminated}
import akka.http.scaladsl.util.FastFuture._
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{OverflowStrategy, QueueOfferResult}
import env.Env
import models.{RemainingQuotas, ServiceDescriptor}
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json._
import utils.JsonImplicits._
import security.{IdGenerator, OtoroshiClaim}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case object SendToAnalytics

object AnalyticsActor {
  def props(implicit env: Env) = Props(new AnalyticsActor())
}

class AnalyticsActor(implicit env: Env) extends Actor {

  implicit lazy val ec = env.masterEc // ExecutionContext.fromExecutor(Executors.newFixedThreadPool(5))

  lazy val logger = Logger("otoroshi-analytics-actor")

  lazy val kafkaWrapperAnalytics = new KafkaWrapper(env.kafkaActorSytem, env, _.analyticsTopic)
  lazy val kafkaWrapperAudit     = new KafkaWrapper(env.kafkaActorSytem, env, _.auditTopic)

  lazy val stream = Source
    .queue[AnalyticEvent](50000, OverflowStrategy.dropHead)
    .groupedWithin(env.maxWebhookSize, FiniteDuration(env.analyticsWindow, TimeUnit.SECONDS))
    .mapAsync(5) { evts =>
      logger.debug(s"SEND_TO_ANALYTICS_HOOK: will send ${evts.size} evts")
      env.datastores.globalConfigDataStore.singleton().fast.map { config =>
        logger.debug("SEND_TO_ANALYTICS_HOOK: " + config.analyticsWebhooks)
        config.kafkaConfig.foreach { kafkaConfig =>
          evts.foreach {
            case evt: AuditEvent => kafkaWrapperAudit.publish(evt.toJson)(env, kafkaConfig)
            case evt             => kafkaWrapperAnalytics.publish(evt.toJson)(env, kafkaConfig)
          }
          if (config.kafkaConfig.isEmpty) {
            kafkaWrapperAnalytics.close()
            kafkaWrapperAudit.close()
          }
        }
        Future.sequence(config.analyticsWebhooks.map { webhook =>
          val state = IdGenerator.extendedToken(128)
          val claim = OtoroshiClaim(
            iss = env.Headers.OtoroshiIssuer,
            sub = "otoroshi-analytics",
            aud = "omoikane",
            exp = DateTime.now().plusSeconds(30).toDate.getTime,
            iat = DateTime.now().toDate.getTime,
            jti = IdGenerator.uuid
          ).serialize(env)
          val headers: Seq[(String, String)] = webhook.headers.toSeq ++ Seq(
            env.Headers.OtoroshiState -> state,
            env.Headers.OtoroshiClaim -> claim
          )

          val url = evts.headOption
            .map(
              evt =>
                webhook.url
                //.replace("@product", env.eventsName)
                  .replace("@service", evt.`@service`)
                  .replace("@serviceId", evt.`@serviceId`)
                  .replace("@id", evt.`@id`)
                  .replace("@messageType", evt.`@type`)
            )
            .getOrElse(webhook.url)
          env.Ws.url(url).withHttpHeaders(headers: _*).post(JsArray(evts.map(_.toJson))).andThen {
            case Success(resp) => {
              logger.debug(s"SEND_TO_ANALYTICS_SUCCESS: ${resp.status} - ${resp.headers} - ${resp.body}")
            }
            case Failure(e) => {
              logger.error("SEND_TO_ANALYTICS_FAILURE: Error while sending AnalyticEvent", e)
            }
          }
        })
      }
    }

  lazy val (queue, done) = stream.toMat(Sink.ignore)(Keep.both).run()(env.materializer)

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
      logger.warn("Restarting analytics actor child")
      context.watch(context.actorOf(AnalyticsActor.props(env), childName))
    case evt => context.child(childName).map(_ ! evt)
  }

  override def preStart(): Unit =
    if (context.child(childName).isEmpty) {
      logger.info(s"Starting new child $childName")
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

  def toAnalytics()(implicit env: Env): Unit = {
    if (true) env.analyticsActor ! this
    Logger("otoroshi-analytics").info(s"${this.`@type`} ${Json.stringify(toJson)}")
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
    from: String,
    env: String,
    duration: Long,
    overhead: Long,
    data: DataInOut,
    status: Int,
    headers: Seq[Header],
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
    "from"            -> o.from,
    "@env"            -> o.env,
    "duration"        -> o.duration,
    "overhead"        -> o.overhead,
    "data"            -> DataInOut.fmt.writes(o.data),
    "status"          -> o.status,
    "headers"         -> o.headers.map(Header.format.writes),
    "identity"        -> o.identity.map(Identity.format.writes).getOrElse(JsNull).as[JsValue],
    "gwError"         -> o.gwError.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    "@serviceId"      -> o.`@serviceId`,
    "@service"        -> o.`@service`,
    "descriptor"      -> ServiceDescriptor.toJson(o.descriptor),
    "@product"        -> o.`@product`,
    "remainingQuotas" -> o.remainingQuotas,
    "viz"             -> o.viz.map(_.toJson).getOrElse(JsNull).as[JsValue]
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
