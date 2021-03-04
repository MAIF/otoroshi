package otoroshi.storage.drivers.lettuce

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import akka.actor.ActorSystem
import akka.util.ByteString
import otoroshi.env.Env
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.{RedisClient, SetArgs}
import play.api.Logger
import otoroshi.storage._

import scala.concurrent.{ExecutionContext, Future}

class ByteStringRedisCodec extends RedisCodec[String, ByteString] {

  private val utf8 = StandardCharsets.UTF_8

  override def decodeKey(bytes: ByteBuffer): String = utf8.decode(bytes).toString

  override def decodeValue(bytes: ByteBuffer): ByteString = ByteString(bytes)

  override def encodeKey(key: String): ByteBuffer = utf8.encode(key)

  override def encodeValue(value: ByteString): ByteBuffer = value.asByteBuffer

}

trait LettuceRedis extends RedisLike {
  def typ(key: String): Future[String]
  def info(): Future[String]
}

class LettuceRedisStandaloneAndSentinels(actorSystem: ActorSystem, client: RedisClient) extends LettuceRedis {

  import actorSystem.dispatcher

  import collection.JavaConverters._
  import scala.compat.java8.FutureConverters._

  lazy val redis = client.connect(new ByteStringRedisCodec()).async()

  lazy val logger = Logger("otoroshi-lettuce-redis")

  def typ(key: String): Future[String] = redis.`type`(key).toScala

  def info(): Future[String] = redis.info().toScala

  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] =
    redis.info().toScala.map(_ => Healthy).recover {
      case _ => Unreachable
    }

  override def stop(): Unit = redis.shutdown(true)

  override def flushall(): Future[Boolean] =
    redis.flushall().toScala.map {
      case "OK" => true
      case _    => false
    }

  override def get(key: String): Future[Option[ByteString]] = redis.get(key).toScala.map(Option.apply)

  override def mget(keys: String*): Future[Seq[Option[ByteString]]] =
    redis.mget(keys: _*).toScala.map(_.asScala.toSeq.map(v => if (v.hasValue) Option(v.getValue) else None))

  override def set(key: String, value: String, exSeconds: Option[Long], pxMilliseconds: Option[Long]): Future[Boolean] =
    setBS(key, ByteString(value), exSeconds, pxMilliseconds)

  override def setBS(
      key: String,
      value: ByteString,
      exSeconds: Option[Long],
      pxMilliseconds: Option[Long]
  ): Future[Boolean] = {
    exSeconds
      .map(v => SetArgs.Builder.ex(v))
      .orElse(
        pxMilliseconds.map(v => SetArgs.Builder.px(v))
      ) match {
      case None       =>
        redis.set(key, value).toScala.map {
          case "OK" => true
          case _    => false
        }
      case Some(args) =>
        redis.set(key, value, args).toScala.map {
          case "OK" => true
          case _    => false
        }
    }
  }

  override def del(keys: String*): Future[Long] = redis.del(keys: _*).toScala.map(_.longValue())

  override def incr(key: String): Future[Long] = redis.incr(key).toScala.map(_.longValue())

  override def incrby(key: String, increment: Long): Future[Long] =
    redis.incrby(key, increment).toScala.map(_.longValue())

  override def exists(key: String): Future[Boolean] = redis.exists(key).toScala.map(_ > 0)

  override def keys(pattern: String): Future[Seq[String]] = redis.keys(pattern).toScala.map(_.asScala.toSeq)

  override def hdel(key: String, fields: String*): Future[Long] = redis.hdel(key, fields: _*).toScala.map(_.longValue())

  override def hgetall(key: String): Future[Map[String, ByteString]] = redis.hgetall(key).toScala.map(_.asScala.toMap)

  override def hset(key: String, field: String, value: String): Future[Boolean] = hsetBS(key, field, ByteString(value))

  override def hsetBS(key: String, field: String, value: ByteString): Future[Boolean] =
    redis.hset(key, field, value).toScala.map(_.booleanValue())

  override def llen(key: String): Future[Long] = redis.llen(key).toScala.map(_.longValue())

  override def lpush(key: String, values: String*): Future[Long] = lpushBS(key, values.map(ByteString.apply): _*)

  override def lpushLong(key: String, values: Long*): Future[Long] =
    lpushBS(key, values.map(v => ByteString(v.toString)): _*)

  override def lpushBS(key: String, values: ByteString*): Future[Long] =
    redis.lpush(key, values: _*).toScala.map(_.longValue())

  override def lrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]] =
    redis.lrange(key, start, stop).toScala.map(_.asScala.toSeq)

  override def ltrim(key: String, start: Long, stop: Long): Future[Boolean] =
    redis.ltrim(key, start, stop).toScala.map {
      case "OK" => true
      case _    => false
    }

  override def pttl(key: String): Future[Long] = redis.pttl(key).toScala.map(_.longValue())

  override def ttl(key: String): Future[Long] = redis.ttl(key).toScala.map(_.longValue())

  override def expire(key: String, seconds: Int): Future[Boolean] =
    redis.expire(key, seconds).toScala.map(_.booleanValue())

  override def pexpire(key: String, milliseconds: Long): Future[Boolean] =
    redis.pexpire(key, milliseconds).toScala.map(_.booleanValue())

  override def sadd(key: String, members: String*): Future[Long] = saddBS(key, members.map(ByteString.apply): _*)

  override def saddBS(key: String, members: ByteString*): Future[Long] =
    redis.sadd(key, members: _*).toScala.map(_.longValue())

  override def sismember(key: String, member: String): Future[Boolean] = sismemberBS(key, ByteString(member))

  override def sismemberBS(key: String, member: ByteString): Future[Boolean] =
    redis.sismember(key, member).toScala.map(_.booleanValue())

  override def smembers(key: String): Future[Seq[ByteString]] = redis.smembers(key).toScala.map(_.asScala.toSeq)

  override def srem(key: String, members: String*): Future[Long] = sremBS(key, members.map(ByteString.apply): _*)

  override def sremBS(key: String, members: ByteString*): Future[Long] =
    redis.srem(key, members: _*).toScala.map(_.longValue())

  override def scard(key: String): Future[Long] = redis.scard(key).toScala.map(_.longValue())

  override def rawGet(key: String): Future[Option[Any]] = redis.get(key).toScala.map(Option.apply)

  override def setnxBS(key: String, value: ByteString, ttl: Option[Long])(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean] = {
    val args = SetArgs.Builder.nx()
    redis.set(key, value, ttl.map(v => args.px(v)).getOrElse(args)).toScala.map {
      case "OK" => true
      case _    => false
    }
  }
}

