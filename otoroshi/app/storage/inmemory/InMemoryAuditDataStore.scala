package storage.inmemory

import akka.util.ByteString
import env.Env
import events.{AuditDataStore, AuditEvent}
import play.api.libs.json.Json
import storage.RedisLike

import scala.concurrent.{ExecutionContext, Future}

class InMemoryAuditDataStore(redisCli: RedisLike) extends AuditDataStore {

  override def count()(implicit ec: ExecutionContext, env: Env): Future[Long] = redisCli.llen("opun:events:audit")

  override def findAllRaw(from: Long = 0, to: Long = 1000)(implicit ec: ExecutionContext,
                                                           env: Env): Future[Seq[ByteString]] =
    redisCli.lrange("opun:events:audit", from, to)

  override def push(event: AuditEvent)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    for {
      config <- env.datastores.globalConfigDataStore.singleton()
      n      <- redisCli.lpush("opun:events:audit", Json.stringify(event.toJson))
      -      <- redisCli.ltrim("opun:events:audit", 0, config.maxLogsSize)
    } yield n
}
