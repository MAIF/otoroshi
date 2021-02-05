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

  private lazy val connectOptions = if (configuration.has("app.pg.uri")) {
    PgConnectOptions.fromUri(configuration.get[String]("app.pg.uri"))
  } else {
    new PgConnectOptions()
      .setPort(configuration.getOptional[Int]("app.pg.port").getOrElse(5432))
      .setHost(configuration.getOptional[String]("app.pg.host").getOrElse("localhost"))
      .setDatabase(configuration.getOptional[String]("app.pg.database").getOrElse("otoroshi"))
      .setUser(configuration.getOptional[String]("app.pg.user").getOrElse("otoroshi"))
      .setPassword(configuration.getOptional[String]("app.pg.password").getOrElse("otoroshi"))
  }

  private lazy val poolOptions = new PoolOptions()
    .setMaxSize(configuration.getOptional[Int]("app.pg.poolSize").getOrElse(20))

  private lazy val client = PgPool.pool(connectOptions, poolOptions)

  private lazy val redis = new ReactivePgRedis(client, reactivePgActorSystem, env, configuration.getOptional[Boolean]("app.pg.avoidJsonPath").getOrElse(false))

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
           |  ttl_starting_at TIMESTAMPTZ default NOW(),
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
    if (configuration.getOptional[Boolean]("app.pg.testMode").getOrElse(false)) {
      Await.result(redis.flushall(), 5.minutes)
    }
  }

  def setupCleanup(): Unit = {
    implicit val ec = reactivePgActorSystem.dispatcher
    cancel.set(reactivePgActorSystem.scheduler.scheduleAtFixedRate(1.minute, 5.minutes)(SchedulerHelper.runnable {
      client.query("DELETE FROM otoroshi.entities WHERE (ttl_starting_at + ttl) < NOW();").executeAsync()
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
                for {
                  w     <- redis.typ(key)
                  ttl   <- redis.pttl(key)
                  value <- fetchValueForType(w, key)
                } yield
                  value match {
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
              case "counter" => redis.setCounter(key, value.as[Long])
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
    typ match {
      case "counter" => redis.getCounter(key).map(_.map(v => JsNumber(v)).getOrElse(JsNumber(0L)))
      case "hash" => redis.hgetall(key).map(m => JsObject(m.map(t => (t._1, JsString(t._2.utf8String)))))
      case "list" => redis.lrange(key, 0, Int.MaxValue - 1).map(l => JsArray(l.map(s => JsString(s.utf8String))))
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

class ReactivePgRedis(pool: PgPool, system: ActorSystem, env: Env, avoidJsonPath: Boolean) extends RedisLike {

  import pgimplicits._

  import collection.JavaConverters._

  implicit val ec = system.dispatcher

  private val logger = Logger("otoroshi-reactive-pg-kv")

  private val debugQueries = env.configuration.getOptional[Boolean]("app.pg.logQueries").getOrElse(false)

  def queryRaw[A](query: String, params: Seq[AnyRef] = Seq.empty, debug: Boolean = false)(f: Seq[Row] => A): Future[A] = {
    if (debug || debugQueries) logger.info(s"""query: "$query", params: "${params.mkString(", ")}"""")
    pool.preparedQuery(query)
      .execute(io.vertx.sqlclient.Tuple.from(params.toArray))
      .scala
      .flatMap { _rows =>
        Try {
          val rows = _rows.asScala.toSeq
          f(rows)
        } match {
          case Success(value) => FastFuture.successful(value)
          case Failure(e) => FastFuture.failed(e)
        }
      }.andThen {
      case Failure(e) => logger.error(s"""Failed to apply query: "$query" with params: "${params.mkString(", ")}"""", e)
    }
  }

  def querySeq[A](query: String, params: Seq[AnyRef] = Seq.empty, debug: Boolean = false)(f: Row => A): Future[Seq[A]] = {
    if (debug || debugQueries) logger.info(s"""query: "$query", params: "${params.mkString(", ")}"""")
    pool.preparedQuery(query)
      .execute(io.vertx.sqlclient.Tuple.from(params.toArray))
      .scala
      .flatMap { _rows =>
        Try {
          _rows.asScala.toSeq.map(f)
        } match {
          case Success(value) => FastFuture.successful(value)
          case Failure(e) => FastFuture.failed(e)
        }
      }.andThen {
      case Failure(e) => logger.error(s"""Failed to apply query: "$query" with params: "${params.mkString(", ")}"""", e)
    }
  }

  def queryOne[A](query: String, params: Seq[AnyRef] = Seq.empty, debug: Boolean = false)(f: Row => A): Future[Option[A]] = {
    if (debug || debugQueries) logger.info(s"""query: "$query", params: "${params.mkString(", ")}"""")
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
      }.andThen {
      case Failure(e) => logger.error(s"""Failed to apply query: "$query" with params: "${params.mkString(", ")}"""", e)
    }
  }

  def queryOneOpt[A](query: String, params: Seq[AnyRef] = Seq.empty, debug: Boolean = false)(f: Row => Option[A]): Future[Option[A]] = {
    if (debug || debugQueries) logger.info(s"""query: "$query", params: "${params.mkString(", ")}"""")
    pool.preparedQuery(query)
      .execute(io.vertx.sqlclient.Tuple.from(params.toArray))
      .scala
      .flatMap { _rows =>
        Try {
          val rows = _rows.asScala
          rows.headOption.flatMap { row =>
            f(row)
          }
        } match {
          case Success(value) => FastFuture.successful(value)
          case Failure(e) => FastFuture.failed(e)
        }
      }.andThen {
      case Failure(e) => logger.error(s"""Failed to apply query: "$query" with params: "${params.mkString(", ")}"""", e)
    }
  }

  def typ(key: String): Future[String] = {
    queryOne(s"select type from otoroshi.entities where key = $$1 and (ttl_starting_at + ttl) > NOW() limit 1;", Seq(key)) { row =>
      row.getString("type")
    }.map(_.getOrElse("none"))
  }

  def info(): Future[String] = {
   queryOne("select version() as version;") { row =>
     row.getString("version")
   }.map(_.getOrElse("none"))
  }

  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = {
    info().map(_ => Healthy).recover { case _ => Unreachable }
  }

  override def stop(): Unit = ()

  override def flushall(): Future[Boolean] = {
    queryRaw(s"truncate otoroshi.entities;") { _ =>
      true
    }
  }

  override def get(key: String): Future[Option[ByteString]] = {
    queryOne(s"select value, counter, type from otoroshi.entities where key = $$1 and (ttl_starting_at + ttl) > NOW() limit 1;", Seq(key)) { row =>
      row.getString("type") match {
        case "counter" => row.getLong("counter").toString.byteString
        case _ => row.getString("value").byteString
      }
    }
  }

  override def mget(keys: String*): Future[Seq[Option[ByteString]]] = {
    val allkeys = keys.map(k => s"'$k'").mkString(", ")
    querySeq(s"select key, value, counter, type from otoroshi.entities where key in ($allkeys) and (ttl_starting_at + ttl) > NOW();") { row =>
      val value = row.getString("type") match {
        case "counter" => row.getLong("counter").toString.byteString
        case _ => row.getString("value").byteString
      }
      (row.getString("key"), value)
    }.map { tuples =>
      val m = tuples.toMap
      keys.map(k => m.get(k))
    }
  }

  override def set(key: String, value: String, exSeconds: Option[Long], pxMilliseconds: Option[Long]): Future[Boolean] = {
    setBS(key, value.byteString, exSeconds, pxMilliseconds)
  }

  override def del(keys: String*): Future[Long] = {
    queryRaw(s"delete from otoroshi.entities where key in (${keys.map(k => s"'$k'").mkString(", ")}) and (ttl_starting_at + ttl) > NOW();") { _ =>
      keys.size
    }
  }

  override def incr(key: String): Future[Long] = incrby(key, 1)

  override def incrby(key: String, increment: Long): Future[Long] = {
    queryOne(
      s"""insert into otoroshi.entities (key, type, counter)
         |values ($$1, 'counter', $$2)
         |on conflict (key)
         |do
         |  update set type = 'counter', counter = otoroshi.entities.counter + $$2 returning counter;
         |""".stripMargin, Seq(key, increment.asInstanceOf[AnyRef])) { row =>
      row.getLong("counter").longValue()
    }.map(_.getOrElse(increment))
  }

  def getCounter(key: String): Future[Option[Long]] = {
    get(key).map(_.map(_.utf8String.toLong))
  }

  def setCounter(key: String, value: Long): Future[Unit] = {
    queryRaw(s"insert into otoroshi.entities (key, type, counter) values ($$1, 'counter', $$2)", Seq(key, value.asInstanceOf[AnyRef])) { _ =>
      ()
    }
  }

  override def exists(key: String): Future[Boolean] = {
    queryOne(s"select type from otoroshi.entities where key = $$1 and (ttl_starting_at + ttl) > NOW() limit 1;", Seq(key)) { row =>
      true
    }.map(_.isDefined)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  override def setBS(key: String, value: ByteString, exSeconds: Option[Long], pxMilliseconds: Option[Long]): Future[Boolean] = {
    val ttl = exSeconds.map(_ * 1000).orElse(pxMilliseconds).map(v => s"'$v milliseconds'::interval").getOrElse("'1000 years'::interval")
    val maybeTtlUpdate = exSeconds.map(_ * 1000).orElse(pxMilliseconds).map(v => s"'$v milliseconds'::interval").map(ttl => s", ttl = $ttl, ttl_starting_at = NOW()").getOrElse("")
    queryOne(
      s"""insert into otoroshi.entities (key, type, ttl, ttl_starting_at, value)
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
    val processed = pattern.replace("*", ".*")
    querySeq(s"select key from otoroshi.entities where key ~ $$1 and (ttl_starting_at + ttl) > NOW();", Seq(processed)) { row =>
      row.getString("key")
    }
  }
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  override def hdel(key: String, fields: String*): Future[Long] = {
    queryRaw(s"update otoroshi.entities set type = 'hash', mvalue = mvalue - ARRAY[${fields.map(m => s"'$m'").mkString(",")}] where key = $$1 and (ttl_starting_at + ttl) > NOW();") { _ =>
      fields.size
    }
  }

  override def hgetall(key: String): Future[Map[String, ByteString]] = {
    queryOne(s"select mvalue from otoroshi.entities where key = $$1 and (ttl_starting_at + ttl) > NOW() limit 1;", Seq(key)) { row =>
      Try {
        Json.parse(row.getJsonObject("mvalue").encode()).as[JsObject].value.mapValues(v => v.asString.byteString).toMap
      } match {
        case Success(s) => s
        case Failure(e) => Json.parse(row.getString("mvalue")).as[JsObject].value.mapValues(v => v.asString.byteString).toMap
      }
    }.map(_.getOrElse(Map.empty[String, ByteString]))
  }

  override def hset(key: String, field: String, value: String): Future[Boolean] =  hsetBS(key, field, value.byteString)

  override def hsetBS(key: String, field: String, value: ByteString): Future[Boolean] = {
    queryRaw(
      s"""insert into otoroshi.entities (key, type, mvalue)
         |values ($$1, 'hash', $$2::jsonb)
         |on conflict (key)
         |do
         |  update set type = 'hash', mvalue = otoroshi.entities.mvalue || $$2::jsonb;
         |""".stripMargin, Seq(key, Json.obj(key -> value.utf8String).stringify)) { _ =>
      true
    }
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
          Try {
            Json.parse(row.getJsonArray("lvalue").encode()).asArray.value.map(e => e.asString.byteString)
          } match {
            case Success(s) => s
            case Failure(exception) => Json.parse(row.getString("lvalue")).asArray.value.map(e => e.asString.byteString)
          }
        }
      }
  }

  override def llen(key: String): Future[Long] = {
    queryOne(s"select jsonb_array_length(lvalue) as length from otoroshi.entities where key = $$1 and (ttl_starting_at + ttl) > NOW();", Seq(key)) { row =>
      row.getLong("length").longValue()
    }.map(_.getOrElse(0L))
  }

  override def lpushBS(key: String, values: ByteString*): Future[Long] = {
    val arr = JsArray(values.map(v => JsString(v.utf8String))).stringify
    queryRaw(
      s"""insert into otoroshi.entities (key, type, lvalue)
         |values ($$1, 'list', '$arr'::jsonb)
         |on conflict (key)
         |do
         |  update set type = 'list', lvalue = otoroshi.entities.lvalue || '$arr'::jsonb;
         |""".stripMargin, Seq(key)) { _ =>
      values.size
    }
  }

  override def lrange(key: String, start: Long, _stop: Long): Future[Seq[ByteString]] = {
    val stop = if (_stop > (Int.MaxValue - 1)) Int.MaxValue - 1 else _stop
    if (avoidJsonPath) {
      getArray(key).map(_.map(_.slice(start.toInt, stop.toInt)).getOrElse(Seq.empty)) // awful but not supported in some cases like cockroachdb
    } else {
      queryOne(s"select jsonb_path_query_array(lvalue, '$$[$start to $stop]') as slice from otoroshi.entities where key = $$1 and (ttl_starting_at + ttl) > NOW();", Seq(key)) { row =>
        Try(row.getJsonArray("slice").encode()).map { s =>
          Try {
            Json.parse(s).asArray.value.map(v => v.asString.byteString)
          } match {
            case Success(value) => value
            case Failure(ex) => Json.parse(row.getString("slice")).asArray.value.map(v => v.asString.byteString)
          }
        }.getOrElse(Seq.empty)
      }.map(_.getOrElse(Seq.empty)).recoverWith {
        case ex: io.vertx.pgclient.PgException if ex.getMessage.contains("jsonb_path_query_array(): unimplemented:") => {
          getArray(key).map(_.map(_.slice(start.toInt, stop.toInt)).getOrElse(Seq.empty)) // awful but not supported in some cases like cockroachdb
        }
      }
    }
  }

  override def ltrim(key: String, start: Long, _stop: Long): Future[Boolean] = {
    val stop = if (_stop > (Int.MaxValue - 1)) Int.MaxValue - 1 else _stop
    if (avoidJsonPath) {
      // awful but not supported in some cases like cockroachdb
      getArray(key).flatMap {
        case None => FastFuture.successful(false)
        case Some(arr) => {
          val newArr = JsArray(arr.slice(start.toInt, stop.toInt).map(i => JsString(i.utf8String))).stringify
          queryRaw(s"""update otoroshi.entities set type = 'list', lvalue = '$newArr'::jsonb where key = $$1 and (ttl_starting_at + ttl) > NOW();""", Seq(key)) { _ =>
            true
          }
        }
      }
    } else {
      queryRaw(s"update otoroshi.entities set type = 'list', lvalue = jsonb_path_query_array(lvalue, '$$[$start to $stop]') where key = $$1 and (ttl_starting_at + ttl) > NOW();", Seq(key)) { _ =>
        true
      }.recoverWith {
        case ex: io.vertx.pgclient.PgException if ex.getMessage.contains("jsonb_path_query_array(): unimplemented:") => {
          // awful but not supported in some cases like cockroachdb
          getArray(key).flatMap {
            case None => FastFuture.successful(false)
            case Some(arr) => {
              val newArr = JsArray(arr.slice(start.toInt, stop.toInt).map(i => JsString(i.utf8String))).stringify
              queryRaw(s"""update otoroshi.entities set type = 'list', lvalue = '$newArr'::jsonb where key = $$1 and (ttl_starting_at + ttl) > NOW();""", Seq(key)) { _ =>
                true
              }
            }
          }
        }
      }
    }
  }
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def pttl(key: String): Future[Long] = {
    queryOne(s"select (ttl_starting_at + ttl) as expire_at from otoroshi.entities where key = $$1 and (ttl_starting_at + ttl) > NOW() limit 1;", Seq(key)) { row =>
      val now = System.currentTimeMillis()
      val ldate = row.getOffsetDateTime("expire_at")
      (ldate.toEpochSecond * 1000) - now
    }.map(_.filter(_ > -1).getOrElse(-1))
  }

  override def ttl(key: String): Future[Long] = pttl(key).map(v => v / 1000L)

  override def expire(key: String, seconds: Int): Future[Boolean] = pexpire(key, seconds * 1000L)

  override def pexpire(key: String, milliseconds: Long): Future[Boolean] = {
    queryRaw(s"update otoroshi.entities set ttl = '$milliseconds milliseconds'::interval, ttl_starting_at = NOW() where key = $$1 and (ttl_starting_at + ttl) > NOW();", Seq(key)) { row =>
      true
    }
  }

  override def sadd(key: String, members: String*): Future[Long] = saddBS(key, members.map(_.byteString): _*)

  override def saddBS(key: String, members: ByteString*): Future[Long] = {
    val obj = JsObject(members.map(m => (m.utf8String, JsString("-")))).stringify
    queryRaw(
      s"""insert into otoroshi.entities (key, type, svalue)
         |values ($$1, 'set', '$obj'::jsonb)
         |on conflict (key)
         |do
         |  update set type = 'set', svalue = otoroshi.entities.svalue || '$obj'::jsonb;
         |""".stripMargin, Seq(key)) { _ =>
      members.size
    }
  }

  override def sismember(key: String, member: String): Future[Boolean] = sismemberBS(key, member.byteString)

  override def sismemberBS(key: String, member: ByteString): Future[Boolean] = {
    queryRaw(s"select svalue from otoroshi.entities where key = $$1 and svalue ? '${member.utf8String}' and (ttl_starting_at + ttl) > NOW() limit 1;", Seq(key)) { rows =>
      rows.nonEmpty
    }
  }

  override def smembers(key: String): Future[Seq[ByteString]] = {
    queryOne(s"select svalue from otoroshi.entities where key = $$1 and (ttl_starting_at + ttl) > NOW() limit 1;", Seq(key)) { row =>
      Try {
        Json.parse(row.getJsonObject("svalue").encode()).as[JsObject].keys.toSeq.map(ByteString.apply)
      } match {
        case Success(r) => r
        case Failure(e) => Json.parse(row.getString("svalue")).as[JsObject].keys.toSeq.map(ByteString.apply)
      }
    }.map(_.getOrElse(Seq.empty))
  }

  override def srem(key: String, members: String*): Future[Long] = sremBS(key, members.map(_.byteString): _*)

  override def sremBS(key: String, members: ByteString*): Future[Long] = {
    queryRaw(s"update otoroshi.entities set type = 'set', svalue = svalue - ARRAY[${members.map(m => s"'$m'").mkString(",")}] where key = $$1 and (ttl_starting_at + ttl) > NOW();", Seq(key)) { _ =>
      members.size
    }
  }

  override def scard(key: String): Future[Long] = {
    queryOne(
      s"""select count(*) from (
         |	select jsonb_object_keys(svalue) as length from otoroshi.entities where key = $$1 and (ttl_starting_at + ttl) > NOW()
         |) A;
         |""".stripMargin, Seq(key)) { row =>
      row.getLong("count").longValue()
    }.map(_.getOrElse(0))
  }

  override def rawGet(key: String): Future[Option[JsValue]] = {
    queryOneOpt(s"select json_agg(t) as json from otoroshi.entities t where key = $$1 and (ttl_starting_at + ttl) > NOW() limit 1;", Seq(key)) { row =>
      Try(row.getString("json")).toOption.map(Json.parse)
    }
  }
}
