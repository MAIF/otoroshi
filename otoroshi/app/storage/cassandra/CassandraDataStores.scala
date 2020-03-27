package storage.cassandra

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import auth.AuthConfigsDataStore
import cluster.{ClusterStateDataStore, InMemoryClusterStateDataStore}
import com.typesafe.config.ConfigFactory
import env.Env
import events.{AlertDataStore, AuditDataStore, HealthCheckDataStore}
import gateway.{InMemoryRequestsDataStore, RequestsDataStore}
import models._
import otoroshi.script.{InMemoryScriptDataStore, ScriptDataStore}
import otoroshi.tcp.{InMemoryTcpServiceDataStoreDataStore, TcpServiceDataStore}
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.{Configuration, Environment, Logger}
import ssl.{CertificateDataStore, ClientCertificateValidationDataStore, InMemoryClientCertificateValidationDataStore}
import storage.inmemory._
import storage.{DataStoreHealth, DataStores, RawDataStore, RedisLike}

import scala.concurrent.{ExecutionContext, Future}

class CassandraDataStores(naive: Boolean,
                          configuration: Configuration,
                          environment: Environment,
                          lifecycle: ApplicationLifecycle,
                          env: Env)
    extends DataStores {

  lazy val logger = Logger("otoroshi-cassandra-datastores")

  lazy val redisStatsItems: Int = configuration.getOptional[Int]("app.cassandra.windowSize").getOrElse(99)

  lazy val actorSystem =
    ActorSystem(
      "otoroshi-cassandra-system",
      configuration
        .getOptional[Configuration]("app.actorsystems.datastore")
        .map(_.underlying)
        .getOrElse(ConfigFactory.empty)
    )

  lazy val mat = Materializer(actorSystem)

  lazy val redis: RedisLike with RawGetRedis = new NewCassandraRedis(
    actorSystem,
    configuration
  )(actorSystem.dispatcher, mat, env)

  override def before(configuration: Configuration,
                      environment: Environment,
                      lifecycle: ApplicationLifecycle): Future[Unit] = {
    logger.info("Now using Cassandra DataStores")
    redis.start()
    _serviceDescriptorDataStore.startCleanup(env)
    _certificateDataStore.startSync()
    FastFuture.successful(())
  }

  override def after(configuration: Configuration,
                     environment: Environment,
                     lifecycle: ApplicationLifecycle): Future[Unit] = {
    _serviceDescriptorDataStore.stopCleanup()
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

  private lazy val _clientCertificateValidationDataStore = new InMemoryClientCertificateValidationDataStore(redis, env)
  override def clientCertificateValidationDataStore: ClientCertificateValidationDataStore =
    _clientCertificateValidationDataStore

  private lazy val _scriptDataStore             = new InMemoryScriptDataStore(redis, env)
  override def scriptDataStore: ScriptDataStore = _scriptDataStore

  private lazy val _tcpServiceDataStore                 = new InMemoryTcpServiceDataStoreDataStore(redis, env)
  override def tcpServiceDataStore: TcpServiceDataStore = _tcpServiceDataStore

  private lazy val _rawDataStore          = new InMemoryRawDataStore(redis)
  override def rawDataStore: RawDataStore = _rawDataStore

  private lazy val _webAuthnAdminDataStore                    = new WebAuthnAdminDataStore()
  override def webAuthnAdminDataStore: WebAuthnAdminDataStore = _webAuthnAdminDataStore

  private lazy val _webAuthnRegistrationsDataStore                            = new WebAuthnRegistrationsDataStore()
  override def webAuthnRegistrationsDataStore: WebAuthnRegistrationsDataStore = _webAuthnRegistrationsDataStore

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
  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = redis.health()(ec)
  override def chaosDataStore: ChaosDataStore                                   = _chaosDataStore
  override def globalJwtVerifierDataStore: GlobalJwtVerifierDataStore           = _jwtVerifDataStore
  override def certificatesDataStore: CertificateDataStore                      = _certificateDataStore
  override def authConfigsDataStore: AuthConfigsDataStore                       = _globalOAuth2ConfigDataStore
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
                redis.rawGet(key).flatMap {
                  case None => FastFuture.successful(JsNull)
                  case Some((typ, ttl, value)) => {
                    fetchValueForType(key, typ, value).map {
                      case JsNull => JsNull
                      case value =>
                        Json.obj(
                          "k" -> key,
                          "v" -> value,
                          "t" -> (if (ttl == -1) -1 else (System.currentTimeMillis() + ttl)),
                          "w" -> typ
                        )

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

  override def fullNdJsonExport(): Future[Source[JsValue, _]] = {

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
                redis.rawGet(key).flatMap {
                  case None => FastFuture.successful(JsNull)
                  case Some((typ, ttl, value)) => {
                    fetchValueForType(key, typ, value).map {
                      case JsNull => JsNull
                      case value =>
                        Json.obj(
                          "k" -> key,
                          "v" -> value,
                          "t" -> (if (ttl == -1) -1 else (System.currentTimeMillis() + ttl)),
                          "w" -> typ
                        )

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

  private def fetchValueForType(key: String, typ: String, value: Any)(
      implicit ec: ExecutionContext
  ): Future[JsValue] = {
    (typ, value) match {
      case ("hash", v: Map[String, ByteString]) =>
        FastFuture.successful(JsObject(v.map(t => (t._1, JsString(t._2.utf8String)))))
      case ("list", v: Seq[ByteString]) => FastFuture.successful(JsArray(v.map(s => JsString(s.utf8String))))
      case ("set", v: Set[ByteString])  => FastFuture.successful(JsArray(v.toSeq.map(s => JsString(s.utf8String))))
      case ("string", v: ByteString) =>
        Option(v) match {
          case None    => FastFuture.successful(JsNull)
          case Some(a) => FastFuture.successful(JsString(a.utf8String))
        }
      case _ => FastFuture.successful(JsNull)
    }
  }
}
