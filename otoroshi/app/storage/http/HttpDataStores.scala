package storage.http

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Framing, Keep, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.ByteString
import auth.AuthConfigsDataStore
import cluster._
import com.typesafe.config.ConfigFactory
import env.Env
import events.{AlertDataStore, AuditDataStore, HealthCheckDataStore}
import gateway.{InMemoryRequestsDataStore, RequestsDataStore}
import models._
import otoroshi.script.{InMemoryScriptDataStore, ScriptDataStore}
import otoroshi.tcp.{InMemoryTcpServiceDataStoreDataStore, TcpServiceDataStore}
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.libs.ws.SourceBody
import play.api.{Configuration, Environment, Logger}
import ssl.{CertificateDataStore, ClientCertificateValidationDataStore, InMemoryClientCertificateValidationDataStore}
import storage.inmemory._
import storage.inmemory.concurrent.{Memory, SwappableInMemoryRedis}
import storage.{DataStoreHealth, DataStores, RawDataStore}
import utils.http.Implicits._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

// experimental
class HttpDbDataStores(configuration: Configuration,
                       environment: Environment,
                       lifecycle: ApplicationLifecycle,
                       env: Env)
    extends DataStores {

  private val logger               = Logger("otoroshi-http-db-datastores")
  private val redisStatsItems: Int = configuration.get[Option[Int]]("app.httpdb.windowSize").getOrElse(99)
  private val stateUrl: String =
    configuration.getOptional[String]("app.httpdb.url").getOrElse("http://127.0.0.1:8888/worker-0/state.json")
  private val stateHeaders: Map[String, String] =
    configuration.getOptional[Map[String, String]]("app.httpdb.headers").getOrElse(Map.empty[String, String])
  private val stateTimeout: FiniteDuration =
    configuration.getOptional[Long]("app.httpdb.timeout").map(_.millis).getOrElse(10.seconds)
  private val statePoll: FiniteDuration =
    configuration.getOptional[Long]("app.httpdb.pollEvery").map(_.millis).getOrElse(10.seconds)
  private val actorSystem =
    ActorSystem(
      "otoroshi-http-db-system",
      configuration
        .getOptional[Configuration]("app.actorsystems.datastore")
        .map(_.underlying)
        .getOrElse(ConfigFactory.empty)
    )
  private val materializer = ActorMaterializer.create(actorSystem)
  private val redis        = new SwappableInMemoryRedis(env, actorSystem)
  private val cancelRef    = new AtomicReference[Cancellable]()
  private val lastHash     = new AtomicReference[Int](0)

  override def before(configuration: Configuration,
                      environment: Environment,
                      lifecycle: ApplicationLifecycle): Future[Unit] = {
    implicit val ec  = actorSystem.dispatcher
    implicit val mat = materializer
    logger.info(s"Now using HttpDb DataStores (loading from '$stateUrl')")
    readStateFromHttp().map { _ =>
      cancelRef.set(
        Source
          .tick(1.second, statePoll, ())
          .mapAsync(1)(_ => writeStateToHttp())
          .recover {
            case t => logger.error(s"Error while scheduling writeStateToHttp: $t")
          }
          .toMat(Sink.ignore)(Keep.left)
          .run()
      )
      // cancelRef.set(actorSystem.scheduler.schedule(1.second, statePoll) {
      //   writeStateToHttp()
      // }(actorSystem.dispatcher))
      redis.start()
      _serviceDescriptorDataStore.startCleanup(env)
      _certificateDataStore.startSync()
      ()
    }
  }

  override def after(configuration: Configuration,
                     environment: Environment,
                     lifecycle: ApplicationLifecycle): Future[Unit] = {
    implicit val ec  = actorSystem.dispatcher
    implicit val mat = materializer
    _certificateDataStore.stopSync()
    _serviceDescriptorDataStore.stopCleanup()
    redis.stop()
    cancelRef.get().cancel()
    writeStateToHttp().map { _ =>
      actorSystem.terminate()
      ()
    }
  }

  private def readStateFromHttp(): Future[Unit] = {
    logger.debug("Reading state from http db ...")
    implicit val ec  = actorSystem.dispatcher
    implicit val mat = materializer
    val store        = new ConcurrentHashMap[String, Any]()
    val expirations  = new ConcurrentHashMap[String, Long]()
    val headers = stateHeaders.toSeq ++ Seq(
      "Accept" -> "application/x-ndjson"
    )
    env.Ws
      .url(stateUrl)
      .withRequestTimeout(stateTimeout)
      .withHttpHeaders(headers: _*)
      .withMethod("GET")
      .stream()
      .flatMap {
        case resp if resp.status != 200 =>
          logger.error("Error while reading data with http db, will retry later")
          resp.ignore()
          FastFuture.successful(())
        case resp if resp.status == 200 =>
          val source = resp.bodyAsSource.via(Framing.delimiter(ByteString("\n"), 1000000, false))
          source
            .runForeach { raw =>
              val item  = Json.parse(raw.utf8String)
              val key   = (item \ "k").as[String]
              val value = (item \ "v").as[JsValue]
              val what  = (item \ "w").as[String]
              val ttl   = (item \ "t").asOpt[Long].getOrElse(-1L)
              fromJson(what, value).foreach(v => store.put(key, v))
              if (ttl > -1L) {
                expirations.put(key, ttl)
              }
            }
            .map { _ =>
              redis.swap(Memory(store, expirations))
            }
      }
  }

  private def fromJson(what: String, value: JsValue): Option[Any] = {

    import collection.JavaConverters._

    what match {
      case "string" => Some(ByteString(value.as[String]))
      case "set" => {
        val list = new java.util.concurrent.CopyOnWriteArraySet[ByteString]
        list.addAll(value.as[JsArray].value.map(a => ByteString(a.as[String])).asJava)
        Some(list)
      }
      case "list" => {
        val list = new java.util.concurrent.CopyOnWriteArrayList[ByteString]
        list.addAll(value.as[JsArray].value.map(a => ByteString(a.as[String])).asJava)
        Some(list)
      }
      case "hash" => {
        val map = new java.util.concurrent.ConcurrentHashMap[String, ByteString]
        map.putAll(value.as[JsObject].value.map(t => (t._1, ByteString(t._2.as[String]))).asJava)
        Some(map)
      }
      case _ => None
    }
  }

  private def writeStateToHttp(): Future[Unit] = {
    implicit val ec  = actorSystem.dispatcher
    implicit val mat = materializer
    val source = completeExport(100)(ec, mat, env).map { item =>
      ByteString(Json.stringify(item) + "\n")
    }
    logger.debug("Writing state to http db ...")
    val headers = stateHeaders.toSeq ++ Seq(
      "Content-Type" -> "application/x-ndjson"
    )
    env.Ws
      .url(stateUrl)
      .withRequestTimeout(stateTimeout)
      .withHttpHeaders(headers: _*)
      .withMethod("POST")
      .withBody(SourceBody(source))
      .stream()
      .map {
        case resp if resp.status != 200 =>
          logger.error("Error while syncing data with http db, will retry later")
          resp.ignore()
        case resp if resp.status == 200 =>
          resp.ignore()
      }
  }

  private lazy val _privateAppsUserDataStore   = new InMemoryPrivateAppsUserDataStore(redis, env)
  private lazy val _backOfficeUserDataStore    = new InMemoryBackOfficeUserDataStore(redis, env)
  private lazy val _serviceGroupDataStore      = new InMemoryServiceGroupDataStore(redis, env)
  private lazy val _globalConfigDataStore      = new InMemoryGlobalConfigDataStore(redis, env)
  private lazy val _apiKeyDataStore            = new InMemoryApiKeyDataStore(redis, env)
  private lazy val _serviceDescriptorDataStore = new InMemoryServiceDescriptorDataStore(redis, redisStatsItems, env)
  private lazy val _u2FAdminDataStore          = new InMemoryU2FAdminDataStore(redis)
  private lazy val _simpleAdminDataStore       = new InMemorySimpleAdminDataStore(redis, env)
  private lazy val _alertDataStore             = new InMemoryAlertDataStore(redis)
  private lazy val _auditDataStore             = new InMemoryAuditDataStore(redis)
  private lazy val _healthCheckDataStore       = new InMemoryHealthCheckDataStore(redis, env)
  private lazy val _errorTemplateDataStore     = new InMemoryErrorTemplateDataStore(redis, env)
  private lazy val _requestsDataStore          = new InMemoryRequestsDataStore()
  private lazy val _canaryDataStore            = new InMemoryCanaryDataStore(redis, env)
  private lazy val _chaosDataStore             = new InMemoryChaosDataStore(redis, env)
  private lazy val _jwtVerifDataStore          = new InMemoryGlobalJwtVerifierDataStore(redis, env)
  private lazy val _authConfigsDataStore       = new InMemoryAuthConfigsDataStore(redis, env)
  private lazy val _certificateDataStore       = new InMemoryCertificateDataStore(redis, env)

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

  private lazy val _webAuthnAdminDataStore = new WebAuthnAdminDataStore()
  override def webAuthnAdminDataStore: WebAuthnAdminDataStore = _webAuthnAdminDataStore

  private lazy val _webAuthnRegistrationsDataStore = new WebAuthnRegistrationsDataStore()
  override def webAuthnRegistrationsDataStore: WebAuthnRegistrationsDataStore = _webAuthnRegistrationsDataStore

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
  override def chaosDataStore: ChaosDataStore                                   = _chaosDataStore
  override def globalJwtVerifierDataStore: GlobalJwtVerifierDataStore           = _jwtVerifDataStore
  override def authConfigsDataStore: AuthConfigsDataStore                       = _authConfigsDataStore
  override def certificatesDataStore: CertificateDataStore                      = _certificateDataStore
  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = redis.health()(ec)
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
                redis.rawGet(key).flatMap {
                  case None => FastFuture.successful(JsNull)
                  case Some(value) => {
                    toJson(value) match {
                      case (_, JsNull) => FastFuture.successful(JsNull)
                      case (what, jsonValue) =>
                        redis.pttl(key).map { ttl =>
                          Json.obj("k" -> key,
                                   "v" -> jsonValue,
                                   "t" -> (if (ttl == -1) -1 else (System.currentTimeMillis() + ttl)),
                                   "w" -> what)
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

  def completeExport(
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
              .map { key =>
                redis.rawGet(key).flatMap {
                  case None => FastFuture.successful(JsNull)
                  case Some(value) => {
                    toJson(value) match {
                      case (_, JsNull) => FastFuture.successful(JsNull)
                      case (what, jsonValue) =>
                        redis.pttl(key).map { ttl =>
                          Json.obj("k" -> key,
                                   "v" -> jsonValue,
                                   "t" -> (if (ttl == -1) -1 else (System.currentTimeMillis() + ttl)),
                                   "w" -> what)
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

  private def toJson(value: Any): (String, JsValue) = {

    import collection.JavaConverters._

    value match {
      case str: String     => ("string", JsString(str))
      case str: ByteString => ("string", JsString(str.utf8String))
      case lng: Long       => ("string", JsString(lng.toString))
      case map: java.util.concurrent.ConcurrentHashMap[String, ByteString] =>
        ("hash", JsObject(map.asScala.toSeq.map(t => (t._1, JsString(t._2.utf8String)))))
      case list: java.util.concurrent.CopyOnWriteArrayList[ByteString] =>
        ("list", JsArray(list.asScala.toSeq.map(a => JsString(a.utf8String))))
      case set: java.util.concurrent.CopyOnWriteArraySet[ByteString] =>
        ("set", JsArray(set.asScala.toSeq.map(a => JsString(a.utf8String))))
      case _ => ("none", JsNull)
    }
  }
}
