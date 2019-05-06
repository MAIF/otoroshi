package storage.redis.next

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import auth.AuthConfigsDataStore
import cluster.{ClusterStateDataStore, InMemoryClusterStateDataStore}
import com.typesafe.config.ConfigFactory
import env.Env
import events.{AlertDataStore, AuditDataStore, HealthCheckDataStore}
import gateway.{InMemoryRequestsDataStore, RequestsDataStore}
import models._
import otoroshi.script.{InMemoryScriptDataStore, ScriptDataStore}
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.{Configuration, Environment, Logger}
import redis._
import redis.util.CRC16
import ssl.{CertificateDataStore, ClientCertificateValidationDataStore, InMemoryClientCertificateValidationDataStore}
import storage._
import storage.inmemory._
import otoroshi.tcp.{InMemoryTcpServiceDataStoreDataStore, TcpServiceDataStore}

import scala.concurrent.{ExecutionContext, Future}

class RedisCPStore(redis: RedisClientPool, env: Env, ec: ExecutionContext) extends RedisCommandsStore(redis, env, ec)

class RedisMCPStore(redis: RedisClientMutablePool, env: Env, ec: ExecutionContext)
    extends RedisCommandsStore(redis, env, ec)

class RedisClusterStore(redis: RedisCluster, env: Env, ec: ExecutionContext)
    extends RedisCommandsStore(redis, env, ec, true)

class RedisLFStore(redis: RedisClientMasterSlaves, env: Env, ec: ExecutionContext)
    extends RedisCommandsStore(redis, env, ec)

class RedisSentinelStore(redis: SentinelMonitoredRedisClient, env: Env, ec: ExecutionContext)
    extends RedisCommandsStore(redis, env, ec)

class RedisSentinelLFStore(redis: SentinelMonitoredRedisClientMasterSlaves, env: Env, ec: ExecutionContext)
    extends RedisCommandsStore(redis, env, ec)

class RedisCPDataStores(configuration: Configuration,
                        environment: Environment,
                        lifecycle: ApplicationLifecycle,
                        env: Env)
    extends AbstractRedisDataStores(configuration, environment, lifecycle, env) {
  lazy val redisCli: RedisClientPool = {
    implicit val ec = redisDispatcher
    val members = configuration
      .getOptional[Seq[Configuration]]("app.redis.pool.members")
      .map(_.map { config =>
        RedisServer(
          host = config.getOptional[String]("host").getOrElse("localhost"),
          port = config.getOptional[Int]("port").getOrElse(6379),
          password = config.getOptional[String]("password")
        )
      })
      .getOrElse(Seq.empty[RedisServer])
    val cli: RedisClientPool = RedisClientPool(
      members
    )(redisActorSystem)
    cli
  }
  lazy val _redis: RedisLike                          = new RedisCPStore(redisCli, env, redisActorSystem.dispatcher)
  override def loggerName: String                     = "otoroshi-redis-pool-datastores"
  override def name: String                           = "Redis Pool"
  override def redis: RedisLike                       = _redis
  override def info(): Future[String]                 = redisCli.info()
  override def typeOfKey(key: String): Future[String] = redisCli.`type`(key)
}

class RedisMCPDataStores(configuration: Configuration,
                         environment: Environment,
                         lifecycle: ApplicationLifecycle,
                         env: Env)
    extends AbstractRedisDataStores(configuration, environment, lifecycle, env) {
  lazy val redisCli: RedisClientMutablePool = {
    implicit val ec = redisDispatcher
    val members = configuration
      .getOptional[Seq[Configuration]]("app.redis.mpool.members")
      .map(_.map { config =>
        RedisServer(
          host = config.getOptional[String]("host").getOrElse("localhost"),
          port = config.getOptional[Int]("port").getOrElse(6379),
          password = config.getOptional[String]("password")
        )
      })
      .getOrElse(Seq.empty[RedisServer])
    val cli: RedisClientMutablePool = RedisClientMutablePool(
      members
    )(redisActorSystem)
    cli
  }
  lazy val _redis: RedisLike                          = new RedisMCPStore(redisCli, env, redisActorSystem.dispatcher)
  override def loggerName: String                     = "otoroshi-redis-mpool-datastores"
  override def name: String                           = "Redis Mutable Pool"
  override def redis: RedisLike                       = _redis
  override def info(): Future[String]                 = redisCli.info()
  override def typeOfKey(key: String): Future[String] = redisCli.`type`(key)
}

