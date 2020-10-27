package otoroshi.storage.drivers.rediscala

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import auth.AuthConfigsDataStore
import cluster.{ClusterStateDataStore, KvClusterStateDataStore}
import com.typesafe.config.ConfigFactory
import env.Env
import events.{AlertDataStore, AuditDataStore, HealthCheckDataStore}
import gateway.{InMemoryRequestsDataStore, RequestsDataStore}
import models._
import otoroshi.models.{SimpleAdminDataStore, WebAuthnAdminDataStore}
import otoroshi.script.{KvScriptDataStore, ScriptDataStore}
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.{Configuration, Environment, Logger}
import redis._
import redis.util.CRC16
import ssl.{CertificateDataStore, ClientCertificateValidationDataStore, KvClientCertificateValidationDataStore}
import otoroshi.storage._
import otoroshi.storage.stores._
import otoroshi.tcp.{KvTcpServiceDataStoreDataStore, TcpServiceDataStore}
import storage.stores.{DataExporterConfigDataStore, KvRawDataStore, TeamDataStore, TenantDataStore}
import otoroshi.utils.syntax.implicits._

import scala.concurrent.{ExecutionContext, Future}

case class RedisMember(host: String, port: Int, password: Option[String]) {
  def toRedisServer = RedisServer(
    host = host,
    port = port,
    password = password
  )
}

object RedisMember {
  def fromString(value: String): Option[RedisMember] = {
    value.trim match {
      case str if str.contains("@") && str.contains(":") =>
        str.split("@").toList match {
          case password :: rest :: Nil =>
            rest.split(":").toList match {
              case host :: port :: Nil => Some(RedisMember(host, port.toInt, Some(password)))
              case _                   => None
            }
          case _ => None
        }
      case str if str.contains(":") =>
        str.split(":").toList match {
          case host :: port :: Nil => Some(RedisMember(host, port.toInt, None))
          case _                   => None
        }
      case _ =>
        None
    }
  }
  def fromList(value: String): Seq[RedisMember] = {
    value.split(",").map(_.trim).flatMap(fromString)
  }
}

@deprecated(message = "Use lettuce instead", since = "1.5.0")
class RedisCPStore(redis: RedisClientPool, env: Env, ec: ExecutionContext) extends RedisCommandsStore(redis, env, ec)

@deprecated(message = "Use lettuce instead", since = "1.5.0")
class RedisMCPStore(redis: RedisClientMutablePool, env: Env, ec: ExecutionContext)
    extends RedisCommandsStore(redis, env, ec)

@deprecated(message = "Use lettuce instead", since = "1.5.0")
class RedisClusterStore(redis: RedisCluster, env: Env, ec: ExecutionContext)
    extends RedisCommandsStore(redis, env, ec, true)

@deprecated(message = "Use lettuce instead", since = "1.5.0")
class RedisLFStore(redis: RedisClientMasterSlaves, env: Env, ec: ExecutionContext)
    extends RedisCommandsStore(redis, env, ec)

@deprecated(message = "Use lettuce instead", since = "1.5.0")
class RedisSentinelStore(redis: SentinelMonitoredRedisClient, env: Env, ec: ExecutionContext)
    extends RedisCommandsStore(redis, env, ec)

@deprecated(message = "Use lettuce instead", since = "1.5.0")
class RedisSentinelLFStore(redis: SentinelMonitoredRedisClientMasterSlaves, env: Env, ec: ExecutionContext)
    extends RedisCommandsStore(redis, env, ec)

