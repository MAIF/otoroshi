package cluster

import java.io.File
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong, AtomicReference}
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.regex.Pattern

import actions.ApiAction
import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
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
import models._
import org.joda.time.DateTime
import play.api.http.HttpEntity
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.libs.ws.{SourceBody, WSAuthScheme}
import play.api.mvc.{AbstractController, BodyParser, ControllerComponents}
import play.api.{Configuration, Environment, Logger}
import redis.RedisClientMasterSlaves
import security.IdGenerator
import ssl.{CertificateDataStore, ClientCertificateValidationDataStore, InMemoryClientCertificateValidationDataStore}
import storage.inmemory._
import storage.{DataStoreHealth, DataStores, Healthy, RedisLike}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.math.BigDecimal.RoundingMode
import scala.util.{Failure, Success, Try}

/**
 * # TODO:
 *
 * [ ] support cassandra
 * [ ] support mongo
 *
 * # Test
 *
 * java -Dhttp.port=8080 -Dhttps.port=8443 -Dotoroshi.cluster.mode=leader -jar otoroshi.jar
 * java -Dhttp.port=9080 -Dhttps.port=9443 -Dotoroshi.cluster.mode=worker -jar otoroshi.jar
 *
 */
object Cluster {
  lazy val logger = Logger("otoroshi-cluster")
}

trait ClusterMode {
  def name: String
  def clusterActive: Boolean
  def isWorker: Boolean
  def isLeader: Boolean
}

object ClusterMode {
  case object Off extends ClusterMode {
    def name: String           = "Off"
    def clusterActive: Boolean = false
    def isWorker: Boolean      = false
    def isLeader: Boolean      = false
  }
  case object Leader extends ClusterMode {
    def name: String           = "Leader"
    def clusterActive: Boolean = true
    def isWorker: Boolean      = false
    def isLeader: Boolean      = true
  }
  case object Worker extends ClusterMode {
    def name: String           = "Worker"
    def clusterActive: Boolean = true
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
    state: WorkerStateConfig = WorkerStateConfig(),
    quotas: WorkerQuotasConfig = WorkerQuotasConfig()
)
case class LeaderConfig(
    name: String = s"otoroshi-leader-${IdGenerator.token(16)}",
    urls: Seq[String] = Seq.empty,
    host: String = "otoroshi-api.foo.bar",
    clientId: String = "admin-api-apikey-id",
    clientSecret: String = "admin-api-apikey-secret",
    groupingBy: Int = 50,
    cacheStateFor: Long = 4000,
    stateDumpPath: Option[String] = None
)
case class ClusterConfig(
    mode: ClusterMode = ClusterMode.Off,
    compression: Int = -1,
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
      leader = LeaderConfig(
        name = configuration
          .getOptional[String]("leader.name")
          .orElse(Option(System.getenv("INSTANCE_NUMBER")).map(i => s"otoroshi-leader-$i"))
          .getOrElse(s"otoroshi-leader-${IdGenerator.token(16)}"),
        urls = configuration
          .getOptional[Seq[String]]("leader.urls")
          .map(_.toSeq)
          .getOrElse(Seq("http://otoroshi-api.foo.bar:8080")),
        host = configuration.getOptional[String]("leader.host").getOrElse("otoroshi-api.foo.bar"),
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
                      stats: JsObject = Json.obj()) {
  def asJson: JsValue = Json.obj(
    "name"     -> name,
    "location" -> location,
    "lastSeen" -> lastSeen.getMillis,
    "timeout"  -> timeout.toMillis,
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
          stats = (value \ "stats").asOpt[JsObject].getOrElse(Json.obj())
        )
      )
    } recover {
      case e => JsError(e.getMessage)
    } get
}

trait ClusterStateDataStore {
  def registerWorkerMember(member: MemberView)(implicit ec: ExecutionContext, env: Env): Future[Unit]
  def getMembers()(implicit ec: ExecutionContext, env: Env): Future[Seq[MemberView]]
  def clearMembers()(implicit ec: ExecutionContext, env: Env): Future[Long]
  def updateDataIn(in: Long)(implicit ec: ExecutionContext, env: Env): Future[Unit]
  def updateDataOut(out: Long)(implicit ec: ExecutionContext, env: Env): Future[Unit]
  def dataInAndOut()(implicit ec: ExecutionContext, env: Env): Future[(Long, Long)]
}

