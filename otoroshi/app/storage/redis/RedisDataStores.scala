package storage.redis

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import auth.AuthConfigsDataStore
import cluster.{ClusterStateDataStore, RedisClusterStateDataStore}
import com.typesafe.config.ConfigFactory
import env.Env
import events.{AlertDataStore, AuditDataStore, HealthCheckDataStore}
import gateway.{InMemoryRequestsDataStore, RequestsDataStore}
import models._
import otoroshi.script.{RedisScriptDataStore, ScriptDataStore}
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.{Configuration, Environment, Logger}
import redis.{RedisClientMasterSlaves, RedisServer}
import ssl.{CertificateDataStore, ClientCertificateValidationDataStore, RedisClientCertificateValidationDataStore}
import storage._
import otoroshi.tcp.{RedisTcpServiceDataStoreDataStore, TcpServiceDataStore}
import storage.inmemory.{InMemoryRawDataStore, WebAuthnAdminDataStore, WebAuthnRegistrationsDataStore}

import scala.concurrent.{ExecutionContext, Future}

class RedisDataStores(configuration: Configuration, environment: Environment, lifecycle: ApplicationLifecycle, env: Env)
    extends DataStores {

  lazy val logger = Logger("otoroshi-redis-datastores")

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
  lazy val redis: RedisClientMasterSlaves = {
    // import collection.JavaConverters._
    implicit val ec = redisDispatcher
    val master = RedisServer(
      host = configuration.getOptional[String]("app.redis.host").getOrElse("localhost"),
      port = configuration.getOptional[Int]("app.redis.port").getOrElse(6379),
      password = configuration.getOptional[String]("app.redis.password")
    )
    val slaves = configuration
      .getOptional[Seq[Configuration]]("app.redis.slaves")
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

  override def before(configuration: Configuration,
                      environment: Environment,
                      lifecycle: ApplicationLifecycle): Future[Unit] = {
    logger.info("Now using Redis DataStores")
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

  private lazy val _privateAppsUserDataStore   = new RedisPrivateAppsUserDataStore(redis, env)
  private lazy val _backOfficeUserDataStore    = new RedisBackOfficeUserDataStore(redis, env)
  private lazy val _serviceGroupDataStore      = new RedisServiceGroupDataStore(redis, env)
  private lazy val _globalConfigDataStore      = new RedisGlobalConfigDataStore(redis, env)
  private lazy val _apiKeyDataStore            = new RedisApiKeyDataStore(redis, env)
  private lazy val _serviceDescriptorDataStore = new RedisServiceDescriptorDataStore(redis, redisStatsItems, env)
  private lazy val _u2FAdminDataStore          = new RedisU2FAdminDataStore(redis)
  private lazy val _simpleAdminDataStore       = new RedisSimpleAdminDataStore(redis)
  private lazy val _alertDataStore             = new RedisAlertDataStore(redis)
  private lazy val _auditDataStore             = new RedisAuditDataStore(redis)
  private lazy val _healthCheckDataStore       = new RedisHealthCheckDataStore(redis, env)
  private lazy val _errorTemplateDataStore     = new RedisErrorTemplateDataStore(redis, env)
  private lazy val _requestsDataStore          = new InMemoryRequestsDataStore()
  private lazy val _canaryDataStore            = new RedisCanaryDataStore(redis, env)
  private lazy val _chaosDataStore             = new RedisChaosDataStore(redis, env)
  private lazy val _jwtVerifDataStore          = new RedisGlobalJwtVerifierDataStore(redis, env)
  private lazy val _authConfigsDataStore       = new RedisAuthConfigsDataStore(redis, env)
  private lazy val _certificateDataStore       = new RedisCertificateDataStore(redis, env)

  private lazy val _clusterStateDataStore                   = new RedisClusterStateDataStore(redis, env)
  override def clusterStateDataStore: ClusterStateDataStore = _clusterStateDataStore

  private lazy val _clientCertificateValidationDataStore = new RedisClientCertificateValidationDataStore(redis, env)
  override def clientCertificateValidationDataStore: ClientCertificateValidationDataStore =
    _clientCertificateValidationDataStore

  private lazy val _scriptDataStore             = new RedisScriptDataStore(redis, env)
  override def scriptDataStore: ScriptDataStore = _scriptDataStore

  private lazy val _tcpServiceDataStore                 = new RedisTcpServiceDataStoreDataStore(redis, env)
  override def tcpServiceDataStore: TcpServiceDataStore = _tcpServiceDataStore

  private lazy val _rawDataStore          = new RedisRawDataStore(redis)
  override def rawDataStore: RawDataStore = _rawDataStore

  private lazy val _webAuthnAdminDataStore                    = new WebAuthnAdminDataStore()
  override def webAuthnAdminDataStore: WebAuthnAdminDataStore = _webAuthnAdminDataStore

  private lazy val _webAuthnRegistrationsDataStore                            = new WebAuthnRegistrationsDataStore()
  override def webAuthnRegistrationsDataStore: WebAuthnRegistrationsDataStore = _webAuthnRegistrationsDataStore

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
    redis.info().map(_ => Healthy).recover {
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
                  w     <- redis.`type`(key)
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

class RedisRawDataStore(redis: RedisClientMasterSlaves) extends RawDataStore {

  override def exists(key: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = redis.exists(key)

  override def get(key: String)(implicit ec: ExecutionContext, env: Env): Future[Option[ByteString]] = redis.get(key)

  override def pttl(key: String)(implicit ec: ExecutionContext, env: Env): Future[Long] = redis.pttl(key)

  override def mget(keys: Seq[String])(implicit ec: ExecutionContext, env: Env): Future[Seq[Option[ByteString]]] =
    redis.mget(keys: _*)

  override def set(key: String, value: ByteString, ttl: Option[Long])(implicit ec: ExecutionContext,
                                                                      env: Env): Future[Boolean] =
    redis.set(key, value, pxMilliseconds = ttl)

  override def del(keys: Seq[String])(implicit ec: ExecutionContext, env: Env): Future[Long] = redis.del(keys: _*)

  override def incrby(key: String, incr: Long)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redis.incrby(key, incr)

  override def keys(pattern: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[String]] = redis.keys(pattern)
}
