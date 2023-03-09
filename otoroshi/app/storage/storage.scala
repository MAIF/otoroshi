package otoroshi.storage

import akka.NotUsed
import akka.http.scaladsl.util.FastFuture
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import otoroshi.auth.AuthConfigsDataStore
import otoroshi.cluster.ClusterStateDataStore
import otoroshi.env.Env
import otoroshi.events._
import otoroshi.gateway.RequestsDataStore
import otoroshi.models._
import otoroshi.next.models.{NgRouteCompositionDataStore, NgRouteDataStore, StoredNgBackendDataStore}
import otoroshi.script.ScriptDataStore
import otoroshi.ssl.{CertificateDataStore, ClientCertificateValidationDataStore}
import otoroshi.storage.drivers.inmemory.{Memory, SwapStrategy, SwappableRedis}
import otoroshi.storage.stores._
import otoroshi.tcp.TcpServiceDataStore
import otoroshi.utils.cache.types.LegitTrieMap
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.{Configuration, Environment, Logger}

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
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
  def routeDataStore: NgRouteDataStore
  def routeCompositionDataStore: NgRouteCompositionDataStore
  def backendsDataStore: StoredNgBackendDataStore
  def wasmPluginsDataStore: WasmPluginDataStore
  ////
  def fullNdJsonImport(exportSource: Source[JsValue, _]): Future[Unit]
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
  def incr(key: String)(implicit ec: ExecutionContext, env: Env): Future[Long]           = incrby(key, 1L)
  def incrby(key: String, incr: Long)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def keys(pattern: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[String]]
  def pexpire(key: String, pttl: Long)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def sadd(key: String, members: Seq[ByteString]): Future[Long]
  def sismember(key: String, member: ByteString): Future[Boolean]
  def smembers(key: String): Future[Seq[ByteString]]
  def strlen(key: String)(implicit ec: ExecutionContext, env: Env): Future[Option[Long]] = get(key).map(_.map(_.size))
  def allMatching(pattern: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[ByteString]] = {
    keys(pattern)
      .flatMap {
        case keys if keys.isEmpty => FastFuture.successful(Seq.empty[Option[ByteString]])
        case keys                 => mget(keys)
      }
      .map(seq => seq.filter(_.isDefined).map(_.get))
  }
}