class RedisLFDataStores(configuration: Configuration,
                        environment: Environment,
                        lifecycle: ApplicationLifecycle,
                        env: Env)
    extends AbstractRedisDataStores(configuration, environment, lifecycle, env) {
  lazy val redisCli: RedisClientMasterSlaves = {
    implicit val ec = redisDispatcher
    val master = RedisServer(
      host = configuration
        .getOptional[String]("app.redis.host")
        .orElse(configuration.getOptional[String]("app.redis.lf.master.host"))
        .getOrElse("localhost"),
      port = configuration
        .getOptional[Int]("app.redis.port")
        .orElse(configuration.getOptional[Int]("app.redis.lf.master.port"))
        .getOrElse(6379),
      password = configuration
        .getOptional[String]("app.redis.password")
        .orElse(configuration.getOptional[String]("app.redis.lf.master.password"))
    )
    val slaves = configuration
      .getOptional[Seq[Configuration]]("app.redis.slaves")
      .orElse(configuration.getOptional[Seq[Configuration]]("app.redis.lf.slaves"))
      .map(_.map { config =>
        // val config = Configuration(cfgobj.toConfig)
        RedisServer(
          host = config.getOptional[String]("host").getOrElse("localhost"),
          port = config.getOptional[Int]("port").getOrElse(6379),
          password = config.getOptional[String]("password")
        )
      })
      .getOrElse(Seq.empty[RedisServer])
    val cli: RedisClientMasterSlaves = RedisClientMasterSlaves(
      master,
      slaves
    )(redisActorSystem)
    cli
  }
  lazy val _redis: RedisLike                          = new RedisLFStore(redisCli, env, redisActorSystem.dispatcher)
  override def loggerName: String                     = "otoroshi-redis-lf-datastores"
  override def name: String                           = "Redis Leader/Followers"
  override def redis: RedisLike                       = _redis
  override def info(): Future[String]                 = redisCli.info()
  override def typeOfKey(key: String): Future[String] = redisCli.`type`(key)
}

class RedisSentinelDataStores(configuration: Configuration,
                              environment: Environment,
                              lifecycle: ApplicationLifecycle,
                              env: Env)
    extends AbstractRedisDataStores(configuration, environment, lifecycle, env) {
  lazy val redisCli: SentinelMonitoredRedisClient = {
    implicit val ec = redisDispatcher
    val members: Seq[(String, Int)] = configuration
      .getOptional[Seq[Configuration]]("app.redis.sentinels.members")
      .map(_.map { config =>
        (
          config.getOptional[String]("host").getOrElse("localhost"),
          config.getOptional[Int]("port").getOrElse(6379)
        )
      })
      .getOrElse(Seq.empty[(String, Int)])
    val master   = configuration.getOptional[String]("app.redis.sentinels.master").get
    val password = configuration.getOptional[String]("app.redis.sentinels.password")
    val db       = configuration.getOptional[Int]("app.redis.sentinels.db")
    val name     = configuration.getOptional[String]("app.redis.sentinels.name").getOrElse("SMRedisClient")
    val cli: SentinelMonitoredRedisClient = SentinelMonitoredRedisClient(
      members,
      master,
      password,
      db,
      name
    )(redisActorSystem)
    cli
  }
  lazy val _redis: RedisLike                          = new RedisSentinelStore(redisCli, env, redisActorSystem.dispatcher)
  override def loggerName: String                     = "otoroshi-redis-sentinel-datastores"
  override def name: String                           = "Redis Sentinels"
  override def redis: RedisLike                       = _redis
  override def info(): Future[String]                 = redisCli.info()
  override def typeOfKey(key: String): Future[String] = redisCli.`type`(key)
}

