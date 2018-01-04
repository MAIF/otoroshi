package storage.redis

import akka.util.ByteString
import env.Env
import events.{AuditDataStore, AuditEvent}
import play.api.libs.json.Json
import redis.RedisClientMasterSlaves

import scala.concurrent.{ExecutionContext, Future}

class RedisAuditDataStore(redisCli: RedisClientMasterSlaves) extends AuditDataStore {

  override def count()(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.llen(s"${env.storageRoot}:events:audit")

  override def findAllRaw(from: Long = 0, to: Long = 1000)(implicit ec: ExecutionContext,
                                                           env: Env): Future[Seq[ByteString]] =
    redisCli.lrange(s"${env.storageRoot}:events:audit", from, to)

  override def push(event: AuditEvent)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    for {
      config <- env.datastores.globalConfigDataStore.singleton()
      n      <- redisCli.lpush(s"${env.storageRoot}:events:audit", Json.stringify(event.toJson))
      -      <- redisCli.ltrim(s"${env.storageRoot}:events:audit", 0, config.maxLogsSize)
    } yield n
}