class LettuceRedisCluster(actorSystem: ActorSystem, client: RedisClusterClient) extends LettuceRedis {

  import actorSystem.dispatcher

  import collection.JavaConverters._
  import scala.compat.java8.FutureConverters._

  lazy val redis = client.connect(new ByteStringRedisCodec()).async()

  lazy val logger = Logger("otoroshi-lettuce-redis-cluster")

  def typ(key: String): Future[String] = redis.`type`(key).toScala

  def info(): Future[String] = redis.info().toScala

  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] =
    redis.info().toScala.map(_ => Healthy).recover {
      case _ => Unreachable
    }

  override def stop(): Unit = {
    redis.shutdown(true)
  }

  override def flushall(): Future[Boolean] =
    redis.flushall().toScala.map {
      case "OK" => true
      case _    => false
    }

  override def get(key: String): Future[Option[ByteString]] = redis.get(key).toScala.map(Option.apply)

  override def mget(keys: String*): Future[Seq[Option[ByteString]]] =
    redis.mget(keys: _*).toScala.map(_.asScala.toSeq.map(v => if (v.hasValue) Option(v.getValue) else None))

  override def set(key: String, value: String, exSeconds: Option[Long], pxMilliseconds: Option[Long]): Future[Boolean] =
    setBS(key, ByteString(value), exSeconds, pxMilliseconds)

  override def setBS(
      key: String,
      value: ByteString,
      exSeconds: Option[Long],
      pxMilliseconds: Option[Long]
  ): Future[Boolean] = {
    exSeconds
      .map(v => SetArgs.Builder.ex(v))
      .orElse(
        pxMilliseconds.map(v => SetArgs.Builder.px(v))
      ) match {
      case None       =>
        redis.set(key, value).toScala.map {
          case "OK" => true
          case _    => false
        }
      case Some(args) =>
        redis.set(key, value, args).toScala.map {
          case "OK" => true
          case _    => false
        }
    }
  }

  override def del(keys: String*): Future[Long] = redis.del(keys: _*).toScala.map(_.longValue())

  override def incr(key: String): Future[Long] = redis.incr(key).toScala.map(_.longValue())

  override def incrby(key: String, increment: Long): Future[Long] =
    redis.incrby(key, increment).toScala.map(_.longValue())

  override def exists(key: String): Future[Boolean] = redis.exists(key).toScala.map(_ > 0)

  override def keys(pattern: String): Future[Seq[String]] = redis.keys(pattern).toScala.map(_.asScala.toSeq)

  override def hdel(key: String, fields: String*): Future[Long] = redis.hdel(key, fields: _*).toScala.map(_.longValue())

  override def hgetall(key: String): Future[Map[String, ByteString]] = redis.hgetall(key).toScala.map(_.asScala.toMap)

  override def hset(key: String, field: String, value: String): Future[Boolean] = hsetBS(key, field, ByteString(value))

  override def hsetBS(key: String, field: String, value: ByteString): Future[Boolean] =
    redis.hset(key, field, value).toScala.map(_.booleanValue())

  override def llen(key: String): Future[Long] = redis.llen(key).toScala.map(_.longValue())

  override def lpush(key: String, values: String*): Future[Long] = lpushBS(key, values.map(ByteString.apply): _*)

  override def lpushLong(key: String, values: Long*): Future[Long] =
    lpushBS(key, values.map(v => ByteString(v.toString)): _*)

  override def lpushBS(key: String, values: ByteString*): Future[Long] =
    redis.lpush(key, values: _*).toScala.map(_.longValue())

  override def lrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]] =
    redis.lrange(key, start, stop).toScala.map(_.asScala.toSeq)

  override def ltrim(key: String, start: Long, stop: Long): Future[Boolean] =
    redis.ltrim(key, start, stop).toScala.map {
      case "OK" => true
      case _    => false
    }

  override def pttl(key: String): Future[Long] = redis.pttl(key).toScala.map(_.longValue())

  override def ttl(key: String): Future[Long] = redis.ttl(key).toScala.map(_.longValue())

  override def expire(key: String, seconds: Int): Future[Boolean] =
    redis.expire(key, seconds).toScala.map(_.booleanValue())

  override def pexpire(key: String, milliseconds: Long): Future[Boolean] =
    redis.pexpire(key, milliseconds).toScala.map(_.booleanValue())

  override def sadd(key: String, members: String*): Future[Long] = saddBS(key, members.map(ByteString.apply): _*)

  override def saddBS(key: String, members: ByteString*): Future[Long] =
    redis.sadd(key, members: _*).toScala.map(_.longValue())

  override def sismember(key: String, member: String): Future[Boolean] = sismemberBS(key, ByteString(member))

  override def sismemberBS(key: String, member: ByteString): Future[Boolean] =
    redis.sismember(key, member).toScala.map(_.booleanValue())

  override def smembers(key: String): Future[Seq[ByteString]] = redis.smembers(key).toScala.map(_.asScala.toSeq)

  override def srem(key: String, members: String*): Future[Long] = sremBS(key, members.map(ByteString.apply): _*)

  override def sremBS(key: String, members: ByteString*): Future[Long] =
    redis.srem(key, members: _*).toScala.map(_.longValue())

  override def scard(key: String): Future[Long] = redis.scard(key).toScala.map(_.longValue())

  override def rawGet(key: String): Future[Option[Any]] = redis.get(key).toScala.map(Option.apply)

  override def setnxBS(key: String, value: ByteString, ttl: Option[Long])(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean] = {
    val args = SetArgs.Builder.nx()
    redis.set(key, value, ttl.map(v => args.px(v)).getOrElse(args)).toScala.map {
      case "OK" => true
      case _    => false
    }
  }
}
