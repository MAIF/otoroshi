package cluster

import java.io.File
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong, AtomicReference}
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.regex.Pattern

import actions.ApiAction
import akka.{Done, NotUsed}
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Compression, Flow, Framing, Sink, Source}
import akka.util.ByteString
import auth.AuthConfigsDataStore
import com.google.common.io.Files
import com.typesafe.config.ConfigFactory
import env.Env
import events.{AlertDataStore, AuditDataStore, HealthCheckDataStore}
import gateway.{InMemoryRequestsDataStore, RequestsDataStore, Retry}
import javax.management.{Attribute, ObjectName}
import models._
import org.joda.time.DateTime
import otoroshi.script.{InMemoryScriptDataStore, ScriptDataStore}
import otoroshi.tcp.{InMemoryTcpServiceDataStoreDataStore, TcpServiceDataStore}
import play.api.http.HttpEntity
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.libs.ws.{DefaultWSProxyServer, SourceBody, WSAuthScheme, WSProxyServer}
import play.api.mvc.{AbstractController, BodyParser, ControllerComponents}
import play.api.{Configuration, Environment, Logger}
import redis.RedisClientMasterSlaves
import security.IdGenerator
import ssl._
import storage.inmemory._
import storage._
import storage.inmemory.concurrent.{Memory, SwappableInMemoryRedis}
import utils.http.Implicits._
import utils.http.MtlsConfig

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.math.BigDecimal.RoundingMode
import scala.util.{Failure, Success, Try}

/**
 * # Test
 *
 * java -Dhttp.port=8080 -Dhttps.port=8443 -Dotoroshi.cluster.mode=leader -Dotoroshi.cluster.autoUpdateState=true -Dapp.adminPassword=password -Dapp.storage=file -Dotoroshi.loggers.otoroshi-cluster=DEBUG -jar otoroshi.jar
 * java -Dhttp.port=9080 -Dhttps.port=9443 -Dotoroshi.cluster.mode=worker  -Dapp.storage=file -Dotoroshi.loggers.otoroshi-cluster=DEBUG -jar otoroshi.jar
 *
 */
object Cluster {
  lazy val logger = Logger("otoroshi-cluster")
}

trait ClusterMode {
  def name: String
  def clusterActive: Boolean
  def isOff: Boolean
  def isWorker: Boolean
  def isLeader: Boolean
}

object ClusterMode {
  case object Off extends ClusterMode {
    def name: String           = "Off"
    def clusterActive: Boolean = false
    def isOff: Boolean         = true
    def isWorker: Boolean      = false
    def isLeader: Boolean      = false
  }
  case object Leader extends ClusterMode {
    def name: String           = "Leader"
    def clusterActive: Boolean = true
    def isOff: Boolean         = false
    def isWorker: Boolean      = false
    def isLeader: Boolean      = true
  }
  case object Worker extends ClusterMode {
    def name: String           = "Worker"
    def clusterActive: Boolean = true
    def isOff: Boolean         = false
    def isWorker: Boolean      = true
    def isLeader: Boolean      = false
  }
  val values: Seq[ClusterMode] =
    Seq(Off, Leader, Worker)
  def apply(name: String): Option[ClusterMode] = name match {
    case "Off"    => Some(Off)
    case "Leader" => Some(Leader)
    case "Worker" => Some(Worker)
    case "off"    => Some(Off)
    case "leader" => Some(Leader)
    case "worker" => Some(Worker)
    case _        => None
  }
}

case class WorkerQuotasConfig(timeout: Long = 2000, pushEvery: Long = 2000, retries: Int = 3)
case class WorkerStateConfig(timeout: Long = 2000, pollEvery: Long = 10000, retries: Int = 3)
case class WorkerConfig(
    name: String = s"otoroshi-worker-${IdGenerator.token(16)}",
    retries: Int = 3,
    timeout: Long = 2000,
    dbPath: Option[String] = None,
    state: WorkerStateConfig = WorkerStateConfig(),
    quotas: WorkerQuotasConfig = WorkerQuotasConfig(),
    //initialCacert: Option[String] = None
)
case class LeaderConfig(
    name: String = s"otoroshi-leader-${IdGenerator.token(16)}",
    urls: Seq[String] = Seq.empty,
    host: String = "otoroshi-api.oto.tools",
    clientId: String = "admin-api-apikey-id",
    clientSecret: String = "admin-api-apikey-secret",
    groupingBy: Int = 50,
    cacheStateFor: Long = 4000,
    stateDumpPath: Option[String] = None
)
case class ClusterConfig(
    mode: ClusterMode = ClusterMode.Off,
    compression: Int = -1,
    proxy: Option[WSProxyServer],
    mtlsConfig: MtlsConfig,
    autoUpdateState: Boolean,
    leader: LeaderConfig = LeaderConfig(),
    worker: WorkerConfig = WorkerConfig()
) {
  def gzip(): Flow[ByteString, ByteString, NotUsed] =
    if (compression == -1) Flow.apply[ByteString] else Compression.gzip(compression)
  def gunzip(): Flow[ByteString, ByteString, NotUsed] =
    if (compression == -1) Flow.apply[ByteString] else Compression.gunzip()
}

object ClusterConfig {
  def apply(configuration: Configuration): ClusterConfig = {
    // Cluster.logger.debug(configuration.underlying.root().render(ConfigRenderOptions.concise()))
    ClusterConfig(
      mode = configuration.getOptional[String]("mode").flatMap(ClusterMode.apply).getOrElse(ClusterMode.Off),
      compression = configuration.getOptional[Int]("compression").getOrElse(-1),
      autoUpdateState = configuration.getOptional[Boolean]("autoUpdateState").getOrElse(false),
      mtlsConfig = MtlsConfig(
        certs = configuration.getOptional[Seq[String]]("mtls.certs").getOrElse(Seq.empty),
        loose = configuration.getOptional[Boolean]("mtls.loose").getOrElse(false),
        mtls = configuration.getOptional[Boolean]("mtls.enabled").getOrElse(false)
      ),
      proxy = configuration.getOptional[String]("proxy.host").map { host =>
        DefaultWSProxyServer(
          host = host,
          port = configuration.getOptional[Int]("proxy.port").getOrElse(3129),
          principal = configuration.getOptional[String]("proxy.principal"),
          password = configuration.getOptional[String]("proxy.password"),
          ntlmDomain = configuration.getOptional[String]("proxy.ntlmDomain"),
          encoding = configuration.getOptional[String]("proxy.encoding"),
          nonProxyHosts = None
        )
      },
      leader = LeaderConfig(
        name = configuration
          .getOptional[String]("leader.name")
          .orElse(Option(System.getenv("INSTANCE_NUMBER")).map(i => s"otoroshi-leader-$i"))
          .getOrElse(s"otoroshi-leader-${IdGenerator.token(16)}"),
        urls = configuration
          .getOptional[String]("leader.url")
          .map(s => Seq(s))
          .orElse(
            configuration
              .getOptional[Seq[String]]("leader.urls")
              .map(_.toSeq)
          )
          .getOrElse(Seq("http://otoroshi-api.oto.tools:8080")),
        host = configuration.getOptional[String]("leader.host").getOrElse("otoroshi-api.oto.tools"),
        clientId = configuration.getOptional[String]("leader.clientId").getOrElse("admin-api-apikey-id"),
        clientSecret = configuration.getOptional[String]("leader.clientSecret").getOrElse("admin-api-apikey-secret"),
        groupingBy = configuration.getOptional[Int]("leader.groupingBy").getOrElse(50),
        cacheStateFor = configuration.getOptional[Long]("leader.cacheStateFor").getOrElse(4000L),
        stateDumpPath = configuration.getOptional[String]("leader.stateDumpPath")
      ),
      worker = WorkerConfig(
        name = configuration
          .getOptional[String]("worker.name")
          .orElse(Option(System.getenv("INSTANCE_NUMBER")).map(i => s"otoroshi-worker-$i"))
          .getOrElse(s"otoroshi-worker-${IdGenerator.token(16)}"),
        retries = configuration.getOptional[Int]("worker.retries").getOrElse(3),
        timeout = configuration.getOptional[Long]("worker.timeout").getOrElse(2000),
        dbPath = configuration.getOptional[String]("worker.dbpath"),
        state = WorkerStateConfig(
          timeout = configuration.getOptional[Long]("worker.state.timeout").getOrElse(2000),
          retries = configuration.getOptional[Int]("worker.state.retries").getOrElse(3),
          pollEvery = configuration.getOptional[Long]("worker.state.pollEvery").getOrElse(10000L)
        ),
        quotas = WorkerQuotasConfig(
          timeout = configuration.getOptional[Long]("worker.quotas.timeout").getOrElse(2000),
          retries = configuration.getOptional[Int]("worker.quotas.retries").getOrElse(3),
          pushEvery = configuration.getOptional[Long]("worker.quotas.pushEvery").getOrElse(2000L)
        )
      )
    )
  }
}

