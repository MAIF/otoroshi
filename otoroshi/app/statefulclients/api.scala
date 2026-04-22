package otoroshi.statefulclients

import akka.http.scaladsl.util.FastFuture
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import java.util.concurrent.Executors
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

trait StatefulClientConfig[A] {
  def isOpen(client: A): Boolean
  def start(env: Env): A
  def stop(client: A): Unit
  def isSameConfig(other: StatefulClientConfig[_]): Boolean
}

case class StatefulClientWrapper[A](config: StatefulClientConfig[A], client: A) {
  def stopClient(): Unit    = config.stop(client)
  def isClientOpen: Boolean = config.isOpen(client)
}

// StatefulClientsManager allow to bind stateful clients to otoroshi node lifecycle,
// scoped to node kind (all, leader, worker),
// with optional pre-allocation from static or global config.
// id of the client is supposed to be stable
class StatefulClientsManager(env: Env) {

  private val logger                                                     = Logger("otoroshi-stateful-clients-manager")
  private val statefulClients: TrieMap[String, StatefulClientWrapper[_]] =
    new TrieMap[String, StatefulClientWrapper[_]]()
  private implicit val ec                                                = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))

  def client[T](id: String, config: StatefulClientConfig[T]): T = synchronized {
    statefulClients.get(id) match {
      case Some(wrapper) =>
        val typed = wrapper.asInstanceOf[StatefulClientWrapper[T]]
        if (config.isSameConfig(typed.config) && typed.isClientOpen) {
          typed.client
        } else {
          logger.info(s"stateful client '$id' config changed or connection closed, reconnecting")
          val newClient: T = config.start(env)
          statefulClients.put(id, StatefulClientWrapper[T](config, newClient))
          Future {
            Try(typed.stopClient()) match {
              case Failure(e) => logger.error(s"Error while stopping client '${id}'", e)
              case _          =>
            }
          }
          newClient
        }
      case None          =>
        logger.info(s"starting new stateful client '$id'")
        val newClient: T = config.start(env)
        statefulClients.put(id, StatefulClientWrapper[T](config, newClient))
        newClient
    }
  }

  private def startAndRegister[T](id: String, config: StatefulClientConfig[T]): Unit = {
    val c: T = config.start(env)
    statefulClients.put(id, StatefulClientWrapper[T](config, c))
  }

  def start(): Future[Unit] = synchronized {
    logger.info("Starting stateful clients manager")
    getClientConfigsFromConfig().foreach { case (id, config) =>
      logger.info(s"starting pre-configured stateful client '$id'")
      startAndRegister(id, config)
    }
    FastFuture.successful(())
  }

  def stop(): Future[Unit] = synchronized {
    statefulClients.foreach { case (id, wrapper) =>
      logger.info(s"stopping stateful client '$id'")
      Try(wrapper.stopClient())
    }
    statefulClients.clear()
    FastFuture.successful(())
  }

  private def getClientConfigsFromConfig(): List[(String, StatefulClientConfig[_])] = {
    val staticConfigs     = env.configurationJson
      .select("otoroshi")
      .select("stateful-clients")
      .asOpt[Seq[JsObject]]
      .getOrElse(Seq.empty)
      .toList
    val staticJsonConfigs = env.configurationJson
      .select("otoroshi")
      .select("stateful-clients-json")
      .asOpt[String]
      .flatMap(str => str.parseJson.asOpt[Seq[JsObject]])
      .getOrElse(Seq.empty)
      .toList
    val dynConfigs        = env.datastores.globalConfigDataStore
      .latest()(env.otoroshiExecutionContext, env)
      .plugins
      .config
      .select("stateful-clients")
      .asOpt[Seq[JsObject]]
      .getOrElse(Seq.empty)
      .toList
    val configs           = staticConfigs ++ staticJsonConfigs ++ dynConfigs
    configs.flatMap { config =>
      val id       = config.select("id").asString
      val kind     = config.select("kind").asString
      val nodeKind = config.select("node_kind").asOpt[String].getOrElse("all")
      if (nodeKind != "all" && nodeKind != env.clusterConfig.mode.name.toLowerCase) {
        Seq.empty
      } else {
        kind match {
          case "redis"  => Seq((id, LettuceStatefulClientConfig(config)))
          case "pg"     => Seq((id, PgStatefulClientConfig(config)))
          case "kafka"  => Seq((id, KafkaStatefulClientConfig(config)))
          case "pulsar" => Seq((id, PulsarStatefulClientConfig(config)))
          case _        => Seq.empty
        }
      }
    }
  }
}
