package otoroshi.storage.drivers.file

import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import auth.AuthConfigsDataStore
import cluster._
import com.google.common.base.Charsets
import com.typesafe.config.ConfigFactory
import env.Env
import events.{AlertDataStore, AuditDataStore, HealthCheckDataStore}
import gateway.{InMemoryRequestsDataStore, RequestsDataStore}
import models._
import otoroshi.script.{InMemoryScriptDataStore, ScriptDataStore}
import otoroshi.storage.drivers.cluster.{Memory, SwappableInMemoryRedis}
import otoroshi.storage.stores._
import otoroshi.storage.{DataStoreHealth, DataStores, RawDataStore}
import otoroshi.tcp.{InMemoryTcpServiceDataStoreDataStore, TcpServiceDataStore}
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.{Configuration, Environment, Logger}
import ssl.{CertificateDataStore, ClientCertificateValidationDataStore, InMemoryClientCertificateValidationDataStore}
import storage.stores.InMemoryRawDataStore

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.hashing.MurmurHash3

class FileDbDataStores(configuration: Configuration,
                       environment: Environment,
                       lifecycle: ApplicationLifecycle,
                       env: Env)
    extends DataStores {

  private val logger               = Logger("otoroshi-file-db-datastores")
  private val dbPath: String       = configuration.getOptional[String]("app.filedb.path").getOrElse("./filedb/state.ndjson")
  private val redisStatsItems: Int = configuration.get[Option[Int]]("app.filedb.windowSize").getOrElse(99)
  private val actorSystem =
    ActorSystem(
      "otoroshi-file-db-system",
      configuration
        .getOptional[Configuration]("app.actorsystems.datastore")
        .map(_.underlying)
        .getOrElse(ConfigFactory.empty)
    )
  private val materializer = Materializer(actorSystem)
  private val redis        = new SwappableInMemoryRedis(env, actorSystem)
  private val cancelRef    = new AtomicReference[Cancellable]()
  private val lastHash     = new AtomicReference[Int](0)

  override def before(configuration: Configuration,
                      environment: Environment,
                      lifecycle: ApplicationLifecycle): Future[Unit] = {
    import collection.JavaConverters._
    logger.info(s"Now using FileDb DataStores (loading '$dbPath')")
    val file = new File(dbPath)
    if (!file.exists()) {
      logger.info(s"Creating FileDb file and directory ('$dbPath')")
      file.getParentFile.mkdirs()
      file.createNewFile()
    }
    readStateFromDisk(Files.readAllLines(file.toPath).asScala.toSeq)
    cancelRef.set(actorSystem.scheduler.scheduleAtFixedRate(1.second, 5.seconds)(utils.SchedulerHelper.runnable {
      Await.result(writeStateToDisk()(actorSystem.dispatcher, materializer), 10.seconds)
    })(actorSystem.dispatcher))
    redis.start()
    _serviceDescriptorDataStore.startCleanup(env)
    _certificateDataStore.startSync()
    FastFuture.successful(())
  }

  override def after(configuration: Configuration,
                     environment: Environment,
                     lifecycle: ApplicationLifecycle): Future[Unit] = {
    _certificateDataStore.stopSync()
    _serviceDescriptorDataStore.stopCleanup()
    redis.stop()
    cancelRef.get().cancel()
    Await.result(writeStateToDisk()(actorSystem.dispatcher, materializer), 10.seconds)
    actorSystem.terminate()
    FastFuture.successful(())
  }

  private def readStateFromDisk(source: Seq[String]): Unit = {
    logger.debug("Reading state from disk ...")
    val store       = new ConcurrentHashMap[String, Any]()
    val expirations = new ConcurrentHashMap[String, Long]()
    source.foreach { raw =>
      val item  = Json.parse(raw)
      val key   = (item \ "k").as[String]
      val value = (item \ "v").as[JsValue]
      val what  = (item \ "w").as[String]
      val ttl   = (item \ "t").asOpt[Long].getOrElse(-1L)
      fromJson(what, value).foreach(v => store.put(key, v))
      if (ttl > -1L) {
        expirations.put(key, ttl)
      }
    }
    redis.swap(Memory(store, expirations))
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

  private def writeStateToDisk()(implicit ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    val file = new File(dbPath)
    completeExport(100)(ec, mat, env)
      .map { item =>
        Json.stringify(item) + "\n"
      }
      .runFold("")(_ + _)
      .map { content =>
        val hash = MurmurHash3.stringHash(content)
        if (hash != lastHash.get()) {
          logger.debug("Writing state to disk ...")
          Files.write(file.toPath, content.getBytes(Charsets.UTF_8))
          lastHash.set(hash)
        }
      }
  }

  private lazy val _privateAppsUserDataStore   = new InMemoryPrivateAppsUserDataStore(redis, env)
  private lazy val _backOfficeUserDataStore    = new InMemoryBackOfficeUserDataStore(redis, env)
  private lazy val _serviceGroupDataStore      = new InMemoryServiceGroupDataStore(redis, env)
  private lazy val _globalConfigDataStore      = new InMemoryGlobalConfigDataStore(redis, env)
  private lazy val _apiKeyDataStore            = new InMemoryApiKeyDataStore(redis, env)
  private lazy val _serviceDescriptorDataStore = new InMemoryServiceDescriptorDataStore(redis, redisStatsItems, env)
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
                  case Some(value) => {
                    toJson(value) match {
                      case (_, JsNull) => FastFuture.successful(JsNull)
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

  def completeExport(
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
