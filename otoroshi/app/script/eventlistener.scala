package otoroshi.script

import java.util.concurrent.atomic.AtomicReference

import akka.actor.{Actor, ActorRef, Props}
import env.Env
import events.{AnalyticEvent, OtoroshiEvent}
import play.api.Logger

object InternalEventListenerActor {
  val logger                                           = Logger("otoroshi-plugins-internal-eventlistener-actor")
  def props(listener: InternalEventListener, env: Env) = Props(new InternalEventListenerActor(listener, env))
}

class InternalEventListenerActor(listener: InternalEventListener, env: Env) extends Actor {
  override def receive: Receive = {
    case evt: OtoroshiEvent =>
      try {
        listener.onEvent(evt)(env)
      } catch {
        case e: Throwable => InternalEventListenerActor.logger.error("Error while dispatching event", e)
      }
    case _ =>
  }
}

trait InternalEventListener {

  private val ref = new AtomicReference[ActorRef]()

  @inline def listening: Boolean = false

  @inline def onEvent(evt: OtoroshiEvent)(implicit env: Env): Unit = ()

  private[script] def startEvent(pluginId: String, env: Env): Unit = {
    if (listening) {
      val actor = env.analyticsActorSystem.actorOf(InternalEventListenerActor.props(this, env))
      ref.set(actor)
      env.analyticsActorSystem.eventStream.subscribe(actor, classOf[AnalyticEvent])
    }
  }

  private[script] def stopEvent(env: Env): Unit = {
    if (listening) {
      Option(ref.get()).foreach(env.analyticsActorSystem.eventStream.unsubscribe(_))
    }
  }
}

trait OtoroshiEventListener extends StartableAndStoppable with NamedPlugin with InternalEventListener {
  final def pluginType: PluginType = EventListenerType
}

object DefaultOtoroshiEventListener extends OtoroshiEventListener

object CompilingOtoroshiEventListener extends OtoroshiEventListener
