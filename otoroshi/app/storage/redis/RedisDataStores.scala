package storage.redis

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import com.typesafe.config.ConfigFactory
import env.Env
import events.{AlertDataStore, AuditDataStore, HealthCheckDataStore}
import gateway.{InMemoryRequestsDataStore, RequestsDataStore}
import models._
import play.api.inject.ApplicationLifecycle
import play.api.{Configuration, Environment, Logger}
import redis.{RedisClientMasterSlaves, RedisServer}
import storage.{DataStoreHealth, DataStores, Healthy, Unreachable}

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
  lazy val redis = {
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
    logger.warn("Now using Redis DataStores")
    FastFuture.successful(())
  }

  override def after(configuration: Configuration,
                     environment: Environment,
                     lifecycle: ApplicationLifecycle): Future[Unit] = {
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
  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = {
    redis.info().map(_ => Healthy).recover {
      case _ => Unreachable
    }
  }
}
