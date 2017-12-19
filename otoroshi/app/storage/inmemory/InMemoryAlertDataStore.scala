package storage.inmemory

import akka.util.ByteString
import env.Env
import events.{AlertDataStore, AlertEvent}
import play.api.libs.json.Json
import storage.RedisLike

import scala.concurrent.{ExecutionContext, Future}

class InMemoryAlertDataStore(redisCli: RedisLike) extends AlertDataStore {

  override def count()(implicit ec: ExecutionContext, env: Env): Future[Long] = redisCli.llen("opun:events:alerts")

  override def findAllRaw(from: Long = 0, to: Long = 1000)(implicit ec: ExecutionContext,
                                                           env: Env): Future[Seq[ByteString]] =
    redisCli.lrange("opun:events:alerts", from, to)

  override def push(event: AlertEvent)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    for {
      config <- env.datastores.globalConfigDataStore.singleton()
      n      <- redisCli.lpush("opun:events:alerts", Json.stringify(event.toJson))
      -      <- redisCli.ltrim("opun:events:alerts", 0, config.maxLogsSize)
    } yield n
}