case class StatsView(
    rate: Double,
    duration: Double,
    overhead: Double,
    dataInRate: Double,
    dataOutRate: Double,
    concurrentHandledRequests: Long
)

case class MemberView(name: String,
                      location: String,
                      lastSeen: DateTime,
                      timeout: Duration,
                      memberType: ClusterMode,
                      stats: JsObject = Json.obj()) {
  def asJson: JsValue = Json.obj(
    "name"     -> name,
    "location" -> location,
    "lastSeen" -> lastSeen.getMillis,
    "timeout"  -> timeout.toMillis,
    "type"     -> memberType.name,
    "stats"    -> stats
  )
  def statsView: StatsView = {
    StatsView(
      rate = (stats \ "rate").asOpt[Double].getOrElse(0.0),
      duration = (stats \ "duration").asOpt[Double].getOrElse(0.0),
      overhead = (stats \ "overhead").asOpt[Double].getOrElse(0.0),
      dataInRate = (stats \ "dataInRate").asOpt[Double].getOrElse(0.0),
      dataOutRate = (stats \ "dataOutRate").asOpt[Double].getOrElse(0.0),
      concurrentHandledRequests = (stats \ "concurrentHandledRequests").asOpt[Long].getOrElse(0L)
    )
  }

  def health: String = {
    val value = System.currentTimeMillis() - lastSeen.getMillis
    if (value < (timeout.toMillis / 2)) {
      "green"
    } else if (value < (3 * (timeout.toMillis / 4))) {
      "orange"
    } else {
      "red"
    }
  }
}

object MemberView {
  def fromJsonSafe(value: JsValue): JsResult[MemberView] =
    Try {
      JsSuccess(
        MemberView(
          name = (value \ "name").as[String],
          location = (value \ "location").as[String],
          lastSeen = new DateTime((value \ "lastSeen").as[Long]),
          timeout = Duration((value \ "timeout").as[Long], TimeUnit.MILLISECONDS),
          memberType = (value \ "type")
            .asOpt[String]
            .map(n => ClusterMode(n).getOrElse(ClusterMode.Off))
            .getOrElse(ClusterMode.Off),
          stats = (value \ "stats").asOpt[JsObject].getOrElse(Json.obj())
        )
      )
    } recover {
      case e => JsError(e.getMessage)
    } get
}

trait ClusterStateDataStore {
  def registerMember(member: MemberView)(implicit ec: ExecutionContext, env: Env): Future[Unit]
  def getMembers()(implicit ec: ExecutionContext, env: Env): Future[Seq[MemberView]]
  def clearMembers()(implicit ec: ExecutionContext, env: Env): Future[Long]
  def updateDataIn(in: Long)(implicit ec: ExecutionContext, env: Env): Future[Unit]
  def updateDataOut(out: Long)(implicit ec: ExecutionContext, env: Env): Future[Unit]
  def dataInAndOut()(implicit ec: ExecutionContext, env: Env): Future[(Long, Long)]
}

class InMemoryClusterStateDataStore(redisLike: RedisLike, env: Env) extends ClusterStateDataStore {

  override def clearMembers()(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    redisLike
      .keys(s"${env.storageRoot}:cluster:members:*")
      .flatMap(
        keys =>
          if (keys.isEmpty) FastFuture.successful(0L)
          else redisLike.del(keys: _*)
      )
  }

