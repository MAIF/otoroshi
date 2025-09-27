package otoroshi.storage.stores

import org.apache.pekko.http.scaladsl.util.FastFuture
import org.apache.pekko.util.ByteString
import otoroshi.env.Env
import otoroshi.storage.{RawDataStore, RedisLike}

import scala.concurrent.{ExecutionContext, Future}

class KvRawDataStore(redis: RedisLike) extends RawDataStore {

  override def exists(key: String)(using ec: ExecutionContext, env: Env): Future[Boolean] = redis.exists(key)

  override def get(key: String)(using ec: ExecutionContext, env: Env): Future[Option[ByteString]] = redis.get(key)

  override def pttl(key: String)(using ec: ExecutionContext, env: Env): Future[Long] = redis.pttl(key)

  override def pexpire(key: String, pttl: Long)(using ec: ExecutionContext, env: Env): Future[Boolean] =
    redis.pexpire(key, pttl)

  override def mget(keys: Seq[String])(using ec: ExecutionContext, env: Env): Future[Seq[Option[ByteString]]] =
    redis.mget(keys*)

  override def set(key: String, value: ByteString, ttl: Option[Long])(using
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean] =
    redis.setBS(key, value, pxMilliseconds = ttl)

  override def del(keys: Seq[String])(using ec: ExecutionContext, env: Env): Future[Long] = {
    if (keys.nonEmpty) {
      redis.del(keys*)
    } else {
      FastFuture.successful(0L)
    }
  }

  override def incrby(key: String, incr: Long)(using ec: ExecutionContext, env: Env): Future[Long] =
    redis.incrby(key, incr)

  override def keys(pattern: String)(using ec: ExecutionContext, env: Env): Future[Seq[String]] = redis.keys(pattern)

  override def setnx(key: String, value: ByteString, ttl: Option[Long])(using
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean] =
    redis.setnxBS(key, value, ttl)

  override def sadd(key: String, members: Seq[ByteString]): Future[Long] = redis.saddBS(key, members*)

  override def sismember(key: String, member: ByteString): Future[Boolean] = redis.sismemberBS(key, member)

  override def smembers(key: String): Future[Seq[ByteString]] = redis.smembers(key)
}
