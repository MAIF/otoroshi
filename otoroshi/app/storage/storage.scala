package otoroshi.storage

import akka.NotUsed
import akka.http.scaladsl.util.FastFuture
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import auth.AuthConfigsDataStore
import cluster.ClusterStateDataStore
import env.Env
import events._
import gateway.RequestsDataStore
import models._
import otoroshi.models.{SimpleAdminDataStore, WebAuthnAdminDataStore}
import otoroshi.script.ScriptDataStore
import otoroshi.storage.stores._
import otoroshi.tcp.TcpServiceDataStore
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.{Configuration, Environment, Logger}
import ssl.{CertificateDataStore, ClientCertificateValidationDataStore}
import storage.stores.{DataExporterConfigDataStore, TeamDataStore, TenantDataStore}

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

sealed trait DataStoreHealth
case object Healthy     extends DataStoreHealth
case object Unhealthy   extends DataStoreHealth
case object Unreachable extends DataStoreHealth

trait DataStores {
  def before(configuration: Configuration, environment: Environment, lifecycle: ApplicationLifecycle): Future[Unit]
  def after(configuration: Configuration, environment: Environment, lifecycle: ApplicationLifecycle): Future[Unit]
  def health()(implicit ec: ExecutionContext): Future[DataStoreHealth]
  def privateAppsUserDataStore: PrivateAppsUserDataStore
  def backOfficeUserDataStore: BackOfficeUserDataStore
  def serviceGroupDataStore: ServiceGroupDataStore
  def globalConfigDataStore: GlobalConfigDataStore
  def apiKeyDataStore: ApiKeyDataStore
  def serviceDescriptorDataStore: ServiceDescriptorDataStore
  def simpleAdminDataStore: SimpleAdminDataStore
  def alertDataStore: AlertDataStore
  def auditDataStore: AuditDataStore
  def healthCheckDataStore: HealthCheckDataStore
  def errorTemplateDataStore: ErrorTemplateDataStore
  def requestsDataStore: RequestsDataStore
  def canaryDataStore: CanaryDataStore
  def chaosDataStore: ChaosDataStore
  def globalJwtVerifierDataStore: GlobalJwtVerifierDataStore
  def authConfigsDataStore: AuthConfigsDataStore
  def certificatesDataStore: CertificateDataStore
  def clusterStateDataStore: ClusterStateDataStore
  def clientCertificateValidationDataStore: ClientCertificateValidationDataStore
  def scriptDataStore: ScriptDataStore
  def tcpServiceDataStore: TcpServiceDataStore
  def rawExport(group: Int)(implicit ec: ExecutionContext, mat: Materializer, env: Env): Source[JsValue, NotUsed]
  def rawDataStore: RawDataStore
  def webAuthnAdminDataStore: WebAuthnAdminDataStore
  def webAuthnRegistrationsDataStore: WebAuthnRegistrationsDataStore
  def tenantDataStore: TenantDataStore
  def teamDataStore: TeamDataStore
  def dataExporterConfigDataStore: DataExporterConfigDataStore
  ////
  def fullNdJsonImport(export: Source[JsValue, _]): Future[Unit]
  def fullNdJsonExport(group: Int, groupWorkers: Int, keyWorkers: Int): Future[Source[JsValue, _]]
}

