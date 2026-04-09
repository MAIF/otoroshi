package otoroshi.statefulclients

import akka.util.ByteString
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import otoroshi.storage.drivers.lettuce.ByteStringRedisCodec
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.JsObject

object LettuceStatefulClientConfig {
  def apply(obj: JsObject) = new LettuceStatefulClientConfig(obj.select("uri").asString)
}

case class LettuceStatefulClientConfig(uri: String) extends StatefulClientConfig[StatefulRedisConnection[String, ByteString]] {

  private var redisClient: RedisClient = _

  override def isOpen(client: StatefulRedisConnection[String, ByteString]): Boolean = client.isOpen

  override def start(): StatefulRedisConnection[String, ByteString] = {
    redisClient = RedisClient.create(uri)
    redisClient.connect(new ByteStringRedisCodec())
  }

  override def stop(client: StatefulRedisConnection[String, ByteString]): Unit = {
    client.close()
    Option(redisClient).foreach(_.shutdown())
  }

  override def sameConfig(other: StatefulClientConfig[_]): Boolean = other match {
    case l: LettuceStatefulClientConfig => l.uri == uri
    case _ => false
  }
}
