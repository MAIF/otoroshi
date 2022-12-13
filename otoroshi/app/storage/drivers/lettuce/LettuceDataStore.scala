package otoroshi.storage.drivers.lettuce

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.masterreplica.{MasterReplica, StatefulRedisMasterReplicaConnection}
import io.lettuce.core.resource.{ClientResources, DefaultClientResources}
import io.lettuce.core.{AbstractRedisClient, ReadFrom, RedisClient, RedisURI}
import otoroshi.auth.AuthConfigsDataStore
import otoroshi.cluster.{Cluster, ClusterStateDataStore, KvClusterStateDataStore}
import otoroshi.env.Env
import otoroshi.events.{AlertDataStore, AuditDataStore, HealthCheckDataStore}
import otoroshi.gateway.{InMemoryRequestsDataStore, RequestsDataStore}
import otoroshi.models._
import otoroshi.next.models._
import otoroshi.script.{KvScriptDataStore, ScriptDataStore}
import otoroshi.ssl.{CertificateDataStore, ClientCertificateValidationDataStore, KvClientCertificateValidationDataStore}
import otoroshi.storage._
import otoroshi.storage.stores._
import otoroshi.tcp.{KvTcpServiceDataStoreDataStore, TcpServiceDataStore}
import otoroshi.utils.syntax.implicits._
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.{Configuration, Environment, Logger}

