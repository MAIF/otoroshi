package cluster.polling

import akka.actor.{Actor, ActorRef, Props}
import env.Env

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
  every: Long
)

object PollingCluster {

  /**
   * TODO
   *
   * [x] add an instance type in the configuration (single, master, worker)
   * [x] if in worker mode, then exposeAdminApi and exposeAdminDashboard should be false, or set to false.
   * [ ] if in worker mode, store every call to a service in a local counter (just reset datastore stats???)
   * [x] start a job that will run every `env.cluster.polling.every` ms if in worker mode
   * [ ]  - fetch full export from master (from config) for each job run and apply it
   * [ ]  - send counter diffs for service calls, duration, etc ...
   * [ ] create a new admin api endpoint exposed only if in master mode that allow to create private app session remotely
   * [ ] create a new admin api endpoint exposed only if in master mode that allow to fetch private app session remotely
   * [ ] for any private app access, if session not available and in worker mode, try to fetch it from master
   * [ ] for any private app login, if in worker mode, create it on the master
   * [x] import and export sessions
   */ 

  def apply(config: PollingClusterConfig, env: Env): ActorRef = {
    env.internalActorSystem.actorOf(PollingClusterActor.props(config, env))
  }
}

object PollingClusterActor {
  def props(config: PollingClusterConfig, env: Env) = Props(new PollingClusterActor(config, env))
}

class PollingClusterActor(config: PollingClusterConfig, env: Env) extends Actor {

  import context.dispatcher
  import scala.concurrent.duration._

  lazy val logger = play.api.Logger("otoroshi-cluster-polling-actor")

  def schedule(): Unit = {
    context.system.scheduler.scheduleOnce(config.every.millis, self, Sync)
  }

  def sync(): Unit = {
    logger.debug("Syncing with master ...")
    schedule()
  }

  override def preStart(): Unit = {
    logger.info("Start poll cluster actor")
    self ! Sync
  }

  override def receive: Receive = {
    case Sync => sync()
    case _ => logger.info("Unhandled message type")
  }
}