package storage

import akka.util.ByteString
import env.Env
import events._
import gateway.RequestsDataStore
import models._
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.{Configuration, Environment, Logger}
import akka.stream.scaladsl._
import akka.stream._
import akka.NotUsed
import akka.http.scaladsl.util.FastFuture

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

trait DataStores {
  def before(configuration: Configuration, environment: Environment, lifecycle: ApplicationLifecycle): Future[Unit]
  def after(configuration: Configuration, environment: Environment, lifecycle: ApplicationLifecycle): Future[Unit]
  def privateAppsUserDataStore: PrivateAppsUserDataStore
  def backOfficeUserDataStore: BackOfficeUserDataStore
  def serviceGroupDataStore: ServiceGroupDataStore
  def globalConfigDataStore: GlobalConfigDataStore
  def apiKeyDataStore: ApiKeyDataStore
  def serviceDescriptorDataStore: ServiceDescriptorDataStore
  def u2FAdminDataStore: U2FAdminDataStore
  def simpleAdminDataStore: SimpleAdminDataStore
  def alertDataStore: AlertDataStore
  def auditDataStore: AuditDataStore
  def healthCheckDataStore: HealthCheckDataStore
  def errorTemplateDataStore: ErrorTemplateDataStore
  def requestsDataStore: RequestsDataStore
  def canaryDataStore: CanaryDataStore
  def flushAll(): Future[Boolean]
}

trait BasicStore[T] {
  def key(id: String): Key
  def keyStr(id: String): String = key(id).key
  def extractId(value: T): String
  def extractKey(value: T): Key = key(extractId(value))
  def findAll(force: Boolean = false)(implicit ec: ExecutionContext, env: Env): Future[Seq[T]]
  def findAllByKeys(ids: Seq[Key])(implicit ec: ExecutionContext, env: Env): Future[Seq[T]] =
    findAllById(ids.map(_.key))
  def findAllById(ids: Seq[String])(implicit ec: ExecutionContext, env: Env): Future[Seq[T]]
  def findByKey(id: Key)(implicit ec: ExecutionContext, env: Env): Future[Option[T]] = findById(id.key)
  def findById(id: String)(implicit ec: ExecutionContext, env: Env): Future[Option[T]]
  def deleteByKey(id: Key)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = delete(id.key)
  def delete(id: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def delete(value: T)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def deleteAll()(implicit ec: ExecutionContext, env: Env): Future[Long]
  def set(value: T, pxMilliseconds: Option[Duration] = None)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def exists(id: Key)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = exists(id.key)
  def exists(id: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def exists(value: T)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  // Streaming
  def streamedFind(predicate: T => Boolean, fetchSize: Int, page: Int = 0, pageSize: Int = Int.MaxValue)(
      implicit ec: ExecutionContext,
      mat: Materializer,
      env: Env
  ): Source[T, NotUsed]
  def streamedFindAndMat(predicate: T => Boolean, fetchSize: Int, page: Int = 0, pageSize: Int = Int.MaxValue)(
      implicit ec: ExecutionContext,
      mat: Materializer,
      env: Env
  ): Future[Seq[T]]
  def clearFromCache(id: String)(implicit env: Env): Unit
  def clearCache(id: String)(implicit env: Env): Unit
}

trait RedisLike {
  def start(): Unit = {}
  def stop(): Unit
  def flushall(): Future[Boolean]
  def get(key: String): Future[Option[ByteString]]
  def mget(keys: String*): Future[Seq[Option[ByteString]]]
  def set(key: String,
          value: String,
          exSeconds: Option[Long] = None,
          pxMilliseconds: Option[Long] = None): Future[Boolean]
  def setBS(key: String,
            value: ByteString,
            exSeconds: Option[Long] = None,
            pxMilliseconds: Option[Long] = None): Future[Boolean]
  def del(keys: String*): Future[Long]
  def incr(key: String): Future[Long]
  def incrby(key: String, increment: Long): Future[Long]
  def exists(key: String): Future[Boolean]
  def keys(pattern: String): Future[Seq[String]]
  def hdel(key: String, fields: String*): Future[Long]
  def hgetall(key: String): Future[Map[String, ByteString]]
  def hset(key: String, field: String, value: String): Future[Boolean]
  def hsetBS(key: String, field: String, value: ByteString): Future[Boolean]
  def llen(key: String): Future[Long]
  def lpush(key: String, values: String*): Future[Long]
  def lpushLong(key: String, values: Long*): Future[Long]
  def lpushBS(key: String, values: ByteString*): Future[Long]
  def lrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]]
  def ltrim(key: String, start: Long, stop: Long): Future[Boolean]
  def pttl(key: String): Future[Long]
  def ttl(key: String): Future[Long]
  def expire(key: String, seconds: Int): Future[Boolean]
  def pexpire(key: String, milliseconds: Long): Future[Boolean]
  def sadd(key: String, members: String*): Future[Long]
  def saddBS(key: String, members: ByteString*): Future[Long]
  def sismember(key: String, member: String): Future[Boolean]
  def sismemberBS(key: String, member: ByteString): Future[Boolean]
  def smembers(key: String): Future[Seq[ByteString]]
  def srem(key: String, members: String*): Future[Long]
  def sremBS(key: String, members: ByteString*): Future[Long]
  def scard(key: String): Future[Long]
}