class RedisSentinelLFDataStores(configuration: Configuration,
                                environment: Environment,
                                lifecycle: ApplicationLifecycle,
                                env: Env)
    extends AbstractRedisDataStores(configuration, environment, lifecycle, env) {
  lazy val redisCli: SentinelMonitoredRedisClientMasterSlaves = {
    implicit val ec = redisDispatcher
    val members: Seq[(String, Int)] = configuration
      .getOptional[Seq[Configuration]]("app.redis.sentinels.lf.members")
      .map(_.map { config =>
        (
          config.getOptional[String]("host").getOrElse("localhost"),
          config.getOptional[Int]("port").getOrElse(6379)
        )
      })
      .getOrElse(Seq.empty[(String, Int)])
    val master = configuration.getOptional[String]("app.redis.sentinels.lf.master").get
    val cli: SentinelMonitoredRedisClientMasterSlaves = SentinelMonitoredRedisClientMasterSlaves(
      members,
      master
    )(redisActorSystem)
    cli
  }
  lazy val _redis: RedisLike                          = new RedisSentinelLFStore(redisCli, env, redisActorSystem.dispatcher)
  override def loggerName: String                     = "otoroshi-redis-sentinel-lf-datastores"
  override def name: String                           = "Redis Sentinel Leader/Followers"
  override def redis: RedisLike                       = _redis
  override def info(): Future[String]                 = redisCli.info()
  override def typeOfKey(key: String): Future[String] = redisCli.`type`(key)
}

class RedisClusterDataStores(configuration: Configuration,
                             environment: Environment,
                             lifecycle: ApplicationLifecycle,
                             env: Env)
    extends AbstractRedisDataStores(configuration, environment, lifecycle, env) {

  lazy val redisCluster: RedisCluster = {
    implicit val ec = redisDispatcher
    val members = configuration
      .getOptional[Seq[Configuration]]("app.redis.cluster.members")
      .map(_.map { config =>
        RedisServer(
          host = config.getOptional[String]("host").getOrElse("localhost"),
          port = config.getOptional[Int]("port").getOrElse(6379),
          password = config.getOptional[String]("password")
        )
      })
      .getOrElse(Seq.empty[RedisServer])
    val cli: RedisCluster = RedisCluster(
      members
    )(redisActorSystem)
    cli
  }

  lazy val _redis: RedisLike = new RedisClusterStore(redisCluster, env, redisActorSystem.dispatcher)

  override def loggerName: String                     = "otoroshi-redis-cluster-datastores"
  override def name: String                           = "Redis Cluster"
  override def redis: RedisLike                       = _redis
  override def info(): Future[String]                 = redisCluster.info()
  override def typeOfKey(key: String): Future[String] = redisCluster.`type`(key)
}

