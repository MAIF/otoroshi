package otoroshi.cluster

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Compression, Flow, Framing, Sink, Source}
import akka.util.ByteString
import com.github.blemale.scaffeine.Scaffeine
import com.google.common.io.Files
import com.typesafe.config.ConfigFactory
import org.apache.commons.codec.binary.Hex
import org.joda.time.DateTime
import otoroshi.auth.AuthConfigsDataStore
import otoroshi.env.Env
import otoroshi.events.{AlertDataStore, AuditDataStore, HealthCheckDataStore}
import otoroshi.gateway.{InMemoryRequestsDataStore, RequestsDataStore, Retry}
import otoroshi.jobs.updates.Version
import otoroshi.models._
import otoroshi.next.models._
import otoroshi.script.{KvScriptDataStore, ScriptDataStore}
import otoroshi.security.IdGenerator
import otoroshi.ssl._
import otoroshi.storage._
import otoroshi.storage.drivers.inmemory._
import otoroshi.storage.stores._
import otoroshi.tcp.{KvTcpServiceDataStoreDataStore, TcpServiceDataStore}
import otoroshi.utils
import otoroshi.utils.SchedulerHelper
import otoroshi.utils.cache.types.{LegitConcurrentHashMap, LegitTrieMap}
import otoroshi.utils.http.Implicits._
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.syntax.implicits._
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.libs.ws.{DefaultWSProxyServer, SourceBody, WSAuthScheme, WSProxyServer}
import play.api.mvc.RequestHeader
import play.api.{Configuration, Environment, Logger}
import redis.RedisClientMasterSlaves

import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong, AtomicReference}
import javax.management.{Attribute, ObjectName}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.math.BigDecimal.RoundingMode
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}

/**
 * # Test
 *
 * java -Dhttp.port=8080 -Dhttps.port=8443 -Dotoroshi.cluster.mode=leader -Dotoroshi.cluster.autoUpdateState=true -Dapp.adminPassword=password -Dapp.storage=file -Dotoroshi.loggers.otoroshi-cluster=DEBUG -jar otoroshi.jar
 * java -Dhttp.port=9080 -Dhttps.port=9443 -Dotoroshi.cluster.mode=worker  -Dapp.storage=file -Dotoroshi.loggers.otoroshi-cluster=DEBUG -jar otoroshi.jar
 * java -Dhttp.port=9080 -Dotoroshi.cluster.leader.url=http://otoroshi-api.oto.tools:9999 -Dotoroshi.cluster.worker.dbpath=./worker.db -Dhttps.port=9443 -Dotoroshi.cluster.mode=worker  -Dapp.storage=file -Dotoroshi.loggers.otoroshi-cluster=DEBUG -jar otoroshi.jar
 * java -Dhttp.port=9080 -Dotoroshi.cluster.leader.url=http://otoroshi-api.oto.tools:9999 -Dotoroshi.cluster.worker.dbpath=./worker.db -Dhttps.port=9443 -Dotoroshi.cluster.mode=worker -jar otoroshi.jar
 */
object Cluster {

  lazy val logger = Logger("otoroshi-cluster")

  def filteredKey(key: String, env: Env): Boolean = {
    key.startsWith(s"${env.storageRoot}:noclustersync:") ||
    // key.startsWith(s"${env.storageRoot}:cluster:") ||
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
    // (key.startsWith(s"${env.storageRoot}:data:") && key.endsWith(":stats:in")) ||
    // (key.startsWith(s"${env.storageRoot}:data:") && key.endsWith(":stats:out")) ||
    key.startsWith(s"${env.storageRoot}:desclookup:") ||
    key.startsWith(s"${env.storageRoot}:scall:") ||
    key.startsWith(s"${env.storageRoot}:data:") ||
    key.startsWith(s"${env.storageRoot}:cache:") ||
    key.startsWith(s"${env.storageRoot}:users:alreadyloggedin") ||
    key.startsWith(s"${env.storageRoot}:migrations") ||
    key.startsWith(s"${env.storageRoot}:dev:")
  }
}

trait ClusterMode {
  def name: String
  def clusterActive: Boolean
  def isOff: Boolean
  def isWorker: Boolean
  def isLeader: Boolean
}

