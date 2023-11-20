package storage.drivers.generic

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.typesafe.config.ConfigFactory
import otoroshi.auth.AuthConfigsDataStore
import otoroshi.cluster.{Cluster, ClusterMode, ClusterStateDataStore, KvClusterStateDataStore}
import otoroshi.env.Env
import otoroshi.events.{AlertDataStore, AuditDataStore, HealthCheckDataStore}
import otoroshi.gateway.{InMemoryRequestsDataStore, RequestsDataStore}
import otoroshi.models._
import otoroshi.next.models._
import otoroshi.script.{KvScriptDataStore, ScriptDataStore}
import otoroshi.ssl.{CertificateDataStore, ClientCertificateValidationDataStore, KvClientCertificateValidationDataStore}
import otoroshi.storage.stores._
import otoroshi.storage.{DataStoreHealth, DataStores, RawDataStore, RedisLike}
import otoroshi.tcp.{KvTcpServiceDataStoreDataStore, TcpServiceDataStore}
import otoroshi.utils.syntax.implicits._
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.{Configuration, Environment, Logger}

import scala.concurrent.{ExecutionContext, Future}

trait GenericRedisLike extends RedisLike {
  def setCounter(key: String, value: Long): Future[Unit]
  def rawGet(key: String): Future[Option[Any]]
  def typ(key: String): Future[String]
}

trait GenericRedisLikeBuilder {
  def build(
    configuration: Configuration,
    environment: Environment,
    lifecycle: ApplicationLifecycle,
    clusterMode: ClusterMode,
    redisStatsItems: Int,
    actorSystem: ActorSystem,
    mat: Materializer,
    logger: Logger,
    env: Env
  ): GenericRedisLike
}

