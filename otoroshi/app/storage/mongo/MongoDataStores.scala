package storage.mongo

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import com.typesafe.config.ConfigFactory
import env.Env
import events.{AlertDataStore, AuditDataStore, HealthCheckDataStore}
import gateway.{InMemoryRequestsDataStore, RequestsDataStore}
import models._
import play.api.inject.ApplicationLifecycle
import play.api.{Configuration, Environment, Logger}
import reactivemongo.api.{MongoConnection, MongoDriver}
import storage.inmemory._
import storage.{DataStoreHealth, DataStores}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Failure

class MongoDataStores(configuration: Configuration,
                      environment: Environment,
                      lifecycle: ApplicationLifecycle,
                      env: Env) extends DataStores {

  lazy val logger = Logger("otoroshi-mongo-datastores")

  lazy val uri: String = configuration.getOptional[String]("app.mongo.uri").get
  lazy val database: String = configuration.getOptional[String]("app.mongo.database").getOrElse("default")
  lazy val strictMode: Boolean = configuration.getOptional[Boolean]("app.mongo.strict").getOrElse(false)

  lazy val parsedUri = MongoConnection.parseURI(uri).get
  lazy val dbName: String = parsedUri.db.getOrElse(database)

  lazy val statsItems: Int = configuration.getOptional[Int]("app.mongo.windowSize").getOrElse(99)

  lazy val actorSystem = ActorSystem(
    "otoroshi-mongo-system",
    configuration
      .getOptional[Configuration]("app.actorsystems.datastore")
      .map(_.underlying)
      .getOrElse(ConfigFactory.empty)
  )

  lazy val driver = new MongoDriver(Some(configuration.underlying), None)
  lazy val connection: MongoConnection = driver.connection(parsedUri, strictMode).get

  lazy val redis = new MongoRedis(actorSystem, connection, dbName)

  override def before(configuration: Configuration, environment: Environment, lifecycle: ApplicationLifecycle) = {
    logger.warn(s"Now using Mongo DataStores dbName:$dbName, uri:$parsedUri")
    redis.start()
    if (configuration.getOptional[Boolean]("app.mongo.testMode").getOrElse(false)) {
      logger.warn("Flushing DB as in test mode")
      Await.result(redis.flushall(), 5.second)
    }
    Await.result(redis.initIndexes(), 5.second)
    FastFuture.successful(())
  }

  override def after(configuration: Configuration, environment: Environment, lifecycle: ApplicationLifecycle) = {

    import actorSystem.dispatcher

    Await.ready(connection.askClose()(10.seconds).map { _ =>
      logger.info("Mongo connections are stopped")
    }.andThen {
      case Failure(reason) =>
        reason.printStackTrace()
        driver.close() // Close anyway
      case _ => driver.close()
    }, 12.seconds)
    redis.stop()
    actorSystem.terminate()
    FastFuture.successful(())
  }

  private lazy val _privateAppsUserDataStore   = new InMemoryPrivateAppsUserDataStore(redis, env)
  private lazy val _backOfficeUserDataStore    = new InMemoryBackOfficeUserDataStore(redis, env)
  private lazy val _serviceGroupDataStore      = new InMemoryServiceGroupDataStore(redis, env)
  private lazy val _globalConfigDataStore      = new InMemoryGlobalConfigDataStore(redis, env)
  private lazy val _apiKeyDataStore            = new InMemoryApiKeyDataStore(redis, env)
  private lazy val _serviceDescriptorDataStore = new InMemoryServiceDescriptorDataStore(redis, statsItems, env)
  private lazy val _u2FAdminDataStore          = new InMemoryU2FAdminDataStore(redis)
  private lazy val _simpleAdminDataStore       = new InMemorySimpleAdminDataStore(redis, env)
  private lazy val _alertDataStore             = new InMemoryAlertDataStore(redis)
  private lazy val _auditDataStore             = new InMemoryAuditDataStore(redis)
  private lazy val _healthCheckDataStore       = new InMemoryHealthCheckDataStore(redis, env)
  private lazy val _errorTemplateDataStore     = new InMemoryErrorTemplateDataStore(redis, env)
  private lazy val _requestsDataStore          = new InMemoryRequestsDataStore()
  private lazy val _canaryDataStore            = new InMemoryCanaryDataStore(redis, env)
  private lazy val _chaosDataStore             = new InMemoryChaosDataStore(redis, env)

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
}