trait RawDataStore {
  def exists(key: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def pttl(key: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def get(key: String)(implicit ec: ExecutionContext, env: Env): Future[Option[ByteString]]
  def mget(keys: Seq[String])(implicit ec: ExecutionContext, env: Env): Future[Seq[Option[ByteString]]]
  def set(key: String, value: ByteString, ttl: Option[Long])(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def setnx(key: String, value: ByteString, ttl: Option[Long])(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def del(keys: Seq[String])(implicit ec: ExecutionContext, env: Env): Future[Long]
  def incr(key: String)(implicit ec: ExecutionContext, env: Env): Future[Long] = incrby(key, 1L)
  def incrby(key: String, incr: Long)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def keys(pattern: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[String]]
  def pexpire(key: String, pttl: Long)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def sadd(key: String, members: Seq[ByteString]): Future[Long]
  def sismember(key: String, member: ByteString): Future[Boolean]
  def smembers(key: String): Future[Seq[ByteString]]
}

trait BasicStore[T] {
  def key(id: String): Key
  def keyStr(id: String): String = key(id).key
  def extractId(value: T): String
  def extractKey(value: T): Key = key(extractId(value))
  def findAll(force: Boolean = false)(implicit ec: ExecutionContext, env: Env): Future[Seq[T]]
  def findAllByKeys(ids: Seq[Key], force: Boolean = false)(implicit ec: ExecutionContext, env: Env): Future[Seq[T]] =
    findAllById(ids.map(_.key), force)
  def findAllById(ids: Seq[String], force: Boolean = false)(implicit ec: ExecutionContext, env: Env): Future[Seq[T]]
  def findByKey(id: Key)(implicit ec: ExecutionContext, env: Env): Future[Option[T]] = findById(id.key)
  def findById(id: String)(implicit ec: ExecutionContext, env: Env): Future[Option[T]]
  def deleteByKey(id: Key)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = delete(id.key)
  def deleteByIds(ids: Seq[String])(implicit ec: ExecutionContext, env: Env): Future[Boolean]
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
  def countAll()(implicit ec: ExecutionContext, env: Env): Future[Long]
}

trait RedisLike {
  def optimized: Boolean = false
  def asOptimized: OptimizedRedisLike = this.asInstanceOf[OptimizedRedisLike]
  def health()(implicit ec: ExecutionContext): Future[DataStoreHealth]
  def start(): Unit = {}
  def stop(): Unit
  def flushall(): Future[Boolean]
  def get(key: String): Future[Option[ByteString]]
  def mget(keys: String*): Future[Seq[Option[ByteString]]] // multi key op ?
  def set(key: String,
          value: String,
          exSeconds: Option[Long] = None,
          pxMilliseconds: Option[Long] = None): Future[Boolean]
  def setnxBS(key: String, value: ByteString, ttl: Option[Long])(implicit ec: ExecutionContext,
                                                                 env: Env): Future[Boolean] = {
    // no comment !!!
    exists(key).flatMap {
      case true  => FastFuture.successful(false)
      case false => setBS(key, value, None, ttl)
    }
  }
  def setBS(key: String,
            value: ByteString,
            exSeconds: Option[Long] = None,
            pxMilliseconds: Option[Long] = None): Future[Boolean]
  def del(keys: String*): Future[Long] // multi key op ?
  def incr(key: String): Future[Long]
  def incrby(key: String, increment: Long): Future[Long]
  def exists(key: String): Future[Boolean]
  def keys(pattern: String): Future[Seq[String]] // multi key op ?
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
  def rawGet(key: String): Future[Option[Any]]
}

trait OptimizedRedisLike {
  def findAllOptimized(kind: String): Future[Seq[JsValue]]
  def extractKind(key: String): Option[String]
  def serviceDescriptors_findByHost(query: ServiceDescriptorQuery): Future[Seq[ServiceDescriptor]]
  def serviceDescriptors_findByEnv(env: String): Future[Seq[ServiceDescriptor]]
  def serviceDescriptors_findByGroup(id: String): Future[Seq[ServiceDescriptor]]
  def apiKeys_findByService(service: ServiceDescriptor): Future[Seq[ApiKey]]
  def apiKeys_findByGroup(serviceId: String): Future[Seq[ApiKey]]
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

  def countAll()(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    redisLike.keys(key("*").key).map(_.size)
  }

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

  def deleteByIds(ids: Seq[String])(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    if (ids.isEmpty) {
      FastFuture.successful(true)
    } else {
      redisLike.del(ids.map(v => keyStr(v)): _*).map(_ > 0)
    }
  }

  def findAll(force: Boolean = false)(implicit ec: ExecutionContext, env: Env): Future[Seq[T]] = {

    def actualFindAll() = {

      @inline
      def oldSchoolFind() = {
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
      }

      if (redisLike.optimized) {
        val optRedis = redisLike.asInstanceOf[OptimizedRedisLike]
        optRedis.extractKind(keyStr("")).map { kind =>
          optRedis.findAllOptimized(kind).map { seq =>
            seq.map(v => fromJsonSafe(v)).collect {
              case JsSuccess(i, _) => i
            }
          }
        } getOrElse {
          oldSchoolFind()
        }
      } else {
        oldSchoolFind()
      }
    }

    if (_findAllCached) {
      val time = System.currentTimeMillis
      val ref  = findAllCache.get()
      if (ref == null) {
        lastFindAllCache.set(time)
        actualFindAll().andThen {
          case Success(services) => findAllCache.set(services)
        }
      } else {
        if (force || (lastFindAllCache.get() + env.cacheTtl) < time) {
          lastFindAllCache.set(time)
          actualFindAll().andThen {
            case Success(services) => findAllCache.set(services)
          }
        } else if ((lastFindAllCache.get() + (env.cacheTtl - 1000)) < time) {
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
  def findAllById(ids: Seq[String], force: Boolean = false)(implicit ec: ExecutionContext, env: Env): Future[Seq[T]] =
    ids match {
      case keys if keys.isEmpty => FastFuture.successful(Seq.empty[T])
      case keys if _findAllCached && findAllCache.get() != null => {
        // TODO: was true, but high impact on perfs, so ...
        findAll(force) // TODO : update findAllCache ??? FIXME ???
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
      .future(
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

class RedisLikeWrapper(redis: RedisLike, env: Env) extends RedisLike {
  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = redis.health()
  override def start(): Unit                                                    = redis.start()
  override def stop(): Unit                                                     = redis.stop()

  override def rawGet(key: String): Future[Option[Any]] = redis.get(key)

  override def flushall(): Future[Boolean] = {
    env.metrics.counter("redis.ops").inc()
    redis.flushall()
  }
  override def get(key: String): Future[Option[ByteString]] = {
    env.metrics.counter("redis.ops").inc()
    redis.get(key)
  }
  override def mget(keys: String*): Future[Seq[Option[ByteString]]] = {
    env.metrics.counter("redis.ops").inc()
    redis.mget(keys: _*)
  }
  override def set(key: String,
                   value: String,
                   exSeconds: Option[Long] = None,
                   pxMilliseconds: Option[Long] = None): Future[Boolean] = {
    env.metrics.counter("redis.ops").inc()
    redis.set(key, value, exSeconds, pxMilliseconds)
  }
  override def setBS(key: String,
                     value: ByteString,
                     exSeconds: Option[Long] = None,
                     pxMilliseconds: Option[Long] = None): Future[Boolean] = {
    env.metrics.counter("redis.ops").inc()
    redis.setBS(key, value, exSeconds, pxMilliseconds)
  }
  override def del(keys: String*): Future[Long] = {
    env.metrics.counter("redis.ops").inc()
    redis.del(keys: _*)
  }
  override def incr(key: String): Future[Long] = {
    env.metrics.counter("redis.ops").inc()
    redis.incr(key)
  }
  override def incrby(key: String, increment: Long): Future[Long] = {
    env.metrics.counter("redis.ops").inc()
    redis.incrby(key, increment)
  }
  override def exists(key: String): Future[Boolean] = {
    env.metrics.counter("redis.ops").inc()
    redis.exists(key)
  }
  override def keys(pattern: String): Future[Seq[String]] = {
    env.metrics.counter("redis.ops").inc()
    redis.keys(pattern)
  }
  override def hdel(key: String, fields: String*): Future[Long] = {
    env.metrics.counter("redis.ops").inc()
    redis.hdel(key, fields: _*)
  }
  override def hgetall(key: String): Future[Map[String, ByteString]] = {
    env.metrics.counter("redis.ops").inc()
    redis.hgetall(key)
  }
  override def hset(key: String, field: String, value: String): Future[Boolean] = {
    env.metrics.counter("redis.ops").inc()
    redis.hset(key, field, value)
  }
  override def hsetBS(key: String, field: String, value: ByteString): Future[Boolean] = {
    env.metrics.counter("redis.ops").inc()
    redis.hsetBS(key, field, value)
  }
  override def llen(key: String): Future[Long] = {
    env.metrics.counter("redis.ops").inc()
    redis.llen(key)
  }
  override def lpush(key: String, values: String*): Future[Long] = {
    env.metrics.counter("redis.ops").inc()
    redis.lpush(key, values: _*)
  }
  override def lpushLong(key: String, values: Long*): Future[Long] = {
    env.metrics.counter("redis.ops").inc()
    redis.lpushLong(key, values: _*)
  }
  override def lpushBS(key: String, values: ByteString*): Future[Long] = {
    env.metrics.counter("redis.ops").inc()
    redis.lpushBS(key, values: _*)
  }
  override def lrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]] = {
    env.metrics.counter("redis.ops").inc()
    redis.lrange(key, start, stop)
  }
  override def ltrim(key: String, start: Long, stop: Long): Future[Boolean] = {
    env.metrics.counter("redis.ops").inc()
    redis.ltrim(key, start, stop)
  }
  override def pttl(key: String): Future[Long] = {
    env.metrics.counter("redis.ops").inc()
    redis.pttl(key)
  }
  override def ttl(key: String): Future[Long] = {
    env.metrics.counter("redis.ops").inc()
    redis.ttl(key)
  }
  override def expire(key: String, seconds: Int): Future[Boolean] = {
    env.metrics.counter("redis.ops").inc()
    redis.expire(key, seconds)
  }
  override def pexpire(key: String, milliseconds: Long): Future[Boolean] = {
    env.metrics.counter("redis.ops").inc()
    redis.pexpire(key, milliseconds)
  }
  override def sadd(key: String, members: String*): Future[Long] = {
    env.metrics.counter("redis.ops").inc()
    redis.sadd(key, members: _*)
  }
  override def saddBS(key: String, members: ByteString*): Future[Long] = {
    env.metrics.counter("redis.ops").inc()
    redis.saddBS(key, members: _*)
  }
  override def sismember(key: String, member: String): Future[Boolean] = {
    env.metrics.counter("redis.ops").inc()
    redis.sismember(key, member)
  }
  override def sismemberBS(key: String, member: ByteString): Future[Boolean] = {
    env.metrics.counter("redis.ops").inc()
    redis.sismemberBS(key, member)
  }
  override def smembers(key: String): Future[Seq[ByteString]] = {
    env.metrics.counter("redis.ops").inc()
    redis.smembers(key)
  }
  override def srem(key: String, members: String*): Future[Long] = {
    env.metrics.counter("redis.ops").inc()
    redis.srem(key, members: _*)
  }
  override def sremBS(key: String, members: ByteString*): Future[Long] = {
    env.metrics.counter("redis.ops").inc()
    redis.sremBS(key, members: _*)
  }
  override def scard(key: String): Future[Long] = {
    env.metrics.counter("redis.ops").inc()
    redis.scard(key)
  }

  override def setnxBS(key: String, value: ByteString, ttl: Option[Long])(implicit ec: ExecutionContext,
                                                                          env: Env): Future[Boolean] = {
    env.metrics.counter("redis.ops").inc()
    redis.setnxBS(key, value, ttl)
  }
}
