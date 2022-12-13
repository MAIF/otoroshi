package otoroshi.storage.drivers.inmemory

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import otoroshi.auth.AuthConfigsDataStore
import otoroshi.cluster.{Cluster, ClusterStateDataStore, KvClusterStateDataStore}
import otoroshi.env.Env
import otoroshi.events.{AlertDataStore, AuditDataStore, HealthCheckDataStore}
import otoroshi.gateway.{InMemoryRequestsDataStore, RequestsDataStore}
import otoroshi.models._
import otoroshi.next.models._
import otoroshi.script.{KvScriptDataStore, ScriptDataStore}
import otoroshi.ssl.{CertificateDataStore, ClientCertificateValidationDataStore, KvClientCertificateValidationDataStore}
import otoroshi.storage.stores._
import otoroshi.storage.{DataStoreHealth, DataStores, RawDataStore, SwappableRedisLikeMetricsWrapper}
import otoroshi.tcp.{KvTcpServiceDataStoreDataStore, TcpServiceDataStore}
import otoroshi.utils.syntax.implicits._
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.{Configuration, Environment, Logger}

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

class InMemoryDataStores(
    configuration: Configuration,
    environment: Environment,
    lifecycle: ApplicationLifecycle,
    persistenceKind: PersistenceKind,
    env: Env
) extends DataStores {

  lazy val logger = Logger("otoroshi-datastores")

  lazy val redisStatsItems: Int = configuration.getOptionalWithFileSupport[Int]("app.inmemory.windowSize").getOrElse(99)

  lazy val actorSystem =
    ActorSystem(
      "otoroshi-inmemory-system",
      configuration
        .getOptionalWithFileSupport[Configuration]("app.actorsystems.datastore")
        .map(_.underlying)
        .getOrElse(ConfigFactory.empty)
    )
  val materializer     = Materializer(actorSystem)
  val _optimized       = configuration.getOptionalWithFileSupport[Boolean]("app.inmemory.optimized").getOrElse(false)
  val _modern          = configuration.getOptionalWithFileSupport[Boolean]("app.inmemory.modern").getOrElse(false)
  // lazy val redis       = new SwappableInMemoryRedis(_optimized, env, actorSystem)
  lazy val _redis      = if (_modern) {
    new ModernSwappableInMemoryRedis(_optimized, env, actorSystem)
  } else {
    new SwappableInMemoryRedis(_optimized, env, actorSystem)
  }
  lazy val redis       = if (env.isDev) new SwappableRedisLikeMetricsWrapper(_redis, env) else _redis

  lazy val persistence = persistenceKind match {
    case PersistenceKind.HttpPersistenceKind => new HttpPersistence(this, env)
    case PersistenceKind.FilePersistenceKind => new FilePersistence(this, env)
    case PersistenceKind.S3PersistenceKind   => new S3Persistence(this, env)
    case _                                   => new NoopPersistence(this, env)
  }

  override def before(
      configuration: Configuration,
      environment: Environment,
      lifecycle: ApplicationLifecycle
  ): Future[Unit] = {
    // logger.info("Now using InMemory DataStores")
    logger.info(persistence.message)
    persistence
      .onStart()
      .flatMap { _ =>
        redis.start()
        _serviceDescriptorDataStore.startCleanup(env)
        _certificateDataStore.startSync()
        FastFuture.successful(())
      }(actorSystem.dispatcher)
  }

  override def after(
      configuration: Configuration,
      environment: Environment,
      lifecycle: ApplicationLifecycle
  ): Future[Unit] = {
    _certificateDataStore.stopSync()
    _serviceDescriptorDataStore.stopCleanup()
    redis.stop()
    persistence
      .onStop()
      .flatMap { _ =>
        actorSystem.terminate()
        FastFuture.successful(())
      }(actorSystem.dispatcher)
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

  private lazy val _clientCertificateValidationDataStore                                  = new KvClientCertificateValidationDataStore(redis, env)
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

  private lazy val _tenantDataStore             = new TenantDataStore(redis, env)
  override def tenantDataStore: TenantDataStore = _tenantDataStore

  private lazy val _teamDataStore           = new TeamDataStore(redis, env)
  override def teamDataStore: TeamDataStore = _teamDataStore

  private lazy val _dataExporterConfigDataStore                         = new DataExporterConfigDataStore(redis, env)
  override def dataExporterConfigDataStore: DataExporterConfigDataStore = _dataExporterConfigDataStore

  private lazy val _routeDataStore              = new KvNgRouteDataStore(redis, env)
  override def routeDataStore: NgRouteDataStore = _routeDataStore

  private lazy val _routesCompositionDataStore                        = new KvNgRouteCompositionDataStore(redis, env)
  override def routeCompositionDataStore: NgRouteCompositionDataStore = _routesCompositionDataStore

  private lazy val _backendsDataStore                      = new KvStoredNgBackendDataStore(redis, env)
  override def backendsDataStore: StoredNgBackendDataStore = _backendsDataStore

  override def privateAppsUserDataStore: PrivateAppsUserDataStore               = _privateAppsUserDataStore
  override def backOfficeUserDataStore: BackOfficeUserDataStore                 = _backOfficeUserDataStore
  override def serviceGroupDataStore: ServiceGroupDataStore                     = _serviceGroupDataStore
  override def globalConfigDataStore: GlobalConfigDataStore                     = _globalConfigDataStore
  override def apiKeyDataStore: ApiKeyDataStore                                 = _apiKeyDataStore
  override def serviceDescriptorDataStore: ServiceDescriptorDataStore           = _serviceDescriptorDataStore
  override def simpleAdminDataStore: SimpleAdminDataStore                       = _simpleAdminDataStore
  override def alertDataStore: AlertDataStore                                   = _alertDataStore
  override def auditDataStore: AuditDataStore                                   = _auditDataStore
  override def healthCheckDataStore: HealthCheckDataStore                       = _healthCheckDataStore
  override def errorTemplateDataStore: ErrorTemplateDataStore                   = _errorTemplateDataStore
  override def requestsDataStore: RequestsDataStore                             = _requestsDataStore
  override def canaryDataStore: CanaryDataStore                                 = _canaryDataStore
  override def chaosDataStore: ChaosDataStore                                   = _chaosDataStore
  override def globalJwtVerifierDataStore: GlobalJwtVerifierDataStore           = _jwtVerifDataStore
  override def authConfigsDataStore: AuthConfigsDataStore                       = _authConfigsDataStore
  override def certificatesDataStore: CertificateDataStore                      = _certificateDataStore
  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = redis.health()(ec)
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
        case keys                 => {
          Future.sequence(
            keys
              .filterNot { key =>
                Cluster.filteredKey(key, env)
              //key == s"${env.storageRoot}:cluster:" ||
              //key == s"${env.storageRoot}:events:audit" ||
              //key == s"${env.storageRoot}:events:alerts" ||
              //key.startsWith(s"${env.storageRoot}:users:backoffice") ||
              //key.startsWith(s"${env.storageRoot}:admins:") ||
              //key.startsWith(s"${env.storageRoot}:u2f:users:") ||
              //// key.startsWith(s"${env.storageRoot}:users:") ||
              //key.startsWith(s"${env.storageRoot}:webauthn:admins:") ||
              //key.startsWith(s"${env.storageRoot}:deschealthcheck:") ||
              //key.startsWith(s"${env.storageRoot}:scall:stats:") ||
              //key.startsWith(s"${env.storageRoot}:scalldur:stats:") ||
              //key.startsWith(s"${env.storageRoot}:scallover:stats:") ||
              //(key.startsWith(s"${env.storageRoot}:data:") && key.endsWith(":stats:in")) ||
              //(key.startsWith(s"${env.storageRoot}:data:") && key.endsWith(":stats:out"))
              }
              .map { key =>
                redis.rawGet(key).flatMap {
                  case None        => FastFuture.successful(JsNull)
                  case Some(value) => {
                    toJson(value) match {
                      case (_, JsNull)       => FastFuture.successful(JsNull)
                      case (what, jsonValue) =>
                        redis.pttl(key).map { ttl =>
                          Json.obj(
                            "k" -> key,
                            "v" -> jsonValue,
                            "t" -> (if (ttl == -1) -1 else (System.currentTimeMillis() + ttl)),
                            "w" -> what
                          )
                        }
                    }
                  }
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
          case keys                 => {
            Source(keys.toList)
              .mapAsync(1) { key =>
                redis.rawGet(key).flatMap {
                  case None        => FastFuture.successful(JsNull)
                  case Some(value) => {
                    toJson(value) match {
                      case (_, JsNull)       => FastFuture.successful(JsNull)
                      case (what, jsonValue) =>
                        redis.pttl(key).map { ttl =>
                          Json.obj(
                            "k" -> key,
                            "v" -> jsonValue,
                            "t" -> (if (ttl == -1) -1 else (System.currentTimeMillis() + ttl)),
                            "w" -> what
                          )
                        }
                    }
                  }
                }
              }
              .runWith(Sink.seq)
              .map(_.filterNot(_ == JsNull))
          }
        }
        .mapConcat(_.toList)
    )
  }

  override def fullNdJsonImport(exportSource: Source[JsValue, _]): Future[Unit] = {

    implicit val ev  = env
    implicit val ecc = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer

    redis
      .keys(s"${env.storageRoot}:*")
      .flatMap(keys => if (keys.nonEmpty) redis.del(keys: _*) else FastFuture.successful(0L))
      .flatMap { _ =>
        exportSource
          .mapAsync(1) { json =>
            val key   = (json \ "k").as[String]
            val value = (json \ "v").as[JsValue]
            val pttl  = (json \ "t").as[Long]
            val what  = (json \ "w").as[String]
            (what match {
              case "counter" => redis.set(key, value.as[Long].toString)
              case "string"  => redis.set(key, value.as[String])
              case "hash"    =>
                Source(value.as[JsObject].value.toList)
                  .mapAsync(1)(v => redis.hset(key, v._1, Json.stringify(v._2)))
                  .runWith(Sink.ignore)
              case "list"    => redis.lpush(key, value.as[JsArray].value.map(Json.stringify): _*)
              case "set"     => redis.sadd(key, value.as[JsArray].value.map(Json.stringify): _*)
              case _         => FastFuture.successful(0L)
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

  private def toJson(value: Any): (String, JsValue) = {

    import collection.JavaConverters._

    value match {
      case str: String                                                     => ("string", JsString(str))
      case str: ByteString                                                 => ("string", JsString(str.utf8String))
      case lng: Long                                                       => ("string", JsString(lng.toString))
      case map: java.util.concurrent.ConcurrentHashMap[String, ByteString] =>
        ("hash", JsObject(map.asScala.toSeq.map(t => (t._1, JsString(t._2.utf8String)))))
      case map: TrieMap[String, ByteString]                                =>
        ("hash", JsObject(map.toSeq.map(t => (t._1, JsString(t._2.utf8String)))))
      case list: java.util.concurrent.CopyOnWriteArrayList[ByteString]     =>
        ("list", JsArray(list.asScala.toSeq.map(a => JsString(a.utf8String))))
      case list: scala.collection.mutable.MutableList[ByteString]          =>
        ("list", JsArray(list.toSeq.map(a => JsString(a.utf8String))))
      case set: java.util.concurrent.CopyOnWriteArraySet[ByteString]       =>
        ("set", JsArray(set.asScala.toSeq.map(a => JsString(a.utf8String))))
      case set: scala.collection.mutable.HashSet[ByteString]               =>
        ("set", JsArray(set.toSeq.map(a => JsString(a.utf8String))))
      case _                                                               => ("none", JsNull)
    }
  }
}