import java.util.concurrent.atomic.AtomicReference
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class LettuceDataStores(
    configuration: Configuration,
    environment: Environment,
    lifecycle: ApplicationLifecycle,
    env: Env
) extends DataStores {

  lazy val logger = Logger("otoroshi-redis-lettuce-datastores")

  lazy val redisStatsItems: Int = configuration.getOptionalWithFileSupport[Int]("app.redis.windowSize").getOrElse(99)

  lazy val redisActorSystem =
    ActorSystem(
      "otoroshi-redis-lettuce-system",
      configuration
        .getOptionalWithFileSupport[Configuration]("app.actorsystems.datastore")
        .map(_.underlying)
        .getOrElse(ConfigFactory.empty)
    )

  val clientRef     = new AtomicReference[AbstractRedisClient]()
  val connectionRef = new AtomicReference[StatefulRedisMasterReplicaConnection[String, ByteString]]()

  lazy val redisDispatcher = redisActorSystem.dispatcher

  lazy val redisConnection        =
    configuration.getOptionalWithFileSupport[String]("app.redis.lettuce.connection").getOrElse("default")
  lazy val redisReadFrom          =
    configuration.getOptionalWithFileSupport[String]("app.redis.lettuce.readFrom").getOrElse("MASTER_PREFERRED")
  lazy val readFrom               = ReadFrom.valueOf(redisReadFrom)
  lazy val redisUris: Seq[String] = configuration
    .getOptionalWithFileSupport[Seq[String]]("app.redis.lettuce.uris")
    .filter(_.nonEmpty)
    .map(_.map(_.trim))
    .orElse(
      configuration.getOptionalWithFileSupport[String]("app.redis.lettuce.urisStr").map(_.split(",").map(_.trim).toSeq)
    )
    .filter(_.nonEmpty)
    .orElse(
      configuration.getOptionalWithFileSupport[String]("app.redis.lettuce.uri").map(v => Seq(v.trim))
    )
    .getOrElse(Seq.empty[String])
  lazy val startTLS               = configuration.getOptionalWithFileSupport[Boolean]("app.redis.lettuce.startTLS").getOrElse(false)
  lazy val verifyPeers            =
    configuration.getOptionalWithFileSupport[Boolean]("app.redis.lettuce.verifyPeers").getOrElse(true)
  lazy val nodesRaw               = redisUris.map { v =>
    val uri = RedisURI.create(v)
    uri.setStartTls(startTLS)
    uri.setVerifyPeer(verifyPeers)
    uri
  }
  lazy val nodes                  = nodesRaw.asJava
  lazy val resources = {
    val default                   = DefaultClientResources.builder().build()
    val computationThreadPoolSize = configuration
      .getOptionalWithFileSupport[Int]("app.redis.lettuce.computationThreadPoolSize")
      .getOrElse(default.computationThreadPoolSize())
    val ioThreadPoolSize          =
      configuration
        .getOptionalWithFileSupport[Int]("app.redis.lettuce.ioThreadPoolSize")
        .getOrElse(default.ioThreadPoolSize())
    ClientResources
      .builder()
      .computationThreadPoolSize(computationThreadPoolSize)
      .ioThreadPoolSize(ioThreadPoolSize)
      .build()
  }

  lazy val redis: LettuceRedis = {

    def standardConnection() = {
      val client = RedisClient.create(resources, nodesRaw.head)
      clientRef.set(client)
      new LettuceRedisStandaloneAndSentinels(redisActorSystem, client)
    }

    redisConnection match {
      case _ if redisUris.isEmpty           => throw new RuntimeException(s"No redis URIs to connect with ...")
      case "default" if redisUris.size == 1 => standardConnection()
      case "standalone"                     => standardConnection()
      case "sentinels"                      => standardConnection()
      case "default" if redisUris.size > 1  => {
        val redisClient = RedisClient.create(resources)
        val connection  = MasterReplica.connect(redisClient, new ByteStringRedisCodec(), nodes)
        connection.setReadFrom(readFrom)
        clientRef.set(redisClient)
        connectionRef.set(connection)
        new LettuceRedisStandaloneAndSentinels(redisActorSystem, redisClient)
      }
      case "master-replicas"                => {
        val redisClient = RedisClient.create(resources)
        val connection  = MasterReplica.connect(redisClient, new ByteStringRedisCodec(), nodes)
        connection.setReadFrom(readFrom)
        clientRef.set(redisClient)
        connectionRef.set(connection)
        new LettuceRedisStandaloneAndSentinels(redisActorSystem, redisClient)
      }
      case "cluster"                        => {
        // docker run -p '7000-7050:7000-7050' -e "IP=0.0.0.0" grokzen/redis-cluster:latest
        // -Dapp.redis.lettuce.connection=cluster -Dapp.redis.lettuce.uris.0=redis://localhost:7000/0 -Dapp.redis.lettuce.uris.1=redis://localhost:7001/0 -Dapp.redis.lettuce.uris.2=redis://localhost:7002/0
        val redisClient = RedisClusterClient.create(resources, nodes)
        clientRef.set(redisClient)
        new LettuceRedisCluster(redisActorSystem, redisClient)
      }
      case _                                => throw new RuntimeException(s"Bad redis connection type '$redisConnection'")
    }
  }

  override def before(
      configuration: Configuration,
      environment: Environment,
      lifecycle: ApplicationLifecycle
  ): Future[Unit] = {
    logger.info("Now using Redis (lettuce) DataStores")
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
    Option(connectionRef.get()).foreach(_.close())
    Option(clientRef.get()).foreach(_.shutdown())
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
    redis.info().map(_ => Healthy).recover { case _ =>
      Unreachable
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
                for {
                  w     <- redis.typ(key)
                  ttl   <- redis.pttl(key)
                  value <- fetchValueForType(w, key)
                } yield value match {
                  case JsNull => JsNull
                  case _      =>
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
                for {
                  w     <- redis.typ(key)
                  ttl   <- redis.pttl(key)
                  value <- fetchValueForType(w, key)
                } yield value match {
                  case JsNull => JsNull
                  case _      =>
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

  private def fetchValueForType(typ: String, key: String)(implicit ec: ExecutionContext): Future[JsValue] = {
    typ match {
      case "hash"   => redis.hgetall(key).map(m => JsObject(m.map(t => (t._1, JsString(t._2.utf8String)))))
      case "list"   => redis.lrange(key, 0, Long.MaxValue).map(l => JsArray(l.map(s => JsString(s.utf8String))))
      case "set"    => redis.smembers(key).map(l => JsArray(l.map(s => JsString(s.utf8String))))
      case "string" =>
        redis.get(key).map {
          case None    => JsNull
          case Some(a) => JsString(a.utf8String)
        }
      case _        => FastFuture.successful(JsNull)
    }
  }
}
