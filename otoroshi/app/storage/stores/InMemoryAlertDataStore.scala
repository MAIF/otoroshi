package otoroshi.storage.stores

import akka.util.ByteString
import env.Env
import events.{AlertDataStore, AlertEvent}
import play.api.libs.json.{JsValue, Json}
import otoroshi.storage.RedisLike

import scala.concurrent.{ExecutionContext, Future}

class InMemoryAlertDataStore(redisCli: RedisLike) extends AlertDataStore {

  override def count()(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.llen(s"${env.storageRoot}:events:alerts")

  override def findAllRaw(from: Long = 0, to: Long = 1000)(implicit ec: ExecutionContext,
                                                           env: Env): Future[Seq[ByteString]] =
    redisCli.lrange(s"${env.storageRoot}:events:alerts", from, to)

  override def push(event: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    for {
      config <- env.datastores.globalConfigDataStore.singleton()
      n      <- redisCli.lpush(s"${env.storageRoot}:events:alerts", Json.stringify(event))
      -      <- redisCli.ltrim(s"${env.storageRoot}:events:alerts", 0, config.maxLogsSize)
    } yield n
}