abstract class AbstractRedisDataStores(configuration: Configuration,
                                       environment: Environment,
                                       lifecycle: ApplicationLifecycle,
                                       env: Env)
    extends DataStores {

  def loggerName: String
  def name: String
  def redis: RedisLike
  def info(): Future[String]
  def typeOfKey(key: String): Future[String]

  lazy val logger = Logger(loggerName)

  lazy val redisStatsItems: Int = configuration.getOptional[Int]("app.redis.windowSize").getOrElse(99)
  lazy val redisActorSystem =
    ActorSystem(
      "otoroshi-redis-system",
      configuration
        .getOptional[Configuration]("app.actorsystems.datastore")
        .map(_.underlying)
        .getOrElse(ConfigFactory.empty)
    )
  lazy val redisDispatcher = redisActorSystem.dispatcher

  override def before(configuration: Configuration,
                      environment: Environment,
                      lifecycle: ApplicationLifecycle): Future[Unit] = {
    logger.info(s"Now using $name DataStores")
    _serviceDescriptorDataStore.startCleanup(env)
    _certificateDataStore.startSync()
    FastFuture.successful(())
  }

  override def after(configuration: Configuration,
                     environment: Environment,
                     lifecycle: ApplicationLifecycle): Future[Unit] = {
    _serviceDescriptorDataStore.stopCleanup()
    _certificateDataStore.stopSync()
    redisActorSystem.terminate()
    FastFuture.successful(())
  }

  private lazy val _privateAppsUserDataStore   = new InMemoryPrivateAppsUserDataStore(redis, env)
  private lazy val _backOfficeUserDataStore    = new InMemoryBackOfficeUserDataStore(redis, env)
  private lazy val _serviceGroupDataStore      = new InMemoryServiceGroupDataStore(redis, env)
  private lazy val _globalConfigDataStore      = new InMemoryGlobalConfigDataStore(redis, env)
  private lazy val _apiKeyDataStore            = new InMemoryApiKeyDataStore(redis, env)
  private lazy val _serviceDescriptorDataStore = new InMemoryServiceDescriptorDataStore(redis, redisStatsItems, env)
  private lazy val _u2FAdminDataStore          = new InMemoryU2FAdminDataStore(redis)
  private lazy val _simpleAdminDataStore       = new InMemorySimpleAdminDataStore(redis, env)
  private lazy val _alertDataStore             = new InMemoryAlertDataStore(redis)
  private lazy val _auditDataStore             = new InMemoryAuditDataStore(redis)
  private lazy val _healthCheckDataStore       = new InMemoryHealthCheckDataStore(redis, env)
  private lazy val _errorTemplateDataStore     = new InMemoryErrorTemplateDataStore(redis, env)
  private lazy val _requestsDataStore          = new InMemoryRequestsDataStore()
  private lazy val _canaryDataStore            = new InMemoryCanaryDataStore(redis, env)
  private lazy val _chaosDataStore             = new InMemoryChaosDataStore(redis, env)
  private lazy val _jwtVerifDataStore          = new InMemoryGlobalJwtVerifierDataStore(redis, env)
  private lazy val _authConfigsDataStore       = new InMemoryAuthConfigsDataStore(redis, env)
  private lazy val _certificateDataStore       = new InMemoryCertificateDataStore(redis, env)

  private lazy val _clusterStateDataStore                   = new InMemoryClusterStateDataStore(redis, env)
  override def clusterStateDataStore: ClusterStateDataStore = _clusterStateDataStore

  private lazy val _clientCertificateValidationDataStore = new InMemoryClientCertificateValidationDataStore(redis, env)
  override def clientCertificateValidationDataStore: ClientCertificateValidationDataStore =
    _clientCertificateValidationDataStore

  private lazy val _scriptDataStore             = new InMemoryScriptDataStore(redis, env)
  override def scriptDataStore: ScriptDataStore = _scriptDataStore

  private lazy val _tcpServiceDataStore                 = new InMemoryTcpServiceDataStoreDataStore(redis, env)
  override def tcpServiceDataStore: TcpServiceDataStore = _tcpServiceDataStore

  private lazy val _rawDataStore                 = new InMemoryRawDataStore(redis)
  override def rawDataStore: RawDataStore        = _rawDataStore

  override def privateAppsUserDataStore: PrivateAppsUserDataStore     = _privateAppsUserDataStore
  override def backOfficeUserDataStore: BackOfficeUserDataStore       = _backOfficeUserDataStore
  override def serviceGroupDataStore: ServiceGroupDataStore           = _serviceGroupDataStore
  override def globalConfigDataStore: GlobalConfigDataStore           = _globalConfigDataStore
  override def apiKeyDataStore: ApiKeyDataStore                       = _apiKeyDataStore
  override def serviceDescriptorDataStore: ServiceDescriptorDataStore = _serviceDescriptorDataStore
  override def u2FAdminDataStore: U2FAdminDataStore                   = _u2FAdminDataStore
  override def simpleAdminDataStore: SimpleAdminDataStore             = _simpleAdminDataStore
  override def alertDataStore: AlertDataStore                         = _alertDataStore
  override def auditDataStore: AuditDataStore                         = _auditDataStore
  override def healthCheckDataStore: HealthCheckDataStore             = _healthCheckDataStore
  override def errorTemplateDataStore: ErrorTemplateDataStore         = _errorTemplateDataStore
  override def requestsDataStore: RequestsDataStore                   = _requestsDataStore
  override def canaryDataStore: CanaryDataStore                       = _canaryDataStore
  override def chaosDataStore: ChaosDataStore                         = _chaosDataStore
  override def globalJwtVerifierDataStore: GlobalJwtVerifierDataStore = _jwtVerifDataStore
  override def authConfigsDataStore: AuthConfigsDataStore             = _authConfigsDataStore
  override def certificatesDataStore: CertificateDataStore            = _certificateDataStore
  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = {
    info().map(_ => Healthy).recover {
      case _ => Unreachable
    }
  }
  override def rawExport(
      group: Int
  )(implicit ec: ExecutionContext, mat: Materializer, env: Env): Source[JsValue, NotUsed] = {
    Source
      .fromFuture(
        redis.keys(s"${env.storageRoot}:*")
      )
      .mapConcat(_.toList)
      .grouped(group)
      .mapAsync(1) {
        case keys if keys.isEmpty => FastFuture.successful(Seq.empty[JsValue])
        case keys => {
          Future.sequence(
            keys
              .filterNot { key =>
                key == s"${env.storageRoot}:cluster:" ||
                key == s"${env.storageRoot}:events:audit" ||
                key == s"${env.storageRoot}:events:alerts" ||
                key.startsWith(s"${env.storageRoot}:users:backoffice") ||
                key.startsWith(s"${env.storageRoot}:admins:") ||
                key.startsWith(s"${env.storageRoot}:u2f:users:") ||
                key.startsWith(s"${env.storageRoot}:deschealthcheck:") ||
                key.startsWith(s"${env.storageRoot}:scall:stats:") ||
                key.startsWith(s"${env.storageRoot}:scalldur:stats:") ||
                key.startsWith(s"${env.storageRoot}:scallover:stats:") ||
                (key.startsWith(s"${env.storageRoot}:data:") && key.endsWith(":stats:in")) ||
                (key.startsWith(s"${env.storageRoot}:data:") && key.endsWith(":stats:out"))
              }
              .map { key =>
                for {
                  w     <- typeOfKey(key)
                  ttl   <- redis.pttl(key)
                  value <- fetchValueForType(w, key)
                } yield
                  value match {
                    case JsNull => JsNull
                    case _ =>
                      Json.obj("k" -> key,
                               "v" -> value,
                               "t" -> (if (ttl == -1) -1 else (System.currentTimeMillis() + ttl)),
                               "w" -> w)
                  }
              }
          )
        }
      }
      .map(_.filterNot(_ == JsNull))
      .mapConcat(_.toList)
  }

  private def fetchValueForType(typ: String, key: String)(implicit ec: ExecutionContext): Future[JsValue] = {
    typ match {
      case "hash" => redis.hgetall(key).map(m => JsObject(m.map(t => (t._1, JsString(t._2.utf8String)))))
      case "list" => redis.lrange(key, 0, Long.MaxValue).map(l => JsArray(l.map(s => JsString(s.utf8String))))
      case "set"  => redis.smembers(key).map(l => JsArray(l.map(s => JsString(s.utf8String))))
      case "string" =>
        redis.get(key).map {
          case None    => JsNull
          case Some(a) => JsString(a.utf8String)
        }
      case _ => FastFuture.successful(JsNull)
    }
  }
}