class InMemoryClusterStateDataStore(redisLike: RedisLike, env: Env) extends ClusterStateDataStore {

  override def clearMembers()(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    redisLike
      .keys(s"${env.storageRoot}:cluster:workers:*")
      .flatMap(
        keys =>
          if (keys.isEmpty) FastFuture.successful(0L)
          else redisLike.del(keys: _*)
      )
  }

  override def registerWorkerMember(member: MemberView)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    val key = s"${env.storageRoot}:cluster:workers:${member.name}"
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
        .keys(s"${env.storageRoot}:cluster:workers:*")
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
      .keys(s"${env.storageRoot}:cluster:workers:*")
      .flatMap(
        keys =>
          if (keys.isEmpty) FastFuture.successful(0L)
          else redisLike.del(keys: _*)
      )
  }

  override def registerWorkerMember(member: MemberView)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    val key = s"${env.storageRoot}:cluster:workers:${member.name}"
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
        .keys(s"${env.storageRoot}:cluster:workers:*")
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
                       val total = items.fold(0L)(_ + _)
                       (total / items.size).toLong
                     }
                 }
               )
             )
             .map(a => a.fold(0L)(_ + _) / a.size)

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
                        val total = items.fold(0L)(_ + _)
                        (total / items.size).toLong
                      }
                  }
                )
              )
              .map(a => a.fold(0L)(_ + _) / a.size)
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
      case Off    => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
      case Worker => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
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
      case Off    => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
      case Worker => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
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
      case Off    => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
      case Worker => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
      case Leader => {
        // Cluster.logger.trace(s"[${env.clusterConfig.mode.name}] updating quotas")
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
                      env.datastores.clusterStateDataStore.registerWorkerMember(
                        MemberView(
                          name = name,
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
      case Off    => NotFound(Json.obj("error" -> "Cluster API not available"))
      case Worker => NotFound(Json.obj("error" -> "Cluster API not available"))
      case Leader => {

        val cachedValue = cachedRef.get()

        ctx.request.headers.get(ClusterAgent.OtoroshiWorkerNameHeader).map { name =>
          env.datastores.clusterStateDataStore.registerWorkerMember(
            MemberView(
              name = name,
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
            val start      = System.currentTimeMillis()
            var stateCache = ByteString.empty
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
            ) //.withHeaders("Content-Encoding" -> "gzip")
          } else {
            Cluster.logger.debug(
              s"[${env.clusterConfig.mode.name}] Sending state from cache (${cachedValue.size / 1024} Kb) ..."
            )
            Ok.sendEntity(HttpEntity.Streamed(Source.single(cachedValue), None, Some("application/x-ndjson")))
          }
        }

        if (cachedValue == null) {
          sendAndCache()
        } else if (caching.get()) {
          Cluster.logger.debug(
            s"[${env.clusterConfig.mode.name}] Sending state from cache (${cachedValue.size / 1024} Kb) ..."
          )
          Ok.sendEntity(HttpEntity.Streamed(Source.single(cachedValue), None, Some("application/x-ndjson")))
        } else if ((cachedAt.get() + env.clusterConfig.leader.cacheStateFor) < System.currentTimeMillis()) {
          sendAndCache()
        } else {
          Cluster.logger.debug(
            s"[${env.clusterConfig.mode.name}] Sending state from cache (${cachedValue.size / 1024} Kb) ..."
          )
          Ok.sendEntity(HttpEntity.Streamed(Source.single(cachedValue), None, Some("application/x-ndjson")))
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

class ClusterAgent(config: ClusterConfig, env: Env) {

  import scala.concurrent.duration._

  implicit lazy val ec    = env.otoroshiExecutionContext
  implicit lazy val mat   = env.otoroshiMaterializer
  implicit lazy val sched = env.otoroshiScheduler

  private val pollRef                       = new AtomicReference[Cancellable]()
  private val pushRef                       = new AtomicReference[Cancellable]()
  private val counter                       = new AtomicInteger(0)
  private val isPollingState                = new AtomicBoolean(false)
  private val isPushingQuotas               = new AtomicBoolean(false)
  private val firstSuccessfulStateFetchDone = new AtomicBoolean(false)

  /////////////
  private val apiIncrementsRef = new AtomicReference[TrieMap[String, AtomicLong]](new TrieMap[String, AtomicLong]())
  private val servicesIncrementsRef = new AtomicReference[TrieMap[String, (AtomicLong, AtomicLong, AtomicLong)]](
    new TrieMap[String, (AtomicLong, AtomicLong, AtomicLong)]()
  )
  /////////////

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
          env.Ws
            .url(otoroshiUrl + s"/api/cluster/sessions/$id")
            .withHttpHeaders(
              "Host"                                    -> config.leader.host,
              ClusterAgent.OtoroshiWorkerNameHeader     -> config.worker.name,
              ClusterAgent.OtoroshiWorkerLocationHeader -> s"${InetAddress.getLocalHost().getHostAddress()}:${env.port}/${env.httpsPort}"
            )
            .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
            .withRequestTimeout(Duration(config.worker.timeout, TimeUnit.MILLISECONDS))
            .get()
            .filter(_.status == 200)
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

  def createSession(user: PrivateAppsUser): Future[Unit] = {
    Retry
      .retry(times = config.worker.retries, delay = 20, ctx = "leader-create-session") { tryCount =>
        env.Ws
          .url(otoroshiUrl + s"/api/cluster/sessions")
          .withHttpHeaders(
            "Host"                                    -> config.leader.host,
            "Content-Type"                            -> "application/json",
            ClusterAgent.OtoroshiWorkerNameHeader     -> config.worker.name,
            ClusterAgent.OtoroshiWorkerLocationHeader -> s"${InetAddress.getLocalHost().getHostAddress()}:${env.port}/${env.httpsPort}"
          )
          .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
          .withRequestTimeout(Duration(config.worker.timeout, TimeUnit.MILLISECONDS))
          .post(user.toJson)
          .filter(_.status == 201)
      }
      .map(_ => ())
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
    if (isPollingState.compareAndSet(false, true)) {
      Cluster.logger.trace(s"[${env.clusterConfig.mode.name}] Fetching state from Otoroshi leader cluster")
      val start = System.currentTimeMillis()
      Retry
        .retry(times = if (cannotServeRequests()) 10 else config.worker.state.retries,
               delay = 20,
               ctx = "leader-fetch-state") { tryCount =>
          env.Ws
            .url(otoroshiUrl + "/api/cluster/state")
            .withHttpHeaders(
              "Host"   -> config.leader.host,
              "Accept" -> "application/x-ndjson",
              //"Accept-Encoding" -> "gzip",
              ClusterAgent.OtoroshiWorkerNameHeader     -> config.worker.name,
              ClusterAgent.OtoroshiWorkerLocationHeader -> s"${InetAddress.getLocalHost().getHostAddress}:${env.port}/${env.httpsPort}"
            )
            .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
            .withRequestTimeout(Duration(config.worker.state.timeout, TimeUnit.MILLISECONDS))
            .withMethod("GET")
            .stream()
            .filter(_.status == 200)
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
  }

  private def pushQuotas(): Unit = {
    implicit val _env = env
    if (isPushingQuotas.compareAndSet(false, true)) {
      val oldApiIncr     = apiIncrementsRef.getAndSet(new TrieMap[String, AtomicLong]())
      val oldServiceIncr = servicesIncrementsRef.getAndSet(new TrieMap[String, (AtomicLong, AtomicLong, AtomicLong)]())
      //if (oldApiIncr.nonEmpty || oldServiceIncr.nonEmpty) {
      val start = System.currentTimeMillis()
      Retry
        .retry(times = if (cannotServeRequests()) 10 else config.worker.state.retries,
               delay = 20,
               ctx = "leader-push-quotas") { tryCount =>
          Cluster.logger.trace(
            s"[${env.clusterConfig.mode.name}] Pushing api quotas updates to Otoroshi leader cluster"
          )
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
                  "typ" -> "globstats",
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
            env.Ws
              .url(otoroshiUrl + "/api/cluster/quotas")
              .withHttpHeaders(
                "Host"         -> config.leader.host,
                "Content-Type" -> "application/x-ndjson",
                // "Content-Encoding" -> "gzip",
                ClusterAgent.OtoroshiWorkerNameHeader     -> config.worker.name,
                ClusterAgent.OtoroshiWorkerLocationHeader -> s"${InetAddress.getLocalHost().getHostAddress()}:${env.port}/${env.httpsPort}"
              )
              .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
              .withRequestTimeout(Duration(config.worker.quotas.timeout, TimeUnit.MILLISECONDS))
              .withMethod("PUT")
              .withBody(wsBody)
              .stream()
              .filter(_.status == 200)
              .andThen {
                case Success(_) =>
                  Cluster.logger.debug(
                    s"[${env.clusterConfig.mode.name}] Pushed quotas in ${System.currentTimeMillis() - start} ms at try $tryCount."
                  )
              }
          }
        }
        .recover {
          case e =>
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
  }

  def startF(): Future[Unit] = FastFuture.successful(start())

  def start(): Unit = {
    if (config.mode == ClusterMode.Worker) {
      Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] Starting cluster agent")
      config.leader.urls.filter(_.toLowerCase.startsWith("http://")) foreach {
        case url => Cluster.logger.warn(s"You have a leader url that uses unsecured http protocol, you should use https instead: $url")
      }
      pollRef.set(env.otoroshiScheduler.schedule(1.second, config.worker.state.pollEvery.millis)(pollState()))
      pushRef.set(env.otoroshiScheduler.schedule(1.second, config.worker.quotas.pushEvery.millis)(pushQuotas()))
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
  lazy val redis: SwappableInMemoryRedis = new SwappableInMemoryRedis(env, actorSystem)

  override def before(configuration: Configuration,
                      environment: Environment,
                      lifecycle: ApplicationLifecycle): Future[Unit] = {
    Cluster.logger.info("Now using Swappable InMemory DataStores")
    redis.start()
    FastFuture.successful(())
  }

  override def after(configuration: Configuration,
                     environment: Environment,
                     lifecycle: ApplicationLifecycle): Future[Unit] = {
    redis.stop()
    actorSystem.terminate()
    FastFuture.successful(())
  }

  def swap(memory: Memory): Unit = {
    redis.swap(memory)
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

  private lazy val _clientCertificateValidationDataStore                   = new InMemoryClientCertificateValidationDataStore(redis, env)
  override def clientCertificateValidationDataStore: ClientCertificateValidationDataStore = _clientCertificateValidationDataStore

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
  )(implicit ec: ExecutionContext, mat: Materializer, env: Env): Source[JsValue, NotUsed] =
    throw new RuntimeException("Worker do not have to raw export !")
}

class Memory(
    val store: ConcurrentHashMap[String, Any],
    val expirations: ConcurrentHashMap[String, Long]
)

object Memory {
  def apply(store: ConcurrentHashMap[String, Any], expirations: ConcurrentHashMap[String, Long]) =
    new Memory(store, expirations)
}

class SwappableInMemoryRedis(env: Env, actorSystem: ActorSystem) extends RedisLike {

  import actorSystem.dispatcher

  import collection.JavaConverters._
  import scala.concurrent.duration._

  val patterns: ConcurrentHashMap[String, Pattern] = new ConcurrentHashMap[String, Pattern]()

  private lazy val _storeHolder = new AtomicReference[Memory](
    Memory(
      store = new ConcurrentHashMap[String, Any],
      expirations = new ConcurrentHashMap[String, Long]
    )
  )

  @inline private def store: ConcurrentHashMap[String, Any]        = _storeHolder.get().store
  @inline private def expirations: ConcurrentHashMap[String, Long] = _storeHolder.get().expirations

  private val cancel = actorSystem.scheduler.schedule(0.millis, 100.millis) {
    val time = System.currentTimeMillis()
    expirations.entrySet().asScala.foreach { entry =>
      if (entry.getValue < time) {
        store.remove(entry.getKey)
        expirations.remove(entry.getKey)
      }
    }
    ()
  }

  def swap(memory: Memory): Unit = {
    val oldSize = store.keySet.size
    _storeHolder.updateAndGet(_ => memory)
    val newSize = store.keySet.size
    Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] Swapping store instance now ! ($oldSize / $newSize)")
  }

  override def stop(): Unit =
    cancel.cancel()

  override def flushall(): Future[Boolean] = {
    store.clear()
    expirations.clear()
    FastFuture.successful(true)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def get(key: String): Future[Option[ByteString]] = {
    val value = Option(store.get(key)).map(_.asInstanceOf[ByteString])
    FastFuture.successful(value)
  }

  override def set(key: String,
                   value: String,
                   exSeconds: Option[Long] = None,
                   pxMilliseconds: Option[Long] = None): Future[Boolean] =
    setBS(key, ByteString(value), exSeconds, pxMilliseconds)

  override def setBS(key: String,
                     value: ByteString,
                     exSeconds: Option[Long] = None,
                     pxMilliseconds: Option[Long] = None): Future[Boolean] = {
    store.put(key, value)
    if (exSeconds.isDefined) {
      expire(key, exSeconds.get.toInt)
    }
    if (pxMilliseconds.isDefined) {
      pexpire(key, pxMilliseconds.get)
    }
    FastFuture.successful(true)
  }

  override def del(keys: String*): Future[Long] = {
    val value = keys
      .map { k =>
        store.remove(k)
        1L
      }
      .foldLeft(0L)((a, b) => a + b)
    FastFuture.successful(value)
  }

  override def incr(key: String): Future[Long] = incrby(key, 1L)

  override def incrby(key: String, increment: Long): Future[Long] = {
    val value: Long    = Option(store.get(key)).map(_.asInstanceOf[ByteString]).map(_.utf8String.toLong).getOrElse(0L)
    val newValue: Long = value + increment
    store.put(key, ByteString(newValue.toString))
    FastFuture.successful(newValue)
  }

  override def exists(key: String): Future[Boolean] = FastFuture.successful(store.containsKey(key))

  override def mget(keys: String*): Future[Seq[Option[ByteString]]] =
    FastFuture.sequence(keys.map(k => get(k)))

  override def keys(pattern: String): Future[Seq[String]] = {
    val pat = patterns.computeIfAbsent(pattern, _ => Pattern.compile(pattern.replaceAll("\\*", ".*")))
    FastFuture.successful(
      store
        .keySet()
        .asScala
        .filter { k =>
          pat.matcher(k).find
        }
        .toSeq
    )
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def hdel(key: String, fields: String*): Future[Long] = {
    val hash = if (!store.containsKey(key)) {
      new ConcurrentHashMap[String, ByteString]()
    } else {
      store.get(key).asInstanceOf[ConcurrentHashMap[String, ByteString]]
    }
    val value = hash
      .keySet()
      .asScala
      .filter(k => fields.contains(k))
      .map(k => {
        hash.remove(k)
        1L
      })
      .foldLeft(0L)(_ + _)
    FastFuture.successful(value)
  }

  override def hgetall(key: String): Future[Map[String, ByteString]] = {
    val hash = if (!store.containsKey(key)) {
      new ConcurrentHashMap[String, ByteString]()
    } else {
      store.get(key).asInstanceOf[ConcurrentHashMap[String, ByteString]]
    }
    FastFuture.successful(hash.asScala.toMap)
  }

  override def hset(key: String, field: String, value: String): Future[Boolean] = hsetBS(key, field, ByteString(value))

  override def hsetBS(key: String, field: String, value: ByteString): Future[Boolean] = {
    val hash = if (!store.containsKey(key)) {
      new ConcurrentHashMap[String, ByteString]()
    } else {
      store.get(key).asInstanceOf[ConcurrentHashMap[String, ByteString]]
    }
    hash.put(field, value)
    store.put(key, hash)
    FastFuture.successful(true)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def emptySeq(): java.util.List[ByteString] =
    new java.util.concurrent.CopyOnWriteArrayList[ByteString]

  override def llen(key: String): Future[Long] = {
    val value = Option(store.get(key)).map(_.asInstanceOf[Seq[ByteString]]).getOrElse(Seq.empty[ByteString]).size.toLong
    FastFuture.successful(value)
  }

  override def lpush(key: String, values: String*): Future[Long] = lpushBS(key, values.map(ByteString.apply): _*)

  override def lpushLong(key: String, values: Long*): Future[Long] =
    lpushBS(key, values.map(_.toString).map(ByteString.apply): _*)

  override def lpushBS(key: String, values: ByteString*): Future[Long] = {
    if (!store.containsKey(key)) {
      store.putIfAbsent(key, emptySeq())
    }
    val seq = store.get(key).asInstanceOf[java.util.List[ByteString]]
    seq.addAll(0, values.asJava)
    FastFuture.successful(values.size.toLong)
  }

  override def lrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]] = {
    val seq    = Option(store.get(key)).map(_.asInstanceOf[java.util.List[ByteString]]).getOrElse(emptySeq())
    val result = seq.asScala.slice(start.toInt, stop.toInt - start.toInt)
    FastFuture.successful(result)
  }

  override def ltrim(key: String, start: Long, stop: Long): Future[Boolean] = {
    if (!store.containsKey(key)) {
      store.putIfAbsent(key, emptySeq())
    }
    val seq    = store.get(key).asInstanceOf[java.util.List[ByteString]]
    val result = seq.asScala.slice(start.toInt, stop.toInt - start.toInt).asJava
    store.put(key, new java.util.concurrent.CopyOnWriteArrayList[ByteString](result))
    FastFuture.successful(true)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def pttl(key: String): Future[Long] =
    FastFuture.successful(
      Option(expirations.get(key))
        .map(e => {
          val ttlValue = e - System.currentTimeMillis()
          if (ttlValue < 0) -1L else ttlValue
        })
        .getOrElse(-1L)
    )

  override def ttl(key: String): Future[Long] =
    pttl(key).map(t => Duration(t, TimeUnit.MILLISECONDS).toSeconds)

  override def expire(key: String, seconds: Int): Future[Boolean] = {
    expirations.put(key, System.currentTimeMillis() + (seconds * 1000L))
    FastFuture.successful(true)
  }

  override def pexpire(key: String, milliseconds: Long): Future[Boolean] = {
    expirations.put(key, System.currentTimeMillis() + milliseconds)
    FastFuture.successful(true)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def emptySet(): java.util.Set[ByteString] =
    new java.util.concurrent.CopyOnWriteArraySet[ByteString]

  override def sadd(key: String, members: String*): Future[Long] = saddBS(key, members.map(ByteString.apply): _*)

  override def saddBS(key: String, members: ByteString*): Future[Long] = {
    if (!store.containsKey(key)) {
      store.putIfAbsent(key, emptySet())
    }
    val seq = store.get(key).asInstanceOf[java.util.Set[ByteString]]
    seq.addAll(members.asJava)
    FastFuture.successful(members.size.toLong)
  }

  override def sismember(key: String, member: String): Future[Boolean] = sismemberBS(key, ByteString(member))

  override def sismemberBS(key: String, member: ByteString): Future[Boolean] = {
    val seq = Option(store.get(key)).map(_.asInstanceOf[java.util.Set[ByteString]]).getOrElse(emptySet())
    FastFuture.successful(seq.contains(member))
  }

  override def smembers(key: String): Future[Seq[ByteString]] = {
    val seq = Option(store.get(key)).map(_.asInstanceOf[java.util.Set[ByteString]]).getOrElse(emptySet())
    FastFuture.successful(seq.asScala.toSeq)
  }

  override def srem(key: String, members: String*): Future[Long] = sremBS(key, members.map(ByteString.apply): _*)

  override def sremBS(key: String, members: ByteString*): Future[Long] = {
    if (!store.containsKey(key)) {
      store.putIfAbsent(key, emptySet())
    }
    val seq    = store.get(key).asInstanceOf[java.util.Set[ByteString]]
    val newSeq = seq.asScala.filterNot(b => members.contains(b)).asJava
    // seq.retainAll(newSeq.asJava)
    store.put(key, new java.util.concurrent.CopyOnWriteArraySet[ByteString](newSeq))
    FastFuture.successful(members.size.toLong)
  }

  override def scard(key: String): Future[Long] = {
    if (!store.containsKey(key)) {
      store.putIfAbsent(key, emptySet())
    }
    val seq = store.get(key).asInstanceOf[java.util.Set[ByteString]]
    FastFuture.successful(seq.size.toLong)
  }

  def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = FastFuture.successful(Healthy)
}
