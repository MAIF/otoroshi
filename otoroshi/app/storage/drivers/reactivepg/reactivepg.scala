package otoroshi.storage.drivers.reactivepg

import java.util.concurrent.atomic.AtomicReference

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import auth.AuthConfigsDataStore
import cluster.{ClusterStateDataStore, KvClusterStateDataStore}
import com.typesafe.config.ConfigFactory
import env.Env
import events.{AlertDataStore, AuditDataStore, HealthCheckDataStore}
import gateway.{InMemoryRequestsDataStore, RequestsDataStore}
import io.vertx.pgclient.{PgConnectOptions, PgPool}
import io.vertx.sqlclient.{PoolOptions, Row}
import models._
import otoroshi.models.{SimpleAdminDataStore, WebAuthnAdminDataStore}
import otoroshi.script.{KvScriptDataStore, ScriptDataStore}
import otoroshi.storage.stores._
import otoroshi.storage.{RedisLike, _}
import otoroshi.tcp.{KvTcpServiceDataStoreDataStore, TcpServiceDataStore}
import otoroshi.utils.syntax.implicits._
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.{Configuration, Environment, Logger}
import ssl.{CertificateDataStore, ClientCertificateValidationDataStore, KvClientCertificateValidationDataStore}
import storage.stores.{DataExporterConfigDataStore, KvRawDataStore, TeamDataStore, TenantDataStore}
import utils.SchedulerHelper

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object pgimplicits {
  implicit class VertxFutureEnhancer[A](val future: io.vertx.core.Future[A]) extends AnyVal {
    def scala: Future[A] = {
      val promise = Promise.apply[A]
      future.onSuccess(a => promise.trySuccess(a))
      future.onFailure(e => promise.tryFailure(e))
      promise.future
    }
  }

  implicit class VertxQueryEnhancer[A](val query: io.vertx.sqlclient.Query[A]) extends AnyVal {
    def executeAsync(): Future[A] = {
      val promise = Promise.apply[A]
      val future = query.execute()
      future.onSuccess(a => promise.trySuccess(a))
      future.onFailure(e => promise.tryFailure(e))
      promise.future
    }
  }

  implicit class VertxPreparedQueryEnhancer[A](val query: io.vertx.sqlclient.PreparedQuery[A]) extends AnyVal {
    def executeAsync(): Future[A] = {
      val promise = Promise.apply[A]
      val future = query.execute()
      future.onSuccess(a => promise.trySuccess(a))
      future.onFailure(e => promise.tryFailure(e))
      promise.future
    }
  }
}

