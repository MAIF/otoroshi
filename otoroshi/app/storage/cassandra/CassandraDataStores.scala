package storage.cassandra

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import auth.AuthConfigsDataStore
import cluster.{ClusterStateDataStore, InMemoryClusterStateDataStore}
import com.typesafe.config.ConfigFactory
import events.{AlertDataStore, AuditDataStore, HealthCheckDataStore}
import gateway.{InMemoryRequestsDataStore, RequestsDataStore}
import models._
import play.api.inject.ApplicationLifecycle
import play.api.{Configuration, Environment, Logger}
import storage.{DataStoreHealth, DataStores}
import storage.inmemory._
import env.Env
import play.api.libs.json._
import ssl.{CertificateDataStore, ClientCertificateValidationDataStore, InMemoryClientCertificateValidationDataStore}

import scala.concurrent.{ExecutionContext, Future}

class CassandraDataStores(configuration: Configuration,
                          environment: Environment,
                          lifecycle: ApplicationLifecycle,
                          env: Env)
    extends DataStores {

  lazy val logger = Logger("otoroshi-cassandra-datastores")

  lazy val cassandraContactPoints: Seq[String] = configuration
    .getOptional[String]("app.cassandra.hosts")
    .map(_.split(",").toSeq)
    .orElse(
      configuration.getOptional[String]("app.cassandra.host").map(e => Seq(e))
    )
    .getOrElse(Seq("127.0.0.1"))
  lazy val cassandraReplicationStrategy: String =
    configuration.getOptional[String]("app.cassandra.replicationStrategy").getOrElse("SimpleStrategy")
  lazy val cassandraReplicationOptions: String =
    configuration.getOptional[String]("app.cassandra.replicationOptions").getOrElse("'dc0': 1")
  lazy val cassandraReplicationFactor: Int =
    configuration.getOptional[Int]("app.cassandra.replicationFactor").getOrElse(1)
  lazy val cassandraPort: Int       = configuration.getOptional[Int]("app.cassandra.port").getOrElse(9042)
  lazy val redisStatsItems: Int     = configuration.getOptional[Int]("app.cassandra.windowSize").getOrElse(99)
  lazy val username: Option[String] = configuration.getOptional[String]("app.cassandra.username")
  lazy val password: Option[String] = configuration.getOptional[String]("app.cassandra.password")

  lazy val actorSystem =
    ActorSystem(
      "otoroshi-cassandra-system",
      configuration
        .getOptional[Configuration]("app.actorsystems.datastore")
        .map(_.underlying)
        .getOrElse(ConfigFactory.empty)
    )
  lazy val redis = new CassandraRedis(
    actorSystem,
    cassandraReplicationStrategy,
    cassandraReplicationFactor,
    cassandraReplicationOptions,
    cassandraContactPoints,
    cassandraPort,
    username,
    password
  )

  override def before(configuration: Configuration,
                      environment: Environment,
                      lifecycle: ApplicationLifecycle): Future[Unit] = {
    logger.info("Now using Cassandra DataStores")
    redis.start()
    _certificateDataStore.startSync()
    FastFuture.successful(())
  }

  override def after(configuration: Configuration,
                     environment: Environment,
                     lifecycle: ApplicationLifecycle): Future[Unit] = {
    _certificateDataStore.stopSync()
    redis.stop()
    actorSystem.terminate()
    FastFuture.successful(())
  }

  private lazy val _privateAppsUserDataStore    = new InMemoryPrivateAppsUserDataStore(redis, env)
  private lazy val _backOfficeUserDataStore     = new InMemoryBackOfficeUserDataStore(redis, env)
  private lazy val _serviceGroupDataStore       = new InMemoryServiceGroupDataStore(redis, env)
  private lazy val _globalConfigDataStore       = new InMemoryGlobalConfigDataStore(redis, env)
  private lazy val _apiKeyDataStore             = new InMemoryApiKeyDataStore(redis, env)
  private lazy val _serviceDescriptorDataStore  = new InMemoryServiceDescriptorDataStore(redis, redisStatsItems, env)
  private lazy val _u2FAdminDataStore           = new InMemoryU2FAdminDataStore(redis)
  private lazy val _simpleAdminDataStore        = new InMemorySimpleAdminDataStore(redis, env)
  private lazy val _alertDataStore              = new InMemoryAlertDataStore(redis)
  private lazy val _auditDataStore              = new InMemoryAuditDataStore(redis)
  private lazy val _healthCheckDataStore        = new InMemoryHealthCheckDataStore(redis, env)
  private lazy val _errorTemplateDataStore      = new InMemoryErrorTemplateDataStore(redis, env)
  private lazy val _requestsDataStore           = new InMemoryRequestsDataStore()
  private lazy val _canaryDataStore             = new InMemoryCanaryDataStore(redis, env)
  private lazy val _chaosDataStore              = new InMemoryChaosDataStore(redis, env)
  private lazy val _jwtVerifDataStore           = new InMemoryGlobalJwtVerifierDataStore(redis, env)
  private lazy val _globalOAuth2ConfigDataStore = new InMemoryAuthConfigsDataStore(redis, env)
  private lazy val _certificateDataStore        = new InMemoryCertificateDataStore(redis, env)

  private lazy val _clusterStateDataStore                   = new InMemoryClusterStateDataStore(redis, env)
  override def clusterStateDataStore: ClusterStateDataStore = _clusterStateDataStore

  private lazy val _clientCertificateValidationDataStore                   = new InMemoryClientCertificateValidationDataStore(redis, env)
  override def clientCertificateValidationDataStore: ClientCertificateValidationDataStore = _clientCertificateValidationDataStore

  override def privateAppsUserDataStore: PrivateAppsUserDataStore               = _privateAppsUserDataStore
  override def backOfficeUserDataStore: BackOfficeUserDataStore                 = _backOfficeUserDataStore
  override def serviceGroupDataStore: ServiceGroupDataStore                     = _serviceGroupDataStore
  override def globalConfigDataStore: GlobalConfigDataStore                     = _globalConfigDataStore
  override def apiKeyDataStore: ApiKeyDataStore                                 = _apiKeyDataStore
  override def serviceDescriptorDataStore: ServiceDescriptorDataStore           = _serviceDescriptorDataStore
  override def u2FAdminDataStore: U2FAdminDataStore                             = _u2FAdminDataStore
  override def simpleAdminDataStore: SimpleAdminDataStore                       = _simpleAdminDataStore
  override def alertDataStore: AlertDataStore                                   = _alertDataStore
  override def auditDataStore: AuditDataStore                                   = _auditDataStore
  override def healthCheckDataStore: HealthCheckDataStore                       = _healthCheckDataStore
  override def errorTemplateDataStore: ErrorTemplateDataStore                   = _errorTemplateDataStore
  override def requestsDataStore: RequestsDataStore                             = _requestsDataStore
  override def canaryDataStore: CanaryDataStore                                 = _canaryDataStore
  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = redis.health()(ec)
  override def chaosDataStore: ChaosDataStore                                   = _chaosDataStore
  override def globalJwtVerifierDataStore: GlobalJwtVerifierDataStore           = _jwtVerifDataStore
  override def certificatesDataStore: CertificateDataStore                      = _certificateDataStore
  override def authConfigsDataStore: AuthConfigsDataStore                       = _globalOAuth2ConfigDataStore
  override def rawExport(
      group: Int
  )(implicit ec: ExecutionContext, mat: Materializer, env: Env): Source[JsValue, NotUsed] =
    throw new RuntimeException("Cluster mode not supported for Cassandra datastore")
}
