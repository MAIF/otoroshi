package otoroshi.statefulclients

import org.apache.pekko.util.ByteString
import io.lettuce.core._
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import otoroshi.storage.drivers.lettuce._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.JsObject

import scala.collection.JavaConverters._

object LettuceStatefulClientConfig {
  def apply(obj: JsObject) = new LettuceStatefulClientConfig(obj.select("uri").asString)
}

case class LettuceStatefulClientConfig(uri: String)
    extends StatefulClientConfig[StatefulRedisConnection[String, ByteString]] {

  private var redisClient: RedisClient = _

  override def isOpen(client: StatefulRedisConnection[String, ByteString]): Boolean = client.isOpen

  override def start(env: otoroshi.env.Env): StatefulRedisConnection[String, ByteString] = {
    redisClient = RedisClient.create(uri)
    redisClient.connect(new ByteStringRedisCodec())
  }

  override def stop(client: StatefulRedisConnection[String, ByteString]): Unit = {
    client.close()
    Option(redisClient).foreach(_.shutdown())
  }

  override def isSameConfig(other: StatefulClientConfig[_]): Boolean = other match {
    case l: LettuceStatefulClientConfig => l.uri == uri
    case _                              => false
  }
}

// Dedicated stateful client for the distributed rate-limiter Redis. Returns a RedisLike (LettuceRedis)
// so it can be used in place of env.datastores.redis. Lifecycle of the underlying RedisClient is managed
// here: stop() shuts down the Lettuce client locally — we never call the wrapper's stop() because that
// would emit a SHUTDOWN command to the Redis server.
case class DistributedRateLimiterLettuceStatefulClientConfig(uri: String) extends StatefulClientConfig[LettuceRedis] {

  private var redisClient: RedisClient = _

  override def isOpen(client: LettuceRedis): Boolean = Option(redisClient).exists(_ != null)

  override def start(env: otoroshi.env.Env): LettuceRedis = {
    val client = RedisClient.create(uri)
    redisClient = client
    new LettuceRedisStandaloneAndSentinels(env.otoroshiActorSystem, client, env)
  }

  override def stop(client: LettuceRedis): Unit = {
    Option(redisClient).foreach(_.shutdown())
  }

  override def isSameConfig(other: StatefulClientConfig[_]): Boolean = other match {
    case l: DistributedRateLimiterLettuceStatefulClientConfig => l.uri == uri
    case _                                                    => false
  }
}

case class LettuceClusterStatefulClientConfig(uris: Seq[String])
    extends StatefulClientConfig[StatefulRedisClusterConnection[String, ByteString]] {

  private var redisClient: RedisClusterClient = _

  override def isOpen(client: StatefulRedisClusterConnection[String, ByteString]): Boolean = client.isOpen

  override def start(env: otoroshi.env.Env): StatefulRedisClusterConnection[String, ByteString] = {
    val nodes = uris.map(RedisURI.create).asJava
    redisClient = RedisClusterClient.create(nodes)
    redisClient.connect(new ByteStringRedisCodec())
  }

  override def stop(client: StatefulRedisClusterConnection[String, ByteString]): Unit = {
    client.close()
    Option(redisClient).foreach(_.shutdown())
  }

  override def isSameConfig(other: StatefulClientConfig[_]): Boolean = other match {
    case l: LettuceClusterStatefulClientConfig => l.uris == uris
    case _                                     => false
  }
}

// Cluster variant of the dedicated rate-limiter stateful client. Returns a RedisLike (LettuceRedis)
// backed by a RedisClusterClient so it can be used in place of env.datastores.redis when the dedicated
// rate-limiter redis is itself a Redis Cluster.
case class DistributedRateLimiterLettuceClusterStatefulClientConfig(uris: Seq[String])
    extends StatefulClientConfig[LettuceRedis] {

  private var redisClient: RedisClusterClient = _

  override def isOpen(client: LettuceRedis): Boolean = Option(redisClient).exists(_ != null)

  override def start(env: otoroshi.env.Env): LettuceRedis = {
    val nodes  = uris.map(RedisURI.create).asJava
    val client = RedisClusterClient.create(nodes)
    redisClient = client
    new LettuceRedisCluster(env.otoroshiActorSystem, client)
  }

  override def stop(client: LettuceRedis): Unit = {
    Option(redisClient).foreach(_.shutdown())
  }

  override def isSameConfig(other: StatefulClientConfig[_]): Boolean = other match {
    case l: DistributedRateLimiterLettuceClusterStatefulClientConfig => l.uris == uris
    case _                                                           => false
  }
}
