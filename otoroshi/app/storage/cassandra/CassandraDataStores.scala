package storage.cassandra

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import com.typesafe.config.ConfigFactory
import events.{AlertDataStore, AuditDataStore, HealthCheckDataStore}
import gateway.{InMemoryRequestsDataStore, RequestsDataStore}
import models._
import play.api.inject.ApplicationLifecycle
import play.api.{Configuration, Environment, Logger}
import storage.DataStores
import storage.inmemory._

import scala.concurrent.Future

class CassandraDataStores(configuration: Configuration, environment: Environment, lifecycle: ApplicationLifecycle)
    extends DataStores {

  lazy val logger = Logger("otoroshi-cassandra-datastores")

  lazy val cassandraContactPoints: Seq[String] = configuration
    .getString("app.cassandra.hosts")
    .map(_.split(",").toSeq)
    .orElse(
      configuration.getString("app.cassandra.host").map(e => Seq(e))
    )
    .getOrElse(Seq("127.0.0.1"))
  lazy val cassandraPort: Int   = configuration.getInt("app.cassandra.port").getOrElse(9042)
  lazy val redisStatsItems: Int = configuration.getInt("app.cassandra.windowSize").getOrElse(99)
  lazy val actorSystem =
    ActorSystem(
      "otoroshi-cassandra-system",
      configuration.getConfig("app.actorsystems.cassandra").map(_.underlying).getOrElse(ConfigFactory.empty)
    )
  lazy val redis = new CassandraRedis(actorSystem, cassandraContactPoints, cassandraPort)

  override def before(configuration: Configuration,
                      environment: Environment,
                      lifecycle: ApplicationLifecycle): Future[Unit] = {
    logger.warn("Now using Cassandra DataStores")
    redis.start()
    FastFuture.successful(())
  }

  override def after(configuration: Configuration,
                     environment: Environment,
                     lifecycle: ApplicationLifecycle): Future[Unit] = {
    redis.stop()
    actorSystem.terminate()
    FastFuture.successful(())
  }

  private lazy val _privateAppsUserDataStore   = new InMemoryPrivateAppsUserDataStore(redis)
  private lazy val _backOfficeUserDataStore    = new InMemoryBackOfficeUserDataStore(redis)
  private lazy val _serviceGroupDataStore      = new InMemoryServiceGroupDataStore(redis)
  private lazy val _globalConfigDataStore      = new InMemoryGlobalConfigDataStore(redis)
  private lazy val _apiKeyDataStore            = new InMemoryApiKeyDataStore(redis)
  private lazy val _serviceDescriptorDataStore = new InMemoryServiceDescriptorDataStore(redis, redisStatsItems)
  private lazy val _u2FAdminDataStore          = new InMemoryU2FAdminDataStore(redis)
  private lazy val _simpleAdminDataStore       = new InMemorySimpleAdminDataStore(redis)
  private lazy val _alertDataStore             = new InMemoryAlertDataStore(redis)
  private lazy val _auditDataStore             = new InMemoryAuditDataStore(redis)
  private lazy val _healthCheckDataStore       = new InMemoryHealthCheckDataStore(redis)
  private lazy val _errorTemplateDataStore     = new InMemoryErrorTemplateDataStore(redis)
  private lazy val _requestsDataStore          = new InMemoryRequestsDataStore()
  private lazy val _canaryDataStore            = new InMemoryCanaryDataStore(redis)

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
}