  override def registerMember(member: MemberView)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    val key = s"${env.storageRoot}:cluster:members:${member.name}"
    redisLike.get(key).flatMap {
      case Some(m) => {
        MemberView.fromJsonSafe(Json.parse(m.utf8String)) match {
          case JsSuccess(v, _) =>
            val stats     = if (member.stats.as[JsObject].value.isEmpty) v.stats else member.stats
            val newMember = member.copy(stats = stats)
            redisLike
              .set(key, Json.stringify(newMember.asJson), pxMilliseconds = Some(member.timeout.toMillis))
              .map(_ => ())
          case _ =>
            redisLike
              .set(key, Json.stringify(member.asJson), pxMilliseconds = Some(member.timeout.toMillis))
              .map(_ => ())
        }
      }
      case None =>
        redisLike.set(key, Json.stringify(member.asJson), pxMilliseconds = Some(member.timeout.toMillis)).map(_ => ())
    }
  }

  override def getMembers()(implicit ec: ExecutionContext, env: Env): Future[Seq[MemberView]] = {
    if (env.clusterConfig.mode == ClusterMode.Leader) {
      redisLike
        .keys(s"${env.storageRoot}:cluster:members:*")
        .flatMap(
          keys =>
            if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]])
            else redisLike.mget(keys: _*)
        )
        .map(
          seq =>
            seq.filter(_.isDefined).map(_.get).map(v => MemberView.fromJsonSafe(Json.parse(v.utf8String))).collect {
              case JsSuccess(i, _) => i
          }
        )
    } else {
      FastFuture.successful(Seq.empty)
    }
  }

  override def updateDataIn(in: Long)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    for {
      _ <- redisLike.lpushLong(s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:in", in)
      _ <- redisLike.ltrim(s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:in", 0, 100)
      _ <- redisLike.pexpire(s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:in",
                             env.clusterConfig.worker.timeout * env.clusterConfig.worker.retries)
    } yield ()
  }

  override def updateDataOut(out: Long)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    for {
      _ <- redisLike.lpushLong(s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:out", out)
      _ <- redisLike.ltrim(s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:out", 0, 100)
      _ <- redisLike.pexpire(s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:out",
                             env.clusterConfig.worker.timeout * env.clusterConfig.worker.retries)
    } yield ()
  }

  override def dataInAndOut()(implicit ec: ExecutionContext, env: Env): Future[(Long, Long)] = {
    for {
      keysIn  <- redisLike.keys(s"${env.storageRoot}:cluster:leader:*:data:in")
      keysOut <- redisLike.keys(s"${env.storageRoot}:cluster:leader:*:data:out")
      in <- Future
             .sequence(
               keysIn.map(
                 key =>
                   redisLike.lrange(key, 0, 100).map { values =>
                     if (values.isEmpty) 0L
                     else {
                       val items = values.map { v =>
                         v.utf8String.toLong
                       }
                       val total    = items.fold(0L)(_ + _)
                       val itemSize = if (items.isEmpty) 1 else items.size
                       (total / itemSize).toLong
                     }
                 }
               )
             )
             .map(a => a.fold(0L)(_ + _) / (if (a.isEmpty) 1 else a.size))

      out <- Future
              .sequence(
                keysOut.map(
                  key =>
                    redisLike.lrange(key, 0, 100).map { values =>
                      if (values.isEmpty) 0L
                      else {
                        val items = values.map { v =>
                          v.utf8String.toLong
                        }
                        val total    = items.fold(0L)(_ + _)
                        val itemSize = if (items.isEmpty) 1 else items.size
                        (total / itemSize).toLong
                      }
                  }
                )
              )
              .map(a => a.fold(0L)(_ + _) / (if (a.isEmpty) 1 else a.size))
    } yield (in, out)
  }
}

class RedisClusterStateDataStore(redisLike: RedisClientMasterSlaves, env: Env) extends ClusterStateDataStore {

  override def clearMembers()(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    redisLike
      .keys(s"${env.storageRoot}:cluster:members:*")
      .flatMap(
        keys =>
          if (keys.isEmpty) FastFuture.successful(0L)
          else redisLike.del(keys: _*)
      )
  }

  override def registerMember(member: MemberView)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    val key = s"${env.storageRoot}:cluster:members:${member.name}"
    redisLike.get(key).flatMap {
      case Some(m) => {
        MemberView.fromJsonSafe(Json.parse(m.utf8String)) match {
          case JsSuccess(v, _) =>
            val stats     = if (member.stats.as[JsObject].value.isEmpty) v.stats else member.stats
            val newMember = member.copy(stats = stats)
            redisLike
              .set(key, Json.stringify(newMember.asJson), pxMilliseconds = Some(member.timeout.toMillis))
              .map(_ => ())
          case _ =>
            redisLike
              .set(key, Json.stringify(member.asJson), pxMilliseconds = Some(member.timeout.toMillis))
              .map(_ => ())
        }
      }
      case None =>
        redisLike.set(key, Json.stringify(member.asJson), pxMilliseconds = Some(member.timeout.toMillis)).map(_ => ())
    }
  }

  override def getMembers()(implicit ec: ExecutionContext, env: Env): Future[Seq[MemberView]] = {
    if (env.clusterConfig.mode == ClusterMode.Leader) {
      redisLike
        .keys(s"${env.storageRoot}:cluster:members:*")
        .flatMap(
          keys =>
            if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]])
            else redisLike.mget(keys: _*)
        )
        .map(
          seq =>
            seq.filter(_.isDefined).map(_.get).map(v => MemberView.fromJsonSafe(Json.parse(v.utf8String))).collect {
              case JsSuccess(i, _) => i
          }
        )
    } else {
      FastFuture.successful(Seq.empty)
    }
  }

  override def updateDataIn(in: Long)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    for {
      _ <- redisLike.lpush(s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:in", in)
      _ <- redisLike.ltrim(s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:in", 0, 100)
      _ <- redisLike.pexpire(s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:in",
                             env.clusterConfig.worker.timeout * env.clusterConfig.worker.retries)
    } yield ()
  }

  override def updateDataOut(out: Long)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    for {
      _ <- redisLike.lpush(s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:out", out)
      _ <- redisLike.ltrim(s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:out", 0, 100)
      _ <- redisLike.pexpire(s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:out",
                             env.clusterConfig.worker.timeout * env.clusterConfig.worker.retries)
    } yield ()
  }

  override def dataInAndOut()(implicit ec: ExecutionContext, env: Env): Future[(Long, Long)] = {
    for {
      keysIn  <- redisLike.keys(s"${env.storageRoot}:cluster:leader:*:data:in")
      keysOut <- redisLike.keys(s"${env.storageRoot}:cluster:leader:*:data:out")
      in <- Future
             .sequence(
               keysIn.map(
                 key =>
                   redisLike.lrange(key, 0, 100).map { values =>
                     if (values.isEmpty) 0L
                     else {
                       val items = values.map { v =>
                         v.utf8String.toLong
                       }
                       val itemSize = if (items.isEmpty) 1 else items.size
                       val total    = items.fold(0L)(_ + _)
                       (total / itemSize).toLong
                     }
                 }
               )
             )
             .map(a => a.fold(0L)(_ + _) / (if (a.isEmpty) 1 else a.size))

      out <- Future
              .sequence(
                keysOut.map(
                  key =>
                    redisLike.lrange(key, 0, 100).map { values =>
                      if (values.isEmpty) 0L
                      else {
                        val items = values.map { v =>
                          v.utf8String.toLong
                        }
                        val itemSize = if (items.isEmpty) 1 else items.size
                        val total    = items.fold(0L)(_ + _)
                        (total / itemSize).toLong
                      }
                  }
                )
              )
              .map(a => a.fold(0L)(_ + _) / (if (a.isEmpty) 1 else a.size))
    } yield (in, out)
  }
}

class ClusterController(ApiAction: ApiAction, cc: ControllerComponents)(
    implicit env: Env
) extends AbstractController(cc) {

  import cluster.ClusterMode.{Leader, Off, Worker}

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  val sourceBodyParser = BodyParser("ClusterController BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  def liveCluster() = ApiAction { ctx =>
    def healthOf(member: MemberView): String = {
      val value = System.currentTimeMillis() - member.lastSeen.getMillis
      if (value < (member.timeout.toMillis / 2)) {
        "green"
      } else if (value < (3 * (member.timeout.toMillis / 4))) {
        "orange"
      } else {
        "red"
      }
    }

    env.clusterConfig.mode match {
      case Off    => NotFound(Json.obj("error" -> "Cluster API not available"))
      case Worker => NotFound(Json.obj("error" -> "Cluster API not available"))
      case Leader => {
        val every = ctx.request.getQueryString("every").map(_.toInt).getOrElse(2000)
        val source = Source
          .tick(FiniteDuration(0, TimeUnit.MILLISECONDS), FiniteDuration(every, TimeUnit.MILLISECONDS), NotUsed)
          .mapAsync(1) { _ =>
            for {
              members <- env.datastores.clusterStateDataStore.getMembers()
              inOut   <- env.datastores.clusterStateDataStore.dataInAndOut()
            } yield (members, inOut)
          }
          .recover {
            case e =>
              Cluster.logger.error("Error", e)
              (Seq.empty[MemberView], (0L, 0L))
          }
          .map {
            case (members, inOut) =>
              val payloadIn: Long  = inOut._1
              val payloadOut: Long = inOut._2
              val healths          = members.map(healthOf)
              val foundOrange      = healths.contains("orange")
              val foundRed         = healths.contains("red")
              val health           = if (foundRed) "red" else (if (foundOrange) "orange" else "green")
              Json.obj(
                "workers"    -> members.size,
                "health"     -> health,
                "payloadIn"  -> payloadIn,
                "payloadOut" -> payloadOut
              )
          }
          .map(Json.stringify)
          .map(slug => s"data: $slug\n\n")
        Ok.chunked(source).as("text/event-stream")
      }
    }
  }

  def getClusterMembers() = ApiAction.async { ctx =>
    env.clusterConfig.mode match {
      case Off    => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
      case Worker => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
      case Leader => {
        val time = Json.obj("time" -> DateTime.now().getMillis)
        env.datastores.clusterStateDataStore.getMembers().map { members =>
          Ok(JsArray(members.map(_.asJson.as[JsObject] ++ time)))
        }
      }
    }
  }

  def clearClusterMembers() = ApiAction.async { ctx =>
    env.clusterConfig.mode match {
      case Off    => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
      case Worker => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
      case Leader => {
        val time = Json.obj("time" -> DateTime.now().getMillis)
        env.datastores.clusterStateDataStore.clearMembers().map { members =>
          Ok(Json.obj("done" -> true))
        }
      }
    }
  }

  def isSessionValid(sessionId: String) = ApiAction.async { ctx =>
    env.clusterConfig.mode match {
      case Off => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
      case Worker => {
        env.clusterAgent.isSessionValid(sessionId).map {
          case Some(user) => Ok(user.toJson)
          case None       => NotFound(Json.obj("error" -> "Session not found"))
        }
      }
      case Leader => {
        Cluster.logger.trace(s"[${env.clusterConfig.mode.name}] valid session $sessionId")
        env.datastores.privateAppsUserDataStore.findById(sessionId).map {
          case Some(user) => Ok(user.toJson)
          case None       => NotFound(Json.obj("error" -> "Session not found"))
        }
      }
    }
  }

  def createSession() = ApiAction.async(parse.json) { ctx =>
    env.clusterConfig.mode match {
      case Off => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
      case Worker => {
        PrivateAppsUser.fmt.reads(ctx.request.body) match {
          case JsError(e) => FastFuture.successful(BadRequest(Json.obj("error" -> "Bad session format")))
          case JsSuccess(user, _) => {
            env.clusterAgent.createSession(user).map {
              case Some(session) => Created(session.toJson)
              case _             => InternalServerError(Json.obj("error" -> "Failed to create session on master"))
            }
          }
        }
      }
      case Leader => {
        Cluster.logger.trace(s"[${env.clusterConfig.mode.name}] creating session")
        PrivateAppsUser.fmt.reads(ctx.request.body) match {
          case JsError(e) => FastFuture.successful(BadRequest(Json.obj("error" -> "Bad session format")))
          case JsSuccess(user, _) =>
            user.save(Duration(System.currentTimeMillis() - user.expiredAt.getMillis, TimeUnit.MILLISECONDS)).map {
              session =>
                Created(session.toJson)
            }
        }
      }
    }
  }

  def updateQuotas() = ApiAction.async(sourceBodyParser) { ctx =>
    env.clusterConfig.mode match {
      case Off => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
      case Worker => {
        ctx.request.body
          .via(env.clusterConfig.gunzip())
          .via(Framing.delimiter(ByteString("\n"), 1024 * 1024))
          .mapAsync(4) { item =>
            val jsItem = Json.parse(item.utf8String)
            (jsItem \ "typ").asOpt[String] match {
              case Some("globstats") => {
                // TODO: membership + global stats ?
                FastFuture.successful(())
              }
              case Some("srvincr") => {
                val id      = (jsItem \ "srv").asOpt[String].getOrElse("--")
                val calls   = (jsItem \ "c").asOpt[Long].getOrElse(0L)
                val dataIn  = (jsItem \ "di").asOpt[Long].getOrElse(0L)
                val dataOut = (jsItem \ "do").asOpt[Long].getOrElse(0L)
                env.clusterAgent.incrementService(id, dataIn, dataOut)
                if (calls - 1 > 0) {
                  (0L to (calls - 1L)).foreach { _ =>
                    env.clusterAgent.incrementService(id, 0L, 0L)
                  }
                }
                FastFuture.successful(())
              }
              case Some("apkincr") => {
                val id        = (jsItem \ "apk").asOpt[String].getOrElse("--")
                val increment = (jsItem \ "i").asOpt[Long].getOrElse(0L)
                env.clusterAgent.incrementApi(id, increment)
                FastFuture.successful(())
              }
              case _ => FastFuture.successful(())
            }
          }
          .runWith(Sink.ignore)
          .map { _ =>
            Ok(Json.obj("done" -> true))
          }
          .recover {
            case e =>
              Cluster.logger.error("Error while updating quotas", e)
              InternalServerError(Json.obj("error" -> e.getMessage))
          }
      }
      case Leader => {
        // Cluster.logger.trace(s"[${env.clusterConfig.mode.name}] updating quotas")
        val budget: Long = ctx.request.getQueryString("budget").map(_.toLong).getOrElse(2000L)
        val start: Long  = System.currentTimeMillis()
        val bytesCounter = new AtomicLong(0L)
        env.datastores.globalConfigDataStore.singleton().flatMap { config =>
          ctx.request.body
            .map(bs => {
              bytesCounter.addAndGet(bs.size)
              bs
            })
            .via(env.clusterConfig.gunzip())
            .via(Framing.delimiter(ByteString("\n"), 1024 * 1024))
            .mapAsync(4) { item =>
              val jsItem = Json.parse(item.utf8String)
              (jsItem \ "typ").asOpt[String] match {
                case Some("globstats") => {
                  ctx.request.headers
                    .get(ClusterAgent.OtoroshiWorkerNameHeader)
                    .map { name =>
                      env.datastores.clusterStateDataStore.registerMember(
                        MemberView(
                          name = name,
                          memberType = ClusterMode.Worker,
                          location = ctx.request.headers.get(ClusterAgent.OtoroshiWorkerLocationHeader).getOrElse("--"),
                          lastSeen = DateTime.now(),
                          timeout =
                            Duration(env.clusterConfig.worker.retries * env.clusterConfig.worker.state.pollEvery,
                                     TimeUnit.MILLISECONDS),
                          stats = jsItem.as[JsObject]
                        )
                      )
                    }
                    .getOrElse(FastFuture.successful(()))
                }
                case Some("srvincr") => {
                  val id      = (jsItem \ "srv").asOpt[String].getOrElse("--")
                  val calls   = (jsItem \ "c").asOpt[Long].getOrElse(0L)
                  val dataIn  = (jsItem \ "di").asOpt[Long].getOrElse(0L)
                  val dataOut = (jsItem \ "do").asOpt[Long].getOrElse(0L)
                  env.datastores.serviceDescriptorDataStore.findById(id).flatMap {
                    case Some(_) =>
                      env.datastores.serviceDescriptorDataStore
                        .updateIncrementableMetrics(id, calls, dataIn, dataOut, config)
                    case None => FastFuture.successful(())
                  }
                }
                case Some("apkincr") => {
                  val id        = (jsItem \ "apk").asOpt[String].getOrElse("--")
                  val increment = (jsItem \ "i").asOpt[Long].getOrElse(0L)
                  env.datastores.apiKeyDataStore.findById(id).flatMap {
                    case Some(apikey) => env.datastores.apiKeyDataStore.updateQuotas(apikey, increment)
                    case None         => FastFuture.successful(())
                  }
                }
                case _ => FastFuture.successful(())
              }

            }
            .runWith(Sink.ignore)
            .andThen {
              case _ =>
                Cluster.logger.trace(s"[${env.clusterConfig.mode.name}] updated quotas (${bytesCounter.get()} b)")
                env.datastores.clusterStateDataStore.updateDataIn(bytesCounter.get())
                if ((System.currentTimeMillis() - start) > budget) {
                  Cluster.logger.warn(
                    s"[${env.clusterConfig.mode.name}] Quotas update from worker ran over time budget, maybe the datastore is slow ?"
                  )
                }
            }
            .map(_ => Ok(Json.obj("done" -> true)))
            .recover {
              case e =>
                Cluster.logger.error("Error while updating quotas", e)
                InternalServerError(Json.obj("error" -> e.getMessage))
            }
        }
      }
    }
  }

  val caching   = new AtomicBoolean(false)
  val cachedAt  = new AtomicLong(0L)
  val cachedRef = new AtomicReference[ByteString]()

  def internalState() = ApiAction { ctx =>
    env.clusterConfig.mode match {
      case Off => NotFound(Json.obj("error" -> "Cluster API not available"))
      case Worker => {
        // TODO: cluster membership
        Ok.sendEntity(
          HttpEntity.Streamed(
            env.datastores
              .rawExport(env.clusterConfig.leader.groupingBy)
              .map { item =>
                ByteString(Json.stringify(item) + "\n")
              }
              .via(env.clusterConfig.gzip()),
            None,
            Some("application/x-ndjson")
          )
        )
      }
      case Leader => {

        val budget: Long = ctx.request.getQueryString("budget").map(_.toLong).getOrElse(2000L)
        val cachedValue  = cachedRef.get()

        ctx.request.headers.get(ClusterAgent.OtoroshiWorkerNameHeader).map { name =>
          env.datastores.clusterStateDataStore.registerMember(
            MemberView(
              name = name,
              memberType = ClusterMode.Worker,
              location = ctx.request.headers.get(ClusterAgent.OtoroshiWorkerLocationHeader).getOrElse("--"),
              lastSeen = DateTime.now(),
              timeout = Duration(env.clusterConfig.worker.retries * env.clusterConfig.worker.state.pollEvery,
                                 TimeUnit.MILLISECONDS)
            )
          )
        }

        def sendAndCache() = {
          // Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] Exporting raw state")
          if (caching.compareAndSet(false, true)) {
            val start: Long = System.currentTimeMillis()
            var stateCache  = ByteString.empty
            Ok.sendEntity(
                HttpEntity.Streamed(
                  env.datastores
                    .rawExport(env.clusterConfig.leader.groupingBy)
                    .map { item =>
                      ByteString(Json.stringify(item) + "\n")
                    }
                    .via(env.clusterConfig.gzip())
                    .alsoTo(Sink.foreach(bs => stateCache = stateCache ++ bs))
                    .alsoTo(Sink.onComplete {
                      case Success(_) =>
                        if ((System.currentTimeMillis() - start) > budget) {
                          Cluster.logger.warn(
                            s"[${env.clusterConfig.mode.name}] Datastore export to worker ran over time budget, maybe the datastore is slow ?"
                          )
                        }
                        cachedRef.set(stateCache)
                        cachedAt.set(System.currentTimeMillis())
                        caching.compareAndSet(true, false)
                        env.datastores.clusterStateDataStore.updateDataOut(stateCache.size)
                        env.clusterConfig.leader.stateDumpPath
                          .foreach(path => Future(Files.write(stateCache.toArray, new File(path))))
                        Cluster.logger.debug(
                          s"[${env.clusterConfig.mode.name}] Exported raw state (${stateCache.size / 1024} Kb) in ${System.currentTimeMillis - start} ms."
                        )
                      case Failure(e) =>
                        Cluster.logger.error(s"[${env.clusterConfig.mode.name}] Stream error while exporting raw state",
                                             e)
                    }),
                  None,
                  Some("application/x-ndjson")
                )
              )
              .withHeaders(
                "X-Data-From" -> s"${System.currentTimeMillis()}"
              ) //.withHeaders("Content-Encoding" -> "gzip")
          } else {
            Cluster.logger.debug(
              s"[${env.clusterConfig.mode.name}] Sending state from cache (${cachedValue.size / 1024} Kb) ..."
            )
            Ok.sendEntity(HttpEntity.Streamed(Source.single(cachedValue), None, Some("application/x-ndjson")))
          }
        }

        if (env.clusterConfig.autoUpdateState) {
          Cluster.logger.debug(
            s"[${env.clusterConfig.mode.name}] Sending state from auto cache (${cachedValue.size / 1024} Kb) ..."
          )
          Ok.sendEntity(
              HttpEntity.Streamed(Source.single(env.clusterLeaderAgent.cachedState), None, Some("application/x-ndjson"))
            )
            .withHeaders(
              "X-Data-From" -> s"${env.clusterLeaderAgent.cachedTimestamp}"
            )
        } else if (cachedValue == null) {
          sendAndCache()
        } else if (caching.get()) {
          Cluster.logger.debug(
            s"[${env.clusterConfig.mode.name}] Sending state from cache (${cachedValue.size / 1024} Kb) ..."
          )
          Ok.sendEntity(HttpEntity.Streamed(Source.single(cachedValue), None, Some("application/x-ndjson")))
            .withHeaders(
              "X-Data-From" -> s"${cachedAt.get()}"
            )
        } else if ((cachedAt.get() + env.clusterConfig.leader.cacheStateFor) < System.currentTimeMillis()) {
          sendAndCache()
        } else {
          Cluster.logger.debug(
            s"[${env.clusterConfig.mode.name}] Sending state from cache (${cachedValue.size / 1024} Kb) ..."
          )
          Ok.sendEntity(HttpEntity.Streamed(Source.single(cachedValue), None, Some("application/x-ndjson")))
            .withHeaders(
              "X-Data-From" -> s"${cachedAt.get()}"
            )
        }
      }
    }
  }
}

object ClusterAgent {

  val OtoroshiWorkerNameHeader     = "Otoroshi-Worker-Name"
  val OtoroshiWorkerLocationHeader = "Otoroshi-Worker-Location"

  def apply(config: ClusterConfig, env: Env) = new ClusterAgent(config, env)
}

object CpuInfo {

  private val mbs      = ManagementFactory.getPlatformMBeanServer
  private val osMXBean = ManagementFactory.getOperatingSystemMXBean

  def cpuLoad(): Double = {
    val name = ObjectName.getInstance("java.lang:type=OperatingSystem")
    val list = mbs.getAttributes(name, Array("ProcessCpuLoad"))
    if (list.isEmpty) return 0.0
    val att   = list.get(0).asInstanceOf[Attribute]
    val value = att.getValue.asInstanceOf[Double]
    if (value == -1.0) return 0.0
    (value * 1000) / 10.0
  }

  def loadAverage(): Double = {
    osMXBean.getSystemLoadAverage
  }
}

object ClusterLeaderAgent {
  def apply(config: ClusterConfig, env: Env) = new ClusterLeaderAgent(config, env)
}

class ClusterLeaderAgent(config: ClusterConfig, env: Env) {
  import scala.concurrent.duration._

  implicit lazy val ec    = env.otoroshiExecutionContext
  implicit lazy val mat   = env.otoroshiMaterializer
  implicit lazy val sched = env.otoroshiScheduler
  implicit lazy val _env  = env

  private val membershipRef   = new AtomicReference[Cancellable]()
  private val stateUpdaterRef = new AtomicReference[Cancellable]()

  private val caching   = new AtomicBoolean(false)
  private val cachedAt  = new AtomicLong(0L)
  private val cachedRef = new AtomicReference[ByteString](ByteString.empty)

  private lazy val hostAddress: String = env.configuration
    .getOptional[String]("otoroshi.cluster.selfAddress")
    .getOrElse(InetAddress.getLocalHost().getHostAddress.toString)

  def renewMemberShip(): Unit = {
    (for {
      rate                      <- env.datastores.serviceDescriptorDataStore.globalCallsPerSec()
      duration                  <- env.datastores.serviceDescriptorDataStore.globalCallsDuration()
      overhead                  <- env.datastores.serviceDescriptorDataStore.globalCallsOverhead()
      dataInRate                <- env.datastores.serviceDescriptorDataStore.dataInPerSecFor("global")
      dataOutRate               <- env.datastores.serviceDescriptorDataStore.dataOutPerSecFor("global")
      concurrentHandledRequests <- env.datastores.requestsDataStore.asyncGetHandledRequests()
    } yield {
      val rt = Runtime.getRuntime
      Json.obj(
        "typ"               -> "globstats",
        "cpu_usage"         -> CpuInfo.cpuLoad(),
        "load_average"      -> CpuInfo.loadAverage(),
        "heap_used"         -> (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024,
        "heap_size"         -> rt.totalMemory() / 1024 / 1024,
        "live_threads"      -> ManagementFactory.getThreadMXBean.getThreadCount,
        "live_peak_threads" -> ManagementFactory.getThreadMXBean.getPeakThreadCount,
        "daemon_threads"    -> ManagementFactory.getThreadMXBean.getDaemonThreadCount,
        "rate" -> BigDecimal(
          Option(rate)
            .filterNot(a => a.isInfinity || a.isNaN || a.isNegInfinity || a.isPosInfinity)
            .getOrElse(0.0)
        ).setScale(3, RoundingMode.HALF_EVEN),
        "duration" -> BigDecimal(
          Option(duration)
            .filterNot(a => a.isInfinity || a.isNaN || a.isNegInfinity || a.isPosInfinity)
            .getOrElse(0.0)
        ).setScale(3, RoundingMode.HALF_EVEN),
        "overhead" -> BigDecimal(
          Option(overhead)
            .filterNot(a => a.isInfinity || a.isNaN || a.isNegInfinity || a.isPosInfinity)
            .getOrElse(0.0)
        ).setScale(3, RoundingMode.HALF_EVEN),
        "dataInRate" -> BigDecimal(
          Option(dataInRate)
            .filterNot(a => a.isInfinity || a.isNaN || a.isNegInfinity || a.isPosInfinity)
            .getOrElse(0.0)
        ).setScale(3, RoundingMode.HALF_EVEN),
        "dataOutRate" -> BigDecimal(
          Option(dataOutRate)
            .filterNot(a => a.isInfinity || a.isNaN || a.isNegInfinity || a.isPosInfinity)
            .getOrElse(0.0)
        ).setScale(3, RoundingMode.HALF_EVEN),
        "concurrentHandledRequests" -> concurrentHandledRequests
      )
    }).flatMap { stats =>
      env.datastores.clusterStateDataStore.registerMember(
        MemberView(
          name = env.clusterConfig.leader.name,
          memberType = ClusterMode.Leader,
          location = s"$hostAddress:${env.port}/${env.httpsPort}",
          lastSeen = DateTime.now(),
          timeout = 120.seconds,
          stats = stats
        )
      )
    }
  }

  def start(): Unit = {
    if (config.mode == ClusterMode.Leader) {
      Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] Starting cluster leader agent")
      membershipRef.set(
        env.otoroshiScheduler.scheduleAtFixedRate(1.second, 30.seconds)(utils.SchedulerHelper.runnable(
          try {
            renewMemberShip()
          } catch {
            case e: Throwable =>
              Cluster.logger.error(s"Error while renewing leader membership of ${env.clusterConfig.leader.name}", e)
          }
        ))
      )
      if (env.clusterConfig.autoUpdateState) {
        Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] Starting cluster state auto update")
        stateUpdaterRef.set(
          env.otoroshiScheduler.scheduleAtFixedRate(1.second, env.clusterConfig.leader.cacheStateFor.millis)(utils.SchedulerHelper.runnable(
            try {
              cacheState()
            } catch {
              case e: Throwable =>
                Cluster.logger.error(s"Error while renewing leader membership of ${env.clusterConfig.leader.name}", e)
            }
          ))
        )
      }
    }
  }
  def stop(): Unit = {
    if (config.mode == ClusterMode.Leader) {
      Option(membershipRef.get()).foreach(_.cancel())
      Option(stateUpdaterRef.get()).foreach(_.cancel())
    }
  }

  def cachedState     = cachedRef.get()
  def cachedTimestamp = cachedAt.get()

  private def cacheState(): Unit = {
    if (caching.compareAndSet(false, true)) {
      val start      = System.currentTimeMillis()
      var stateCache = ByteString.empty
      env.datastores
        .rawExport(env.clusterConfig.leader.groupingBy)
        .map { item =>
          ByteString(Json.stringify(item) + "\n")
        }
        .via(env.clusterConfig.gzip())
        .alsoTo(Sink.foreach(bs => stateCache = stateCache ++ bs))
        .alsoTo(Sink.onComplete {
          case Success(_) =>
            cachedRef.set(stateCache)
            cachedAt.set(System.currentTimeMillis())
            caching.compareAndSet(true, false)
            env.datastores.clusterStateDataStore.updateDataOut(stateCache.size)
            env.clusterConfig.leader.stateDumpPath
              .foreach(path => Future(Files.write(stateCache.toArray, new File(path))))
            Cluster.logger.debug(
              s"[${env.clusterConfig.mode.name}] Auto-cache updated in ${System.currentTimeMillis() - start} ms."
            )
          case Failure(e) =>
            Cluster.logger.error(s"[${env.clusterConfig.mode.name}] Stream error while exporting raw state", e)
        })
        .runWith(Sink.ignore)
    }
  }
}