object ClusterMode {
  case object Off    extends ClusterMode {
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
  def apply(name: String): Option[ClusterMode] =
    name match {
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
    dataStaleAfter: Long = 10 * 60 * 1000L,
    dbPath: Option[String] = None,
    state: WorkerStateConfig = WorkerStateConfig(),
    quotas: WorkerQuotasConfig = WorkerQuotasConfig(),
    tenants: Seq[TenantId] = Seq.empty,
    swapStrategy: SwapStrategy = SwapStrategy.Replace
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

case class InstanceLocation(
    provider: String,
    zone: String,
    region: String,
    datacenter: String,
    rack: String
) {
  def desc: String  =
    s"provider: '${provider}', region: '${region}', zone: '${zone}', datacenter: '${datacenter}', rack: '${rack}''"
  def json: JsValue = Json.obj(
    "provider"   -> provider,
    "zone"       -> zone,
    "region"     -> region,
    "datacenter" -> datacenter,
    "rack"       -> rack
  )
}

case class InstanceExposition(
    urls: Seq[String],
    hostname: String,
    ipAddress: Option[String],
    clientId: Option[String],
    clientSecret: Option[String],
    tls: Option[MtlsConfig]
) {
  def json: JsValue = Json
    .obj(
      "urls"     -> urls,
      "hostname" -> hostname
    )
    .applyOnWithOpt(clientId) { case (obj, cid) =>
      obj ++ Json.obj("clientId" -> cid)
    }
    .applyOnWithOpt(clientSecret) { case (obj, cid) =>
      obj ++ Json.obj("clientSecret" -> cid)
    }
    .applyOnWithOpt(ipAddress) { case (obj, cid) =>
      obj ++ Json.obj("ipAddress" -> cid)
    }
    .applyOnWithOpt(tls) { case (obj, cid) =>
      obj ++ Json.obj("tls" -> cid.json)
    }
}

case class RelayRouting(
    enabled: Boolean,
    leaderOnly: Boolean,
    location: InstanceLocation,
    exposition: InstanceExposition
) {
  def json: JsValue = Json.obj(
    "enabled"    -> enabled,
    "leaderOnly" -> leaderOnly,
    "location"   -> location.json,
    "exposition" -> exposition.json
  )
}

object RelayRouting {
  val logger                                    = Logger("otoroshi-relay-routing")
  val default                                   = RelayRouting(
    enabled = false,
    leaderOnly = false,
    location = InstanceLocation(
      provider = "local",
      zone = "local",
      region = "local",
      datacenter = "local",
      rack = "local"
    ),
    exposition = InstanceExposition(
      urls = Seq.empty,
      hostname = "otoroshi-api.oto.tools",
      clientId = None,
      clientSecret = None,
      ipAddress = None,
      tls = None
    )
  )
  def parse(json: String): Option[RelayRouting] = Try {
    val value = Json.parse(json)
    RelayRouting(
      enabled = value.select("enabled").asOpt[Boolean].getOrElse(false),
      leaderOnly = value.select("leaderOnly").asOpt[Boolean].getOrElse(false),
      location = InstanceLocation(
        provider = value.select("location").select("provider").asOpt[String].getOrElse("local"),
        zone = value.select("location").select("zone").asOpt[String].getOrElse("local"),
        region = value.select("location").select("region").asOpt[String].getOrElse("local"),
        datacenter = value.select("location").select("datacenter").asOpt[String].getOrElse("local"),
        rack = value.select("location").select("rack").asOpt[String].getOrElse("local")
      ),
      exposition = InstanceExposition(
        urls = value.select("exposition").select("urls").asOpt[Seq[String]].getOrElse(default.exposition.urls),
        hostname = value.select("exposition").select("hostname").asOpt[String].getOrElse(default.exposition.hostname),
        clientId = value.select("exposition").select("clientId").asOpt[String].filter(_.nonEmpty),
        clientSecret = value.select("exposition").select("clientSecret").asOpt[String].filter(_.nonEmpty),
        ipAddress = value.select("exposition").select("ipAddress").asOpt[String].filter(_.nonEmpty),
        tls = value.select("exposition").select("tls").asOpt[JsValue].flatMap(v => MtlsConfig.format.reads(v).asOpt)
      )
    )
  } match {
    case Failure(e)     => None
    case Success(value) => value.some
  }
}

case class ClusterConfig(
    mode: ClusterMode = ClusterMode.Off,
    compression: Int = -1,
    proxy: Option[WSProxyServer],
    mtlsConfig: MtlsConfig,
    streamed: Boolean,
    relay: RelayRouting,
    // autoUpdateState: Boolean,
    retryDelay: Long,
    retryFactor: Long,
    leader: LeaderConfig = LeaderConfig(),
    worker: WorkerConfig = WorkerConfig()
) {
  def id: String                                      = ClusterConfig.clusterNodeId
  def name: String                                    = if (mode.isOff) "standalone" else (if (mode.isLeader) leader.name else worker.name)
  def gzip(): Flow[ByteString, ByteString, NotUsed]   =
    if (compression == -1) Flow.apply[ByteString] else Compression.gzip(compression)
  def gunzip(): Flow[ByteString, ByteString, NotUsed] =
    if (compression == -1) Flow.apply[ByteString] else Compression.gunzip()
}

object ClusterConfig {
  lazy val clusterNodeId = s"node_${IdGenerator.uuid}"
  def apply(configuration: Configuration): ClusterConfig = {
    // Cluster.logger.debug(configuration.underlying.root().render(ConfigRenderOptions.concise()))
    ClusterConfig(
      mode =
        configuration.getOptionalWithFileSupport[String]("mode").flatMap(ClusterMode.apply).getOrElse(ClusterMode.Off),
      compression = configuration.getOptionalWithFileSupport[Int]("compression").getOrElse(-1),
      retryDelay = configuration.getOptionalWithFileSupport[Long]("retryDelay").getOrElse(300L),
      retryFactor = configuration.getOptionalWithFileSupport[Long]("retryFactor").getOrElse(2L),
      streamed = configuration.getOptionalWithFileSupport[Boolean]("streamed").getOrElse(true),
      relay = RelayRouting(
        enabled = configuration.getOptionalWithFileSupport[Boolean]("relay.enabled").getOrElse(false),
        leaderOnly = configuration.getOptionalWithFileSupport[Boolean]("relay.leaderOnly").getOrElse(false),
        location = InstanceLocation(
          provider = configuration.getOptionalWithFileSupport[String]("relay.location.provider").getOrElse("local"),
          zone = configuration.getOptionalWithFileSupport[String]("relay.location.zone").getOrElse("local"),
          region = configuration.getOptionalWithFileSupport[String]("relay.location.region").getOrElse("local"),
          datacenter = configuration.getOptionalWithFileSupport[String]("relay.location.datacenter").getOrElse("local"),
          rack = configuration.getOptionalWithFileSupport[String]("relay.location.rack").getOrElse("local")
        ),
        exposition = InstanceExposition(
          urls = configuration.getOptionalWithFileSupport[String]("relay.exposition.url").map(v => Seq(v)).orElse {
            configuration
              .getOptionalWithFileSupport[String]("relay.exposition.urlsStr")
              .map(v => v.split(",").toSeq.map(_.trim))
              .orElse(
                configuration.getOptionalWithFileSupport[Seq[String]]("relay.exposition.urls")
              )
              .filter(_.nonEmpty)
          } getOrElse (Seq.empty),
          hostname = configuration
            .getOptionalWithFileSupport[String]("relay.exposition.hostname")
            .getOrElse("otoroshi-api.oto.tools"),
          clientId = configuration.getOptionalWithFileSupport[String]("relay.exposition.clientId"),
          clientSecret = configuration.getOptionalWithFileSupport[String]("relay.exposition.clientSecret"),
          ipAddress = configuration.getOptionalWithFileSupport[String]("relay.exposition.ipAddress"),
          tls = {
            val enabled =
              configuration
                .getOptionalWithFileSupport[Boolean]("relay.exposition.tls.mtls")
                .orElse(configuration.getOptionalWithFileSupport[Boolean]("relay.exposition.tls.enabled"))
                .getOrElse(false)
            if (enabled) {
              val loose        =
                configuration.getOptionalWithFileSupport[Boolean]("relay.exposition.tls.loose").getOrElse(false)
              val trustAll     =
                configuration.getOptionalWithFileSupport[Boolean]("relay.exposition.tls.trustAll").getOrElse(false)
              val certs        =
                configuration.getOptionalWithFileSupport[Seq[String]]("relay.exposition.tls.certs").getOrElse(Seq.empty)
              val trustedCerts = configuration
                .getOptionalWithFileSupport[Seq[String]]("relay.exposition.tls.trustedCerts")
                .getOrElse(Seq.empty)
              MtlsConfig(
                certs = certs,
                trustedCerts = trustedCerts,
                mtls = enabled,
                loose = loose,
                trustAll = trustAll
              ).some
            } else {
              None
            }
          }
        )
      ),
      // autoUpdateState = configuration.getOptionalWithFileSupport[Boolean]("autoUpdateState").getOrElse(true),
      mtlsConfig = MtlsConfig(
        certs = configuration.getOptionalWithFileSupport[Seq[String]]("mtls.certs").getOrElse(Seq.empty),
        trustedCerts = configuration.getOptionalWithFileSupport[Seq[String]]("mtls.trustedCerts").getOrElse(Seq.empty),
        loose = configuration.getOptionalWithFileSupport[Boolean]("mtls.loose").getOrElse(false),
        trustAll = configuration.getOptionalWithFileSupport[Boolean]("mtls.trustAll").getOrElse(false),
        mtls = configuration.getOptionalWithFileSupport[Boolean]("mtls.enabled").getOrElse(false)
      ),
      proxy = configuration.getOptionalWithFileSupport[String]("proxy.host").map { host =>
        DefaultWSProxyServer(
          host = host,
          port = configuration.getOptionalWithFileSupport[Int]("proxy.port").getOrElse(3129),
          principal = configuration.getOptionalWithFileSupport[String]("proxy.principal"),
          password = configuration.getOptionalWithFileSupport[String]("proxy.password"),
          ntlmDomain = configuration.getOptionalWithFileSupport[String]("proxy.ntlmDomain"),
          encoding = configuration.getOptionalWithFileSupport[String]("proxy.encoding"),
          nonProxyHosts = None
        )
      },
      leader = LeaderConfig(
        name = configuration
          .getOptionalWithFileSupport[String]("leader.name")
          .orElse(Option(System.getenv("INSTANCE_NUMBER")).map(i => s"otoroshi-leader-$i"))
          .getOrElse(s"otoroshi-leader-${IdGenerator.token(16)}"),
        urls = configuration
          .getOptionalWithFileSupport[String]("leader.url")
          .map(s => Seq(s))
          .orElse(
            configuration
              .getOptionalWithFileSupport[Seq[String]]("leader.urls")
              .map(_.toSeq)
          )
          .getOrElse(Seq("http://otoroshi-api.oto.tools:8080")),
        host = configuration.getOptionalWithFileSupport[String]("leader.host").getOrElse("otoroshi-api.oto.tools"),
        clientId = configuration.getOptionalWithFileSupport[String]("leader.clientId").getOrElse("admin-api-apikey-id"),
        clientSecret =
          configuration.getOptionalWithFileSupport[String]("leader.clientSecret").getOrElse("admin-api-apikey-secret"),
        groupingBy = configuration.getOptionalWithFileSupport[Int]("leader.groupingBy").getOrElse(50),
        cacheStateFor = configuration.getOptionalWithFileSupport[Long]("leader.cacheStateFor").getOrElse(4000L),
        stateDumpPath = configuration.getOptionalWithFileSupport[String]("leader.stateDumpPath")
      ),
      worker = WorkerConfig(
        name = configuration
          .getOptionalWithFileSupport[String]("worker.name")
          .orElse(Option(System.getenv("INSTANCE_NUMBER")).map(i => s"otoroshi-worker-$i"))
          .getOrElse(s"otoroshi-worker-${IdGenerator.token(16)}"),
        retries = configuration.getOptionalWithFileSupport[Int]("worker.retries").getOrElse(3),
        timeout = configuration.getOptionalWithFileSupport[Long]("worker.timeout").getOrElse(2000),
        dataStaleAfter =
          configuration.getOptionalWithFileSupport[Long]("worker.dataStaleAfter").getOrElse(10 * 60 * 1000L),
        dbPath = configuration.getOptionalWithFileSupport[String]("worker.dbpath"),
        state = WorkerStateConfig(
          timeout = configuration.getOptionalWithFileSupport[Long]("worker.state.timeout").getOrElse(2000),
          retries = configuration.getOptionalWithFileSupport[Int]("worker.state.retries").getOrElse(3),
          pollEvery = configuration.getOptionalWithFileSupport[Long]("worker.state.pollEvery").getOrElse(10000L)
        ),
        quotas = WorkerQuotasConfig(
          timeout = configuration.getOptionalWithFileSupport[Long]("worker.quotas.timeout").getOrElse(2000),
          retries = configuration.getOptionalWithFileSupport[Int]("worker.quotas.retries").getOrElse(3),
          pushEvery = configuration.getOptionalWithFileSupport[Long]("worker.quotas.pushEvery").getOrElse(2000L)
        ),
        tenants = configuration
          .getOptionalWithFileSupport[Seq[String]]("worker.tenants")
          .orElse(
            configuration.getOptionalWithFileSupport[String]("worker.tenantsStr").map(_.split(",").toSeq.map(_.trim))
          )
          .map(_.map(TenantId.apply))
          .getOrElse(Seq.empty),
        swapStrategy = configuration.getOptionalWithFileSupport[String]("worker.swapStrategy") match {
          case Some("Merge") => SwapStrategy.Merge
          case _             => SwapStrategy.Replace
        }
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

case class MemberView(
    id: String,
    name: String,
    location: String,
    httpPort: Int,
    httpsPort: Int,
    internalHttpPort: Int,
    internalHttpsPort: Int,
    lastSeen: DateTime,
    timeout: Duration,
    memberType: ClusterMode,
    relay: RelayRouting,
    tunnels: Seq[String],
    stats: JsObject = Json.obj()
) {
  def json: JsValue   = asJson
  def asJson: JsValue =
    Json.obj(
      "id"                -> id,
      "name"              -> name,
      "location"          -> location,
      "httpPort"          -> httpPort,
      "httpsPort"         -> httpsPort,
      "internalHttpPort"  -> internalHttpPort,
      "internalHttpsPort" -> internalHttpsPort,
      "lastSeen"          -> lastSeen.getMillis,
      "timeout"           -> timeout.toMillis,
      "type"              -> memberType.name,
      "stats"             -> stats,
      "relay"             -> relay.json,
      "tunnels"           -> tunnels
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
  def fromJsonSafe(value: JsValue)(implicit env: Env): JsResult[MemberView] =
    Try {
      JsSuccess(
        MemberView(
          id = (value \ "id").as[String],
          name = (value \ "name").as[String],
          location = (value \ "location").as[String],
          lastSeen = new DateTime((value \ "lastSeen").as[Long]),
          timeout = Duration((value \ "timeout").as[Long], TimeUnit.MILLISECONDS),
          memberType = (value \ "type")
            .asOpt[String]
            .map(n => ClusterMode(n).getOrElse(ClusterMode.Off))
            .getOrElse(ClusterMode.Off),
          stats = (value \ "stats").asOpt[JsObject].getOrElse(Json.obj()),
          tunnels = (value \ "tunnels").asOpt[Seq[String]].map(_.distinct).getOrElse(Seq.empty),
          httpsPort = (value \ "httpsPort").asOpt[Int].getOrElse(env.exposedHttpsPortInt),
          httpPort = (value \ "httpPort").asOpt[Int].getOrElse(env.exposedHttpPortInt),
          internalHttpsPort = (value \ "internalHttpsPort").asOpt[Int].getOrElse(env.httpsPort),
          internalHttpPort = (value \ "internalHttpPort").asOpt[Int].getOrElse(env.httpPort),
          relay = RelayRouting(
            enabled = true,
            leaderOnly = false,
            location = InstanceLocation(
              provider = value.select("relay").select("location").select("provider").asOpt[String].getOrElse("local"),
              zone = value.select("relay").select("location").select("zone").asOpt[String].getOrElse("local"),
              region = value.select("relay").select("location").select("region").asOpt[String].getOrElse("local"),
              datacenter =
                value.select("relay").select("location").select("datacenter").asOpt[String].getOrElse("local"),
              rack = value.select("relay").select("location").select("rack").asOpt[String].getOrElse("local")
            ),
            exposition = InstanceExposition(
              urls = value
                .select("relay")
                .select("exposition")
                .select("urls")
                .asOpt[Seq[String]]
                .getOrElse(Seq(s"${env.rootScheme}${env.adminApiExposedHost}")),
              hostname = value
                .select("relay")
                .select("exposition")
                .select("hostname")
                .asOpt[String]
                .getOrElse(env.adminApiExposedHost),
              clientId = value.select("relay").select("exposition").select("clientId").asOpt[String].filter(_.nonEmpty),
              clientSecret =
                value.select("relay").select("exposition").select("clientSecret").asOpt[String].filter(_.nonEmpty),
              ipAddress =
                value.select("relay").select("exposition").select("ipAddress").asOpt[String].filter(_.nonEmpty),
              tls = value
                .select("relay")
                .select("exposition")
                .select("tls")
                .asOpt[JsValue]
                .flatMap(v => MtlsConfig.format.reads(v).asOpt)
            )
          )
        )
      )
    } recover { case e =>
      JsError(e.getMessage)
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

class KvClusterStateDataStore(redisLike: RedisLike, env: Env) extends ClusterStateDataStore {

  override def clearMembers()(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    redisLike
      .keys(s"${env.storageRoot}:cluster:members:*")
      .flatMap(keys =>
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
          case _               =>
            redisLike
              .set(key, Json.stringify(member.asJson), pxMilliseconds = Some(member.timeout.toMillis))
              .map(_ => ())
        }
      }
      case None    =>
        redisLike.set(key, Json.stringify(member.asJson), pxMilliseconds = Some(member.timeout.toMillis)).map(_ => ())
    }
  }

  override def getMembers()(implicit ec: ExecutionContext, env: Env): Future[Seq[MemberView]] = {
    // if (env.clusterConfig.mode == ClusterMode.Leader) {
    redisLike
      .keys(s"${env.storageRoot}:cluster:members:*")
      .flatMap(keys =>
        if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]])
        else redisLike.mget(keys: _*)
      )
      .map(seq =>
        seq.filter(_.isDefined).map(_.get).map(v => MemberView.fromJsonSafe(Json.parse(v.utf8String))).collect {
          case JsSuccess(i, _) => i
        }
      )
    // } else {
    //   FastFuture.successful(Seq.empty)
    // }
  }

  override def updateDataIn(in: Long)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    for {
      _ <- redisLike.lpushLong(s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:in", in)
      _ <- redisLike.ltrim(s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:in", 0, 100)
      _ <- redisLike.pexpire(
             s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:in",
             env.clusterConfig.worker.timeout * env.clusterConfig.worker.retries
           )
    } yield ()
  }

  override def updateDataOut(out: Long)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    for {
      _ <- redisLike.lpushLong(s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:out", out)
      _ <- redisLike.ltrim(s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:out", 0, 100)
      _ <- redisLike.pexpire(
             s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:out",
             env.clusterConfig.worker.timeout * env.clusterConfig.worker.retries
           )
    } yield ()
  }

  override def dataInAndOut()(implicit ec: ExecutionContext, env: Env): Future[(Long, Long)] = {
    for {
      keysIn  <- redisLike.keys(s"${env.storageRoot}:cluster:leader:*:data:in")
      keysOut <- redisLike.keys(s"${env.storageRoot}:cluster:leader:*:data:out")
      in      <- Future
                   .sequence(
                     keysIn.map(key =>
                       redisLike.lrange(key, 0, 100).map { values =>
                         if (values.isEmpty) 0L
                         else {
                           val items    = values.map { v =>
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
                 keysOut.map(key =>
                   redisLike.lrange(key, 0, 100).map { values =>
                     if (values.isEmpty) 0L
                     else {
                       val items    = values.map { v =>
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
      .flatMap(keys =>
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
          case _               =>
            redisLike
              .set(key, Json.stringify(member.asJson), pxMilliseconds = Some(member.timeout.toMillis))
              .map(_ => ())
        }
      }
      case None    =>
        redisLike.set(key, Json.stringify(member.asJson), pxMilliseconds = Some(member.timeout.toMillis)).map(_ => ())
    }
  }

  override def getMembers()(implicit ec: ExecutionContext, env: Env): Future[Seq[MemberView]] = {
    // if (env.clusterConfig.mode == ClusterMode.Leader) {
    redisLike
      .keys(s"${env.storageRoot}:cluster:members:*")
      .flatMap(keys =>
        if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]])
        else redisLike.mget(keys: _*)
      )
      .map(seq =>
        seq.filter(_.isDefined).map(_.get).map(v => MemberView.fromJsonSafe(Json.parse(v.utf8String))).collect {
          case JsSuccess(i, _) => i
        }
      )
    // } else {
    //   FastFuture.successful(Seq.empty)
    // }
  }

  override def updateDataIn(in: Long)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    for {
      _ <- redisLike.lpush(s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:in", in)
      _ <- redisLike.ltrim(s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:in", 0, 100)
      _ <- redisLike.pexpire(
             s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:in",
             env.clusterConfig.worker.timeout * env.clusterConfig.worker.retries
           )
    } yield ()
  }

  override def updateDataOut(out: Long)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    for {
      _ <- redisLike.lpush(s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:out", out)
      _ <- redisLike.ltrim(s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:out", 0, 100)
      _ <- redisLike.pexpire(
             s"${env.storageRoot}:cluster:leader:${env.clusterConfig.leader.name}:data:out",
             env.clusterConfig.worker.timeout * env.clusterConfig.worker.retries
           )
    } yield ()
  }

  override def dataInAndOut()(implicit ec: ExecutionContext, env: Env): Future[(Long, Long)] = {
    for {
      keysIn  <- redisLike.keys(s"${env.storageRoot}:cluster:leader:*:data:in")
      keysOut <- redisLike.keys(s"${env.storageRoot}:cluster:leader:*:data:out")
      in      <- Future
                   .sequence(
                     keysIn.map(key =>
                       redisLike.lrange(key, 0, 100).map { values =>
                         if (values.isEmpty) 0L
                         else {
                           val items    = values.map { v =>
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
                 keysOut.map(key =>
                   redisLike.lrange(key, 0, 100).map { values =>
                     if (values.isEmpty) 0L
                     else {
                       val items    = values.map { v =>
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

object ClusterAgent {

  val OtoroshiWorkerIdHeader                = "Otoroshi-Worker-Id"
  val OtoroshiWorkerNameHeader              = "Otoroshi-Worker-Name"
  val OtoroshiWorkerLocationHeader          = "Otoroshi-Worker-Location"
  val OtoroshiWorkerHttpPortHeader          = "Otoroshi-Worker-Http-Port"
  val OtoroshiWorkerHttpsPortHeader         = "Otoroshi-Worker-Https-Port"
  val OtoroshiWorkerInternalHttpPortHeader  = "Otoroshi-Worker-Internal-Http-Port"
  val OtoroshiWorkerInternalHttpsPortHeader = "Otoroshi-Worker-Internal-Https-Port"
  val OtoroshiWorkerRelayRoutingHeader      = "Otoroshi-Worker-Relay-Routing"

  def apply(config: ClusterConfig, env: Env) = new ClusterAgent(config, env)

  private def clusterGetApikey(env: Env, id: String)(implicit
      executionContext: ExecutionContext,
      mat: Materializer
  ): Future[Option[JsValue]] = {
    val cfg         = env.clusterConfig
    val otoroshiUrl = cfg.leader.urls.head
    env.MtlsWs
      .url(otoroshiUrl + s"/api/apikeys/$id", cfg.mtlsConfig)
      .withHttpHeaders(
        "Host" -> cfg.leader.host
      )
      .withAuth(cfg.leader.clientId, cfg.leader.clientSecret, WSAuthScheme.BASIC)
      .withRequestTimeout(Duration(cfg.worker.timeout, TimeUnit.MILLISECONDS))
      .withMaybeProxyServer(cfg.proxy)
      .get()
      .map {
        case r if r.status == 200 => r.json.some
        case r                    =>
          r.ignore()
          None
      }
  }

  def clusterSaveApikey(env: Env, apikey: ApiKey)(implicit
      executionContext: ExecutionContext,
      mat: Materializer
  ): Future[Unit] = {
    val cfg         = env.clusterConfig
    val otoroshiUrl = cfg.leader.urls.head
    clusterGetApikey(env, apikey.clientId)
      .flatMap {
        case None    => {
          val request = env.MtlsWs
            .url(otoroshiUrl + s"/api/apikeys", cfg.mtlsConfig)
            .withHttpHeaders(
              "Host" -> cfg.leader.host
            )
            .withAuth(cfg.leader.clientId, cfg.leader.clientSecret, WSAuthScheme.BASIC)
            .withRequestTimeout(Duration(cfg.worker.timeout, TimeUnit.MILLISECONDS))
            .withMaybeProxyServer(cfg.proxy)
          request
            .post(apikey.toJson)
            .map(_.ignore())
            .andThen { case Failure(_) =>
              request.ignore()
            }
        }
        case Some(_) => {
          val request = env.MtlsWs
            .url(otoroshiUrl + s"/api/apikeys/${apikey.clientId}", cfg.mtlsConfig)
            .withHttpHeaders(
              "Host" -> cfg.leader.host
            )
            .withAuth(cfg.leader.clientId, cfg.leader.clientSecret, WSAuthScheme.BASIC)
            .withRequestTimeout(Duration(cfg.worker.timeout, TimeUnit.MILLISECONDS))
            .withMaybeProxyServer(cfg.proxy)
          request
            .put(apikey.toJson)
            .map(_.ignore())
            .andThen { case Failure(_) =>
              request.ignore()
            }
        }
      }
      .map(_ => ())
  }
}

object CpuInfo {

  private val mbs      = ManagementFactory.getPlatformMBeanServer
  private val osMXBean = ManagementFactory.getOperatingSystemMXBean

  def cpuLoad(): Double = {
    val name  = ObjectName.getInstance("java.lang:type=OperatingSystem")
    val list  = mbs.getAttributes(name, Array("ProcessCpuLoad"))
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
  def getIpAddress(): String = {
    import java.net._
    val all   = "0.0.0.0"
    val local = "127.0.0.1"
    val res1  = Try {
      val socket = new Socket()
      socket.connect(new InetSocketAddress("www.otoroshi.io", 443))
      val ip     = socket.getLocalAddress.getHostAddress
      socket.close()
      ip
    } match {
      case Failure(_)     => all
      case Success(value) => value
    }
    val res2  = Try {
      val socket = new DatagramSocket()
      socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
      val ip     = socket.getLocalAddress.getHostAddress
      socket.close()
      ip
    } match {
      case Failure(_)     => all
      case Success(value) => value
    }
    val res3  = InetAddress.getLocalHost.getHostAddress
    val res   = if (res1 != all && res1 != local) {
      res1
    } else if (res2 != all && res2 != local) {
      res2
    } else {
      res3
    }
    res
    // val enumeration = NetworkInterface.getNetworkInterfaces.asScala.toSeq
    // enumeration.foreach(_.getDisplayName.debugPrintln)
    // val ipAddresses = enumeration.flatMap(p => p.getInetAddresses.asScala.toSeq)
    // val address = ipAddresses.find { address =>
    //   val host = address.getHostAddress
    //   host.contains(".") && !address.isLoopbackAddress
    // }.getOrElse(InetAddress.getLocalHost)
    // address.getHostAddress
  }
}

class ClusterLeaderAgent(config: ClusterConfig, env: Env) {
  import scala.concurrent.duration._

  implicit lazy val ec    = env.otoroshiExecutionContext
  implicit lazy val mat   = env.otoroshiMaterializer
  implicit lazy val sched = env.otoroshiScheduler
  implicit lazy val _env  = env

  private val membershipRef   = new AtomicReference[Cancellable]()
  private val stateUpdaterRef = new AtomicReference[Cancellable]()

  private val caching     = new AtomicBoolean(false)
  private val cachedAt    = new AtomicLong(0L)
  private val cacheCount  = new AtomicLong(0L)
  private val cacheDigest = new AtomicReference[String]("--")
  private val cachedRef   = new AtomicReference[ByteString](ByteString.empty)

  private lazy val hostAddress: String = {
    env.configuration
      .getOptionalWithFileSupport[String]("otoroshi.cluster.selfAddress")
      .getOrElse(ClusterLeaderAgent.getIpAddress())
  }

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
        "typ"                       -> "globstats",
        "cpu_usage"                 -> CpuInfo.cpuLoad(),
        "load_average"              -> CpuInfo.loadAverage(),
        "heap_used"                 -> (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024,
        "heap_size"                 -> rt.totalMemory() / 1024 / 1024,
        "live_threads"              -> ManagementFactory.getThreadMXBean.getThreadCount,
        "live_peak_threads"         -> ManagementFactory.getThreadMXBean.getPeakThreadCount,
        "daemon_threads"            -> ManagementFactory.getThreadMXBean.getDaemonThreadCount,
        "rate"                      -> BigDecimal(
          Option(rate)
            .filterNot(a => a.isInfinity || a.isNaN || a.isNegInfinity || a.isPosInfinity)
            .getOrElse(0.0)
        ).setScale(3, RoundingMode.HALF_EVEN),
        "duration"                  -> BigDecimal(
          Option(duration)
            .filterNot(a => a.isInfinity || a.isNaN || a.isNegInfinity || a.isPosInfinity)
            .getOrElse(0.0)
        ).setScale(3, RoundingMode.HALF_EVEN),
        "overhead"                  -> BigDecimal(
          Option(overhead)
            .filterNot(a => a.isInfinity || a.isNaN || a.isNegInfinity || a.isPosInfinity)
            .getOrElse(0.0)
        ).setScale(3, RoundingMode.HALF_EVEN),
        "dataInRate"                -> BigDecimal(
          Option(dataInRate)
            .filterNot(a => a.isInfinity || a.isNaN || a.isNegInfinity || a.isPosInfinity)
            .getOrElse(0.0)
        ).setScale(3, RoundingMode.HALF_EVEN),
        "dataOutRate"               -> BigDecimal(
          Option(dataOutRate)
            .filterNot(a => a.isInfinity || a.isNaN || a.isNegInfinity || a.isPosInfinity)
            .getOrElse(0.0)
        ).setScale(3, RoundingMode.HALF_EVEN),
        "concurrentHandledRequests" -> concurrentHandledRequests
      )
    }).flatMap { stats =>
      env.datastores.clusterStateDataStore.registerMember(
        MemberView(
          id = ClusterConfig.clusterNodeId,
          name = env.clusterConfig.leader.name,
          memberType = ClusterMode.Leader,
          location = hostAddress,
          httpPort = env.exposedHttpPortInt,
          httpsPort = env.exposedHttpsPortInt,
          internalHttpPort = env.httpPort,
          internalHttpsPort = env.httpsPort,
          lastSeen = DateTime.now(),
          timeout = 120.seconds,
          stats = stats,
          relay = env.clusterConfig.relay,
          tunnels = env.tunnelManager.currentTunnels.toSeq
        )
      )
    }
  }

  def start(): Unit = {
    if (config.mode == ClusterMode.Leader) {
      if (Cluster.logger.isDebugEnabled)
        Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] Starting cluster leader agent")
      membershipRef.set(
        env.otoroshiScheduler.scheduleAtFixedRate(2.second, 5.seconds)(
          SchedulerHelper.runnable(
            try {
              renewMemberShip()
            } catch {
              case e: Throwable =>
                Cluster.logger.error(s"Error while renewing leader membership of ${env.clusterConfig.leader.name}", e)
            }
          )
        )
      )
      // if (env.clusterConfig.autoUpdateState) {
      if (Cluster.logger.isDebugEnabled)
        Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] Starting cluster state auto update")
      stateUpdaterRef.set(
        env.otoroshiScheduler.scheduleAtFixedRate(1.second, env.clusterConfig.leader.cacheStateFor.millis)(
          utils.SchedulerHelper.runnable(
            try {
              cacheState()
            } catch {
              case e: Throwable =>
                caching.compareAndSet(true, false)
                Cluster.logger
                  .error(s"Error while renewing leader state cache of ${env.clusterConfig.leader.name}", e)
            }
          )
        )
      )
      // }
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
  def cachedCount     = cacheCount.get()
  def cachedDigest    = cacheDigest.get()

  private def cacheState(): Future[Unit] = {
    if (caching.compareAndSet(false, true)) {
      env.metrics.withTimerAsync("otoroshi.core.cluster.cache-state") {
        // TODO: handle in proxy state ?
        val start   = System.currentTimeMillis()
        // var stateCache = ByteString.empty
        val counter = new AtomicLong(0L)
        val digest  = MessageDigest.getInstance("SHA-256")
        env.datastores
          .rawExport(env.clusterConfig.leader.groupingBy)
          .map { item =>
            ByteString(Json.stringify(item) + "\n")
          }
          .alsoTo(Sink.foreach { item =>
            digest.update(item.asByteBuffer)
            counter.incrementAndGet()
          })
          .via(env.clusterConfig.gzip())
          // .alsoTo(Sink.fold(ByteString.empty)(_ ++ _))
          // .alsoTo(Sink.foreach(bs => stateCache = stateCache ++ bs))
          // .alsoTo(Sink.onComplete {
          //   case Success(_) =>
          //     cachedRef.set(stateCache)
          //     cachedAt.set(System.currentTimeMillis())
          //     caching.compareAndSet(true, false)
          //     env.datastores.clusterStateDataStore.updateDataOut(stateCache.size)
          //     env.clusterConfig.leader.stateDumpPath
          //       .foreach(path => Future(Files.write(stateCache.toArray, new File(path))))
          //     Cluster.logger.debug(
          //       s"[${env.clusterConfig.mode.name}] Auto-cache updated in ${System.currentTimeMillis() - start} ms."
          //     )
          //   case Failure(e) =>
          //     Cluster.logger.error(s"[${env.clusterConfig.mode.name}] Stream error while exporting raw state", e)
          // })
          //.runWith(Sink.ignore)
          .runWith(Sink.fold(ByteString.empty)(_ ++ _))
          .applyOnIf(env.vaults.leaderFetchOnly) { fu =>
            fu.flatMap { stateCache =>
              env.vaults.fillSecretsAsync("cluster-state", stateCache.utf8String).map { filledStateCacheStr =>
                val bs = filledStateCacheStr.byteString
                digest.reset()
                digest.update(bs.asByteBuffer)
                bs
              }
            }
          }
          .andThen {
            case Success(stateCache) => {
              caching.compareAndSet(true, false)
              cachedRef.set(stateCache)
              cachedAt.set(System.currentTimeMillis())
              cacheCount.set(counter.get())
              cacheDigest.set(Hex.encodeHexString(digest.digest()))
              env.datastores.clusterStateDataStore.updateDataOut(stateCache.size)
              env.clusterConfig.leader.stateDumpPath
                .foreach(path => Future(Files.write(stateCache.toArray, new File(path))))
              if (Cluster.logger.isDebugEnabled)
                Cluster.logger.debug(
                  s"[${env.clusterConfig.mode.name}] Auto-cache updated in ${System.currentTimeMillis() - start} ms."
                )
            }
            case Failure(err)        =>
              caching.compareAndSet(true, false)
              Cluster.logger.error(s"[${env.clusterConfig.mode.name}] Stream error while exporting raw state", err)
          }
          .map(_ => ())
      }
    } else {
      ().vfuture
    }
  }
}

class ClusterAgent(config: ClusterConfig, env: Env) {

  import scala.concurrent.duration._

  implicit lazy val ec    = env.otoroshiExecutionContext
  implicit lazy val mat   = env.otoroshiMaterializer
  implicit lazy val sched = env.otoroshiScheduler

  private val _modern = env.configuration.betterGetOptional[Boolean]("otoroshi.cluster.worker.modern").getOrElse(false)

  private val lastPoll                      = new AtomicReference[DateTime](DateTime.parse("1970-01-01T00:00:00.000"))
  private val pollRef                       = new AtomicReference[Cancellable]()
  private val pushRef                       = new AtomicReference[Cancellable]()
  private val counter                       = new AtomicInteger(0)
  private val isPollingState                = new AtomicBoolean(false)
  private val isPushingQuotas               = new AtomicBoolean(false)
  private val firstSuccessfulStateFetchDone = new AtomicBoolean(false)

  private lazy val hostAddress: String = {
    env.configuration
      .getOptionalWithFileSupport[String]("otoroshi.cluster.selfAddress")
      .getOrElse(ClusterLeaderAgent.getIpAddress())
  }

  /////////////
  private val apiIncrementsRef      =
    new AtomicReference[TrieMap[String, AtomicLong]](new LegitTrieMap[String, AtomicLong]())
  private val servicesIncrementsRef = new AtomicReference[TrieMap[String, (AtomicLong, AtomicLong, AtomicLong)]](
    new LegitTrieMap[String, (AtomicLong, AtomicLong, AtomicLong)]()
  )
  private val workerSessionsCache   = Scaffeine()
    .maximumSize(1000L)
    .expireAfterWrite(env.clusterConfig.worker.state.pollEvery.millis * 3)
    .build[String, PrivateAppsUser]()
  /////////////

  def lastSync: DateTime = lastPoll.get()

  private def otoroshiUrl: String = {
    val count = counter.incrementAndGet() % (if (config.leader.urls.nonEmpty) config.leader.urls.size else 1)
    config.leader.urls.zipWithIndex.find(t => t._2 == count).map(_._1).getOrElse(config.leader.urls.head)
  }

  def cannotServeRequests(): Boolean = {
    !firstSuccessfulStateFetchDone.get()
  }

  def isLoginTokenValid(token: String): Future[Boolean] = {
    if (env.clusterConfig.mode.isWorker) {
      Retry
        .retry(
          times = config.worker.retries,
          delay = config.retryDelay,
          factor = config.retryFactor,
          ctx = "leader-login-token-valid"
        ) { tryCount =>
          if (Cluster.logger.isDebugEnabled)
            Cluster.logger.debug(s"Checking if login token $token is valid with a leader")
          env.MtlsWs
            .url(otoroshiUrl + s"/api/cluster/login-tokens/$token", config.mtlsConfig)
            .withHttpHeaders(
              "Host"                                             -> config.leader.host,
              ClusterAgent.OtoroshiWorkerIdHeader                -> ClusterConfig.clusterNodeId,
              ClusterAgent.OtoroshiWorkerNameHeader              -> config.worker.name,
              ClusterAgent.OtoroshiWorkerLocationHeader          -> s"$hostAddress",
              ClusterAgent.OtoroshiWorkerHttpPortHeader          -> env.exposedHttpPortInt.toString,
              ClusterAgent.OtoroshiWorkerHttpsPortHeader         -> env.exposedHttpsPortInt.toString,
              ClusterAgent.OtoroshiWorkerInternalHttpPortHeader  -> env.httpPort.toString,
              ClusterAgent.OtoroshiWorkerInternalHttpsPortHeader -> env.httpsPort.toString
            )
            .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
            .withRequestTimeout(Duration(config.worker.timeout, TimeUnit.MILLISECONDS))
            .withMaybeProxyServer(config.proxy)
            .get()
            .filter { resp =>
              if (resp.status == 200 && Cluster.logger.isDebugEnabled)
                Cluster.logger.debug(s"Login token $token is valid")
              resp.ignore() // ignoreIf(resp.status != 200)
              resp.status == 200
            }
            .map(_ => true)
        }
        .recover { case e =>
          if (Cluster.logger.isDebugEnabled)
            Cluster.logger.debug(
              s"[${env.clusterConfig.mode.name}] Error while checking login token with Otoroshi leader cluster"
            )
          false
        }
    } else {
      FastFuture.successful(false)
    }
  }

  def getUserToken(token: String): Future[Option[JsValue]] = {
    if (env.clusterConfig.mode.isWorker) {
      Retry
        .retry(
          times = config.worker.retries,
          delay = config.retryDelay,
          factor = config.retryFactor,
          ctx = "leader-user-token-get"
        ) { tryCount =>
          if (Cluster.logger.isDebugEnabled)
            Cluster.logger.debug(s"Checking if user token $token is valid with a leader")
          env.MtlsWs
            .url(otoroshiUrl + s"/api/cluster/user-tokens/$token", config.mtlsConfig)
            .withHttpHeaders(
              "Host"                                             -> config.leader.host,
              ClusterAgent.OtoroshiWorkerIdHeader                -> ClusterConfig.clusterNodeId,
              ClusterAgent.OtoroshiWorkerNameHeader              -> config.worker.name,
              ClusterAgent.OtoroshiWorkerLocationHeader          -> s"$hostAddress",
              ClusterAgent.OtoroshiWorkerHttpPortHeader          -> env.exposedHttpPortInt.toString,
              ClusterAgent.OtoroshiWorkerHttpsPortHeader         -> env.exposedHttpsPortInt.toString,
              ClusterAgent.OtoroshiWorkerInternalHttpPortHeader  -> env.httpPort.toString,
              ClusterAgent.OtoroshiWorkerInternalHttpsPortHeader -> env.httpsPort.toString
            )
            .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
            .withRequestTimeout(Duration(config.worker.timeout, TimeUnit.MILLISECONDS))
            .withMaybeProxyServer(config.proxy)
            .get()
            .filter { resp =>
              if (resp.status == 200 && Cluster.logger.isDebugEnabled)
                Cluster.logger.debug(s"User token $token is valid")
              resp.ignoreIf(resp.status != 200)
              resp.status == 200
            }
            .map(resp => Some(Json.parse(resp.body)))
        }
        .recover { case e =>
          if (Cluster.logger.isDebugEnabled)
            Cluster.logger.debug(
              s"[${env.clusterConfig.mode.name}] Error while checking user token with Otoroshi leader cluster"
            )
          None
        }
    } else {
      FastFuture.successful(None)
    }
  }

  def createLoginToken(token: String): Future[Option[String]] = {
    if (env.clusterConfig.mode.isWorker) {
      if (Cluster.logger.isDebugEnabled) Cluster.logger.debug(s"Creating login token for $token on the leader")
      Retry
        .retry(
          times = config.worker.retries,
          delay = config.retryDelay,
          factor = config.retryFactor,
          ctx = "leader-create-login-token"
        ) { tryCount =>
          val request = env.MtlsWs
            .url(otoroshiUrl + s"/api/cluster/login-tokens/$token", config.mtlsConfig)
            .withHttpHeaders(
              "Host"                                             -> config.leader.host,
              "Content-Type"                                     -> "application/json",
              ClusterAgent.OtoroshiWorkerIdHeader                -> ClusterConfig.clusterNodeId,
              ClusterAgent.OtoroshiWorkerNameHeader              -> config.worker.name,
              ClusterAgent.OtoroshiWorkerLocationHeader          -> s"$hostAddress",
              ClusterAgent.OtoroshiWorkerHttpPortHeader          -> env.exposedHttpPortInt.toString,
              ClusterAgent.OtoroshiWorkerHttpsPortHeader         -> env.exposedHttpsPortInt.toString,
              ClusterAgent.OtoroshiWorkerInternalHttpPortHeader  -> env.httpPort.toString,
              ClusterAgent.OtoroshiWorkerInternalHttpsPortHeader -> env.httpsPort.toString
            )
            .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
            .withRequestTimeout(Duration(config.worker.timeout, TimeUnit.MILLISECONDS))
            .withMaybeProxyServer(config.proxy)
          request
            .post(Json.obj())
            .andThen { case Failure(_) =>
              request.ignore()
            }
            .filter { resp =>
              if (Cluster.logger.isDebugEnabled)
                Cluster.logger.debug(s"login token for ${token} created on the leader ${resp.status}")
              resp.ignore() // ignoreIf(resp.status != 201)
              resp.status == 201
            }
            .map(_ => Some(token))
        }
    } else {
      FastFuture.successful(None)
    }
  }

  def setUserToken(token: String, user: JsValue): Future[Option[Unit]] = {
    if (env.clusterConfig.mode.isWorker) {
      if (Cluster.logger.isDebugEnabled)
        Cluster.logger.debug(s"Creating user token for ${token} on the leader: ${Json.prettyPrint(user)}")
      Retry
        .retry(
          times = config.worker.retries,
          delay = config.retryDelay,
          factor = config.retryFactor,
          ctx = "leader-create-user-token"
        ) { tryCount =>
          val request = env.MtlsWs
            .url(otoroshiUrl + s"/api/cluster/user-tokens", config.mtlsConfig)
            .withHttpHeaders(
              "Host"                                             -> config.leader.host,
              "Content-Type"                                     -> "application/json",
              ClusterAgent.OtoroshiWorkerIdHeader                -> ClusterConfig.clusterNodeId,
              ClusterAgent.OtoroshiWorkerNameHeader              -> config.worker.name,
              ClusterAgent.OtoroshiWorkerLocationHeader          -> s"$hostAddress",
              ClusterAgent.OtoroshiWorkerHttpPortHeader          -> env.exposedHttpPortInt.toString,
              ClusterAgent.OtoroshiWorkerHttpsPortHeader         -> env.exposedHttpsPortInt.toString,
              ClusterAgent.OtoroshiWorkerInternalHttpPortHeader  -> env.httpPort.toString,
              ClusterAgent.OtoroshiWorkerInternalHttpsPortHeader -> env.httpsPort.toString
            )
            .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
            .withRequestTimeout(Duration(config.worker.timeout, TimeUnit.MILLISECONDS))
            .withMaybeProxyServer(config.proxy)
          request
            .post(user)
            .andThen { case Failure(_) =>
              request.ignore()
            }
            .filter { resp =>
              if (Cluster.logger.isDebugEnabled)
                Cluster.logger.debug(s"User token for ${token} created on the leader ${resp.status}")
              resp.ignore() // ignoreIf(resp.status != 201)
              resp.status == 201
            }
            .map(_ => Some(()))
        }
    } else {
      FastFuture.successful(None)
    }
  }

  def isSessionValid(id: String, reqOpt: Option[RequestHeader]): Future[Option[PrivateAppsUser]] = {
    if (env.clusterConfig.mode.isWorker) {
      Retry
        .retry(
          times = config.worker.retries,
          delay = config.retryDelay,
          factor = config.retryFactor,
          ctx = "leader-session-valid"
        ) { tryCount =>
          if (Cluster.logger.isDebugEnabled) Cluster.logger.debug(s"Checking if session $id is valid with a leader")
          env.MtlsWs
            .url(otoroshiUrl + s"/api/cluster/sessions/$id", config.mtlsConfig)
            .withHttpHeaders(
              "Host"                                             -> config.leader.host,
              ClusterAgent.OtoroshiWorkerIdHeader                -> ClusterConfig.clusterNodeId,
              ClusterAgent.OtoroshiWorkerNameHeader              -> config.worker.name,
              ClusterAgent.OtoroshiWorkerLocationHeader          -> s"$hostAddress",
              ClusterAgent.OtoroshiWorkerHttpPortHeader          -> env.exposedHttpPortInt.toString,
              ClusterAgent.OtoroshiWorkerHttpsPortHeader         -> env.exposedHttpsPortInt.toString,
              ClusterAgent.OtoroshiWorkerInternalHttpPortHeader  -> env.httpPort.toString,
              ClusterAgent.OtoroshiWorkerInternalHttpsPortHeader -> env.httpsPort.toString
            )
            .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
            .withRequestTimeout(Duration(config.worker.timeout, TimeUnit.MILLISECONDS))
            .withMaybeProxyServer(config.proxy)
            .get()
            .andThen { case Failure(failure) =>
              Cluster.logger.error(s"${env.clusterConfig.mode.name}] Failed to check session on leader", failure)
            }
            .filter { resp =>
              if (resp.status == 200 && Cluster.logger.isDebugEnabled) Cluster.logger.debug(s"Session $id is valid")
              resp.ignoreIf(resp.status != 200)
              resp.status == 200
            }
            .map(resp => PrivateAppsUser.fmt.reads(Json.parse(resp.body)).asOpt)
        }
        .recover { case e =>
          if (Cluster.logger.isDebugEnabled)
            Cluster.logger.debug(
              s"[${env.clusterConfig.mode.name}] Error while checking session with Otoroshi leader cluster"
            )
          workerSessionsCache.getIfPresent(id) match {
            case None        => {
              if (Cluster.logger.isDebugEnabled)
                Cluster.logger.debug(
                  s"[${env.clusterConfig.mode.name}] no local session found after leader call failed"
                )
              PrivateAppsUser.fromCookie(id, reqOpt)(env) match {
                case None        =>
                  if (Cluster.logger.isDebugEnabled)
                    Cluster.logger.debug(
                      s"[${env.clusterConfig.mode.name}] no cookie session found after leader call failed"
                    )
                  None
                case Some(local) =>
                  Cluster.logger.warn(
                    s"[${env.clusterConfig.mode.name}] using cookie created session as leader call failed !"
                  )
                  local.some
              }
            }
            case Some(local) => {
              Cluster.logger.warn(
                s"[${env.clusterConfig.mode.name}] Using locally created session as leader call failed !"
              )
              local.some
            }
          }
        }
    } else {
      FastFuture.successful(None)
    }
  }

  def createSession(user: PrivateAppsUser): Future[Option[PrivateAppsUser]] = {
    if (env.clusterConfig.mode.isWorker) {
      if (Cluster.logger.isDebugEnabled)
        Cluster.logger.debug(s"Creating session for ${user.email} on the leader: ${Json.prettyPrint(user.json)}")
      workerSessionsCache.put(user.randomId, user)
      Retry
        .retry(
          times = config.worker.retries,
          delay = config.retryDelay,
          factor = config.retryFactor,
          ctx = "leader-create-session"
        ) { tryCount =>
          val request = env.MtlsWs
            .url(otoroshiUrl + s"/api/cluster/sessions", config.mtlsConfig)
            .withHttpHeaders(
              "Host"                                             -> config.leader.host,
              "Content-Type"                                     -> "application/json",
              ClusterAgent.OtoroshiWorkerIdHeader                -> ClusterConfig.clusterNodeId,
              ClusterAgent.OtoroshiWorkerNameHeader              -> config.worker.name,
              ClusterAgent.OtoroshiWorkerLocationHeader          -> s"$hostAddress",
              ClusterAgent.OtoroshiWorkerHttpPortHeader          -> env.exposedHttpPortInt.toString,
              ClusterAgent.OtoroshiWorkerHttpsPortHeader         -> env.exposedHttpsPortInt.toString,
              ClusterAgent.OtoroshiWorkerInternalHttpPortHeader  -> env.httpPort.toString,
              ClusterAgent.OtoroshiWorkerInternalHttpsPortHeader -> env.httpsPort.toString
            )
            .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
            .withRequestTimeout(Duration(config.worker.timeout, TimeUnit.MILLISECONDS))
            .withMaybeProxyServer(config.proxy)
          request
            .post(user.toJson)
            .andThen { case Failure(failure) =>
              request.ignore()
              Cluster.logger.error(s"${env.clusterConfig.mode.name}] Failed to create session on leader", failure)
            }
            .filter { resp =>
              if (Cluster.logger.isDebugEnabled)
                Cluster.logger.debug(s"Session for ${user.name} created on the leader ${resp.status}")
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
      if (Cluster.logger.isTraceEnabled) Cluster.logger.trace(s"[${env.clusterConfig.mode.name}] Increment API $id")
      if (!apiIncrementsRef.get().contains(id)) {
        apiIncrementsRef.get().putIfAbsent(id, new AtomicLong(0L))
      }
      apiIncrementsRef.get().get(id).foreach(_.incrementAndGet())
    }
  }

  def incrementService(id: String, dataIn: Long, dataOut: Long): Unit = {
    if (env.clusterConfig.mode == ClusterMode.Worker) {
      if (Cluster.logger.isTraceEnabled) Cluster.logger.trace(s"[${env.clusterConfig.mode.name}] Increment Service $id")
      if (!servicesIncrementsRef.get().contains("global")) {
        servicesIncrementsRef.get().putIfAbsent("global", (new AtomicLong(0L), new AtomicLong(0L), new AtomicLong(0L)))
      }
      servicesIncrementsRef.get().get("global").foreach { case (calls, dataInCounter, dataOutCounter) =>
        calls.incrementAndGet()
        dataInCounter.addAndGet(dataIn)
        dataOutCounter.addAndGet(dataOut)
      }
      if (!servicesIncrementsRef.get().contains(id)) {
        servicesIncrementsRef.get().putIfAbsent(id, (new AtomicLong(0L), new AtomicLong(0L), new AtomicLong(0L)))
      }
      servicesIncrementsRef.get().get(id).foreach { case (calls, dataInCounter, dataOutCounter) =>
        calls.incrementAndGet()
        dataInCounter.addAndGet(dataIn)
        dataOutCounter.addAndGet(dataOut)
      }
    }
  }

  private def fromJson(what: String, value: JsValue, modern: Boolean): Option[Any] = {

    import collection.JavaConverters._

    what match {
      case "counter"        => Some(ByteString(value.as[Long].toString))
      case "string"         => Some(ByteString(value.as[String]))
      case "set" if modern  => {
        val list = scala.collection.mutable.HashSet.empty[ByteString]
        list.++=(value.as[JsArray].value.map(a => ByteString(a.as[String])))
        Some(list)
      }
      case "list" if modern => {
        val list = scala.collection.mutable.MutableList.empty[ByteString]
        list.++=(value.as[JsArray].value.map(a => ByteString(a.as[String])))
        Some(list)
      }
      case "hash" if modern => {
        val map = new LegitTrieMap[String, ByteString]()
        map.++=(value.as[JsObject].value.map(t => (t._1, ByteString(t._2.as[String]))))
        Some(map)
      }
      case "set"            => {
        val list = new java.util.concurrent.CopyOnWriteArraySet[ByteString]
        list.addAll(value.as[JsArray].value.map(a => ByteString(a.as[String])).asJava)
        Some(list)
      }
      case "list"           => {
        val list = new java.util.concurrent.CopyOnWriteArrayList[ByteString]
        list.addAll(value.as[JsArray].value.map(a => ByteString(a.as[String])).asJava)
        Some(list)
      }
      case "hash"           => {
        val map = new LegitConcurrentHashMap[String, ByteString]
        map.putAll(value.as[JsObject].value.map(t => (t._1, ByteString(t._2.as[String]))).asJava)
        Some(map)
      }
      case _                => None
    }
  }

  private def pollState(): Unit = {
    try {
      if (isPollingState.compareAndSet(false, true)) {
        if (Cluster.logger.isDebugEnabled)
          Cluster.logger.debug(
            s"[${env.clusterConfig.mode.name}] Fetching state from Otoroshi leader cluster (${DateTime.now()})"
          )
        val start = System.currentTimeMillis()
        Retry
          .retry(
            times = if (cannotServeRequests()) 10 else config.worker.state.retries,
            delay = config.retryDelay,
            factor = config.retryFactor,
            ctx = "leader-fetch-state"
          ) { tryCount =>
            val request  = env.MtlsWs
              .url(otoroshiUrl + s"/api/cluster/state?budget=${config.worker.state.timeout}", config.mtlsConfig)
              .withHttpHeaders(
                "Host"                                             -> config.leader.host,
                "Accept"                                           -> "application/x-ndjson",
                // "Accept-Encoding" -> "gzip",
                ClusterAgent.OtoroshiWorkerIdHeader                -> ClusterConfig.clusterNodeId,
                ClusterAgent.OtoroshiWorkerNameHeader              -> config.worker.name,
                ClusterAgent.OtoroshiWorkerLocationHeader          -> s"$hostAddress",
                ClusterAgent.OtoroshiWorkerHttpPortHeader          -> env.exposedHttpPortInt.toString,
                ClusterAgent.OtoroshiWorkerHttpsPortHeader         -> env.exposedHttpsPortInt.toString,
                ClusterAgent.OtoroshiWorkerInternalHttpPortHeader  -> env.httpPort.toString,
                ClusterAgent.OtoroshiWorkerInternalHttpsPortHeader -> env.httpsPort.toString,
                ClusterAgent.OtoroshiWorkerRelayRoutingHeader      -> env.clusterConfig.relay.json.stringify
              )
              .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
              .withRequestTimeout(Duration(config.worker.state.timeout, TimeUnit.MILLISECONDS))
              .withMaybeProxyServer(config.proxy)
              .withMethod("GET")
            val response = if (env.clusterConfig.streamed) {
              request.stream()
            } else {
              request.execute()
            }
            response
              .filter { resp =>
                resp.ignoreIf(resp.status != 200)
                resp.status == 200
              }
              .filterWithCause("State is too old !") { resp =>
                val responseFrom = resp.header("X-Data-From").map(_.toLong)
                val from         = new DateTime(responseFrom.getOrElse(0))
                val predicate    = from.isAfter(DateTime.now().minusMillis(env.clusterConfig.worker.dataStaleAfter.toInt))
                if (!predicate) {
                  val nodeName = resp.header("Otoroshi-Leader-Node-Name").getOrElse("--")
                  Cluster.logger.warn(
                    s"State data coming from '$nodeName' is too old (${from.toString()}). Maybe the leader node '$nodeName' has an issue and needs to be restarted. Failing state fetch !"
                  )
                  resp.ignore()
                }
                predicate
              }
              .flatMap { resp =>
                if (Cluster.logger.isDebugEnabled)
                  Cluster.logger.debug(
                    s"[${env.clusterConfig.mode.name}] Fetching state from Otoroshi leader cluster done ! (${DateTime.now()})"
                  )
                val store          = new LegitConcurrentHashMap[String, Any]()
                val expirations    = new LegitConcurrentHashMap[String, Long]()
                val responseFrom   = resp.header("X-Data-From").map(_.toLong)
                val responseDigest = resp.header("X-Data-Digest")
                val responseCount  = resp.header("X-Data-Count")
                val fromVersion    =
                  resp.header("Otoroshi-Leader-Node-Version").map(Version.apply).getOrElse(Version("0.0.0"))
                val counter        = new AtomicLong(0L)
                val digest         = MessageDigest.getInstance("SHA-256")
                val from           = new DateTime(responseFrom.getOrElse(0))

                val responseBody =
                  if (env.clusterConfig.streamed) resp.bodyAsSource else Source.single(resp.bodyAsBytes)
                responseBody
                  .via(env.clusterConfig.gunzip())
                  .via(Framing.delimiter(ByteString("\n"), 32 * 1024 * 1024, true))
                  .alsoTo(Sink.foreach { item =>
                    digest.update((item ++ ByteString("\n")).asByteBuffer)
                    counter.incrementAndGet()
                  })
                  .map(bs => Try(Json.parse(bs.utf8String)))
                  .collect { case Success(item) => item }
                  .runWith(Sink.foreach { item =>
                    val key   = (item \ "k").as[String]
                    val value = (item \ "v").as[JsValue]
                    val what  = (item \ "w").as[String]
                    val ttl   = (item \ "t").asOpt[Long].getOrElse(-1L)
                    fromJson(what, value, _modern).foreach(v => store.put(key, v))
                    if (ttl > -1L) {
                      expirations.put(key, ttl)
                    }
                  })
                  .flatMap { _ =>
                    val cliDigest = Hex.encodeHexString(digest.digest())
                    if (Cluster.logger.isDebugEnabled)
                      Cluster.logger.debug(
                        s"[${env.clusterConfig.mode.name}] Consumed state in ${System
                          .currentTimeMillis() - start} ms at try $tryCount. (${DateTime.now()})"
                      )
                    val valid     = (for {
                      count <- responseCount
                      dig   <- responseDigest
                    } yield {
                      val v = (count.toLong == counter.get()) && (dig == cliDigest)
                      if (!v) {
                        Cluster.logger.warn(
                          s"[${env.clusterConfig.mode.name}] state polling validation failed (${tryCount}): expected count: ${count} / ${counter
                            .get()} : ${count.toLong == counter.get()}, expected hash: ${dig} / ${cliDigest} : ${dig == cliDigest}, trying again !"
                        )
                      }
                      v
                    }).getOrElse(true)
                    if (valid) {
                      lastPoll.set(DateTime.now())
                      if (!store.isEmpty) {
                        firstSuccessfulStateFetchDone.compareAndSet(false, true)
                        if (Cluster.logger.isDebugEnabled)
                          Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] start swap (${DateTime.now()})")
                        env.datastores.asInstanceOf[SwappableInMemoryDataStores].swap(Memory(store, expirations))
                        if (Cluster.logger.isDebugEnabled)
                          Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] stop swap (${DateTime.now()})")
                        if (fromVersion.isBefore(env.otoroshiVersionSem)) {
                          // TODO: run other migrations ?
                          if (fromVersion.isBefore(Version("1.4.999"))) {
                            if (Cluster.logger.isDebugEnabled)
                              Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] running exporters migration !")
                            DataExporterConfigMigrationJob
                              .extractExporters(env)
                              .flatMap(c => DataExporterConfigMigrationJob.saveExporters(c, env))
                          }
                        }
                      }
                      FastFuture.successful(())
                    } else {
                      FastFuture.failed(
                        PollStateValidationError(
                          responseCount.map(_.toLong).getOrElse(0L),
                          counter.get(),
                          responseDigest.getOrElse("--"),
                          cliDigest
                        )
                      )
                    }
                  }
              }
          }
          .recover { case e =>
            Cluster.logger.error(
              s"[${env.clusterConfig.mode.name}] Error while trying to fetch state from Otoroshi leader cluster",
              e
            )
          }
          .andThen { case _ =>
            isPollingState.compareAndSet(true, false)
          }
      } else {
        if (Cluster.logger.isDebugEnabled)
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
        val oldApiIncr     = apiIncrementsRef.getAndSet(new LegitTrieMap[String, AtomicLong]())
        val oldServiceIncr =
          servicesIncrementsRef.getAndSet(new LegitTrieMap[String, (AtomicLong, AtomicLong, AtomicLong)]())
        //if (oldApiIncr.nonEmpty || oldServiceIncr.nonEmpty) {
        val start          = System.currentTimeMillis()
        Retry
          .retry(
            times = if (cannotServeRequests()) 10 else config.worker.quotas.retries,
            delay = config.retryDelay,
            factor = config.retryFactor,
            ctx = "leader-push-quotas"
          ) { tryCount =>
            if (Cluster.logger.isTraceEnabled)
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
            } yield ByteString(
              Json.stringify(
                Json.obj(
                  "typ"                       -> "globstats",
                  "cpu_usage"                 -> CpuInfo.cpuLoad(),
                  "load_average"              -> CpuInfo.loadAverage(),
                  "heap_used"                 -> (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024,
                  "heap_size"                 -> rt.totalMemory() / 1024 / 1024,
                  "live_threads"              -> ManagementFactory.getThreadMXBean.getThreadCount,
                  "live_peak_threads"         -> ManagementFactory.getThreadMXBean.getPeakThreadCount,
                  "daemon_threads"            -> ManagementFactory.getThreadMXBean.getDaemonThreadCount,
                  "rate"                      -> BigDecimal(
                    Option(rate)
                      .filterNot(a => a.isInfinity || a.isNaN || a.isNegInfinity || a.isPosInfinity)
                      .getOrElse(0.0)
                  ).setScale(3, RoundingMode.HALF_EVEN),
                  "duration"                  -> BigDecimal(
                    Option(duration)
                      .filterNot(a => a.isInfinity || a.isNaN || a.isNegInfinity || a.isPosInfinity)
                      .getOrElse(0.0)
                  ).setScale(3, RoundingMode.HALF_EVEN),
                  "overhead"                  -> BigDecimal(
                    Option(overhead)
                      .filterNot(a => a.isInfinity || a.isNaN || a.isNegInfinity || a.isPosInfinity)
                      .getOrElse(0.0)
                  ).setScale(3, RoundingMode.HALF_EVEN),
                  "dataInRate"                -> BigDecimal(
                    Option(dataInRate)
                      .filterNot(a => a.isInfinity || a.isNaN || a.isNegInfinity || a.isPosInfinity)
                      .getOrElse(0.0)
                  ).setScale(3, RoundingMode.HALF_EVEN),
                  "dataOutRate"               -> BigDecimal(
                    Option(dataOutRate)
                      .filterNot(a => a.isInfinity || a.isNaN || a.isNegInfinity || a.isPosInfinity)
                      .getOrElse(0.0)
                  ).setScale(3, RoundingMode.HALF_EVEN),
                  "concurrentHandledRequests" -> concurrentHandledRequests
                )
              ) + "\n"
            )) flatMap { stats =>
              val apiIncrSource     = Source(oldApiIncr.toList.map { case (key, inc) =>
                ByteString(Json.stringify(Json.obj("typ" -> "apkincr", "apk" -> key, "i" -> inc.get())) + "\n")
              })
              val serviceIncrSource = Source(oldServiceIncr.toList.map { case (key, (calls, dataIn, dataOut)) =>
                ByteString(
                  Json.stringify(
                    Json.obj(
                      "typ" -> "srvincr",
                      "srv" -> key,
                      "c"   -> calls.get(),
                      "di"  -> dataIn.get(),
                      "do"  -> dataOut.get()
                    )
                  ) + "\n"
                )
              })
              val globalSource      = Source.single(stats)
              val body              = apiIncrSource.concat(serviceIncrSource).concat(globalSource).via(env.clusterConfig.gzip())
              val wsBody            = SourceBody(body)
              val request           = env.MtlsWs
                .url(otoroshiUrl + s"/api/cluster/quotas?budget=${config.worker.quotas.timeout}", config.mtlsConfig)
                .withHttpHeaders(
                  "Host"                                             -> config.leader.host,
                  "Content-Type"                                     -> "application/x-ndjson",
                  // "Content-Encoding" -> "gzip",
                  ClusterAgent.OtoroshiWorkerIdHeader                -> ClusterConfig.clusterNodeId,
                  ClusterAgent.OtoroshiWorkerNameHeader              -> config.worker.name,
                  ClusterAgent.OtoroshiWorkerLocationHeader          -> s"$hostAddress",
                  ClusterAgent.OtoroshiWorkerHttpPortHeader          -> env.exposedHttpPortInt.toString,
                  ClusterAgent.OtoroshiWorkerHttpsPortHeader         -> env.exposedHttpsPortInt.toString,
                  ClusterAgent.OtoroshiWorkerInternalHttpPortHeader  -> env.httpPort.toString,
                  ClusterAgent.OtoroshiWorkerInternalHttpsPortHeader -> env.httpsPort.toString,
                  ClusterAgent.OtoroshiWorkerRelayRoutingHeader      -> env.clusterConfig.relay.json.stringify
                )
                .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
                .withRequestTimeout(Duration(config.worker.quotas.timeout, TimeUnit.MILLISECONDS))
                .withMaybeProxyServer(config.proxy)
                .withMethod("PUT")
                .withBody(wsBody)
              request
                .stream()
                .andThen { case Failure(_) =>
                  request.ignore()
                }
                .filter { resp =>
                  resp.ignore()
                  resp.status == 200
                }
                .andThen {
                  case Success(_) =>
                    if (Cluster.logger.isDebugEnabled)
                      Cluster.logger.debug(
                        s"[${env.clusterConfig.mode.name}] Pushed quotas in ${System.currentTimeMillis() - start} ms at try $tryCount."
                      )
                  case Failure(e) => e.printStackTrace()
                }
            }
          }
          .recover { case e =>
            e.printStackTrace()
            oldApiIncr.foreach { case (key, c) =>
              apiIncrementsRef.get().getOrElseUpdate(key, new AtomicLong(0L)).addAndGet(c.get())
            }
            oldServiceIncr.foreach { case (key, (counter1, counter2, counter3)) =>
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
          .andThen { case _ =>
            isPushingQuotas.compareAndSet(true, false)
          }
        //} else {
        //  isPushingQuotas.compareAndSet(true, false)
        //}
      } else {
        if (Cluster.logger.isDebugEnabled)
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
      config.leader.urls.filter(_.toLowerCase.contains("http://")) foreach { case url =>
        Cluster.logger.warn(s"A leader url uses unsecure transport ($url), you should use https instead")
      }
    }
    if (env.clusterConfig.relay.enabled) {
      Cluster.logger.warn("relay routing is enabled !")
      Cluster.logger.warn("be aware that this feature is EXPERIMENTAL and might not work as expected.")
      Cluster.logger.info(s"instance location: ${env.clusterConfig.relay.location.desc}")
    }
  }

  def startF(): Future[Unit] = FastFuture.successful(start())

  def start(): Unit = {
    if (config.mode == ClusterMode.Worker) {
      if (Cluster.logger.isDebugEnabled)
        Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] Starting cluster agent")
      pollRef.set(
        env.otoroshiScheduler.scheduleAtFixedRate(1.second, config.worker.state.pollEvery.millis)(
          utils.SchedulerHelper.runnable(
            pollState()
          )
        )
      )
      pushRef.set(
        env.otoroshiScheduler.scheduleAtFixedRate(1.second, config.worker.quotas.pushEvery.millis)(
          utils.SchedulerHelper.runnable(
            pushQuotas()
          )
        )
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

case class PollStateValidationError(expectedCount: Long, count: Long, expectedHash: String, hash: String)
    extends RuntimeException(s"PollStateValidationError($expectedCount, $count, $expectedHash, $hash)")
    with NoStackTrace

class SwappableInMemoryDataStores(
    configuration: Configuration,
    environment: Environment,
    lifecycle: ApplicationLifecycle,
    env: Env
) extends DataStores {

  import akka.stream.Materializer

  import scala.concurrent.duration._
  import scala.util.hashing.MurmurHash3

  lazy val redisStatsItems: Int  = configuration.betterGet[Option[Int]]("app.inmemory.windowSize").getOrElse(99)
  lazy val experimental: Boolean =
    configuration.betterGet[Option[Boolean]]("app.inmemory.experimental").getOrElse(false)
  lazy val actorSystem           =
    ActorSystem(
      "otoroshi-swapinmemory-system",
      configuration
        .getOptionalWithFileSupport[Configuration]("app.actorsystems.datastore")
        .map(_.underlying)
        .getOrElse(ConfigFactory.empty)
    )
  private val materializer       = Materializer(actorSystem)
  val _optimized                 = configuration.betterGetOptional[Boolean]("app.inmemory.optimized").getOrElse(false)
  val _modern                    = configuration.betterGetOptional[Boolean]("otoroshi.cluster.worker.modern").getOrElse(false)
  lazy val redis                 = if (_modern) {
    new ModernSwappableInMemoryRedis(_optimized, env, actorSystem)
  } else {
    new SwappableInMemoryRedis(_optimized, env, actorSystem)
  }

  override def before(
      configuration: Configuration,
      environment: Environment,
      lifecycle: ApplicationLifecycle
  ): Future[Unit] = {
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
      cancelRef.set(
        actorSystem.scheduler.scheduleAtFixedRate(1.second, 5.seconds)(
          utils.SchedulerHelper.runnable(
            // AWAIT: valid
            Await.result(writeStateToDisk(dbPath)(actorSystem.dispatcher, materializer), 10.seconds)
          )
        )(actorSystem.dispatcher)
      )
    }
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
    cancelRef.get().cancel()
    dbPathOpt.foreach { dbPath =>
      // AWAIT: valid
      Await.result(writeStateToDisk(dbPath)(actorSystem.dispatcher, materializer), 10.seconds)
    }
    actorSystem.terminate()
    FastFuture.successful(())
  }

  def swap(memory: Memory): Unit = {
    redis.swap(memory, env.clusterConfig.worker.swapStrategy)
  }

  private val cancelRef                 = new AtomicReference[Cancellable]()
  private val lastHash                  = new AtomicReference[Int](0)
  private val dbPathOpt: Option[String] = env.clusterConfig.worker.dbPath

  private def readStateFromDisk(source: Seq[String]): Unit = {
    if (Cluster.logger.isDebugEnabled) Cluster.logger.debug("Reading state from disk ...")
    val store       = new LegitConcurrentHashMap[String, Any]()
    val expirations = new LegitConcurrentHashMap[String, Long]()
    source.foreach { raw =>
      val item  = Json.parse(raw)
      val key   = (item \ "k").as[String]
      val value = (item \ "v").as[JsValue]
      val what  = (item \ "w").as[String]
      val ttl   = (item \ "t").asOpt[Long].getOrElse(-1L)
      fromJson(what, value, _modern).foreach(v => store.put(key, v))
      if (ttl > -1L) {
        expirations.put(key, ttl)
      }
    }
    redis.swap(Memory(store, expirations), env.clusterConfig.worker.swapStrategy)
  }

  private def fromJson(what: String, value: JsValue, modern: Boolean): Option[Any] = {

    import collection.JavaConverters._

    what match {
      case "counter"        => Some(ByteString(value.as[Long].toString))
      case "string"         => Some(ByteString(value.as[String]))
      case "set" if modern  => {
        val list = scala.collection.mutable.HashSet.empty[ByteString]
        list.++=(value.as[JsArray].value.map(a => ByteString(a.as[String])))
        Some(list)
      }
      case "list" if modern => {
        val list = scala.collection.mutable.MutableList.empty[ByteString]
        list.++=(value.as[JsArray].value.map(a => ByteString(a.as[String])))
        Some(list)
      }
      case "hash" if modern => {
        val map = new LegitTrieMap[String, ByteString]()
        map.++=(value.as[JsObject].value.map(t => (t._1, ByteString(t._2.as[String]))))
        Some(map)
      }
      case "set"            => {
        val list = new java.util.concurrent.CopyOnWriteArraySet[ByteString]
        list.addAll(value.as[JsArray].value.map(a => ByteString(a.as[String])).asJava)
        Some(list)
      }
      case "list"           => {
        val list = new java.util.concurrent.CopyOnWriteArrayList[ByteString]
        list.addAll(value.as[JsArray].value.map(a => ByteString(a.as[String])).asJava)
        Some(list)
      }
      case "hash"           => {
        val map = new LegitConcurrentHashMap[String, ByteString]
        map.putAll(value.as[JsObject].value.map(t => (t._1, ByteString(t._2.as[String]))).asJava)
        Some(map)
      }
      case _                => None
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
          if (Cluster.logger.isDebugEnabled) Cluster.logger.debug("Writing state to disk ...")
          java.nio.file.Files.write(file.toPath, content.getBytes(com.google.common.base.Charsets.UTF_8))
          lastHash.set(hash)
        }
      }
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
        case keys                 => {
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
            val what  = (json \ "what").as[String]
            (what match {
              case "counter" => redis.set(key, value.as[String])
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
        case keys                 => {
          Future.sequence(
            keys
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
