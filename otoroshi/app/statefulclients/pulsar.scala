package otoroshi.statefulclients

import com.sksamuel.pulsar4s.PulsarClient
import otoroshi.events.{PulsarConfig, PulsarSetting}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.JsObject

import java.util.concurrent.atomic.AtomicBoolean

object PulsarStatefulClientConfig {
  def apply(obj: JsObject) = new PulsarStatefulClientConfig(PulsarConfig.format.reads(obj).get)
}

case class PulsarStatefulClientConfig(config: PulsarConfig) extends StatefulClientConfig[PulsarClient] {

  private val open = new AtomicBoolean(false)

  override def start(env: otoroshi.env.Env): PulsarClient = {
    val client = PulsarSetting.client(env, config)
    open.set(true)
    client
  }

  override def stop(client: PulsarClient): Unit = {
    open.set(false)
    client.close()
  }

  override def isOpen(client: PulsarClient): Boolean = open.get()

  override def sameConfig(other: StatefulClientConfig[_]): Boolean = other match {
    case p: PulsarStatefulClientConfig => p.config == config
    case _ => false
  }
}