class ClusterAgent(config: ClusterConfig, env: Env) {

  import scala.concurrent.duration._

  implicit lazy val ec    = env.otoroshiExecutionContext
  implicit lazy val mat   = env.otoroshiMaterializer
  implicit lazy val sched = env.otoroshiScheduler

  private val lastPoll                      = new AtomicReference[DateTime](DateTime.parse("1970-01-01T00:00:00.000"))
  private val pollRef                       = new AtomicReference[Cancellable]()
  private val pushRef                       = new AtomicReference[Cancellable]()
  private val counter                       = new AtomicInteger(0)
  private val isPollingState                = new AtomicBoolean(false)
  private val isPushingQuotas               = new AtomicBoolean(false)
  private val firstSuccessfulStateFetchDone = new AtomicBoolean(false)

  private lazy val hostAddress: String = env.configuration
    .getOptional[String]("otoroshi.cluster.selfAddress")
    .getOrElse(InetAddress.getLocalHost().getHostAddress.toString)

  /////////////
  private val apiIncrementsRef = new AtomicReference[TrieMap[String, AtomicLong]](new TrieMap[String, AtomicLong]())
  private val servicesIncrementsRef = new AtomicReference[TrieMap[String, (AtomicLong, AtomicLong, AtomicLong)]](
    new TrieMap[String, (AtomicLong, AtomicLong, AtomicLong)]()
  )
  /////////////

