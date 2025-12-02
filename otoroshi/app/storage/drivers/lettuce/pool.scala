package otoroshi.storage.drivers.lettuce


import akka.http.scaladsl.util.FastFuture
import io.lettuce.core._
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.RedisCodec
import otoroshi.env.Env
import play.api.Logger

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future}

object RedisConnectionPool {
  val logger = Logger("otoroshi-lettuce-connections-pool")
}

class DumbRedisConnectionPool[K, V](
  client: RedisClient,
  codec: RedisCodec[K, V],
  maxSize: Int,
)(implicit ec: ExecutionContext, env: Env) {

  private val counter = new AtomicInteger(0)
  private val connections: Seq[(Int, StatefulRedisConnection[K, V])] = (1 to maxSize).map { idx =>
    if (RedisConnectionPool.logger.isDebugEnabled) RedisConnectionPool.logger.debug(s"create redis connection: ${idx}")
    (idx, client.connect(codec))
  }

  def acquire(): Future[(Int, StatefulRedisConnection[K, V])] = {
    val connIdx = counter.incrementAndGet() % connections.size
    FastFuture.successful(connections.apply(connIdx))
  }

  def release(conn: (Int, StatefulRedisConnection[K, V])): Unit = ()

  def close(): Future[Unit] = {
    connections.foreach(_._2.close())
    FastFuture.successful(())
  }
}