trait RedisLikeStore[T] extends BasicStore[T] {
  def fmt: Format[T]
  private lazy val name                          = this.getClass.getSimpleName.replace("$", "")
  def _findAllCached(implicit env: Env): Boolean = env.useCache
  def redisLike(implicit env: Env): RedisLike
  def reader: Reads[T]          = fmt
  def writer: Writes[T]         = fmt
  def toJson(value: T): JsValue = writer.writes(value)
  def fromJsons(value: JsValue): T =
    try {
      reader.reads(value).get
    } catch {
      case e: Throwable => {
        Logger("otoroshi-redis-like-store").error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }
  def fromJsonSafe(value: JsValue): JsResult[T] = reader.reads(value)

  private val findAllCache     = new java.util.concurrent.atomic.AtomicReference[Seq[T]](null)
  private val lastFindAllCache = new java.util.concurrent.atomic.AtomicLong(0L)

  def clearFromCache(id: String)(implicit env: Env): Unit = {
    if (_findAllCached) {
      val values = findAllCache.get
      if (values != null) {
        findAllCache.set(values.filterNot(s => extractId(s) == id))
      }
    }
  }

  def clearCache(id: String)(implicit env: Env): Unit = {
    if (_findAllCached) {
      findAllCache.set(null)
    }
  }

  def findAll(force: Boolean = false)(implicit ec: ExecutionContext, env: Env): Future[Seq[T]] = {

    def actualFindAll() =
      redisLike
        .keys(key("*").key)
        .flatMap(
          keys =>
            if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]])
            else redisLike.mget(keys: _*)
        )
        .map(
          seq =>
            seq.filter(_.isDefined).map(_.get).map(v => fromJsonSafe(Json.parse(v.utf8String))).collect {
              case JsSuccess(i, _) => i
          }
        )

    if (_findAllCached) {
      val time = System.currentTimeMillis
      val ref  = findAllCache.get()
      if (ref == null) {
        lastFindAllCache.set(time)
        actualFindAll().andThen {
          case Success(services) => findAllCache.set(services)
        }
      } else {
        if (force || (lastFindAllCache.get() + 2000) < time) {
          lastFindAllCache.set(time)
          actualFindAll().andThen {
            case Success(services) => findAllCache.set(services)
          }
        } else if ((lastFindAllCache.get() + 1000) < time) {
          lastFindAllCache.set(time)
          actualFindAll().andThen {
            case Success(services) => findAllCache.set(services)
          }
          FastFuture.successful(ref)
        } else {
          FastFuture.successful(ref)
        }
      }
    } else {
      actualFindAll()
    }
  }
  def findAllById(ids: Seq[String])(implicit ec: ExecutionContext, env: Env): Future[Seq[T]] = ids match {
    case keys if keys.isEmpty => FastFuture.successful(Seq.empty[T])
    case keys if _findAllCached && findAllCache.get() != null => {
      findAll(true) // TODO : update findAllCache ??? FIXME ???
      FastFuture.successful(findAllCache.get().filter(s => keys.contains(extractId(s))))
    }
    case keys =>
      redisLike.mget(keys.map(keyStr): _*).map { values: Seq[Option[ByteString]] =>
        values.flatMap { opt =>
          opt.flatMap(bs => fromJsonSafe(Json.parse(bs.utf8String)).asOpt)
        }
      }
  }
  def findById(id: String)(implicit ec: ExecutionContext, env: Env): Future[Option[T]] =
    redisLike.get(key(id).key).map(_.flatMap(v => fromJsonSafe(Json.parse(v.utf8String)).asOpt))
  def deleteAll()(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisLike.keys(key("*").key).flatMap { keys =>
      redisLike.del(keys: _*)
    }
  def delete(id: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    redisLike.del(key(id).key).map(_ > 0)
  def delete(value: T)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = delete(extractId(value))
  def set(value: T, pxMilliseconds: Option[Duration] = None)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    redisLike.set(key(extractId(value)).key,
                  Json.stringify(toJson(value)),
                  pxMilliseconds = pxMilliseconds.map(d => d.toMillis))
  def exists(id: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = redisLike.exists(key(id).key)
  def exists(value: T)(implicit ec: ExecutionContext, env: Env): Future[Boolean]   = exists(extractId(value))
  // Streamed
  def streamedFind(predicate: T => Boolean, fetchSize: Int, page: Int = 1, pageSize: Int = Int.MaxValue)(
      implicit ec: ExecutionContext,
      mat: Materializer,
      env: Env
  ): Source[T, NotUsed] = {
    if (fetchSize <= 0) {
      throw new RuntimeException("FetchSize should be positive")
    }
    val position = (page - 1) * pageSize
    Source
      .fromFuture(
        redisLike.keys(key("*").key)
      )
      .mapConcat(_.toList)
      .grouped(fetchSize)
      .mapAsync(1) {
        case keys if keys.isEmpty => FastFuture.successful(Seq.empty[Option[ByteString]])
        case keys                 => redisLike.mget(keys: _*)
      }
      .map { items =>
        items
          .filter(_.isDefined)
          .map(_.get)
          .map(v => fromJsonSafe(Json.parse(v.utf8String)))
          .collect { case JsSuccess(i, _) => i }
      }
      .mapConcat(_.toList)
      .drop(position)
      .take(pageSize)
  }
  def streamedFindAndMat(predicate: T => Boolean, fetchSize: Int, page: Int = 0, pageSize: Int = Int.MaxValue)(
      implicit ec: ExecutionContext,
      mat: Materializer,
      env: Env
  ): Future[Seq[T]] =
    streamedFind(predicate, fetchSize, page, pageSize).runWith(Sink.seq[T])
}