class GenericDataStores(
  configuration: Configuration,
  environment: Environment,
  lifecycle: ApplicationLifecycle,
  clusterMode: ClusterMode,
  redisStatsItems: Int,
  builder: GenericRedisLikeBuilder,
  env: Env
) extends DataStores {

  lazy val logger = Logger("otoroshi-generic-datastores")

  lazy val actorSystem =
    ActorSystem(
      "otoroshi-generic-system",
      configuration
        .getOptionalWithFileSupport[Configuration]("app.actorsystems.datastore")
        .map(_.underlying)
        .getOrElse(ConfigFactory.empty)
    )

  lazy val mat = Materializer(actorSystem)

  lazy val redis: GenericRedisLike = builder.build(configuration, environment, lifecycle, clusterMode, redisStatsItems, actorSystem, mat, logger, env)

  override def before(
                       configuration: Configuration,
                       environment: Environment,
                       lifecycle: ApplicationLifecycle
                     ): Future[Unit] = {
    redis.start()
    _serviceDescriptorDataStore.startCleanup(env)
    _certificateDataStore.startSync()
    FastFuture.successful(())
  }

  override def after(
                      configuration: Configuration,
                      environment: Environment,
                      lifecycle: ApplicationLifecycle
                    ): Future[Unit] = {
    _serviceDescriptorDataStore.stopCleanup()
    _certificateDataStore.stopSync()
    redis.stop()
    actorSystem.terminate()
    FastFuture.successful(())
  }

  private lazy val _privateAppsUserDataStore = new KvPrivateAppsUserDataStore(redis, env)
  private lazy val _backOfficeUserDataStore = new KvBackOfficeUserDataStore(redis, env)
  private lazy val _serviceGroupDataStore = new KvServiceGroupDataStore(redis, env)
  private lazy val _globalConfigDataStore = new KvGlobalConfigDataStore(redis, env)
  private lazy val _apiKeyDataStore = new KvApiKeyDataStore(redis, env)
  private lazy val _serviceDescriptorDataStore = new KvServiceDescriptorDataStore(redis, redisStatsItems, env)
  private lazy val _simpleAdminDataStore = new KvSimpleAdminDataStore(redis, env)
  private lazy val _alertDataStore = new KvAlertDataStore(redis)
  private lazy val _auditDataStore = new KvAuditDataStore(redis)
  private lazy val _healthCheckDataStore = new KvHealthCheckDataStore(redis, env)
  private lazy val _errorTemplateDataStore = new KvErrorTemplateDataStore(redis, env)
  private lazy val _requestsDataStore = new InMemoryRequestsDataStore()
  private lazy val _canaryDataStore = new KvCanaryDataStore(redis, env)
  private lazy val _chaosDataStore = new KvChaosDataStore(redis, env)
  private lazy val _jwtVerifDataStore = new KvGlobalJwtVerifierDataStore(redis, env)
  private lazy val _globalOAuth2ConfigDataStore = new KvAuthConfigsDataStore(redis, env)
  private lazy val _certificateDataStore = new KvCertificateDataStore(redis, env)

  private lazy val _clusterStateDataStore = new KvClusterStateDataStore(redis, env)

  override def clusterStateDataStore: ClusterStateDataStore = _clusterStateDataStore

  private lazy val _clientCertificateValidationDataStore = new KvClientCertificateValidationDataStore(redis, env)

  override def clientCertificateValidationDataStore: ClientCertificateValidationDataStore =
    _clientCertificateValidationDataStore

  private lazy val _scriptDataStore = new KvScriptDataStore(redis, env)

  override def scriptDataStore: ScriptDataStore = _scriptDataStore

  private lazy val _tcpServiceDataStore = new KvTcpServiceDataStoreDataStore(redis, env)

  override def tcpServiceDataStore: TcpServiceDataStore = _tcpServiceDataStore

  private lazy val _rawDataStore = new KvRawDataStore(redis)

  override def rawDataStore: RawDataStore = _rawDataStore

  private lazy val _webAuthnAdminDataStore = new KvWebAuthnAdminDataStore()

  override def webAuthnAdminDataStore: WebAuthnAdminDataStore = _webAuthnAdminDataStore

  private lazy val _webAuthnRegistrationsDataStore = new WebAuthnRegistrationsDataStore()

  override def webAuthnRegistrationsDataStore: WebAuthnRegistrationsDataStore = _webAuthnRegistrationsDataStore

  private lazy val _tenantDataStore = new TenantDataStore(redis, env)

  override def tenantDataStore: TenantDataStore = _tenantDataStore

  private lazy val _teamDataStore = new TeamDataStore(redis, env)

  override def teamDataStore: TeamDataStore = _teamDataStore

  private lazy val _dataExporterConfigDataStore = new DataExporterConfigDataStore(redis, env)

  override def dataExporterConfigDataStore: DataExporterConfigDataStore = _dataExporterConfigDataStore

  private lazy val _routeDataStore = new KvNgRouteDataStore(redis, env)

  override def routeDataStore: NgRouteDataStore = _routeDataStore

  private lazy val _routesCompositionDataStore = new KvNgRouteCompositionDataStore(redis, env)

  override def routeCompositionDataStore: NgRouteCompositionDataStore = _routesCompositionDataStore

  private lazy val _backendsDataStore = new KvStoredNgBackendDataStore(redis, env)

  override def backendsDataStore: StoredNgBackendDataStore = _backendsDataStore

  private lazy val _wasmPluginDataStore = new KvWasmPluginDataStore(redis, env)

  override def wasmPluginsDataStore: WasmPluginDataStore = _wasmPluginDataStore

  private lazy val _adminPreferencesDatastore = new AdminPreferencesDatastore(env)

  def adminPreferencesDatastore: AdminPreferencesDatastore = _adminPreferencesDatastore

  override def privateAppsUserDataStore: PrivateAppsUserDataStore = _privateAppsUserDataStore

  override def backOfficeUserDataStore: BackOfficeUserDataStore = _backOfficeUserDataStore

  override def serviceGroupDataStore: ServiceGroupDataStore = _serviceGroupDataStore

  override def globalConfigDataStore: GlobalConfigDataStore = _globalConfigDataStore

  override def apiKeyDataStore: ApiKeyDataStore = _apiKeyDataStore

  override def serviceDescriptorDataStore: ServiceDescriptorDataStore = _serviceDescriptorDataStore

  override def simpleAdminDataStore: SimpleAdminDataStore = _simpleAdminDataStore

  override def alertDataStore: AlertDataStore = _alertDataStore

  override def auditDataStore: AuditDataStore = _auditDataStore

  override def healthCheckDataStore: HealthCheckDataStore = _healthCheckDataStore

  override def errorTemplateDataStore: ErrorTemplateDataStore = _errorTemplateDataStore

  override def requestsDataStore: RequestsDataStore = _requestsDataStore

  override def canaryDataStore: CanaryDataStore = _canaryDataStore

  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = redis.health()(ec)

  override def chaosDataStore: ChaosDataStore = _chaosDataStore

  override def globalJwtVerifierDataStore: GlobalJwtVerifierDataStore = _jwtVerifDataStore

  override def certificatesDataStore: CertificateDataStore = _certificateDataStore

  override def authConfigsDataStore: AuthConfigsDataStore = _globalOAuth2ConfigDataStore

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
              .filterNot(key => Cluster.filteredKey(key, env))
              .map { key =>
                for {
                  w <- redis.typ(key)
                  ttl <- redis.pttl(key)
                  value <- fetchValueForType(w, key)
                } yield value match {
                  case JsNull => JsNull
                  case _ =>
                    Json.obj(
                      "k" -> key,
                      "v" -> value,
                      "t" -> (if (ttl == -1) -1 else (System.currentTimeMillis() + ttl)),
                      "w" -> w
                    )
                }
              }
          )
        }
      }
      .map(_.filterNot(_ == JsNull))
      .mapConcat(_.toList)
  }

  override def fullNdJsonExport(group: Int, groupWorkers: Int, keyWorkers: Int): Future[Source[JsValue, _]] = {

    implicit val ev = env
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
                  w <- redis.typ(key)
                  ttl <- redis.pttl(key)
                  value <- fetchValueForType(w, key)
                } yield value match {
                  case JsNull => JsNull
                  case _ =>
                    Json.obj(
                      "k" -> key,
                      "v" -> value,
                      "t" -> (if (ttl == -1) -1 else (System.currentTimeMillis() + ttl)),
                      "w" -> w
                    )
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

    implicit val ev = env
    implicit val ecc = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer

    redis
      .keys(s"${env.storageRoot}:*")
      .flatMap(keys => if (keys.nonEmpty) redis.del(keys: _*) else FastFuture.successful(0L))
      .flatMap { _ =>
        exportSource
          .mapAsync(1) { json =>
            val key = (json \ "k").as[String]
            val value = (json \ "v").as[JsValue]
            val pttl = (json \ "t").as[Long]
            val what = (json \ "w").as[String]
            (what match {
              case "counter" => redis.set(key, value.as[Long].toString)
              case "string" => redis.set(key, value.as[String])
              case "hash" =>
                Source(value.as[JsObject].value.toList)
                  .mapAsync(1)(v => redis.hset(key, v._1, Json.stringify(v._2)))
                  .runWith(Sink.ignore)
              case "list" => redis.lpush(key, value.as[JsArray].value.map(Json.stringify): _*)
              case "set" => redis.sadd(key, value.as[JsArray].value.map(Json.stringify): _*)
              case _ => FastFuture.successful(0L)
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
      case "set" => redis.smembers(key).map(l => JsArray(l.map(s => JsString(s.utf8String))))
      case "string" =>
        redis.get(key).map {
          case None => JsNull
          case Some(a) => JsString(a.utf8String)
        }
      case _ => FastFuture.successful(JsNull)
    }
  }
}
