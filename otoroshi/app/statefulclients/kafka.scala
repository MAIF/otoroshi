package otoroshi.statefulclients

import org.apache.kafka.clients.producer.Producer
import otoroshi.events.{KafkaConfig, KafkaSettings}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.JsObject

import java.util.concurrent.atomic.AtomicBoolean

object KafkaStatefulClientConfig {
  def apply(obj: JsObject) = new KafkaStatefulClientConfig(KafkaConfig.format.reads(obj).get)
}

case class KafkaStatefulClientConfig(config: KafkaConfig) extends StatefulClientConfig[Producer[Array[Byte], String]] {

  private val open = new AtomicBoolean(false)

  override def start(env: otoroshi.env.Env): Producer[Array[Byte], String] = {
    val producer = KafkaSettings.producerSettings(env, config).createKafkaProducer()
    open.set(true)
    producer
  }

  override def stop(client: Producer[Array[Byte], String]): Unit = {
    open.set(false)
    client.close()
  }

  override def isOpen(client: Producer[Array[Byte], String]): Boolean = open.get()

  override def isSameConfig(other: StatefulClientConfig[_]): Boolean = other match {
    case k: KafkaStatefulClientConfig => k.config == config
    case _                            => false
  }
}
