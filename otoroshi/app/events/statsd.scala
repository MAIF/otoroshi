package events

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Try}
import scala.util.control.NonFatal

import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorSystem, Props}
import play.api.libs.json._

import env.Env

import github.gphat.censorinus._

case class StatsdConfig(datadog: Boolean, host: String, port: Int)

case class StatsdEventClose()
case class StatsdEvent(action: String,
                       name: String,
                       value: Double,
                       strValue: String,
                       sampleRate: Double,
                       bypassSampler: Boolean,
                       config: StatsdConfig)

class StatsdWrapper(actorSystem: ActorSystem, env: Env) {

  lazy val statsdActor = actorSystem.actorOf(StatsdActor.props(env))

  lazy val defaultSampleRate: Double = 1.0

  def close(): Unit = {
    statsdActor ! StatsdEventClose()
  }

  def counter(
      name: String,
      value: Double,
      sampleRate: Double = defaultSampleRate,
      bypassSampler: Boolean = false
  )(implicit optConfig: Option[StatsdConfig]): Unit = {
    optConfig.foreach(
      config => statsdActor ! StatsdEvent("counter", name, value, "", sampleRate, bypassSampler, config)
    )
    if (optConfig.isEmpty) close()
  }

  def decrement(
      name: String,
      value: Double = 1,
      sampleRate: Double = defaultSampleRate,
      bypassSampler: Boolean = false
  )(implicit optConfig: Option[StatsdConfig]): Unit = {
    optConfig.foreach(
      config => statsdActor ! StatsdEvent("decrement", name, value, "", sampleRate, bypassSampler, config)
    )
    if (optConfig.isEmpty) close()
  }

  def gauge(
      name: String,
      value: Double,
      sampleRate: Double = defaultSampleRate,
      bypassSampler: Boolean = false
  )(implicit optConfig: Option[StatsdConfig]): Unit = {
    optConfig.foreach(config => statsdActor ! StatsdEvent("gauge", name, value, "", sampleRate, bypassSampler, config))
    if (optConfig.isEmpty) close()
  }

  def increment(
      name: String,
      value: Double = 1,
      sampleRate: Double = defaultSampleRate,
      bypassSampler: Boolean = false
  )(implicit optConfig: Option[StatsdConfig]): Unit = {
    optConfig.foreach(
      config => statsdActor ! StatsdEvent("increment", name, value, "", sampleRate, bypassSampler, config)
    )
    if (optConfig.isEmpty) close()
  }

  def meter(
      name: String,
      value: Double,
      sampleRate: Double = defaultSampleRate,
      bypassSampler: Boolean = false
  )(implicit optConfig: Option[StatsdConfig]): Unit = {
    optConfig.foreach(config => statsdActor ! StatsdEvent("meter", name, value, "", sampleRate, bypassSampler, config))
    if (optConfig.isEmpty) close()
  }

  def set(name: String, value: String)(implicit optConfig: Option[StatsdConfig]): Unit = {
    optConfig.foreach(config => statsdActor ! StatsdEvent("set", name, 0.0, value, 0.0, false, config))
    if (optConfig.isEmpty) close()
  }

  def timer(name: String, milliseconds: Double, sampleRate: Double = defaultSampleRate, bypassSampler: Boolean = false)(
      implicit optConfig: Option[StatsdConfig]
  ): Unit = {
    optConfig.foreach(
      config => statsdActor ! StatsdEvent("timer", name, milliseconds, "", sampleRate, bypassSampler, config)
    )
    if (optConfig.isEmpty) close()
  }
}

class StatsdActor(env: Env) extends Actor {

  implicit val ec = env.otoroshiExecutionContext

  var config: Option[StatsdConfig]           = None
  var statsdclient: Option[StatsDClient]     = None
  var datadogclient: Option[DogStatsDClient] = None

  lazy val logger = play.api.Logger("otoroshi-statsd-actor")