  def lastSync: DateTime = lastPoll.get()

  private def otoroshiUrl: String = {
    val count = counter.incrementAndGet() % (if (config.leader.urls.nonEmpty) config.leader.urls.size else 1)
    config.leader.urls.zipWithIndex.find(t => t._2 == count).map(_._1).getOrElse(config.leader.urls.head)
  }

  def cannotServeRequests(): Boolean = {
    !firstSuccessfulStateFetchDone.get()
  }

  def isSessionValid(id: String): Future[Option[PrivateAppsUser]] = {
    if (env.clusterConfig.mode.isWorker) {
      Retry
        .retry(times = config.worker.retries, delay = 20, ctx = "leader-session-valid") { tryCount =>
          env.MtlsWs
            .url(otoroshiUrl + s"/api/cluster/sessions/$id", config.mtlsConfig)
            .withHttpHeaders(
              "Host"                                    -> config.leader.host,
              ClusterAgent.OtoroshiWorkerNameHeader     -> config.worker.name,
              ClusterAgent.OtoroshiWorkerLocationHeader -> s"$hostAddress:${env.port}/${env.httpsPort}"
            )
            .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
            .withRequestTimeout(Duration(config.worker.timeout, TimeUnit.MILLISECONDS))
            .withMaybeProxyServer(config.proxy)
            .get()
            .filter { resp =>
              resp.ignoreIf(resp.status != 200)
              resp.status == 200
            }
            .map(resp => PrivateAppsUser.fmt.reads(Json.parse(resp.body)).asOpt)
        }
        .recover {
          case e =>
            Cluster.logger.debug(
              s"[${env.clusterConfig.mode.name}] Error while checking session with Otoroshi leader cluster"
            )
            None
        }
    } else {
      FastFuture.successful(None)
    }
  }

