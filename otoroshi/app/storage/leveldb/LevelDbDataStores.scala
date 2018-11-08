package storage.leveldb

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
import play.api.{Configuration, Environment, Logger}
import play.api.inject.ApplicationLifecycle
import storage.{DataStoreHealth, DataStores}
import storage.inmemory._
import env.Env
import play.api.libs.json._
import ssl.CertificateDataStore

import scala.concurrent.{ExecutionContext, Future}

class LevelDbDataStores(configuration: Configuration,
                        environment: Environment,
                        lifecycle: ApplicationLifecycle,
                        env: Env)
    extends DataStores {

  lazy val logger = Logger("otoroshi-leveldb-datastores")

  logger.info(s"path at $dbPath")

  lazy val dbPath: String       = configuration.getOptional[String]("app.leveldb.path").getOrElse("./leveldb")
  lazy val redisStatsItems: Int = configuration.getOptional[Int]("app.leveldb.windowSize").getOrElse(99)
  lazy val actorSystem =
    ActorSystem(
      "otoroshi-leveldb-system",
      configuration
        .getOptional[Configuration]("app.actorsystems.datastore")
        .map(_.underlying)
        .getOrElse(ConfigFactory.empty)
    )
  lazy val redis = new LevelDbRedis(actorSystem, dbPath)

  override def before(configuration: Configuration,
                      environment: Environment,
                      lifecycle: ApplicationLifecycle): Future[Unit] = {
    logger.warn("Now using LevelDB DataStores")
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

  private lazy val _clusterStateDataStore       = new InMemoryClusterStateDataStore(redis, env)
  override def clusterStateDataStore: ClusterStateDataStore                     = _clusterStateDataStore

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
  override def rawExport(group: Int)(implicit ec: ExecutionContext, mat: Materializer, env: Env): Source[JsValue, NotUsed] = {
    Source
      .fromFuture(
        redis.keys(s"${env.storageRoot}:*")
      )
      .mapConcat(_.toList)
      .grouped(group)
      .mapAsync(1) {
        case keys if keys.isEmpty => FastFuture.successful(Seq.empty[JsValue])
        case keys                 => {
          Future.sequence(keys
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
            redis.get(key).flatMap { value =>
              val (what, jsonValue) = toJson(value.get)
              redis.pttl(key).map { ttl =>
                Json.obj("k" -> key, "v" -> jsonValue, "t" -> (if (ttl == -1) -1 else (System.currentTimeMillis() + ttl)), "w" -> what)
              }
            }
          })
        }
      }
      .mapConcat(_.toList)
  }

  private def toJson(value: Any): (String, JsValue) = {

    def strToTuple(str: String): (String, JsValue) = {
      val parts = str.split("<#>")
      (parts(0), JsString(parts(1)))
    }

    value match {
      case str: ByteString if str.containsSlice(ByteString("<#>")) => ("hash",   JsObject(str.utf8String.split(";;;").map(strToTuple).toSeq))
      case str: ByteString if str.containsSlice(ByteString(";;;")) => ("list",   JsArray(str.utf8String.split(";;;").toSeq.map(JsString.apply)))
      case str: ByteString if str.containsSlice(ByteString(";;>")) => ("set",    JsArray(str.utf8String.split(";;>").toSeq.map(JsString.apply)))
      case str: ByteString                                         => ("string", JsString(str.utf8String))
      case e => throw new RuntimeException(s"Unkown type for ${value}")
    }
  }
}