trait BasicStore[T] {
  def key(id: String): String
  // def keyStr(id: String): String                                                                                    = key(id).key
  def extractId(value: T): String
  def extractKey(value: T): String = key(extractId(value))
  def findAll(force: Boolean = false)(implicit ec: ExecutionContext, env: Env): Future[Seq[T]]
  //def findAllByKeys(ids: Seq[Key], force: Boolean = false)(implicit ec: ExecutionContext, env: Env): Future[Seq[T]] =
  //  findAllById(ids.map(_.key), force)
  def findAllById(ids: Seq[String], force: Boolean = false)(implicit ec: ExecutionContext, env: Env): Future[Seq[T]]
  // def findByKey(id: Key)(implicit ec: ExecutionContext, env: Env): Future[Option[T]]                                = findById(id.key)
  def findById(id: String)(implicit ec: ExecutionContext, env: Env): Future[Option[T]]
  def findByIdAndFillSecrets(id: String)(implicit ec: ExecutionContext, env: Env): Future[Option[T]]
  // def deleteByKey(id: Key)(implicit ec: ExecutionContext, env: Env): Future[Boolean]                                = delete(id.key)
  def deleteByIds(ids: Seq[String])(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def delete(id: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def delete(value: T)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def deleteAll()(implicit ec: ExecutionContext, env: Env): Future[Long]
  def set(value: T, pxMilliseconds: Option[Duration] = None)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  // def exists(id: Key)(implicit ec: ExecutionContext, env: Env): Future[Boolean]                                     = exists(id.key)
  def exists(id: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def exists(value: T)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  // Streaming
  def streamedFind(predicate: T => Boolean, fetchSize: Int, page: Int = 0, pageSize: Int = Int.MaxValue)(implicit
      ec: ExecutionContext,
      mat: Materializer,
      env: Env
  ): Source[T, NotUsed]
  def streamedFindAndMat(predicate: T => Boolean, fetchSize: Int, page: Int = 0, pageSize: Int = Int.MaxValue)(implicit
      ec: ExecutionContext,
      mat: Materializer,
      env: Env
  ): Future[Seq[T]]
  def clearFromCache(id: String)(implicit env: Env): Unit
  def clearCache(id: String)(implicit env: Env): Unit
  def countAll()(implicit ec: ExecutionContext, env: Env): Future[Long]
  def findAllAndFillSecrets()(implicit ec: ExecutionContext, env: Env): Future[Seq[T]]
}

trait RedisLike {
  def optimized: Boolean              = false
  def asOptimized: OptimizedRedisLike = this.asInstanceOf[OptimizedRedisLike]
  def health()(implicit ec: ExecutionContext): Future[DataStoreHealth]
  def start(): Unit = {}
  def stop(): Unit
  def flushall(): Future[Boolean]
  def get(key: String): Future[Option[ByteString]]
  def mget(keys: String*): Future[Seq[Option[ByteString]]] // multi key op ?
  def set(
      key: String,
      value: String,
      exSeconds: Option[Long] = None,
      pxMilliseconds: Option[Long] = None
  ): Future[Boolean]
  def setnxBS(key: String, value: ByteString, ttl: Option[Long])(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean] = {
    // no comment !!!
    exists(key).flatMap {
      case true  => FastFuture.successful(false)
      case false => setBS(key, value, None, ttl)
    }
  }
  def setBS(
      key: String,
      value: ByteString,
      exSeconds: Option[Long] = None,
      pxMilliseconds: Option[Long] = None
  ): Future[Boolean]
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
  def findAllOptimized(kind: String, kindKey: String): Future[Seq[JsValue]]
  def serviceDescriptors_findByHost(
      query: ServiceDescriptorQuery
  )(implicit ec: ExecutionContext, env: Env): Future[Seq[ServiceDescriptor]] = {
    FastFuture.failed(new NotImplementedError())
  }
  def serviceDescriptors_findByEnv(
      ev: String
  )(implicit ec: ExecutionContext, env: Env): Future[Seq[ServiceDescriptor]] = {
    env.datastores.serviceDescriptorDataStore.findAll().map(_.filter(_.env == ev))
  }
  def serviceDescriptors_findByGroup(
      id: String
  )(implicit ec: ExecutionContext, env: Env): Future[Seq[ServiceDescriptor]] = {
    env.datastores.serviceDescriptorDataStore.findAll().map(_.filter(_.groups.contains(id)))
  }
  def apiKeys_findByService(
      service: ServiceDescriptor
  )(implicit ec: ExecutionContext, env: Env): Future[Seq[ApiKey]] = {
    env.datastores.apiKeyDataStore.findAll().map { keys =>
      keys.filter { key =>
        key.authorizedOnService(service.id) || key.authorizedOnOneGroupFrom(service.groups)
      }
    }
  }
  def apiKeys_findByGroup(groupId: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[ApiKey]] = {
    env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
      case Some(group) => {
        env.datastores.apiKeyDataStore.findAll().map { keys =>
          keys.filter { key =>
            key.authorizedOnGroup(group.id)
          }
        }
      }
      case None        => FastFuture.failed(new GroupNotFoundException(groupId))
    }
  }
  def extractKind(key: String, env: Env): Option[String] = {
    import otoroshi.utils.syntax.implicits._
    val ds = env.datastores
    key match {
      case _ if key.startsWith(ds.serviceDescriptorDataStore.key(""))  => "service-descriptor".some
      case _ if key.startsWith(ds.apiKeyDataStore.key(""))             => "apikey".some
      case _ if key.startsWith(ds.certificatesDataStore.key(""))       => "certificate".some
      case _ if key.startsWith(ds.serviceGroupDataStore.key(""))       => "service-group".some
      case _ if key.startsWith(ds.globalJwtVerifierDataStore.key(""))  => "jwt-verifier".some
      case _ if key.startsWith(ds.authConfigsDataStore.key(""))        => "auth-module".some
      case _ if key.startsWith(ds.scriptDataStore.key(""))             => "script".some
      case _ if key.startsWith(ds.dataExporterConfigDataStore.key("")) => "data-exporter".some
      case _ if key.startsWith(ds.teamDataStore.key(""))               => "team".some
      case _ if key.startsWith(ds.tenantDataStore.key(""))             => "tenant".some
      case _ if key.startsWith(ds.tcpServiceDataStore.key(""))         => "tcp-service".some
      case _ if key.startsWith(ds.globalConfigDataStore.key(""))       => "global-config".some
      case _                                                           => None
    }
  }
}

trait RedisLikeStore[T] extends BasicStore[T] {
  def fmt: Format[T]
  private lazy val name                          = this.getClass.getSimpleName.replace("$", "")
  def _findAllCached(implicit env: Env): Boolean = env.useCache
  def redisLike(implicit env: Env): RedisLike
  def reader: Reads[T]                           = fmt
  def writer: Writes[T]                          = fmt
  def toJson(value: T): JsValue                  = writer.writes(value)
  def fromJsons(value: JsValue): T               =
    try {
      reader.reads(value).get
    } catch {
      case e: Throwable => {
        Logger("otoroshi-redis-like-store").error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }
  def fromJsonSafe(value: JsValue): JsResult[T]  = reader.reads(value)

  private val findAllCache     = new java.util.concurrent.atomic.AtomicReference[Seq[T]](null)
  private val lastFindAllCache = new java.util.concurrent.atomic.AtomicLong(0L)

  def countAll()(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    redisLike.keys(key("*")).map(_.size)
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
      val ks = ids.map(v => key(v))
      redisLike.del(ks: _*).map(_ > 0)
    }
  }

  def findAllAndFillSecrets()(implicit ec: ExecutionContext, env: Env): Future[Seq[T]] = {
    if (env.vaults.enabled) {
      Source
        .single(key("*"))
        .mapAsync(1)(redisLike.keys)
        .mapAsync(1) { keys =>
          if (keys.isEmpty) FastFuture.successful(Seq.empty[(Option[ByteString], String)])
          else redisLike.mget(keys: _*).map(seq => seq.zip(keys))
        }
        .map(seq => seq.filter(_._1.isDefined).map(t => (t._1.get.utf8String, t._2)))
        .flatMapConcat(values => Source(values.toList))
        .mapAsync(1) { case (value, key) =>
          if (value.contains("${vault://")) {
            env.vaults.fillSecretsAsync(key, value).map { filledValue =>
              fromJsonSafe(Json.parse(filledValue))
            }
          } else {
            fromJsonSafe(Json.parse(value)).vfuture
          }
        }
        .collect { case JsSuccess(i, _) =>
          i
        }
        .runWith(Sink.seq)(env.otoroshiMaterializer)
    } else {
      redisLike
        .keys(key("*"))
        .flatMap(keys =>
          if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]])
          else redisLike.mget(keys: _*)
        )
        .map(seq =>
          seq
            .filter(_.isDefined)
            .map(_.get)
            .map(_.utf8String)
            .map { v =>
              fromJsonSafe(Json.parse(v))
            }
            .collect { case JsSuccess(i, _) =>
              i
            }
        )
    }
  }

  def findAll(force: Boolean = false)(implicit ec: ExecutionContext, env: Env): Future[Seq[T]] =
    /*env.metrics.withTimerAsync("otoroshi.core.store.find-all")*/ {

      def actualFindAll() = {

        @inline
        def oldSchoolFind() = {
          redisLike
            .keys(key("*"))
            .flatMap(keys =>
              if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]])
              else redisLike.mget(keys: _*)
            )
            .map(seq =>
              seq.filter(_.isDefined).map(_.get).map(v => fromJsonSafe(Json.parse(v.utf8String))).collect {
                case JsSuccess(i, _) => i
              }
            )
        }

        if (redisLike.optimized) {
          val optRedis = redisLike.asInstanceOf[OptimizedRedisLike]
          val kindKey  = key("")
          optRedis.extractKind(kindKey, env).map { kind =>
            optRedis.findAllOptimized(kind, kindKey).map { seq =>
              seq.map(v => fromJsonSafe(v)).collect { case JsSuccess(i, _) =>
                i
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
          actualFindAll().andThen { case Success(services) =>
            findAllCache.set(services)
          }
        } else {
          if (force || (lastFindAllCache.get() + env.cacheTtl) < time) {
            lastFindAllCache.set(time)
            actualFindAll().andThen { case Success(services) =>
              findAllCache.set(services)
            }
          } else if ((lastFindAllCache.get() + (env.cacheTtl - 1000)) < time) {
            lastFindAllCache.set(time)
            actualFindAll().andThen { case Success(services) =>
              findAllCache.set(services)
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
      case keys if keys.isEmpty                                 => FastFuture.successful(Seq.empty[T])
      case keys if _findAllCached && findAllCache.get() != null => {
        // TODO: was true, but high impact on perfs, so ...
        findAll(force) // TODO : update findAllCache ??? FIXME ???
        FastFuture.successful(findAllCache.get().filter(s => keys.contains(extractId(s))))
      }
      case keys                                                 =>
        redisLike.mget(keys.map(key): _*).map { values: Seq[Option[ByteString]] =>
          values.flatMap { opt =>
            opt.flatMap(bs => fromJsonSafe(Json.parse(bs.utf8String)).asOpt)
          }
        }
    }
  def findById(id: String)(implicit ec: ExecutionContext, env: Env): Future[Option[T]]                               =
    redisLike.get(key(id)).map(_.flatMap(v => fromJsonSafe(Json.parse(v.utf8String)).asOpt))

  def findByIdAndFillSecrets(id: String)(implicit ec: ExecutionContext, env: Env): Future[Option[T]] = {
    redisLike.get(key(id)).flatMap {
      case None           => None.vfuture
      case Some(rawValue) => {
        val value = rawValue.utf8String
        if (env.vaults.enabled && value.contains("${vault://")) {
          env.vaults.fillSecretsAsync(id, value).map { filledValue =>
            fromJsonSafe(Json.parse(filledValue)).asOpt
          }
        } else {
          fromJsonSafe(Json.parse(value)).asOpt.vfuture
        }
      }
    }
  }

  def deleteAll()(implicit ec: ExecutionContext, env: Env): Future[Long]                                               =
    redisLike.keys(key("*")).flatMap { keys =>
      redisLike.del(keys: _*)
    }
  def delete(id: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]                                     =
    redisLike.del(key(id)).map(_ > 0)
  def delete(value: T)(implicit ec: ExecutionContext, env: Env): Future[Boolean]                                       = delete(extractId(value))
  def set(value: T, pxMilliseconds: Option[Duration] = None)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    redisLike.set(
      key(extractId(value)),
      Json.stringify(toJson(value)),
      pxMilliseconds = pxMilliseconds.map(d => d.toMillis)
    )
  def exists(id: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean]                                     = redisLike.exists(key(id))
  def exists(value: T)(implicit ec: ExecutionContext, env: Env): Future[Boolean]                                       = exists(extractId(value))
  // Streamed
  def streamedFind(predicate: T => Boolean, fetchSize: Int, page: Int = 1, pageSize: Int = Int.MaxValue)(implicit
      ec: ExecutionContext,
      mat: Materializer,
      env: Env
  ): Source[T, NotUsed] = {
    if (fetchSize <= 0) {
      throw new RuntimeException("FetchSize should be positive")
    }
    val position = (page - 1) * pageSize
    Source
      .future(
        redisLike.keys(key("*"))
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
  def streamedFindAndMat(predicate: T => Boolean, fetchSize: Int, page: Int = 0, pageSize: Int = Int.MaxValue)(implicit
      ec: ExecutionContext,
      mat: Materializer,
      env: Env
  ): Future[Seq[T]]                                                                                                    =
    streamedFind(predicate, fetchSize, page, pageSize).runWith(Sink.seq[T])
}

trait MetricsWrapper {

  def env: Env

  private val logger = Logger("otoroshi-metrics-wrapper")

  private val opsKey      = "otoroshi.core.storage.ops"
  private val opsReadKey  = s"$opsKey.read"
  private val opsWriteKey = s"$opsKey.write"

  logger.warn("Metrics wrapper is enabled !")

  @inline
  def countRead(key: String): Unit = {
    env.metrics.counterInc(opsKey)
    env.metrics.counterInc(opsReadKey)
  }

  @inline
  def countWrite(key: String, op: String): Unit = {
    // logger.info(s"write: ${op} '${key}'")
    env.metrics.counterInc(opsKey)
    env.metrics.counterInc(opsWriteKey)
  }
}

class RedisLikeMetricsWrapper(redis: RedisLike, val env: Env) extends RedisLike with MetricsWrapper {

  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = redis.health()
  override def start(): Unit                                                    = redis.start()
  override def stop(): Unit                                                     = redis.stop()

  override def rawGet(key: String): Future[Option[Any]] = {
    countRead(key)
    redis.get(key)
  }

  override def flushall(): Future[Boolean] = {
    countWrite("*", "flushall")
    redis.flushall()
  }
  override def get(key: String): Future[Option[ByteString]] = {
    countRead(key)
    redis.get(key)
  }
  override def mget(keys: String*): Future[Seq[Option[ByteString]]] = {
    countRead(keys.mkString(", "))
    redis.mget(keys: _*)
  }
  override def set(
      key: String,
      value: String,
      exSeconds: Option[Long] = None,
      pxMilliseconds: Option[Long] = None
  ): Future[Boolean] = {
    countWrite(key, "set")
    redis.set(key, value, exSeconds, pxMilliseconds)
  }
  override def setBS(
      key: String,
      value: ByteString,
      exSeconds: Option[Long] = None,
      pxMilliseconds: Option[Long] = None
  ): Future[Boolean] = {
    countWrite(key, "set")
    redis.setBS(key, value, exSeconds, pxMilliseconds)
  }
  override def del(keys: String*): Future[Long] = {
    countWrite(keys.mkString(", "), "del")
    redis.del(keys: _*)
  }
  override def incr(key: String): Future[Long] = {
    countWrite(key, "incr")
    redis.incr(key)
  }
  override def incrby(key: String, increment: Long): Future[Long] = {
    countWrite(key, "incrby")
    redis.incrby(key, increment)
  }
  override def exists(key: String): Future[Boolean] = {
    countRead(key)
    redis.exists(key)
  }
  override def keys(pattern: String): Future[Seq[String]] = {
    countRead(pattern)
    redis.keys(pattern)
  }
  override def hdel(key: String, fields: String*): Future[Long] = {
    countWrite(key, "hdel")
    redis.hdel(key, fields: _*)
  }
  override def hgetall(key: String): Future[Map[String, ByteString]] = {
    countRead(key)
    redis.hgetall(key)
  }
  override def hset(key: String, field: String, value: String): Future[Boolean] = {
    countWrite(key, "hset")
    redis.hset(key, field, value)
  }
  override def hsetBS(key: String, field: String, value: ByteString): Future[Boolean] = {
    countWrite(key, "hset")
    redis.hsetBS(key, field, value)
  }
  override def llen(key: String): Future[Long] = {
    countRead(key)
    redis.llen(key)
  }
  override def lpush(key: String, values: String*): Future[Long] = {
    countWrite(key, "lpush")
    redis.lpush(key, values: _*)
  }
  override def lpushLong(key: String, values: Long*): Future[Long] = {
    countWrite(key, "lpush")
    redis.lpushLong(key, values: _*)
  }
  override def lpushBS(key: String, values: ByteString*): Future[Long] = {
    countWrite(key, "lpush")
    redis.lpushBS(key, values: _*)
  }
  override def lrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]] = {
    countRead(key)
    redis.lrange(key, start, stop)
  }
  override def ltrim(key: String, start: Long, stop: Long): Future[Boolean] = {
    countWrite(key, "ltrim")
    redis.ltrim(key, start, stop)
  }
  override def pttl(key: String): Future[Long] = {
    countRead(key)
    redis.pttl(key)
  }
  override def ttl(key: String): Future[Long] = {
    countRead(key)
    redis.ttl(key)
  }
  override def expire(key: String, seconds: Int): Future[Boolean] = {
    countWrite(key, "expire")
    redis.expire(key, seconds)
  }
  override def pexpire(key: String, milliseconds: Long): Future[Boolean] = {
    countWrite(key, "pexpire")
    redis.pexpire(key, milliseconds)
  }
  override def sadd(key: String, members: String*): Future[Long] = {
    countWrite(key, "sadd")
    redis.sadd(key, members: _*)
  }
  override def saddBS(key: String, members: ByteString*): Future[Long] = {
    countWrite(key, "sadd")
    redis.saddBS(key, members: _*)
  }
  override def sismember(key: String, member: String): Future[Boolean] = {
    countRead(key)
    redis.sismember(key, member)
  }
  override def sismemberBS(key: String, member: ByteString): Future[Boolean] = {
    countRead(key)
    redis.sismemberBS(key, member)
  }
  override def smembers(key: String): Future[Seq[ByteString]] = {
    countRead(key)
    redis.smembers(key)
  }
  override def srem(key: String, members: String*): Future[Long] = {
    countWrite(key, "srem")
    redis.srem(key, members: _*)
  }
  override def sremBS(key: String, members: ByteString*): Future[Long] = {
    countWrite(key, "srem")
    redis.sremBS(key, members: _*)
  }
  override def scard(key: String): Future[Long] = {
    countRead(key)
    redis.scard(key)
  }
  override def setnxBS(key: String, value: ByteString, ttl: Option[Long])(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean] = {
    countWrite(key, "srem")
    redis.setnxBS(key, value, ttl)
  }
}

class SwappableRedisLikeMetricsWrapper(redis: RedisLike with SwappableRedis, val env: Env)
    extends RedisLike
    with MetricsWrapper
    with SwappableRedis {

  private val incropt = new IncrOptimizer(200, 10000)

  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = redis.health()
  override def start(): Unit                                                    = redis.start()
  override def stop(): Unit                                                     = redis.stop()

  override def rawGet(key: String): Future[Option[Any]] = {
    countRead(key)
    redis.get(key)
  }

  override def flushall(): Future[Boolean] = {
    countWrite("*", "flushall")
    redis.flushall()
  }
  override def get(key: String): Future[Option[ByteString]] = {
    countRead(key)
    redis.get(key)
  }
  override def mget(keys: String*): Future[Seq[Option[ByteString]]] = {
    countRead(keys.mkString(", "))
    redis.mget(keys: _*)
  }
  override def set(
      key: String,
      value: String,
      exSeconds: Option[Long] = None,
      pxMilliseconds: Option[Long] = None
  ): Future[Boolean] = {
    countWrite(key, "set")
    redis.set(key, value, exSeconds, pxMilliseconds)
  }
  override def setBS(
      key: String,
      value: ByteString,
      exSeconds: Option[Long] = None,
      pxMilliseconds: Option[Long] = None
  ): Future[Boolean] = {
    countWrite(key, "set")
    redis.setBS(key, value, exSeconds, pxMilliseconds)
  }
  override def del(keys: String*): Future[Long] = {
    countWrite(keys.mkString(", "), "del")
    redis.del(keys: _*)
  }
  override def incr(key: String): Future[Long] = {
    incropt.incrBy(key, 1L) { _ =>
      countWrite(key, "incr")
      redis.incr(key)
    }(env.otoroshiExecutionContext)
  }
  override def incrby(key: String, increment: Long): Future[Long] = {
    incropt.incrBy(key, increment) { _ =>
      countWrite(key, "incrby")
      redis.incrby(key, increment)
    }(env.otoroshiExecutionContext)
  }
  override def exists(key: String): Future[Boolean] = {
    countRead(key)
    redis.exists(key)
  }
  override def keys(pattern: String): Future[Seq[String]] = {
    countRead(pattern)
    redis.keys(pattern)
  }
  override def hdel(key: String, fields: String*): Future[Long] = {
    countWrite(key, "hdel")
    redis.hdel(key, fields: _*)
  }
  override def hgetall(key: String): Future[Map[String, ByteString]] = {
    countRead(key)
    redis.hgetall(key)
  }
  override def hset(key: String, field: String, value: String): Future[Boolean] = {
    countWrite(key, "hset")
    redis.hset(key, field, value)
  }
  override def hsetBS(key: String, field: String, value: ByteString): Future[Boolean] = {
    countWrite(key, "hset")
    redis.hsetBS(key, field, value)
  }
  override def llen(key: String): Future[Long] = {
    countRead(key)
    redis.llen(key)
  }
  override def lpush(key: String, values: String*): Future[Long] = {
    countWrite(key, "lpush")
    redis.lpush(key, values: _*)
  }
  override def lpushLong(key: String, values: Long*): Future[Long] = {
    countWrite(key, "lpush")
    redis.lpushLong(key, values: _*)
  }
  override def lpushBS(key: String, values: ByteString*): Future[Long] = {
    countWrite(key, "lpush")
    redis.lpushBS(key, values: _*)
  }
  override def lrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]] = {
    countRead(key)
    redis.lrange(key, start, stop)
  }
  override def ltrim(key: String, start: Long, stop: Long): Future[Boolean] = {
    countWrite(key, "ltrim")
    redis.ltrim(key, start, stop)
  }
  override def pttl(key: String): Future[Long] = {
    countRead(key)
    redis.pttl(key)
  }
  override def ttl(key: String): Future[Long] = {
    countRead(key)
    redis.ttl(key)
  }
  override def expire(key: String, seconds: Int): Future[Boolean] = {
    countWrite(key, "expire")
    redis.expire(key, seconds)
  }
  override def pexpire(key: String, milliseconds: Long): Future[Boolean] = {
    countWrite(key, "pexpire")
    redis.pexpire(key, milliseconds)
  }
  override def sadd(key: String, members: String*): Future[Long] = {
    countWrite(key, "sadd")
    redis.sadd(key, members: _*)
  }
  override def saddBS(key: String, members: ByteString*): Future[Long] = {
    countWrite(key, "sadd")
    redis.saddBS(key, members: _*)
  }
  override def sismember(key: String, member: String): Future[Boolean] = {
    countRead(key)
    redis.sismember(key, member)
  }
  override def sismemberBS(key: String, member: ByteString): Future[Boolean] = {
    countRead(key)
    redis.sismemberBS(key, member)
  }
  override def smembers(key: String): Future[Seq[ByteString]] = {
    countRead(key)
    redis.smembers(key)
  }
  override def srem(key: String, members: String*): Future[Long] = {
    countWrite(key, "srem")
    redis.srem(key, members: _*)
  }
  override def sremBS(key: String, members: ByteString*): Future[Long] = {
    countWrite(key, "srem")
    redis.sremBS(key, members: _*)
  }
  override def scard(key: String): Future[Long] = {
    countRead(key)
    redis.scard(key)
  }
  override def setnxBS(key: String, value: ByteString, ttl: Option[Long])(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean] = {
    countWrite(key, "setnx")
    redis.setnxBS(key, value, ttl)
  }

  override def swap(memory: Memory, strategy: SwapStrategy): Unit = redis.swap(memory, strategy)
}

case class IncrOptimizerItem(
    ops: Int,
    time: Int,
    last: AtomicLong,
    incr: AtomicLong,
    current: AtomicLong,
    curOps: AtomicInteger
) {
  def setCurrent(value: Long): Unit = current.set(value)
  def incrBy(increment: Long)(f: Long => Future[Long])(implicit ec: ExecutionContext): Future[Long] = {
    val elapsed     = System.currentTimeMillis() - last.get()
    val tooMuchOps  = curOps.incrementAndGet() > ops
    val tooMuchTime = elapsed > time
    if (tooMuchOps || tooMuchTime) {
      val total = incr.get() + increment
      f(total).map { r =>
        last.set(System.currentTimeMillis())
        incr.addAndGet(0 - total)
        curOps.addAndGet(0 - ops)
        current.set(r)
        r
      }
    } else {
      val c = current.addAndGet(increment)
      incr.addAndGet(increment)
      c.vfuture
    }
  }
}

class IncrOptimizer(ops: Int, time: Int) {
  private val cache = new LegitTrieMap[String, IncrOptimizerItem]()
  def incrBy(key: String, increment: Long)(f: Long => Future[Long])(implicit ec: ExecutionContext): Future[Long] = {
    cache.get(key) match {
      case None       =>
        f(increment).map { r =>
          val item = IncrOptimizerItem(
            ops,
            time,
            new AtomicLong(System.currentTimeMillis()),
            new AtomicLong(0L),
            new AtomicLong(r),
            new AtomicInteger(0)
          )
          cache.putIfAbsent(key, item) match {
            case None    =>
              cache.get(key).foreach(i => i.setCurrent(r)) // when already there ....not sure about it !
              r
            case Some(_) => r
          }
        }
      case Some(item) => item.incrBy(increment)(f)
    }
  }
}
