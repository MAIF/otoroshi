
import env.Env
import events.{AlertEvent, OtoroshiEvent}
import otoroshi.script.OtoroshiEventListener

class CustomListener extends OtoroshiEventListener {
  override def onEvent(evt: OtoroshiEvent)(implicit env: Env): Unit = {
    case alert: AlertEvent if alert.`@serviceId` == "admin-api" =>
      println("Alert ! Alert !")
    case _ =>
  }
}