  def createSession(user: PrivateAppsUser): Future[Option[PrivateAppsUser]] = {
    if (env.clusterConfig.mode.isWorker) {
      Retry
        .retry(times = config.worker.retries, delay = 20, ctx = "leader-create-session") { tryCount =>
          env.MtlsWs
            .url(otoroshiUrl + s"/api/cluster/sessions", config.mtlsConfig)
            .withHttpHeaders(
              "Host"                                    -> config.leader.host,
              "Content-Type"                            -> "application/json",
              ClusterAgent.OtoroshiWorkerNameHeader     -> config.worker.name,
              ClusterAgent.OtoroshiWorkerLocationHeader -> s"$hostAddress:${env.port}/${env.httpsPort}"
            )
            .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
            .withRequestTimeout(Duration(config.worker.timeout, TimeUnit.MILLISECONDS))
            .withMaybeProxyServer(config.proxy)
            .post(user.toJson)
            .filter { resp =>
              resp.ignoreIf(resp.status != 201)
              resp.status == 201
            }
            .map(resp => PrivateAppsUser.fmt.reads(Json.parse(resp.body)).asOpt)
        }
    } else {
      FastFuture.successful(None)
    }
  }

  def incrementApi(id: String, increment: Long): Unit = {
    if (env.clusterConfig.mode == ClusterMode.Worker) {
      Cluster.logger.trace(s"[${env.clusterConfig.mode.name}] Increment API $id")
      if (!apiIncrementsRef.get().contains(id)) {
        apiIncrementsRef.get().putIfAbsent(id, new AtomicLong(0L))
      }
      apiIncrementsRef.get().get(id).foreach(_.incrementAndGet())
    }
  }

