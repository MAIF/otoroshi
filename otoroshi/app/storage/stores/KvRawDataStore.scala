package otoroshi.storage.stores

import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.storage.{RawDataStore, RedisLike}

import scala.concurrent.{ExecutionContext, Future}

class KvRawDataStore(redis: RedisLike) extends RawDataStore {

  override def exists(key: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = redis.exists(key)

  override def get(key: String)(implicit ec: ExecutionContext, env: Env): Future[Option[ByteString]] = redis.get(key)

  override def pttl(key: String)(implicit ec: ExecutionContext, env: Env): Future[Long] = redis.pttl(key)

  override def pexpire(key: String, pttl: Long)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    redis.pexpire(key, pttl)

  override def mget(keys: Seq[String])(implicit ec: ExecutionContext, env: Env): Future[Seq[Option[ByteString]]] =
    redis.mget(keys: _*)

  override def set(key: String, value: ByteString, ttl: Option[Long])(implicit ec: ExecutionContext,
                                                                      env: Env): Future[Boolean] =
    redis.setBS(key, value, pxMilliseconds = ttl)

  override def del(keys: Seq[String])(implicit ec: ExecutionContext, env: Env): Future[Long] = redis.del(keys: _*)

  override def incrby(key: String, incr: Long)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redis.incrby(key, incr)

  override def keys(pattern: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[String]] = redis.keys(pattern)

  override def setnx(key: String, value: ByteString, ttl: Option[Long])(implicit ec: ExecutionContext,
                                                                        env: Env): Future[Boolean] =
    redis.setnxBS(key, value, ttl)

  override def sadd(key: String, members: Seq[ByteString]): Future[Long] = redis.saddBS(key, members:_*)

  override def sismember(key: String, member: ByteString): Future[Boolean] = redis.sismemberBS(key, member)

  override def smembers(key: String): Future[Seq[ByteString]] = redis.smembers(key)
}
