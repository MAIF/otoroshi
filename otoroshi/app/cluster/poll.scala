package cluster.polling

import akka.actor.{Actor, ActorRef, Props}
import env.Env
import models._
import play.api.Logger
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.libs.ws.WSAuthScheme

import scala.concurrent.{ExecutionContext, Future}

sealed trait PollingClusterMessage
case object Sync extends PollingClusterMessage

sealed trait OtoroshiInstanceType {
  def name: String
}

object OtoroshiInstanceTypes {

  case object AutonomousInstance extends OtoroshiInstanceType {
    def name: String = "AutonomousInstance"
  }
  case object WorkerInstance extends OtoroshiInstanceType {
    def name: String = "WorkerInstance"
  }
  case object MasterInstance extends OtoroshiInstanceType {
    def name: String = "MasterInstance"
  }
  
  def apply(value: String): Option[OtoroshiInstanceType] = value match {
    case "AutonomousInstance" => Some(AutonomousInstance)
    case "WorkerInstance" => Some(WorkerInstance)
    case "MasterInstance" => Some(MasterInstance)
    case _ => None
  }
}

case class PollingClusterConfig(
  every: Long,
  otoroshiLocation: String,
  otoroshiHost: String,
  otoroshiScheme: String,
  otoroshiClientId: String,
  otoroshiClientSecret: String,
)

object PollingCluster {

  /**
   * TODO
   *
   * [x] add an instance type in the configuration (single, master, worker)
   * [x] if in worker mode, then exposeAdminApi and exposeAdminDashboard should be false, or set to false.
   * [ ] if in worker mode, store every call to a service in a local counter (just reset datastore stats???)
   * [x] start a job that will run every `env.cluster.polling.every` ms if in worker mode
   * [x]  - fetch full export from master (from config) for each job run and apply it
   * [ ]  - send counter diffs for service calls, duration, etc ...
   * [x] create a new admin api endpoint exposed only if in master mode that allow to create private app session remotely
   * [x] create a new admin api endpoint exposed only if in master mode that allow to fetch private app session remotely
   * [ ] for any private app access, if session not available and in worker mode, try to fetch it from master
   * [ ] for any private app login, if in worker mode, create it on the master
   * [x] import and export sessions
   */ 

  def apply(config: PollingClusterConfig, env: Env): ActorRef = {
    env.internalActorSystem.actorOf(PollingClusterActor.props(config, env))
  }

  def fetchConfig(config: PollingClusterConfig)(implicit env: Env, ec: ExecutionContext): Future[JsValue] = {
    env.Ws.url(s"${config.otoroshiScheme}://${config.otoroshiLocation}/api/otoroshi.json")
      .withHttpHeaders(
        "Host" -> config.otoroshiHost
      ).withAuth(
      config.otoroshiClientId,
      config.otoroshiClientSecret,
      WSAuthScheme.BASIC
    ).get().map(_.json)
  }

  def sync(config: PollingClusterConfig, logger: Logger)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    logger.debug("Syncing with master ...")
    for {
      masterConfig       <- fetchConfig(config)
      globalConfig       = GlobalConfig.fromJsons((masterConfig \ "config").as[JsObject])
      serviceGroups      = (masterConfig \ "serviceGroups").as[JsArray]
      apiKeys            = (masterConfig \ "apiKeys").as[JsArray]
      serviceDescriptors = (masterConfig \ "serviceDescriptors").as[JsArray]
      errorTemplates     = (masterConfig \ "errorTemplates").as[JsArray]
      paSessions         = (masterConfig \ "pappsSessions").as[JsArray]
      // TODO : extract stats diff
      _ <- env.datastores.flushAll()
      _ <- globalConfig.save()
      _ <- Future.sequence(serviceGroups.value.map(ServiceGroup.fromJsons).map(_.save()))
      _ <- Future.sequence(apiKeys.value.map(ApiKey.fromJsons).map(_.save()))
      _ <- Future.sequence(serviceDescriptors.value.map(ServiceDescriptor.fromJsons).map(_.save()))
      _ <- Future.sequence(errorTemplates.value.map(ErrorTemplate.fromJsons).map(_.save()))
      _ <- Future.sequence(paSessions.value.map(PrivateAppsUser.fromJsons).map(_.saveWithExpiration()))
      // TODO : send stats diffs
    } yield ()
  }
}

object PollingClusterActor {
  def props(config: PollingClusterConfig, env: Env) = Props(new PollingClusterActor(config)(env))
}

class PollingClusterActor(config: PollingClusterConfig)(implicit env: Env) extends Actor {

  import context.dispatcher
  import scala.concurrent.duration._

  lazy val logger = play.api.Logger("otoroshi-cluster-polling-actor")

  def schedule(): Unit = {
    context.system.scheduler.scheduleOnce(config.every.millis, self, Sync)
  }

  override def preStart(): Unit = {
    logger.info("Start poll cluster actor")
    self ! Sync
  }

  override def receive: Receive = {
    case Sync => PollingCluster.sync(config, logger).map(_ => schedule())
    case _ => logger.info("Unhandled message type")
  }
}