  override def receive: Receive = {
    case StatsdEventClose() => {
      config = None
      statsdclient.foreach(_.shutdown())
      datadogclient.foreach(_.shutdown())
      statsdclient = None
      datadogclient = None
    }
    case event: StatsdEvent if config.isEmpty => {
      config = Some(event.config)
      statsdclient.foreach(_.shutdown())
      datadogclient.foreach(_.shutdown())
      event.config.datadog match {
        case true =>
          logger.debug("Running statsd for DataDog")
          datadogclient = Some(new DogStatsDClient(event.config.host, event.config.port, "otoroshi"))
        case false =>
          logger.debug("Running statsd")
          statsdclient = Some(new StatsDClient(event.config.host, event.config.port, "otoroshi"))
      }
      self ! event
    }
    case event: StatsdEvent if config.isDefined && config.get != event.config => {
      config = Some(event.config)
      statsdclient.foreach(_.shutdown())
      datadogclient.foreach(_.shutdown())
      event.config.datadog match {
        case true =>
          logger.debug("Reconfiguring statsd for DataDog")
          datadogclient = Some(new DogStatsDClient(event.config.host, event.config.port, "otoroshi"))
        case false =>
          logger.debug("Reconfiguring statsd")
          statsdclient = Some(new StatsDClient(event.config.host, event.config.port, "otoroshi"))
      }
      self ! event
    }
    case StatsdEvent("counter", name, value, _, sampleRate, bypassSampler, StatsdConfig(false, _, _)) =>
      statsdclient.get.counter(name, value, sampleRate, bypassSampler)
    case StatsdEvent("decrement", name, value, _, sampleRate, bypassSampler, StatsdConfig(false, _, _)) =>
      statsdclient.get.decrement(name, value, sampleRate, bypassSampler)
    case StatsdEvent("gauge", name, value, _, sampleRate, bypassSampler, StatsdConfig(false, _, _)) =>
      statsdclient.get.gauge(name, value, sampleRate, bypassSampler)
    case StatsdEvent("increment", name, value, _, sampleRate, bypassSampler, StatsdConfig(false, _, _)) =>
      statsdclient.get.increment(name, value, sampleRate, bypassSampler)
    case StatsdEvent("meter", name, value, _, sampleRate, bypassSampler, StatsdConfig(false, _, _)) =>
      statsdclient.get.meter(name, value, sampleRate, bypassSampler)
    case StatsdEvent("timer", name, value, _, sampleRate, bypassSampler, StatsdConfig(false, _, _)) =>
      statsdclient.get.timer(name, value, sampleRate, bypassSampler)
    case StatsdEvent("set", name, _, value, sampleRate, bypassSampler, StatsdConfig(false, _, _)) =>
      statsdclient.get.set(name, value)

    case StatsdEvent("counter", name, value, _, sampleRate, bypassSampler, StatsdConfig(true, _, _)) =>
      datadogclient.get.counter(name, value, sampleRate, Seq.empty[String], bypassSampler)
    case StatsdEvent("decrement", name, value, _, sampleRate, bypassSampler, StatsdConfig(true, _, _)) =>
      datadogclient.get.decrement(name, value, sampleRate, Seq.empty[String], bypassSampler)
    case StatsdEvent("gauge", name, value, _, sampleRate, bypassSampler, StatsdConfig(true, _, _)) =>
      datadogclient.get.gauge(name, value, sampleRate, Seq.empty[String], bypassSampler)
    case StatsdEvent("increment", name, value, _, sampleRate, bypassSampler, StatsdConfig(true, _, _)) =>
      datadogclient.get.increment(name, value, sampleRate, Seq.empty[String], bypassSampler)
    case StatsdEvent("meter", name, value, _, sampleRate, bypassSampler, StatsdConfig(true, _, _)) =>
      datadogclient.get.histogram(name, value, sampleRate, Seq.empty[String], bypassSampler)
    case StatsdEvent("timer", name, value, _, sampleRate, bypassSampler, StatsdConfig(true, _, _)) =>
      datadogclient.get.timer(name, value, sampleRate, Seq.empty[String], bypassSampler)
    case StatsdEvent("set", name, _, value, sampleRate, bypassSampler, StatsdConfig(true, _, _)) =>
      datadogclient.get.set(name, value, Seq.empty[String])

    case _ =>
  }
}

object StatsdActor {
  def props(env: Env) = Props(new StatsdActor(env))
}