class RedisCommandsStore(redis: RedisCommands, env: Env, executionContext: ExecutionContext, cluster: Boolean = false)
    extends RedisLike {

  implicit val ec = executionContext

  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = {
    redis.info().map(_ => Healthy).recover {
      case _ => Unreachable
    }
  }

  override def stop(): Unit = ()

  override def flushall(): Future[Boolean] = redis.flushall()

  override def get(key: String): Future[Option[ByteString]] = redis.get(key)

  override def mget(keys: String*): Future[Seq[Option[ByteString]]] = {
    if (cluster) {
      val keysAndHash: Seq[(Int, Seq[(String, Int)])] = keys.map(k => (k, CRC16.crc16(k))).groupBy(_._2).toSeq
      Future
        .sequence(keysAndHash.map { otherKeysTuple =>
          val (_, seq)          = otherKeysTuple
          val keys: Seq[String] = seq.map(_._1)
          redis.mget(keys: _*).map(_.zip(keys))
        })
        .map { res =>
          val results: Map[String, Option[ByteString]] = res.flatten.map(t => (t._2, t._1)).toMap
          keys.map(k => results.get(k).flatten)
        }
    } else {
      redis.mget(keys: _*)
    }
  }

  override def set(key: String, value: String, exSeconds: Option[Long], pxMilliseconds: Option[Long]): Future[Boolean] =
    setBS(key, ByteString(value), exSeconds, pxMilliseconds)

  override def setBS(key: String,
                     value: ByteString,
                     exSeconds: Option[Long],
                     pxMilliseconds: Option[Long]): Future[Boolean] = redis.set(key, value, exSeconds, pxMilliseconds)

  override def del(keys: String*): Future[Long] = {
    if (cluster) {
      Future
        .sequence(keys.toSeq.groupBy(CRC16.crc16).map { otherKeys =>
          redis.del(otherKeys._2: _*)
        })
        .map(_.foldLeft(0L)(_ + _))
    } else {
      redis.del(keys: _*)
    }
  }

  override def incr(key: String): Future[Long] = redis.incr(key)

  override def incrby(key: String, increment: Long): Future[Long] = redis.incrby(key, increment)

  override def exists(key: String): Future[Boolean] = redis.exists(key)

  override def keys(pattern: String): Future[Seq[String]] = redis.keys(pattern)

  override def hdel(key: String, fields: String*): Future[Long] = redis.hdel(key, fields: _*)

  override def hgetall(key: String): Future[Map[String, ByteString]] = redis.hgetall(key)

  override def hset(key: String, field: String, value: String): Future[Boolean] = hsetBS(key, field, ByteString(value))

  override def hsetBS(key: String, field: String, value: ByteString): Future[Boolean] = redis.hset(key, field, value)

  override def llen(key: String): Future[Long] = redis.llen(key)

  override def lpush(key: String, values: String*): Future[Long] = lpushBS(key, values.map(ByteString.apply): _*)

  override def lpushLong(key: String, values: Long*): Future[Long] =
    lpushBS(key, values.map(v => ByteString(v.toString)): _*)

  override def lpushBS(key: String, values: ByteString*): Future[Long] = redis.lpush(key, values: _*)

  override def lrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]] = redis.lrange(key, start, stop)

  override def ltrim(key: String, start: Long, stop: Long): Future[Boolean] = redis.ltrim(key, start, stop)

  override def pttl(key: String): Future[Long] = redis.pttl(key)

  override def ttl(key: String): Future[Long] = redis.ttl(key)

  override def expire(key: String, seconds: Int): Future[Boolean] = redis.expire(key, seconds)

  override def pexpire(key: String, milliseconds: Long): Future[Boolean] = redis.pexpire(key, milliseconds)

  override def sadd(key: String, members: String*): Future[Long] = saddBS(key, members.map(ByteString.apply): _*)

  override def saddBS(key: String, members: ByteString*): Future[Long] = redis.sadd(key, members: _*)

  override def sismember(key: String, member: String): Future[Boolean] = sismemberBS(key, ByteString(member))

  override def sismemberBS(key: String, member: ByteString): Future[Boolean] = redis.sismember(key, member)

  override def smembers(key: String): Future[Seq[ByteString]] = redis.smembers(key)

  override def srem(key: String, members: String*): Future[Long] = sremBS(key, members.map(ByteString.apply): _*)

  override def sremBS(key: String, members: ByteString*): Future[Long] = redis.srem(key, members: _*)

  override def scard(key: String): Future[Long] = redis.scard(key)
}