  def incrementService(id: String, dataIn: Long, dataOut: Long): Unit = {
    if (env.clusterConfig.mode == ClusterMode.Worker) {
      Cluster.logger.trace(s"[${env.clusterConfig.mode.name}] Increment Service $id")
      if (!servicesIncrementsRef.get().contains("global")) {
        servicesIncrementsRef.get().putIfAbsent("global", (new AtomicLong(0L), new AtomicLong(0L), new AtomicLong(0L)))
      }
      servicesIncrementsRef.get().get("global").foreach {
        case (calls, dataInCounter, dataOutCounter) =>
          calls.incrementAndGet()
          dataInCounter.addAndGet(dataIn)
          dataOutCounter.addAndGet(dataOut)
      }
      if (!servicesIncrementsRef.get().contains(id)) {
        servicesIncrementsRef.get().putIfAbsent(id, (new AtomicLong(0L), new AtomicLong(0L), new AtomicLong(0L)))
      }
      servicesIncrementsRef.get().get(id).foreach {
        case (calls, dataInCounter, dataOutCounter) =>
          calls.incrementAndGet()
          dataInCounter.addAndGet(dataIn)
          dataOutCounter.addAndGet(dataOut)
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

  private def pollState(): Unit = {
    try {
      if (isPollingState.compareAndSet(false, true)) {
        Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] Fetching state from Otoroshi leader cluster")
        val start = System.currentTimeMillis()
        Retry
          .retry(times = if (cannotServeRequests()) 10 else config.worker.state.retries,
                 delay = 20,
                 ctx = "leader-fetch-state") { tryCount =>
            env.MtlsWs
              .url(otoroshiUrl + s"/api/cluster/state?budget=${config.worker.state.timeout}", config.mtlsConfig)
              .withHttpHeaders(
                "Host"   -> config.leader.host,
                "Accept" -> "application/x-ndjson",
                // "Accept-Encoding" -> "gzip",
                ClusterAgent.OtoroshiWorkerNameHeader     -> config.worker.name,
                ClusterAgent.OtoroshiWorkerLocationHeader -> s"$hostAddress:${env.port}/${env.httpsPort}"
              )
              .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
              .withRequestTimeout(Duration(config.worker.state.timeout, TimeUnit.MILLISECONDS))
              .withMaybeProxyServer(config.proxy)
              .withMethod("GET")
              .stream()
              .filter { resp =>
                resp.ignoreIf(resp.status != 200)
                resp.status == 200
              }
              .flatMap { resp =>
                val store       = new ConcurrentHashMap[String, Any]()
                val expirations = new ConcurrentHashMap[String, Long]()
                resp.bodyAsSource
                  .via(env.clusterConfig.gunzip())
                  .via(Framing.delimiter(ByteString("\n"), 1024 * 1024))
                  .runWith(Sink.foreach { bs =>
                    val item  = Json.parse(bs.utf8String)
                    val key   = (item \ "k").as[String]
                    val value = (item \ "v").as[JsValue]
                    val what  = (item \ "w").as[String]
                    val ttl   = (item \ "t").asOpt[Long].getOrElse(-1L)
                    fromJson(what, value).foreach(v => store.put(key, v))
                    if (ttl > -1L) {
                      expirations.put(key, ttl)
                    }
                  })
                  .map { _ =>
                    Cluster.logger.debug(
                      s"[${env.clusterConfig.mode.name}] Consumed state in ${System.currentTimeMillis() - start} ms at try $tryCount."
                    )
                    lastPoll.set(DateTime.now())
                    if (!store.isEmpty) {
                      firstSuccessfulStateFetchDone.compareAndSet(false, true)
                      env.datastores.asInstanceOf[SwappableInMemoryDataStores].swap(Memory(store, expirations))
                    }
                  }
              }
          }
          .recover {
            case e =>
              Cluster.logger.error(
                s"[${env.clusterConfig.mode.name}] Error while trying to fetch state from Otoroshi leader cluster",
                e
              )
          }
          .andThen {
            case _ => isPollingState.compareAndSet(true, false)
          }
      } else {
        Cluster.logger.debug(
          s"[${env.clusterConfig.mode.name}] Still fetching state from Otoroshi leader cluster, retying later ..."
        )
      }
    } catch {
      case e: Throwable =>
        isPollingState.compareAndSet(true, false)
        Cluster.logger.error(s"Error while polling state from leader", e)
    }
  }

  private def pushQuotas(): Unit = {
    try {
      implicit val _env = env
      if (isPushingQuotas.compareAndSet(false, true)) {
        val oldApiIncr = apiIncrementsRef.getAndSet(new TrieMap[String, AtomicLong]())
        val oldServiceIncr =
          servicesIncrementsRef.getAndSet(new TrieMap[String, (AtomicLong, AtomicLong, AtomicLong)]())
        //if (oldApiIncr.nonEmpty || oldServiceIncr.nonEmpty) {
        val start = System.currentTimeMillis()
        Retry
          .retry(times = if (cannotServeRequests()) 10 else config.worker.quotas.retries,
                 delay = 20,
                 ctx = "leader-push-quotas") { tryCount =>
            Cluster.logger.trace(
              s"[${env.clusterConfig.mode.name}] Pushing api quotas updates to Otoroshi leader cluster"
            )
            val rt = Runtime.getRuntime
            (for {
              rate                      <- env.datastores.serviceDescriptorDataStore.globalCallsPerSec()
              duration                  <- env.datastores.serviceDescriptorDataStore.globalCallsDuration()
              overhead                  <- env.datastores.serviceDescriptorDataStore.globalCallsOverhead()
              dataInRate                <- env.datastores.serviceDescriptorDataStore.dataInPerSecFor("global")
              dataOutRate               <- env.datastores.serviceDescriptorDataStore.dataOutPerSecFor("global")
              concurrentHandledRequests <- env.datastores.requestsDataStore.asyncGetHandledRequests()
            } yield
              ByteString(
                Json.stringify(
                  Json.obj(
                    "typ"               -> "globstats",
                    "cpu_usage"         -> CpuInfo.cpuLoad(),
                    "load_average"      -> CpuInfo.loadAverage(),
                    "heap_used"         -> (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024,
                    "heap_size"         -> rt.totalMemory() / 1024 / 1024,
                    "live_threads"      -> ManagementFactory.getThreadMXBean.getThreadCount,
                    "live_peak_threads" -> ManagementFactory.getThreadMXBean.getPeakThreadCount,
                    "daemon_threads"    -> ManagementFactory.getThreadMXBean.getDaemonThreadCount,
                    "rate" -> BigDecimal(
                      Option(rate)
                        .filterNot(a => a.isInfinity || a.isNaN || a.isNegInfinity || a.isPosInfinity)
                        .getOrElse(0.0)
                    ).setScale(3, RoundingMode.HALF_EVEN),
                    "duration" -> BigDecimal(
                      Option(duration)
                        .filterNot(a => a.isInfinity || a.isNaN || a.isNegInfinity || a.isPosInfinity)
                        .getOrElse(0.0)
                    ).setScale(3, RoundingMode.HALF_EVEN),
                    "overhead" -> BigDecimal(
                      Option(overhead)
                        .filterNot(a => a.isInfinity || a.isNaN || a.isNegInfinity || a.isPosInfinity)
                        .getOrElse(0.0)
                    ).setScale(3, RoundingMode.HALF_EVEN),
                    "dataInRate" -> BigDecimal(
                      Option(dataInRate)
                        .filterNot(a => a.isInfinity || a.isNaN || a.isNegInfinity || a.isPosInfinity)
                        .getOrElse(0.0)
                    ).setScale(3, RoundingMode.HALF_EVEN),
                    "dataOutRate" -> BigDecimal(
                      Option(dataOutRate)
                        .filterNot(a => a.isInfinity || a.isNaN || a.isNegInfinity || a.isPosInfinity)
                        .getOrElse(0.0)
                    ).setScale(3, RoundingMode.HALF_EVEN),
                    "concurrentHandledRequests" -> concurrentHandledRequests
                  )
                ) + "\n"
              )) flatMap { stats =>
              val apiIncrSource = Source(oldApiIncr.toList.map {
                case (key, inc) =>
                  ByteString(Json.stringify(Json.obj("typ" -> "apkincr", "apk" -> key, "i" -> inc.get())) + "\n")
              })
              val serviceIncrSource = Source(oldServiceIncr.toList.map {
                case (key, (calls, dataIn, dataOut)) =>
                  ByteString(
                    Json.stringify(
                      Json.obj("typ" -> "srvincr",
                               "srv" -> key,
                               "c"   -> calls.get(),
                               "di"  -> dataIn.get(),
                               "do"  -> dataOut.get())
                    ) + "\n"
                  )
              })
              val globalSource = Source.single(stats)
              val body         = apiIncrSource.concat(serviceIncrSource).concat(globalSource).via(env.clusterConfig.gzip())
              val wsBody       = SourceBody(body)
              env.MtlsWs
                .url(otoroshiUrl + s"/api/cluster/quotas?budget=${config.worker.quotas.timeout}", config.mtlsConfig)
                .withHttpHeaders(
                  "Host"         -> config.leader.host,
                  "Content-Type" -> "application/x-ndjson",
                  // "Content-Encoding" -> "gzip",
                  ClusterAgent.OtoroshiWorkerNameHeader     -> config.worker.name,
                  ClusterAgent.OtoroshiWorkerLocationHeader -> s"$hostAddress:${env.port}/${env.httpsPort}"
                )
                .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
                .withRequestTimeout(Duration(config.worker.quotas.timeout, TimeUnit.MILLISECONDS))
                .withMaybeProxyServer(config.proxy)
                .withMethod("PUT")
                .withBody(wsBody)
                .stream()
                .filter { resp =>
                  resp.ignore()
                  resp.status == 200
                }
                .andThen {
                  case Success(_) =>
                    Cluster.logger.debug(
                      s"[${env.clusterConfig.mode.name}] Pushed quotas in ${System.currentTimeMillis() - start} ms at try $tryCount."
                    )
                  case Failure(e) => e.printStackTrace()
                }
            }
          }
          .recover {
            case e =>
              e.printStackTrace()
              oldApiIncr.foreach {
                case (key, c) => apiIncrementsRef.get().getOrElseUpdate(key, new AtomicLong(0L)).addAndGet(c.get())
              }
              oldServiceIncr.foreach {
                case (key, (counter1, counter2, counter3)) =>
                  val (c1, c2, c3) = servicesIncrementsRef
                    .get()
                    .getOrElseUpdate(key, (new AtomicLong(0L), new AtomicLong(0L), new AtomicLong(0L)))
                  c1.addAndGet(counter1.get())
                  c2.addAndGet(counter2.get())
                  c3.addAndGet(counter3.get())
              }

              Cluster.logger.error(
                s"[${env.clusterConfig.mode.name}] Error while trying to push api quotas updates to Otoroshi leader cluster",
                e
              )
          }
          .andThen {
            case _ => isPushingQuotas.compareAndSet(true, false)
          }
        //} else {
        //  isPushingQuotas.compareAndSet(true, false)
        //}
      } else {
        Cluster.logger.debug(
          s"[${env.clusterConfig.mode.name}] Still pushing api quotas updates to Otoroshi leader cluster, retying later ..."
        )
      }
    } catch {
      case e: Throwable =>
        isPushingQuotas.compareAndSet(true, false)
        Cluster.logger.error(s"Error while pushing quotas to leader", e)
    }
  }

  def warnAboutHttpLeaderUrls(): Unit = {
    if (env.clusterConfig.mode == ClusterMode.Worker) {
      config.leader.urls.filter(_.toLowerCase.contains("http://")) foreach {
        case url => Cluster.logger.warn(s"A leader url uses unsecured transport ($url), you should use https instead")
      }
    }
  }

  def startF(): Future[Unit] = FastFuture.successful(start())

  def start(): Unit = {
    if (config.mode == ClusterMode.Worker) {
      Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] Starting cluster agent")
      pollRef.set(
        env.otoroshiScheduler.scheduleAtFixedRate(1.second, config.worker.state.pollEvery.millis)(utils.SchedulerHelper.runnable(
          pollState()
        ))
      )
      pushRef.set(
        env.otoroshiScheduler.scheduleAtFixedRate(1.second, config.worker.quotas.pushEvery.millis)(utils.SchedulerHelper.runnable(
          pushQuotas()
        ))
      )
    }
  }
  def stop(): Unit = {
    if (config.mode == ClusterMode.Worker) {
      Option(pollRef.get()).foreach(_.cancel())
      Option(pushRef.get()).foreach(_.cancel())
    }
  }
}

class SwappableInMemoryDataStores(configuration: Configuration,
                                  environment: Environment,
                                  lifecycle: ApplicationLifecycle,
                                  env: Env)
    extends DataStores {

  import scala.concurrent.duration._
  import scala.util.hashing.MurmurHash3
  import akka.stream.Materializer

  lazy val redisStatsItems: Int  = configuration.get[Option[Int]]("app.inmemory.windowSize").getOrElse(99)
  lazy val experimental: Boolean = configuration.get[Option[Boolean]]("app.inmemory.experimental").getOrElse(false)
  lazy val actorSystem =
    ActorSystem(
      "otoroshi-inmemory-system",
      configuration
        .getOptional[Configuration]("app.actorsystems.datastore")
        .map(_.underlying)
        .getOrElse(ConfigFactory.empty)
    )
  private val materializer = Materializer(actorSystem)
  lazy val redis           = new SwappableInMemoryRedis(env, actorSystem)

  override def before(configuration: Configuration,
                      environment: Environment,
                      lifecycle: ApplicationLifecycle): Future[Unit] = {
    import collection.JavaConverters._
    Cluster.logger.info("Now using Swappable InMemory DataStores")
    dbPathOpt.foreach { dbPath =>
      val file = new File(dbPath)
      if (!file.exists()) {
        Cluster.logger.info(s"Creating ClusterDb file and directory ('$dbPath')")
        file.getParentFile.mkdirs()
        file.createNewFile()
      }
      readStateFromDisk(java.nio.file.Files.readAllLines(file.toPath).asScala.toSeq)
      cancelRef.set(actorSystem.scheduler.scheduleAtFixedRate(1.second, 5.seconds)(utils.SchedulerHelper.runnable(
        Await.result(writeStateToDisk(dbPath)(actorSystem.dispatcher, materializer), 10.seconds)
      ))(actorSystem.dispatcher))
    }
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
    cancelRef.get().cancel()
    dbPathOpt.foreach { dbPath =>
      Await.result(writeStateToDisk(dbPath)(actorSystem.dispatcher, materializer), 10.seconds)
    }
    actorSystem.terminate()
    FastFuture.successful(())
  }

  def swap(memory: Memory): Unit = {
    redis.swap(memory)
  }

  private val cancelRef                 = new AtomicReference[Cancellable]()
  private val lastHash                  = new AtomicReference[Int](0)
  private val dbPathOpt: Option[String] = env.clusterConfig.worker.dbPath

  private def readStateFromDisk(source: Seq[String]): Unit = {
    Cluster.logger.debug("Reading state from disk ...")
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

  private def writeStateToDisk(dbPath: String)(implicit ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    val file = new File(dbPath)
    completeExport(100)(ec, mat, env)
      .map { item =>
        Json.stringify(item) + "\n"
      }
      .runFold("")(_ + _)
      .map { content =>
        val hash = MurmurHash3.stringHash(content)
        if (hash != lastHash.get()) {
          Cluster.logger.debug("Writing state to disk ...")
          java.nio.file.Files.write(file.toPath, content.getBytes(com.google.common.base.Charsets.UTF_8))
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
  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = FastFuture.successful(Healthy)
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
            val what  = (json \ "what").as[String]
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