@deprecated(message = "Use lettuce instead", since = "1.5.0")
class RedisCPDataStores(configuration: Configuration,
                        environment: Environment,
                        lifecycle: ApplicationLifecycle,
                        env: Env)
    extends AbstractRedisDataStores(configuration, environment, lifecycle, env) {
  lazy val redisCli: RedisClientPool = {
    implicit val ec = redisDispatcher
    val members = configuration
      .getOptionalWithFileSupport[Seq[Configuration]]("app.redis.pool.members")
      .map(_.map { config =>
        RedisServer(
          host = config.getOptionalWithFileSupport[String]("host").getOrElse("localhost"),
          port = config.getOptionalWithFileSupport[Int]("port").getOrElse(6379),
          password = config.getOptionalWithFileSupport[String]("password")
        )
      })
      .filter(_.nonEmpty)
      .orElse {
        configuration
          .getOptionalWithFileSupport[String]("app.redis.pool.membersStr")
          .map(RedisMember.fromList)
          .map(_.map(_.toRedisServer))
      }
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

@deprecated(message = "Use lettuce instead", since = "1.5.0")
class RedisMCPDataStores(configuration: Configuration,
                         environment: Environment,
                         lifecycle: ApplicationLifecycle,
                         env: Env)
    extends AbstractRedisDataStores(configuration, environment, lifecycle, env) {
  lazy val redisCli: RedisClientMutablePool = {
    implicit val ec = redisDispatcher
    val members = configuration
      .getOptionalWithFileSupport[Seq[Configuration]]("app.redis.mpool.members")
      .map(_.map { config =>
        RedisServer(
          host = config.getOptionalWithFileSupport[String]("host").getOrElse("localhost"),
          port = config.getOptionalWithFileSupport[Int]("port").getOrElse(6379),
          password = config.getOptionalWithFileSupport[String]("password")
        )
      })
      .filter(_.nonEmpty)
      .orElse {
        configuration
          .getOptionalWithFileSupport[String]("app.redis.mpool.membersStr")
          .map(RedisMember.fromList)
          .map(_.map(_.toRedisServer))
      }
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

@deprecated(message = "Use lettuce instead", since = "1.5.0")
class RedisLFDataStores(configuration: Configuration,
                        environment: Environment,
                        lifecycle: ApplicationLifecycle,
                        env: Env)
    extends AbstractRedisDataStores(configuration, environment, lifecycle, env) {
  lazy val redisCli: RedisClientMasterSlaves = {
    implicit val ec = redisDispatcher
    val master = RedisServer(
      host = configuration
        .getOptionalWithFileSupport[String]("app.redis.host")
        .orElse(configuration.getOptionalWithFileSupport[String]("app.redis.lf.master.host"))
        .getOrElse("localhost"),
      port = configuration
        .getOptionalWithFileSupport[Int]("app.redis.port")
        .orElse(configuration.getOptionalWithFileSupport[Int]("app.redis.lf.master.port"))
        .getOrElse(6379),
      password = configuration
        .getOptionalWithFileSupport[String]("app.redis.password")
        .orElse(configuration.getOptionalWithFileSupport[String]("app.redis.lf.master.password"))
    )
    val slaves = configuration
      .getOptionalWithFileSupport[Seq[Configuration]]("app.redis.slaves")
      .orElse(configuration.getOptionalWithFileSupport[Seq[Configuration]]("app.redis.lf.slaves"))
      .map(_.map { config =>
        // val config = Configuration(cfgobj.toConfig)
        RedisServer(
          host = config.getOptionalWithFileSupport[String]("host").getOrElse("localhost"),
          port = config.getOptionalWithFileSupport[Int]("port").getOrElse(6379),
          password = config.getOptionalWithFileSupport[String]("password")
        )
      })
      .filter(_.nonEmpty)
      .orElse {
        configuration.getOptionalWithFileSupport[String]("app.redis.slavesStr").map(RedisMember.fromList).map(_.map(_.toRedisServer))
      }
      .orElse {
        configuration
          .getOptionalWithFileSupport[String]("app.redis.lf.slavesStr")
          .map(RedisMember.fromList)
          .map(_.map(_.toRedisServer))
      }
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

@deprecated(message = "Use lettuce instead", since = "1.5.0")
class RedisSentinelDataStores(configuration: Configuration,
                              environment: Environment,
                              lifecycle: ApplicationLifecycle,
                              env: Env)
    extends AbstractRedisDataStores(configuration, environment, lifecycle, env) {
  lazy val redisCli: SentinelMonitoredRedisClient = {
    implicit val ec = redisDispatcher
    val members: Seq[(String, Int)] = configuration
      .getOptionalWithFileSupport[Seq[Configuration]]("app.redis.sentinels.members")
      .map(_.map { config =>
        (
          config.getOptionalWithFileSupport[String]("host").getOrElse("localhost"),
          config.getOptionalWithFileSupport[Int]("port").getOrElse(6379)
        )
      })
      .filter(_.nonEmpty)
      .orElse {
        configuration
          .getOptionalWithFileSupport[String]("app.redis.sentinels.membersStr")
          .map(RedisMember.fromList)
          .map(_.map(m => (m.host, m.port)))
      }
      .getOrElse(Seq.empty[(String, Int)])
    val master   = configuration.getOptionalWithFileSupport[String]("app.redis.sentinels.master").get
    val password = configuration.getOptionalWithFileSupport[String]("app.redis.sentinels.password")
    val db       = configuration.getOptionalWithFileSupport[Int]("app.redis.sentinels.db")
    val name     = configuration.getOptionalWithFileSupport[String]("app.redis.sentinels.name").getOrElse("SMRedisClient")
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

@deprecated(message = "Use lettuce instead", since = "1.5.0")
class RedisSentinelLFDataStores(configuration: Configuration,
                                environment: Environment,
                                lifecycle: ApplicationLifecycle,
                                env: Env)
    extends AbstractRedisDataStores(configuration, environment, lifecycle, env) {
  lazy val redisCli: SentinelMonitoredRedisClientMasterSlaves = {
    implicit val ec = redisDispatcher
    val members: Seq[(String, Int)] = configuration
      .getOptionalWithFileSupport[Seq[Configuration]]("app.redis.sentinels.lf.members")
      .map(_.map { config =>
        (
          config.getOptionalWithFileSupport[String]("host").getOrElse("localhost"),
          config.getOptionalWithFileSupport[Int]("port").getOrElse(6379)
        )
      })
      .filter(_.nonEmpty)
      .orElse {
        configuration
          .getOptionalWithFileSupport[String]("app.redis.sentinels.lf.membersStr")
          .map(RedisMember.fromList)
          .map(_.map(m => (m.host, m.port)))
      }
      .getOrElse(Seq.empty[(String, Int)])
    val master = configuration.getOptionalWithFileSupport[String]("app.redis.sentinels.lf.master").get
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

@deprecated(message = "Use lettuce instead", since = "1.5.0")
class RedisClusterDataStores(configuration: Configuration,
                             environment: Environment,
                             lifecycle: ApplicationLifecycle,
                             env: Env)
    extends AbstractRedisDataStores(configuration, environment, lifecycle, env) {

  lazy val redisCluster: RedisCluster = {
    implicit val ec = redisDispatcher
    val members = configuration
      .getOptionalWithFileSupport[Seq[Configuration]]("app.redis.cluster.members")
      .map(_.map { config =>
        RedisServer(
          host = config.getOptionalWithFileSupport[String]("host").getOrElse("localhost"),
          port = config.getOptionalWithFileSupport[Int]("port").getOrElse(6379),
          password = config.getOptionalWithFileSupport[String]("password")
        )
      })
      .filter(_.nonEmpty)
      .orElse {
        configuration
          .getOptionalWithFileSupport[String]("app.redis.sentinels.membersStr")
          .map(RedisMember.fromList)
          .map(_.map(_.toRedisServer))
      }
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

@deprecated(message = "Use lettuce instead", since = "1.5.0")
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

  lazy val redisStatsItems: Int = configuration.getOptionalWithFileSupport[Int]("app.redis.windowSize").getOrElse(99)
  lazy val redisActorSystem =
    ActorSystem(
      "otoroshi-redis-system",
      configuration
        .getOptionalWithFileSupport[Configuration]("app.actorsystems.datastore")
        .map(_.underlying)
        .getOrElse(ConfigFactory.empty)
    )
  lazy val redisDispatcher = redisActorSystem.dispatcher

  override def before(configuration: Configuration,
                      environment: Environment,
                      lifecycle: ApplicationLifecycle): Future[Unit] = {
    logger.info(s"Now using $name DataStores")
    logger.warn(s"You are using the rediscala datastore implementation that is now deprecated. It will eventually be replaced by the 'lettuce' implementation.")
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

  private lazy val _privateAppsUserDataStore   = new KvPrivateAppsUserDataStore(redis, env)
  private lazy val _backOfficeUserDataStore    = new KvBackOfficeUserDataStore(redis, env)
  private lazy val _serviceGroupDataStore      = new KvServiceGroupDataStore(redis, env)
  private lazy val _globalConfigDataStore      = new KvGlobalConfigDataStore(redis, env)
  private lazy val _apiKeyDataStore            = new KvApiKeyDataStore(redis, env)
  private lazy val _serviceDescriptorDataStore = new KvServiceDescriptorDataStore(redis, redisStatsItems, env)
  private lazy val _simpleAdminDataStore       = new KvSimpleAdminDataStore(redis, env)
  private lazy val _alertDataStore             = new KvAlertDataStore(redis)
  private lazy val _auditDataStore             = new KvAuditDataStore(redis)
  private lazy val _healthCheckDataStore       = new KvHealthCheckDataStore(redis, env)
  private lazy val _errorTemplateDataStore     = new KvErrorTemplateDataStore(redis, env)
  private lazy val _requestsDataStore          = new InMemoryRequestsDataStore()
  private lazy val _canaryDataStore            = new KvCanaryDataStore(redis, env)
  private lazy val _chaosDataStore             = new KvChaosDataStore(redis, env)
  private lazy val _jwtVerifDataStore          = new KvGlobalJwtVerifierDataStore(redis, env)
  private lazy val _authConfigsDataStore       = new KvAuthConfigsDataStore(redis, env)
  private lazy val _certificateDataStore       = new KvCertificateDataStore(redis, env)

  private lazy val _clusterStateDataStore                   = new KvClusterStateDataStore(redis, env)
  override def clusterStateDataStore: ClusterStateDataStore = _clusterStateDataStore

  private lazy val _clientCertificateValidationDataStore = new KvClientCertificateValidationDataStore(redis, env)
  override def clientCertificateValidationDataStore: ClientCertificateValidationDataStore =
    _clientCertificateValidationDataStore

  private lazy val _scriptDataStore             = new KvScriptDataStore(redis, env)
  override def scriptDataStore: ScriptDataStore = _scriptDataStore

  private lazy val _tcpServiceDataStore                 = new KvTcpServiceDataStoreDataStore(redis, env)
  override def tcpServiceDataStore: TcpServiceDataStore = _tcpServiceDataStore

  private lazy val _rawDataStore          = new KvRawDataStore(redis)
  override def rawDataStore: RawDataStore = _rawDataStore

  private lazy val _webAuthnAdminDataStore                    = new KvWebAuthnAdminDataStore()
  override def webAuthnAdminDataStore: WebAuthnAdminDataStore = _webAuthnAdminDataStore

  private lazy val _webAuthnRegistrationsDataStore                            = new WebAuthnRegistrationsDataStore()
  override def webAuthnRegistrationsDataStore: WebAuthnRegistrationsDataStore = _webAuthnRegistrationsDataStore

  private lazy val _tenantDataStore = new TenantDataStore(redis, env)
  override def tenantDataStore: TenantDataStore = _tenantDataStore

  private lazy val _teamDataStore = new TeamDataStore(redis, env)
  override def teamDataStore: TeamDataStore = _teamDataStore

  private lazy val _dataExporterConfigDataStore = new DataExporterConfigDataStore(redis, env)
  override def dataExporterConfigDataStore: DataExporterConfigDataStore = _dataExporterConfigDataStore

  override def privateAppsUserDataStore: PrivateAppsUserDataStore     = _privateAppsUserDataStore
  override def backOfficeUserDataStore: BackOfficeUserDataStore       = _backOfficeUserDataStore
  override def serviceGroupDataStore: ServiceGroupDataStore           = _serviceGroupDataStore
  override def globalConfigDataStore: GlobalConfigDataStore           = _globalConfigDataStore
  override def apiKeyDataStore: ApiKeyDataStore                       = _apiKeyDataStore
  override def serviceDescriptorDataStore: ServiceDescriptorDataStore = _serviceDescriptorDataStore
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
      .future(
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
                key.startsWith(s"${env.storageRoot}:users:") ||
                key.startsWith(s"${env.storageRoot}:webauthn:admins:") ||
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

  override def fullNdJsonExport(group: Int, groupWorkers: Int, keyWorkers: Int): Future[Source[JsValue, _]] = {

    implicit val ev  = env
    implicit val ecc = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer

    FastFuture.successful(
      Source
        .future(redis.keys(s"${env.storageRoot}:*"))
        .mapConcat(_.toList)
        .grouped(10)
        .mapAsync(1) {
          case keys if keys.isEmpty => FastFuture.successful(Seq.empty[JsValue])
          case keys => {
            Source(keys.toList)
              .mapAsync(1) { key =>
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
              .runWith(Sink.seq)
              .map(_.filterNot(_ == JsNull))
          }
        }
        .mapConcat(_.toList)
    )
  }

  override def fullNdJsonImport(export: Source[JsValue, _]): Future[Unit] = {

    implicit val ev  = env
    implicit val ecc = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer

    redis
      .keys(s"${env.storageRoot}:*")
      .flatMap(keys => if (keys.nonEmpty) redis.del(keys: _*) else FastFuture.successful(0L))
      .flatMap { _ =>
        export
          .mapAsync(1) { json =>
            val key   = (json \ "k").as[String]
            val value = (json \ "v").as[JsValue]
            val pttl  = (json \ "t").as[Long]
            val what  = (json \ "w").as[String]
            (what match {
              case "string" => redis.set(key, value.as[String])
              case "hash" =>
                Source(value.as[JsObject].value.toList)
                  .mapAsync(1)(v => redis.hset(key, v._1, Json.stringify(v._2)))
                  .runWith(Sink.ignore)
              case "list" => redis.lpush(key, value.as[JsArray].value.map(Json.stringify): _*)
              case "set"  => redis.sadd(key, value.as[JsArray].value.map(Json.stringify): _*)
              case _      => FastFuture.successful(0L)
            }).flatMap { _ =>
              if (pttl > -1L) {
                redis.pexpire(key, pttl)
              } else {
                FastFuture.successful(true)
              }
            }
          }
          .runWith(Sink.ignore)
          .map(_ => ())
      }
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

@deprecated(message = "Use lettuce instead", since = "1.5.0")
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

  override def rawGet(key: String): Future[Option[Any]] = redis.get(key)

  override def setnxBS(key: String, value: ByteString, ttl: Option[Long])(implicit ec: ExecutionContext,
                                                                          env: Env): Future[Boolean] =
    redis.setnx(key, value).flatMap {
      case false => FastFuture.successful(false)
      case true =>
        ttl match {
          case None    => FastFuture.successful(true)
          case Some(v) => redis.pexpire(key, v).map(_ => true)
        }
    }
}