class ReactivePgDataStores(configuration: Configuration,
                        environment: Environment,
                        lifecycle: ApplicationLifecycle,
                        env: Env)
  extends DataStores {

  import pgimplicits._

  private val logger = Logger("otoroshi-reactive-pg-datastores")

  private lazy val reactivePgStatsItems: Int = configuration.getOptionalWithFileSupport[Int]("app.redis.windowSize").getOrElse(99)

  private lazy val reactivePgActorSystem =
    ActorSystem(
      "otoroshi-reactive-pg-system",
      configuration
        .getOptionalWithFileSupport[Configuration]("app.actorsystems.datastore")
        .map(_.underlying)
        .getOrElse(ConfigFactory.empty)
    )

  private lazy val connectOptions = new PgConnectOptions()
    .setPort(5432)
    .setHost("localhost")
    .setDatabase("otoroshi")
    .setUser("otoroshi")
    .setPassword("otoroshi")

  private lazy val poolOptions = new PoolOptions()
    .setMaxSize(10)

  private lazy val client = PgPool.pool(connectOptions, poolOptions)

  private lazy val redis = new ReactivePgRedis(client, reactivePgActorSystem, env)

  private val cancel = new AtomicReference[Cancellable]()

  def runSchemaCreation(): Unit = {
    implicit val ec = reactivePgActorSystem.dispatcher
    logger.info("Running database migrations ...")
    Await.result((for {
      _ <- client.query("CREATE SCHEMA IF NOT EXISTS otoroshi;").executeAsync()
      _ <- client.query(s"""
           |create table if not exists otoroshi.entities (
           |  key text not null,
           |  type text not null,
           |  ttl_starting_at TIMESTAMPTZ not null,
           |  ttl interval default '1000 years'::interval,
           |  counter bigint default 0,
           |  value text,
           |  lvalue jsonb default '[]'::jsonb,
           |  svalue jsonb default '{}'::jsonb,
           |  mvalue jsonb default '{}'::jsonb,
           |  PRIMARY KEY (key)
           |);
           |""".stripMargin).executeAsync()
    } yield ()), 5.minutes)
  }

  def setupCleanup(): Unit = {
    implicit val ec = reactivePgActorSystem.dispatcher
    cancel.set(reactivePgActorSystem.scheduler.scheduleAtFixedRate(1.minute, 5.minutes)(SchedulerHelper.runnable {
      // client.query("DELETE FROM otoroshi.entities WHERE (ttl_starting_at + ttl) < NOW();").executeAsync()
    }))
  }

  override def before(configuration: Configuration,
                      environment: Environment,
                      lifecycle: ApplicationLifecycle): Future[Unit] = {
    logger.info("Now using PostgreSQL (reactive-pg) DataStores")
    runSchemaCreation()
    setupCleanup()
    _serviceDescriptorDataStore.startCleanup(env)
    _certificateDataStore.startSync()
    FastFuture.successful(())
  }

  override def after(configuration: Configuration,
                     environment: Environment,
                     lifecycle: ApplicationLifecycle): Future[Unit] = {
    _serviceDescriptorDataStore.stopCleanup()
    _certificateDataStore.stopSync()
    Option(cancel.get()).foreach(_.cancel())
    client.close()
    reactivePgActorSystem.terminate()
    FastFuture.successful(())
  }

  private lazy val _privateAppsUserDataStore   = new KvPrivateAppsUserDataStore(redis, env)
  private lazy val _backOfficeUserDataStore    = new KvBackOfficeUserDataStore(redis, env)
  private lazy val _serviceGroupDataStore      = new KvServiceGroupDataStore(redis, env)
  private lazy val _globalConfigDataStore      = new KvGlobalConfigDataStore(redis, env)
  private lazy val _apiKeyDataStore            = new KvApiKeyDataStore(redis, env)
  private lazy val _serviceDescriptorDataStore = new KvServiceDescriptorDataStore(redis, reactivePgStatsItems, env)
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

  private lazy val _clientCertificateValidationDataStore = new KvClientCertificateValidationDataStore(redis, env)
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

  private lazy val _tenantDataStore = new TenantDataStore(redis, env)
  override def tenantDataStore: TenantDataStore = _tenantDataStore

  private lazy val _teamDataStore = new TeamDataStore(redis, env)
  override def teamDataStore: TeamDataStore = _teamDataStore

  private lazy val _dataExporterConfigDataStore = new DataExporterConfigDataStore(redis, env)
  override def dataExporterConfigDataStore: DataExporterConfigDataStore = _dataExporterConfigDataStore

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
    redis.info().map(_ => Healthy).recover {
      case _ => Unreachable
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
                  // key.startsWith(s"${env.storageRoot}:users:") ||
                  key.startsWith(s"${env.storageRoot}:webauthn:admins:") ||
                  key.startsWith(s"${env.storageRoot}:deschealthcheck:") ||
                  key.startsWith(s"${env.storageRoot}:scall:stats:") ||
                  key.startsWith(s"${env.storageRoot}:scalldur:stats:") ||
                  key.startsWith(s"${env.storageRoot}:scallover:stats:") ||
                  (key.startsWith(s"${env.storageRoot}:data:") && key.endsWith(":stats:in")) ||
                  (key.startsWith(s"${env.storageRoot}:data:") && key.endsWith(":stats:out"))
              }
              .map { key =>
                // TODO: handle with getRaw like mongo
                for {
                  w     <- redis.typ(key)
                  ttl   <- redis.pttl(key)
                  value <- fetchValueForType(w, key)
                } yield
                  value match {
                    case JsNull => JsNull
                    case _ =>
                      Json.obj("k" -> key,
                        "v" -> value,
                        "t" -> (if (ttl == -1) -1 else (System.currentTimeMillis() + ttl)),
                        "w" -> w)
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
          case keys => {
            Source(keys.toList)
              .mapAsync(1) { key =>
              // TODO: handle with getRaw like mongo
                for {
                  w     <- redis.typ(key)
                  ttl   <- redis.pttl(key)
                  value <- fetchValueForType(w, key)
                } yield
                  value match {
                    case JsNull => JsNull
                    case _ =>
                      Json.obj("k" -> key,
                        "v" -> value,
                        "t" -> (if (ttl == -1) -1 else (System.currentTimeMillis() + ttl)),
                        "w" -> w)
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
    // TODO: handle with getRaw like mongo
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

  private def fetchValueForType(typ: String, key: String)(implicit ec: ExecutionContext): Future[JsValue] = {
    // TODO: handle with getRaw like mongo
    typ match {
      case "hash" => redis.hgetall(key).map(m => JsObject(m.map(t => (t._1, JsString(t._2.utf8String)))))
      case "list" => redis.lrange(key, 0, Long.MaxValue).map(l => JsArray(l.map(s => JsString(s.utf8String))))
      case "set"  => redis.smembers(key).map(l => JsArray(l.map(s => JsString(s.utf8String))))
      case "string" =>
        redis.get(key).map {
          case None    => JsNull
          case Some(a) => JsString(a.utf8String)
        }
      case _ => FastFuture.successful(JsNull)
    }
  }
}

class ReactivePgRedis(pool: PgPool, system: ActorSystem, env: Env) extends RedisLike {

  import pgimplicits._

  import collection.JavaConverters._

  implicit val ec = system.dispatcher
  implicit val mat = env.otoroshiMaterializer

  private val logger = Logger("otoroshi-reactive-pg-kv")

  val debugQueries = true

  def queryOne[A](query: String, params: Seq[AnyRef] = Seq.empty)(f: Row => A): Future[Option[A]] = {
    if (debugQueries) logger.info(s"query: $query, params: $params")
    pool.preparedQuery(query)
      .execute(io.vertx.sqlclient.Tuple.from(params.toArray))
      .scala
      .flatMap { _rows =>
        Try {
          val rows = _rows.asScala
          rows.headOption.map { row =>
            f(row)
          }
        } match {
          case Success(value) => FastFuture.successful(value)
          case Failure(e) => FastFuture.failed(e)
        }
      }
  }

  def typ(key: String): Future[String] = {
    pool.query(s"select type from otoroshi.entities where key = '${key}' and (ttl_starting_at + ttl) > NOW() limit 1;")
      .executeAsync()
      .map(_.asScala.head.getString("type"))
  }

  def info(): Future[String] = {
    FastFuture.successful("ok") // TODO: fix it
  }

  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = {
    FastFuture.successful(Healthy)
  }

  override def stop(): Unit = ()

  override def flushall(): Future[Boolean] = {
    pool.query(s"truncate otoroshi.entities;")
      .executeAsync()
      .map(_ => true)
  }

  override def get(key: String): Future[Option[ByteString]] = {
    pool.query(s"select value, counter, type from otoroshi.entities where key = '${key}' and (ttl_starting_at + ttl) > NOW() limit 1;")
      .executeAsync()
      .map { _rows =>
        val rows = _rows.asScala
        rows.headOption.map { row =>
          row.getString("type") match {
            case "counter" => row.getLong("counter").toString.byteString
            case _ => row.getString("value").byteString
          }
        }
      }
  }

  override def mget(keys: String*): Future[Seq[Option[ByteString]]] = {
    // TODO: improve
    Source(keys.toList)
      .mapAsync(1)(key => get(key)).runFold(Seq.empty[Option[ByteString]])((seq, opt) => seq :+ opt)
  }

  override def set(key: String, value: String, exSeconds: Option[Long], pxMilliseconds: Option[Long]): Future[Boolean] = {
    setBS(key, value.byteString, exSeconds, pxMilliseconds)
  }

  override def del(keys: String*): Future[Long] = {
    pool.query(s"delete from otoroshi.entities where key in (${keys.map(k => s"'$k'").mkString(", ")}) and (ttl_starting_at + ttl) > NOW();")
      .executeAsync()
      .map(_ => keys.size)
  }

  override def incr(key: String): Future[Long] = incrby(key, 1)

  override def incrby(key: String, increment: Long): Future[Long] = {
    pool.query(s"update otoroshi.entities set type = 'counter', counter = counter + $increment where key = '${key}' and (ttl_starting_at + ttl) > NOW() returning counter;")
      .executeAsync()
      .map(_.asScala.headOption.flatMap(r => Try(r.getLong("counter").longValue()).toOption).getOrElse(0L))
  }

  override def exists(key: String): Future[Boolean] = {
    pool.query(s"select type from otoroshi.entities where key = '${key}' and (ttl_starting_at + ttl) > NOW() limit 1;")
      .executeAsync()
      .map(_.asScala.nonEmpty)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  override def setBS(key: String, value: ByteString, exSeconds: Option[Long], pxMilliseconds: Option[Long]): Future[Boolean] = {
    val ttl = exSeconds.map(_ * 1000).orElse(pxMilliseconds).map(v => s"'$v milliseconds'::interval").getOrElse("'1000 years'::interval")
    val maybeTtlUpdate = exSeconds.map(_ * 1000).orElse(pxMilliseconds).map(v => s"'$v milliseconds'::interval").map(ttl => s", ttl = $ttl, ttl_starting_at = NOW()").getOrElse("")
    queryOne(
      s"""
         |insert into otoroshi.entities (key, type, ttl, ttl_starting_at, value)
         |values ('${key}', 'string', $ttl, NOW(), '${value.utf8String}')
         |ON CONFLICT (key)
         |DO
         |  update set type = 'string', value = '${value.utf8String}'${maybeTtlUpdate};
         |""".stripMargin) { _ =>
      true
    }.map(_.getOrElse(true))
  }
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  override def keys(pattern: String): Future[Seq[String]] = {
    pool.query(s"select key from otoroshi.entities where key ~ '${pattern.replace("*", ".*")}' and (ttl_starting_at + ttl) > NOW();")
      .executeAsync()
      .map { _rows =>
        val rows = _rows.asScala
        rows.map(r => r.getString("key")).toSeq
      }
  }
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  override def hdel(key: String, fields: String*): Future[Long] = {
    pool.query(s"update otoroshi.entities set type = 'hash', mvalue = mvalue - ARRAY[${fields.map(m => s"'$m'").mkString(",")}] where key = '${key}' and (ttl_starting_at + ttl) > NOW();")
      .executeAsync()
      .map(_ => fields.size)
  }

  override def hgetall(key: String): Future[Map[String, ByteString]] = {
    pool.query(s"select mvalue from otoroshi.entities where key = '${key}' and (ttl_starting_at + ttl) > NOW() limit 1;")
      .executeAsync()
      .map { _rows =>
        _rows.asScala
          .headOption
          .map(r => Json.parse(r.getString("mvalue")).as[JsObject].value.mapValues(v => v.asString.byteString))
          .getOrElse(Map.empty[String, ByteString]).toMap
      }
  }

  override def hset(key: String, field: String, value: String): Future[Boolean] =  hsetBS(key, field, value.byteString)

  override def hsetBS(key: String, field: String, value: ByteString): Future[Boolean] = {
    pool.query(s"""update otoroshi.entities set type = 'hash', mvalue = mvalue || '{"${key}":"${value.utf8String}"}'::jsonb where key = '${key}' and (ttl_starting_at + ttl) > NOW();""")
      .executeAsync()
      .map(_ => true)
  }
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  override def lpush(key: String, values: String*): Future[Long] = lpushBS(key, values.map(_.byteString): _*)

  override def lpushLong(key: String, values: Long*): Future[Long] = lpushBS(key, values.map(_.toString.byteString): _*)

  private def getArray(key: String): Future[Option[Seq[ByteString]]] = {
    pool.query(s"select lvalue from otoroshi.entities where key = '${key}' and (ttl_starting_at + ttl) > NOW() limit 1;")
      .executeAsync()
      .map { _rows =>
        val rows = _rows.asScala
        rows.headOption.map { row =>
          Json.parse(row.getString("lvalue")).asArray.value.map(e => e.asString.byteString)
        }
      }
  }

  override def llen(key: String): Future[Long] = getArray(key).map(_.map(_.size.toLong).getOrElse(0L)) // TODO: improve, use jsonb_array_length(lvallue) as length

  override def lpushBS(key: String, values: ByteString*): Future[Long] = {
    pool.query(s"""update otoroshi.entities set type = 'list', lvalue = lvalue || '[${values.map(v => s""""$v""").mkString(",")}]'::jsonb where key = '${key}' and (ttl_starting_at + ttl) > NOW();""")
      .executeAsync()
      .map(_ => values.size)
  }

  override def lrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]] = getArray(key).map(_.map(_.slice(start.toInt, stop.toInt)).getOrElse(Seq.empty)) // TODO: improve

  override def ltrim(key: String, start: Long, stop: Long): Future[Boolean] = { // TODO: improve
    getArray(key).flatMap {
      case None => FastFuture.successful(false)
      case Some(arr) => {
        val newArr = JsArray(arr.slice(start.toInt, stop.toInt).map(i => JsString(i.utf8String))).stringify
        pool.query(s"""update otoroshi.entities set type = 'list', lvalue = '$newArr'::jsonb where key = '${key}' and (ttl_starting_at + ttl) > NOW();""")
          .executeAsync()
          .map(_ => true)
      }
    }
  }
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def pttl(key: String): Future[Long] = {
    pool.query(s"select (ttl_starting_at + ttl) as expire_at from otoroshi.entities where key = '${key}' and (ttl_starting_at + ttl) > NOW() limit 1;")
      .executeAsync()
      .map { _rows =>
        val rows = _rows.asScala
        rows.headOption.map { row =>
          val now = System.currentTimeMillis()
          val ldate = row.getOffsetDateTime("expire_at")
          (ldate.toEpochSecond * 1000) - now
        } filter(_ > -1) getOrElse(-1)
      }
  }

  override def ttl(key: String): Future[Long] = pttl(key).map(v => v / 1000L)

  override def expire(key: String, seconds: Int): Future[Boolean] = pexpire(key, seconds * 1000L)

  override def pexpire(key: String, milliseconds: Long): Future[Boolean] = {
    // TODO: make it work with millis
    pool.query(s"update otoroshi.entities set ttl = '$milliseconds milliseconds'::interval, ttl_starting_at = NOW() where key = '${key}' and (ttl_starting_at + ttl) > NOW();")
      .executeAsync()
      .map(_ => true)
  }

  override def sadd(key: String, members: String*): Future[Long] = saddBS(key, members.map(_.byteString): _*)

  override def saddBS(key: String, members: ByteString*): Future[Long] = {
    pool.query(s"update otoroshi.entities set type = 'set', svalue = svalue || '{${members.map(m => s""""$m":"-"""").mkString(",")}}'::jsonb where key = '${key}' and (ttl_starting_at + ttl) > NOW();")
      .executeAsync()
      .map(_ => members.size)
  }

  override def sismember(key: String, member: String): Future[Boolean] = sismemberBS(key, member.byteString)

  override def sismemberBS(key: String, member: ByteString): Future[Boolean] = {
    pool.query(s"select svalue from otoroshi.entities where key = '${key}' and svalue -> '${member.utf8String}' = '-' and (ttl_starting_at + ttl) > NOW() limit 1;")
      .executeAsync()
      .map { _rows =>
        _rows.asScala.nonEmpty
      }
  }

  override def smembers(key: String): Future[Seq[ByteString]] = {
    pool.query(s"select svalue from otoroshi.entities where key = '${key}' and (ttl_starting_at + ttl) > NOW() limit 1;")
      .executeAsync()
      .map { _rows =>
        _rows.asScala.headOption.map(r => Json.parse(r.getString("svalue")).as[JsObject].keys.toSeq.map(ByteString.apply)).getOrElse(Seq.empty)
      }
  }

  override def srem(key: String, members: String*): Future[Long] = sremBS(key, members.map(_.byteString): _*)

  override def sremBS(key: String, members: ByteString*): Future[Long] = {
    pool.query(s"update otoroshi.entities set type = 'set', svalue = svalue - ARRAY[${members.map(m => s"'$m'").mkString(",")}] where key = '${key}' and (ttl_starting_at + ttl) > NOW();")
      .executeAsync()
      .map(_ => members.size)
  }

  override def scard(key: String): Future[Long] = smembers(key).map(_.size) // TODO: improve

  override def rawGet(key: String): Future[Option[JsValue]] = {
    pool.query(s"select json_agg(t) as json from otoroshi.entities t where key = '${key}' and (ttl_starting_at + ttl) > NOW() limit 1;")
      .executeAsync()
      .map(_.asScala.headOption.flatMap(r => Try(r.getString("json")).toOption).map(Json.parse))
  }
}
