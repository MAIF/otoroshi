package env

import java.lang.management.ManagementFactory
import java.rmi.registry.LocateRegistry
import java.security.KeyPairGenerator
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import akka.actor.{ActorSystem, PoisonPill, Scheduler}
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.stream.ActorMaterializer
import auth.AuthModuleConfig
import cluster.{ClusterAgent, _}
import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}
import events._
import gateway.CircuitBreakersHolder
import health.{HealthCheckerActor, StartHealthCheck}
import javax.management.remote.{JMXConnectorServerFactory, JMXServiceURL}
import models._
import org.joda.time.DateTime
import org.mindrot.jbcrypt.BCrypt
import otoroshi.script.{ScriptCompiler, ScriptManager}
import play.api._
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsNull, JsObject, JsValue, Json}
import play.api.libs.ws._
import play.api.libs.ws.ahc._
import security.{ClaimCrypto, IdGenerator}
import ssl.FakeKeyStore.KeystoreSettings
import ssl.{Cert, DynamicSSLEngineProvider, FakeKeyStore}
import storage.DataStores
import storage.cassandra.CassandraDataStores
import storage.inmemory.InMemoryDataStores
import storage.leveldb.LevelDbDataStores
import storage.mongo.MongoDataStores
import storage.redis.RedisDataStores
import storage.redis.next._
import utils.Metrics
import utils.http._
import otoroshi.tcp.{TcpProxy, TcpService}
import otoroshi.script.AccessValidatorRef
import storage.file.FileDbDataStores
import storage.http.HttpDbDataStores

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.io.Source
import scala.util.{Failure, Success}

case class SidecarConfig(
    serviceId: String,
    target: Target,
    from: String = "127.0.0.1",
    apiKeyClientId: Option[String] = None,
    strict: Boolean = true
)

class Env(val configuration: Configuration,
          val environment: Environment,
          val lifecycle: ApplicationLifecycle,
          wsClient: WSClient,
          val circuitBeakersHolder: CircuitBreakersHolder) {

  val logger = Logger("otoroshi-env")

  val otoroshiConfig: Configuration = (for {
    appConfig <- configuration.getOptional[Configuration]("app")
    otoConfig <- configuration.getOptional[Configuration]("otoroshi")
  } yield {
    val appConfigJson: JsObject =
      Json.parse(appConfig.underlying.root().render(ConfigRenderOptions.concise())).as[JsObject]
    val otoConfigJson: JsObject =
      Json.parse(otoConfig.underlying.root().render(ConfigRenderOptions.concise())).as[JsObject]
    val finalConfigJson1: JsObject = appConfigJson ++ otoConfigJson
    Configuration(ConfigFactory.parseString(Json.stringify(finalConfigJson1)))
  }) getOrElse configuration

  private lazy val xmasStart =
    DateTime.now().withMonthOfYear(12).withDayOfMonth(20).withMillisOfDay(0)
  private lazy val xmasStop =
    DateTime.now().withMonthOfYear(12).dayOfMonth().withMaximumValue().plusDays(1).withMillisOfDay(1)

  private lazy val halloweenStart =
    DateTime.now().withMonthOfYear(10).withDayOfMonth(31).withMillisOfDay(0)
  private lazy val halloweenStop =
    DateTime.now().withMonthOfYear(10).withDayOfMonth(31).plusDays(1).withMillisOfDay(1)

  def otoroshiLogo: String = {
    val now = DateTime.now()
    if (now.isAfter(xmasStart) && now.isBefore(xmasStop)) {
      "/__otoroshi_assets/images/otoroshi-logo-xmas.png"
    } else if (now.isAfter(halloweenStart) && now.isBefore(halloweenStop)) {
      "/__otoroshi_assets/images/otoroshi-logo-halloween3.png"
    } else {
      "/__otoroshi_assets/images/otoroshi-logo-color.png"
    }
  }

  val otoroshiActorSystem: ActorSystem = ActorSystem(
    "otoroshi-actor-system",
    configuration
      .getOptional[Configuration]("app.actorsystems.otoroshi")
      .map(_.underlying)
      .getOrElse(ConfigFactory.empty)
  )
  val otoroshiExecutionContext: ExecutionContext = otoroshiActorSystem.dispatcher
  val otoroshiScheduler: Scheduler               = otoroshiActorSystem.scheduler
  val otoroshiMaterializer: ActorMaterializer    = ActorMaterializer.create(otoroshiActorSystem)

  def timeout(duration: FiniteDuration): Future[Unit] = {
    val promise = Promise[Unit]
    otoroshiActorSystem.scheduler.scheduleOnce(duration) {
      promise.trySuccess(())
    }(otoroshiExecutionContext)
    promise.future
  }

  val (analyticsActor, alertsActor, healthCheckerActor) = {
    implicit val ec = otoroshiExecutionContext
    val aa          = otoroshiActorSystem.actorOf(AnalyticsActorSupervizer.props(this))
    val ala         = otoroshiActorSystem.actorOf(AlertsActorSupervizer.props(this))
    val ha          = otoroshiActorSystem.actorOf(HealthCheckerActor.props(this))
    timeout(FiniteDuration(5, SECONDS)).andThen {
      case _ if clusterConfig.mode != ClusterMode.Worker => ha ! StartHealthCheck()
    }
    (aa, ala, ha)
  }

  lazy val sidecarConfig: Option[SidecarConfig] = (
    configuration.getOptional[String]("app.sidecar.serviceId"),
    configuration.getOptional[String]("app.sidecar.target"),
    configuration.getOptional[String]("app.sidecar.from"),
    configuration.getOptional[String]("app.sidecar.apikey.clientId"),
    configuration.getOptional[Boolean]("app.sidecar.strict")
  ) match {
    case (Some(serviceId), Some(target), from, clientId, strict) =>
      val conf = SidecarConfig(
        serviceId = serviceId,
        target = Target(target.split("://")(1), target.split("://")(0)),
        from = from.getOrElse("127.0.0.1"),
        apiKeyClientId = clientId,
        strict = strict.getOrElse(true)
      )
      Some(conf)
    case a => None
  }

  lazy val maxWebhookSize: Int = configuration.getOptional[Int]("app.webhooks.size").getOrElse(100)

  lazy val clusterConfig: ClusterConfig = ClusterConfig(
    configuration.getOptional[Configuration]("otoroshi.cluster").getOrElse(Configuration.empty)
  )
  lazy val clusterAgent: ClusterAgent             = ClusterAgent(clusterConfig, this)
  lazy val clusterLeaderAgent: ClusterLeaderAgent = ClusterLeaderAgent(clusterConfig, this)

  lazy val globalMaintenanceMode: Boolean =
    configuration.getOptional[Boolean]("otoroshi.maintenanceMode").getOrElse(false)

  lazy val metricsEnabled: Boolean =
    configuration.getOptional[Boolean]("otoroshi.metrics.enabled").getOrElse(true)

  lazy val emptyContentLengthIsChunked: Boolean =
    configuration.getOptional[Boolean]("otoroshi.options.emptyContentLengthIsChunked").getOrElse(false)

  lazy val detectApiKeySooner: Boolean =
    configuration.getOptional[Boolean]("otoroshi.options.detectApiKeySooner").getOrElse(false)

  lazy val metricsAccessKey: Option[String] =
    configuration.getOptional[String]("otoroshi.metrics.accessKey").orElse(healthAccessKey)

  lazy val metricsEvery: FiniteDuration =
    configuration
      .getOptional[Long]("otoroshi.metrics.every")
      .map(v => FiniteDuration(v, TimeUnit.MILLISECONDS))
      .getOrElse(FiniteDuration(30, TimeUnit.SECONDS))

  lazy val requestTimeout: FiniteDuration =
    configuration.getOptional[Int]("app.proxy.requestTimeout").map(_.millis).getOrElse(1.hour)

  lazy val manualDnsResolve: Boolean =
    configuration.getOptional[Boolean]("otoroshi.options.manualDnsResolve").getOrElse(true)
  lazy val useOldHeadersComposition: Boolean =
    configuration.getOptional[Boolean]("otoroshi.options.useOldHeadersComposition").getOrElse(false)
  lazy val sendClientChainAsPem: Boolean =
    configuration.getOptional[Boolean]("otoroshi.options.sendClientChainAsPem").getOrElse(false)
  lazy val validateRequests: Boolean = configuration.getOptional[Boolean]("otoroshi.requests.validate").getOrElse(true)
  lazy val maxUrlLength: Long =
    Option(configuration.underlying.getBytes("otoroshi.requests.maxUrlLength")).map(_.toLong).getOrElse(4 * 1024L)
  lazy val maxCookieLength: Long =
    Option(configuration.underlying.getBytes("otoroshi.requests.maxCookieLength")).map(_.toLong).getOrElse(16 * 1024L)
  lazy val maxHeaderValueLength: Long = Option(
    configuration.underlying.getBytes("otoroshi.requests.maxHeaderValueLength")
  ).map(_.toLong).getOrElse(16 * 1024L)
  lazy val maxHeaderNameLength: Long =
    Option(configuration.underlying.getBytes("otoroshi.requests.maxHeaderNameLength")).map(_.toLong).getOrElse(128L)

  lazy val healthAccessKey: Option[String] = configuration.getOptional[String]("app.health.accessKey")
  lazy val overheadThreshold: Double       = configuration.getOptional[Double]("app.overheadThreshold").getOrElse(500.0)
  lazy val healthLimit: Double             = configuration.getOptional[Double]("app.health.limit").getOrElse(1000.0)
  lazy val throttlingWindow: Int           = configuration.getOptional[Int]("app.throttlingWindow").getOrElse(10)
  lazy val analyticsWindow: Int            = configuration.getOptional[Int]("app.analyticsWindow").getOrElse(30)
  lazy val eventsName: String              = configuration.getOptional[String]("app.eventsName").getOrElse("otoroshi")
  lazy val storageRoot: String             = configuration.getOptional[String]("app.storageRoot").getOrElse("otoroshi")
  lazy val useCache: Boolean               = configuration.getOptional[Boolean]("otoroshi.cache.enabled").getOrElse(false)
  lazy val cacheTtl: Int                   = configuration.getOptional[Int]("otoroshi.cache.ttl").filter(_ >= 2000).getOrElse(2000)
  lazy val useRedisScan: Boolean           = configuration.getOptional[Boolean]("app.redis.useScan").getOrElse(false)
  lazy val secret: String                  = configuration.getOptional[String]("play.crypto.secret").get
  lazy val secretSession: String =
    configuration.getOptional[String]("otoroshi.sessions.secret").map(_.padTo(16, "0").mkString("").take(16)).get
  lazy val sharedKey: String     = configuration.getOptional[String]("app.claim.sharedKey").get
  lazy val env: String           = configuration.getOptional[String]("app.env").getOrElse("prod")
  lazy val name: String          = configuration.getOptional[String]("app.instance.name").getOrElse("otoroshi")
  lazy val rack: String          = configuration.getOptional[String]("app.instance.rack").getOrElse("local")
  lazy val infraProvider: String = configuration.getOptional[String]("app.instance.provider").getOrElse("local")
  lazy val dataCenter: String    = configuration.getOptional[String]("app.instance.dc").getOrElse("local")
  lazy val zone: String          = configuration.getOptional[String]("app.instance.zone").getOrElse("local")
  lazy val region: String        = configuration.getOptional[String]("app.instance.region").getOrElse("local")
  lazy val liveJs: Boolean = configuration
    .getOptional[String]("app.env")
    .filter(_ == "dev")
    .map(_ => true)
    .orElse(configuration.getOptional[Boolean]("app.liveJs"))
    .getOrElse(false)

  lazy val exposeAdminApi: Boolean =
    if (clusterConfig.mode.isWorker) false
    else configuration.getOptional[Boolean]("app.adminapi.exposed").getOrElse(true)
  lazy val exposeAdminDashboard: Boolean =
    if (clusterConfig.mode.isWorker) false
    else configuration.getOptional[Boolean]("app.backoffice.exposed").getOrElse(true)
  lazy val adminApiProxyHttps: Boolean = configuration.getOptional[Boolean]("app.adminapi.proxy.https").getOrElse(false)
  lazy val adminApiProxyUseLocal: Boolean =
    configuration.getOptional[Boolean]("app.adminapi.proxy.local").getOrElse(true)
  lazy val domain: String = configuration.getOptional[String]("app.domain").getOrElse("oto.tools")
  lazy val adminApiSubDomain: String =
    configuration.getOptional[String]("app.adminapi.targetSubdomain").getOrElse("otoroshi-admin-internal-api")
  lazy val adminApiExposedSubDomain: String =
    configuration.getOptional[String]("app.adminapi.exposedSubdomain").getOrElse("otoroshi-api")
  lazy val backOfficeSubDomain: String =
    configuration.getOptional[String]("app.backoffice.subdomain").getOrElse("otoroshi")
  lazy val privateAppsSubDomain: String =
    configuration.getOptional[String]("app.privateapps.subdomain").getOrElse("privateapps")
  lazy val privateAppsPort: Option[Int] =
    configuration.getOptional[Int]("app.privateapps.port")
  lazy val retries: Int = configuration.getOptional[Int]("app.retries").getOrElse(5)

  lazy val backOfficeServiceId = configuration.getOptional[String]("app.adminapi.defaultValues.backOfficeServiceId").get
  lazy val backOfficeGroupId   = configuration.getOptional[String]("app.adminapi.defaultValues.backOfficeGroupId").get
  lazy val backOfficeApiKeyClientId =
    configuration.getOptional[String]("app.adminapi.defaultValues.backOfficeApiKeyClientId").get
  lazy val backOfficeApiKeyClientSecret =
    configuration.getOptional[String]("app.adminapi.defaultValues.backOfficeApiKeyClientSecret").get

  def composeMainUrl(subdomain: String): String = s"$subdomain.$domain"

  lazy val adminApiExposedHost = composeMainUrl(adminApiExposedSubDomain)
  lazy val adminApiHost        = composeMainUrl(adminApiSubDomain)
  lazy val backOfficeHost      = composeMainUrl(backOfficeSubDomain)
  lazy val privateAppsHost     = composeMainUrl(privateAppsSubDomain)

  lazy val procNbr = Runtime.getRuntime.availableProcessors()

  lazy val gatewayClient = {
    val parser: WSConfigParser = new WSConfigParser(configuration.underlying, environment.classLoader)
    val config: AhcWSClientConfig = new AhcWSClientConfig(wsClientConfig = parser.parse()).copy(
      keepAlive = configuration.getOptional[Boolean]("app.proxy.keepAlive").getOrElse(true)
      //setHttpClientCodecMaxChunkSize(1024 * 100)
    )
    val wsClientConfig: WSClientConfig = config.wsClientConfig.copy(
      userAgent = Some("Otoroshi-akka"),
      compressionEnabled = configuration.getOptional[Boolean]("app.proxy.compressionEnabled").getOrElse(false),
      idleTimeout =
        configuration.getOptional[Int]("app.proxy.idleTimeout").map(_.millis).getOrElse((2 * 60 * 1000).millis),
      connectionTimeout = configuration
        .getOptional[Int]("app.proxy.connectionTimeout")
        .map(_.millis)
        .getOrElse((2 * 60 * 1000).millis)
    )
    val ahcClient: AhcWSClient = AhcWSClient(
      config.copy(
        wsClientConfig = wsClientConfig
      )
    )(otoroshiMaterializer)

    WsClientChooser(
      ahcClient,
      new AkkWsClient(wsClientConfig, this)(otoroshiActorSystem, otoroshiMaterializer),
      sslconfig => {
        val wsClientConfig: WSClientConfig = config.wsClientConfig.copy(
          compressionEnabled = configuration.getOptional[Boolean]("app.proxy.compressionEnabled").getOrElse(false),
          idleTimeout =
            configuration.getOptional[Int]("app.proxy.idleTimeout").map(_.millis).getOrElse((2 * 60 * 1000).millis),
          connectionTimeout = configuration
            .getOptional[Int]("app.proxy.connectionTimeout")
            .map(_.millis)
            .getOrElse((2 * 60 * 1000).millis),
          ssl = sslconfig
        )
        AhcWSClient(
          config.copy(
            wsClientConfig = wsClientConfig
          )
        )(otoroshiMaterializer)
      },
      configuration.getOptional[Boolean]("app.proxy.useAkkaClient").getOrElse(false),
      this
    )
  }

  lazy val _internalClient = {
    val parser: WSConfigParser = new WSConfigParser(configuration.underlying, environment.classLoader)
    val config: AhcWSClientConfig = new AhcWSClientConfig(wsClientConfig = parser.parse()).copy(
      keepAlive = configuration.getOptional[Boolean]("app.proxy.keepAlive").getOrElse(true)
      //setHttpClientCodecMaxChunkSize(1024 * 100)
    )
    val wsClientConfig: WSClientConfig = config.wsClientConfig.copy(
      userAgent = Some("Otoroshi-akka"),
      compressionEnabled = configuration.getOptional[Boolean]("app.proxy.compressionEnabled").getOrElse(false),
      idleTimeout =
        configuration.getOptional[Int]("app.proxy.idleTimeout").map(_.millis).getOrElse((2 * 60 * 1000).millis),
      connectionTimeout = configuration
        .getOptional[Int]("app.proxy.connectionTimeout")
        .map(_.millis)
        .getOrElse((2 * 60 * 1000).millis)
    )
    WsClientChooser(
      wsClient,
      new AkkWsClient(wsClientConfig, this)(otoroshiActorSystem, otoroshiMaterializer),
      sslconfig => {
        val wsClientConfig: WSClientConfig = config.wsClientConfig.copy(
          compressionEnabled = configuration.getOptional[Boolean]("app.proxy.compressionEnabled").getOrElse(false),
          idleTimeout =
            configuration.getOptional[Int]("app.proxy.idleTimeout").map(_.millis).getOrElse((2 * 60 * 1000).millis),
          connectionTimeout = configuration
            .getOptional[Int]("app.proxy.connectionTimeout")
            .map(_.millis)
            .getOrElse((2 * 60 * 1000).millis),
          ssl = sslconfig
        )
        AhcWSClient(
          config.copy(
            wsClientConfig = wsClientConfig
          )
        )(otoroshiMaterializer)
      },
      configuration.getOptional[Boolean]("app.proxy.useAkkaClient").getOrElse(false),
      this
    )
  }

  // lazy val geoloc = new GeoLite2GeolocationHelper(this)
  // lazy val ua = new UserAgentHelper(this)

  lazy val statsd  = new StatsdWrapper(otoroshiActorSystem, this)
  lazy val metrics = new Metrics(this, lifecycle)

  lazy val hash = s"${System.currentTimeMillis()}"

  lazy val backOfficeSessionExp = configuration.getOptional[Long]("app.backoffice.session.exp").get

  lazy val exposedRootScheme = configuration.getOptional[String]("app.rootScheme").getOrElse("https")

  def rootScheme               = s"${exposedRootScheme}://"
  def exposedRootSchemeIsHttps = exposedRootScheme == "https"

  def Ws = _internalClient

  lazy val snowflakeSeed      = configuration.getOptional[Long]("app.snowflake.seed").get
  lazy val snowflakeGenerator = IdGenerator(snowflakeSeed)
  lazy val redirections: Seq[String] =
    configuration.getOptional[Seq[String]]("app.redirections").map(_.toSeq).getOrElse(Seq.empty[String])

  lazy val crypto = ClaimCrypto(sharedKey)

  object Headers {
    lazy val OtoroshiVizFromLabel         = configuration.getOptional[String]("otoroshi.headers.trace.label").get
    lazy val OtoroshiVizFrom              = configuration.getOptional[String]("otoroshi.headers.trace.from").get
    lazy val OtoroshiGatewayParentRequest = configuration.getOptional[String]("otoroshi.headers.trace.parent").get
    lazy val OtoroshiAdminProfile         = configuration.getOptional[String]("otoroshi.headers.request.adminprofile").get
    lazy val OtoroshiClientId             = configuration.getOptional[String]("otoroshi.headers.request.clientid").get
    lazy val OtoroshiSimpleApiKeyClientId =
      configuration.getOptional[String]("otoroshi.headers.request.simpleapiclientid").get
    lazy val OtoroshiClientSecret     = configuration.getOptional[String]("otoroshi.headers.request.clientsecret").get
    lazy val OtoroshiRequestId        = configuration.getOptional[String]("otoroshi.headers.request.id").get
    lazy val OtoroshiRequestTimestamp = configuration.getOptional[String]("otoroshi.headers.request.timestamp").get
    lazy val OtoroshiAuthorization    = configuration.getOptional[String]("otoroshi.headers.request.authorization").get
    lazy val OtoroshiBearer           = configuration.getOptional[String]("otoroshi.headers.request.bearer").get
    lazy val OtoroshiJWTAuthorization =
      configuration.getOptional[String]("otoroshi.headers.request.jwtAuthorization").get
    lazy val OtoroshiBasicAuthorization =
      configuration.getOptional[String]("otoroshi.headers.request.basicAuthorization").get
    lazy val OtoroshiBearerAuthorization =
      configuration.getOptional[String]("otoroshi.headers.request.bearerAuthorization").get
    lazy val OtoroshiProxiedHost  = configuration.getOptional[String]("otoroshi.headers.response.proxyhost").get
    lazy val OtoroshiGatewayError = configuration.getOptional[String]("otoroshi.headers.response.error").get
    lazy val OtoroshiErrorMsg     = configuration.getOptional[String]("otoroshi.headers.response.errormsg").get
    lazy val OtoroshiProxyLatency = configuration.getOptional[String]("otoroshi.headers.response.proxylatency").get
    lazy val OtoroshiUpstreamLatency =
      configuration.getOptional[String]("otoroshi.headers.response.upstreamlatency").get
    lazy val OtoroshiDailyCallsRemaining = configuration.getOptional[String]("otoroshi.headers.response.dailyquota").get
    lazy val OtoroshiMonthlyCallsRemaining =
      configuration.getOptional[String]("otoroshi.headers.response.monthlyquota").get
    lazy val OtoroshiState                = configuration.getOptional[String]("otoroshi.headers.comm.state").get
    lazy val OtoroshiStateResp            = configuration.getOptional[String]("otoroshi.headers.comm.stateresp").get
    lazy val OtoroshiClaim                = configuration.getOptional[String]("otoroshi.headers.comm.claim").get
    lazy val OtoroshiHealthCheckLogicTest = configuration.getOptional[String]("otoroshi.headers.healthcheck.test").get
    lazy val OtoroshiHealthCheckLogicTestResult =
      configuration.getOptional[String]("otoroshi.headers.healthcheck.testresult").get
    lazy val OtoroshiIssuer          = configuration.getOptional[String]("otoroshi.headers.jwt.issuer").get
    lazy val OtoroshiTrackerId       = configuration.getOptional[String]("otoroshi.headers.canary.tracker").get
    lazy val OtoroshiClientCertChain = configuration.getOptional[String]("otoroshi.headers.client.cert.chain").get
  }

  logger.info(s"Otoroshi version ${otoroshiVersion}")
  logger.info(s"Admin API exposed on http://$adminApiExposedHost:$port")
  logger.info(s"Admin UI  exposed on http://$backOfficeHost:$port")

  lazy val datastores: DataStores = {
    configuration.getOptional[String]("app.storage").getOrElse("redis") match {
      case _ if clusterConfig.mode == ClusterMode.Worker =>
        new SwappableInMemoryDataStores(configuration, environment, lifecycle, this)
      case "redis-pool" if clusterConfig.mode == ClusterMode.Leader =>
        new RedisCPDataStores(configuration, environment, lifecycle, this)
      case "redis-mpool" if clusterConfig.mode == ClusterMode.Leader =>
        new RedisMCPDataStores(configuration, environment, lifecycle, this)
      case "redis-cluster" if clusterConfig.mode == ClusterMode.Leader =>
        new RedisClusterDataStores(configuration, environment, lifecycle, this)
      case "redis-lf" if clusterConfig.mode == ClusterMode.Leader =>
        new RedisLFDataStores(configuration, environment, lifecycle, this)
      case "redis-sentinel" if clusterConfig.mode == ClusterMode.Leader =>
        new RedisSentinelDataStores(configuration, environment, lifecycle, this)
      case "redis-sentinel-lf" if clusterConfig.mode == ClusterMode.Leader =>
        new RedisSentinelLFDataStores(configuration, environment, lifecycle, this)
      case "redis" if clusterConfig.mode == ClusterMode.Leader =>
        new RedisDataStores(configuration, environment, lifecycle, this)
      case "inmemory" if clusterConfig.mode == ClusterMode.Leader =>
        new InMemoryDataStores(configuration, environment, lifecycle, this)
      case "leveldb" if clusterConfig.mode == ClusterMode.Leader =>
        new LevelDbDataStores(configuration, environment, lifecycle, this)
      case "file" if clusterConfig.mode == ClusterMode.Leader =>
        new FileDbDataStores(configuration, environment, lifecycle, this)
      case "http" if clusterConfig.mode == ClusterMode.Leader =>
        new HttpDbDataStores(configuration, environment, lifecycle, this)
      case "cassandra-naive" if clusterConfig.mode == ClusterMode.Leader =>
        new CassandraDataStores(true, configuration, environment, lifecycle, this)
      case "cassandra" if clusterConfig.mode == ClusterMode.Leader =>
        new CassandraDataStores(false, configuration, environment, lifecycle, this)
      case "mongo" if clusterConfig.mode == ClusterMode.Leader =>
        new MongoDataStores(configuration, environment, lifecycle, this)
      case "redis"             => new RedisDataStores(configuration, environment, lifecycle, this)
      case "inmemory"          => new InMemoryDataStores(configuration, environment, lifecycle, this)
      case "leveldb"           => new LevelDbDataStores(configuration, environment, lifecycle, this)
      case "file"              => new FileDbDataStores(configuration, environment, lifecycle, this)
      case "http"              => new HttpDbDataStores(configuration, environment, lifecycle, this)
      case "cassandra-naive"   => new CassandraDataStores(true, configuration, environment, lifecycle, this)
      case "cassandra"         => new CassandraDataStores(false, configuration, environment, lifecycle, this)
      case "mongo"             => new MongoDataStores(configuration, environment, lifecycle, this)
      case "redis-pool"        => new RedisCPDataStores(configuration, environment, lifecycle, this)
      case "redis-mpool"       => new RedisMCPDataStores(configuration, environment, lifecycle, this)
      case "redis-cluster"     => new RedisClusterDataStores(configuration, environment, lifecycle, this)
      case "redis-lf"          => new RedisLFDataStores(configuration, environment, lifecycle, this)
      case "redis-sentinel"    => new RedisSentinelDataStores(configuration, environment, lifecycle, this)
      case "redis-sentinel-lf" => new RedisSentinelLFDataStores(configuration, environment, lifecycle, this)
      case e                   => throw new RuntimeException(s"Bad storage value from conf: $e")
    }
  }

  val scriptingEnabled = configuration.getOptional[Boolean]("otoroshi.scripts.enabled").getOrElse(false)
  val scriptCompiler   = new ScriptCompiler(this)
  val scriptManager    = new ScriptManager(this).start()

  if (scriptingEnabled) logger.warn("Scripting is enabled on this Otoroshi instance !")

  if (useCache) logger.warn(s"Datastores will use cache to speed up operations")

  val servers = TcpService.runServers(this)

  datastores.before(configuration, environment, lifecycle)
  // geoloc.start()
  // ua.start()
  lifecycle.addStopHook(() => {
    implicit val ec = otoroshiExecutionContext
    // geoloc.stop()
    // ua.stop()
    healthCheckerActor ! PoisonPill
    analyticsActor ! PoisonPill
    alertsActor ! PoisonPill
    scriptManager.stop()
    clusterAgent.stop()
    clusterLeaderAgent.stop()
    otoroshiActorSystem.terminate()
    datastores.after(configuration, environment, lifecycle)
    servers.stop()
    // FastFuture.successful(())
  })

  lazy val port =
    configuration
      .getOptional[Int]("play.server.http.port")
      .orElse(configuration.getOptional[Int]("http.port"))
      .getOrElse(9999)

  lazy val httpsPort =
    configuration
      .getOptional[Int]("play.server.https.port")
      .orElse(configuration.getOptional[Int]("https.port"))
      .getOrElse(9999)

  lazy val defaultConfig = GlobalConfig(
    perIpThrottlingQuota = 500,
    throttlingQuota = 100000
  )

  lazy val backOfficeGroup = ServiceGroup(
    id = backOfficeGroupId,
    name = "Otoroshi Admin Api group"
  )

  lazy val backOfficeApiKey = ApiKey(
    backOfficeApiKeyClientId,
    backOfficeApiKeyClientSecret,
    "Otoroshi Backoffice ApiKey",
    backOfficeGroupId,
    validUntil = None
  )

  private lazy val backOfficeDescriptorHostHeader: String = s"$adminApiSubDomain.$domain"

  lazy val backOfficeDescriptor = ServiceDescriptor(
    id = backOfficeServiceId,
    groupId = backOfficeGroupId,
    name = "otoroshi-admin-api",
    env = "prod",
    subdomain = adminApiExposedSubDomain,
    domain = domain,
    targets = Seq(
      Target(
        host = if (adminApiProxyUseLocal) s"127.0.0.1:$port" else s"$adminApiHost:$port",
        scheme = if (adminApiProxyHttps) "https" else "http"
      )
    ),
    detectApiKeySooner = false,
    redirectToLocal = false,
    localHost = s"127.0.0.1:$port",
    forceHttps = false,
    additionalHeaders = Map(
      "Host" -> backOfficeDescriptorHostHeader
    ),
    publicPatterns = Seq("/health", "/metrics"),
    allowHttp10 = true,
    removeHeadersIn = Seq.empty,
    removeHeadersOut = Seq.empty,
    accessValidator = AccessValidatorRef(),
    missingOnlyHeadersIn = Map.empty,
    missingOnlyHeadersOut = Map.empty,
    stripPath = true
  )

  lazy val otoroshiVersion     = "1.4.14-dev"
  lazy val latestVersionHolder = new AtomicReference[JsValue](JsNull)
  lazy val checkForUpdates     = configuration.getOptional[Boolean]("app.checkForUpdates").getOrElse(true)


  lazy val jmxEnabled = configuration.getOptional[Boolean]("otoroshi.jmx.enabled").getOrElse(false)
  lazy val jmxPort    = configuration.getOptional[Int]("otoroshi.jmx.port").getOrElse(16000)

   if (jmxEnabled) {
     LocateRegistry.createRegistry(jmxPort)
     val mbs = ManagementFactory.getPlatformMBeanServer
     val url = new JMXServiceURL(s"service:jmx:rmi://localhost/jndi/rmi://localhost:$jmxPort/jmxrmi")
     val svr = JMXConnectorServerFactory.newJMXConnectorServer(url, null, mbs)
     svr.start()
     logger.info(s"Starting JMX remote server at 127.0.0.1:$jmxPort")
  }

  timeout(300.millis).andThen {
    case _ =>
      implicit val ec = otoroshiExecutionContext // internalActorSystem.dispatcher

      clusterAgent.warnAboutHttpLeaderUrls()
      if (clusterConfig.mode == ClusterMode.Leader) {
        logger.info(s"Running Otoroshi Leader agent !")
        clusterLeaderAgent.start()
      } else if (clusterConfig.mode == ClusterMode.Worker) {
        logger.info(s"Running Otoroshi Worker agent !")
        clusterAgent.startF()
      } else {
        DynamicSSLEngineProvider.setCurrentEnv(this)
        configuration.getOptional[Seq[String]]("otoroshi.ssl.cipherSuites").filterNot(_.isEmpty).foreach { s =>
          DynamicSSLEngineProvider.logger.warn(s"Using custom SSL cipher suites: ${s.mkString(", ")}")
        }
        configuration.getOptional[Seq[String]]("otoroshi.ssl.protocols").filterNot(_.isEmpty).foreach { p =>
          DynamicSSLEngineProvider.logger.warn(s"Using custom SSL protocols: ${p.mkString(", ")}}")
        }
      }
      datastores.globalConfigDataStore
        .isOtoroshiEmpty()
        .andThen {
          case Success(true) if clusterConfig.mode == ClusterMode.Worker => {
            logger.info(s"The main datastore seems to be empty, registering default config.")
            defaultConfig.save()(ec, this)
          }
          case Success(true) if clusterConfig.mode != ClusterMode.Worker => {
            logger.info(s"The main datastore seems to be empty, registering some basic services")
            val login    = configuration.getOptional[String]("app.adminLogin").getOrElse("admin@otoroshi.io")
            val password = configuration.getOptional[String]("app.adminPassword").getOrElse(IdGenerator.token(32))
            val headers: Seq[(String, String)] = configuration
              .getOptional[Seq[String]]("app.importFromHeaders")
              .map(headers => headers.toSeq.map(h => h.split(":")).map(h => (h(0).trim, h(1).trim)))
              .getOrElse(Seq.empty[(String, String)])
            if (configuration.has("app.importFrom")) {
              configuration.getOptional[String]("app.importFrom") match {
                case Some(url) if url.startsWith("http://") || url.startsWith("https://") => {
                  logger.info(s"Importing from URL: $url")
                  _internalClient.url(url).withHttpHeaders(headers: _*).get().fast.map { resp =>
                    val json = resp.json.as[JsObject]
                    datastores.globalConfigDataStore
                      .fullImport(json)(ec, this)
                      .andThen {
                        case Success(_) => logger.info("Successful import !")
                        case Failure(e) => logger.error("Error while importing initial data !", e)
                      }(ec)
                  }
                }
                case Some(path) => {
                  logger.info(s"Importing from: $path")
                  val source = Source.fromFile(path).getLines().mkString("\n")
                  val json   = Json.parse(source).as[JsObject]
                  datastores.globalConfigDataStore
                    .fullImport(json)(ec, this)
                    .andThen {
                      case Success(_) => logger.info("Successful import !")
                      case Failure(e) => logger.error("Error while importing initial data !", e)
                    }(ec)
                }
              }
            } else {
              configuration.getOptional[play.api.Configuration]("app.initialData") match {
                case Some(obj) => {
                  val importJson = Json
                    .parse(
                      obj.underlying
                        .root()
                        .render(ConfigRenderOptions.concise())
                    )
                    .as[JsObject]
                  logger.info(s"Importing from config file")
                  datastores.globalConfigDataStore
                    .fullImport(importJson)(ec, this)
                    .andThen {
                      case Success(_) => logger.info("Successful import !")
                      case Failure(e) => logger.error("Error while importing initial data !", e)
                    }(ec)
                }
                case _ => {
                  val defaultGroup = ServiceGroup("default", "default-group", "The default service group")
                  val defaultGroupApiKey = ApiKey("9HFCzZIPUQQvfxkq",
                                                  "lmwAGwqtJJM7nOMGKwSAdOjC3CZExfYC7qXd4aPmmseaShkEccAnmpULvgnrt6tp",
                                                  "default-apikey",
                                                  "default",
                                                  validUntil = None)
                  logger.info(
                    s"You can log into the Otoroshi admin console with the following credentials: $login / $password"
                  )
                  for {
                    _ <- defaultConfig.save()(ec, this)
                    _ <- backOfficeGroup.save()(ec, this)
                    _ <- defaultGroup.save()(ec, this)
                    _ <- backOfficeDescriptor.save()(ec, this)
                    _ <- backOfficeApiKey.save()(ec, this)
                    _ <- defaultGroupApiKey.save()(ec, this)
                    _ <- datastores.simpleAdminDataStore
                          .registerUser(login, BCrypt.hashpw(password, BCrypt.gensalt()), "Otoroshi Admin", None)(
                            ec,
                            this
                          )
                  } yield ()
                }
              }
            }
          }
        }
        .map { _ =>
          datastores.serviceDescriptorDataStore.findById(backOfficeServiceId)(ec, this).map {
            case Some(s) if !s.publicPatterns.contains("/health") =>
              logger.info("Updating BackOffice service to handle health check ...")
              s.copy(publicPatterns = s.publicPatterns :+ "/health").save()(ec, this)
            case Some(s) if !s.publicPatterns.contains("/metrics") =>
              logger.info("Updating BackOffice service to handle metrics ...")
              s.copy(publicPatterns = s.publicPatterns :+ "/metrics").save()(ec, this)
            case _ =>
          }
        }

      if (checkForUpdates) {
        otoroshiActorSystem.scheduler.schedule(5.second, 24.hours) {
          datastores.globalConfigDataStore
            .singleton()(otoroshiExecutionContext, this)
            .map { globalConfig =>
              var cleanVersion: Double = otoroshiVersion.toLowerCase() match {
                case v if v.contains("-snapshot") =>
                  v.replace(".", "").replace("v", "").replace("-snapshot", "").toDouble - 0.5
                case v if v.contains("-dev") =>
                  v.replace(".", "").replace("-dev", "").replace("v", "").toDouble - 0.5
                case v => v.replace(".", "").replace("-dev", "").replace("v", "").replace("-snapshot", "").toDouble
              }
              _internalClient
                .url("https://updates.otoroshi.io/api/versions/latest")
                .withRequestTimeout(10.seconds)
                .withHttpHeaders(
                  "Otoroshi-Version" -> otoroshiVersion,
                  "Otoroshi-Id"      -> globalConfig.otoroshiId
                )
                .get()
                .map { response =>
                  val body = response.json.as[JsObject]

                  val latestVersion      = (body \ "version_raw").as[String]
                  val latestVersionClean = (body \ "version_number").as[Double]
                  latestVersionHolder.set(
                    body ++ Json.obj(
                      "current_version_raw"    -> otoroshiVersion,
                      "current_version_number" -> cleanVersion,
                      "outdated"               -> (latestVersionClean > cleanVersion)
                    )
                  )
                  if (latestVersionClean > cleanVersion) {
                    logger.info(
                      s"A new version of Otoroshi ($latestVersion, your version is $otoroshiVersion) is available. You can download it on https://maif.github.io/otoroshi/ or at https://github.com/MAIF/otoroshi/releases/tag/$latestVersion"
                    )
                  }
                }
            }
            .andThen {
              case Failure(e) => e.printStackTrace()
            }
        }
      }
      ()
  }(otoroshiExecutionContext)

  timeout(5000.millis).andThen {
    case _ if clusterConfig.mode != ClusterMode.Worker => {
      implicit val ec = otoroshiExecutionContext
      implicit val ev = this
      for {
        _ <- datastores.globalConfigDataStore.migrate()
        _ <- datastores.certificatesDataStore
              .findAll()
              .map { certs =>
                val hasInitialCert =
                (configuration.has("otoroshi.ssl.initialCacert") && configuration.has("otoroshi.ssl.initialCert") && configuration
                  .has("otoroshi.ssl.initialCertKey")) || configuration.has("otoroshi.ssl.initialCerts")
                if (!hasInitialCert && certs.isEmpty) {
                  val foundOtoroshiCa         = certs.find(c => c.ca && c.id == Cert.OtoroshiCA)
                  val foundOtoroshiDomainCert = certs.find(c => c.domain == s"*.${this.domain}")
                  val keyPairGenerator        = KeyPairGenerator.getInstance(KeystoreSettings.KeyPairAlgorithmName)
                  keyPairGenerator.initialize(KeystoreSettings.KeyPairKeyLength)
                  val keyPair1 = keyPairGenerator.generateKeyPair()
                  val keyPair2 = keyPairGenerator.generateKeyPair()
                  val keyPair3 = keyPairGenerator.generateKeyPair()
                  val ca       = FakeKeyStore.createCA(s"CN=Otoroshi Root", FiniteDuration(365, TimeUnit.DAYS), keyPair1)
                  val caCert   = Cert(ca, keyPair1, None, false).enrich()
                  if (foundOtoroshiCa.isEmpty) {
                    logger.info(s"Generating CA certificate for Otoroshi self signed certificates ...")
                    caCert.copy(id = Cert.OtoroshiCA).save()
                  }
                  if (foundOtoroshiDomainCert.isEmpty) {
                    logger.info(s"Generating a self signed SSL certificate for https://*.${this.domain} ...")
                    val cert1 = FakeKeyStore.createCertificateFromCA(s"*.${this.domain}",
                                                                     FiniteDuration(365, TimeUnit.DAYS),
                                                                     keyPair2,
                                                                     ca,
                                                                     keyPair1)
                    Cert(cert1, keyPair2, foundOtoroshiCa.getOrElse(caCert), false).enrich().save()
                  }
                }
              }
        //_ <- clusterAgent.startF()
      } yield ()
    }
  }(otoroshiExecutionContext)

  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  lazy val sessionDomain = configuration.getOptional[String]("play.http.session.domain").get
  lazy val playSecret    = configuration.getOptional[String]("play.http.secret.key").get

  def sign(message: String): String =
    scala.util.Try {
      val mac = javax.crypto.Mac.getInstance("HmacSHA256")
      mac.init(new javax.crypto.spec.SecretKeySpec(playSecret.getBytes("utf-8"), "HmacSHA256"))
      org.apache.commons.codec.binary.Hex.encodeHexString(mac.doFinal(message.getBytes("utf-8")))
    } match {
      case scala.util.Success(s) => s
      case scala.util.Failure(e) => {
        logger.error(s"Error while signing: ${message}", e)
        throw e
      }
    }

  def extractPrivateSessionId(cookie: play.api.mvc.Cookie): Option[String] = {
    cookie.value.split("::").toList match {
      case signature :: value :: Nil if sign(value) == signature => Some(value)
      case _                                                     => None
    }
  }

  def extractPrivateSessionIdFromString(value: String): Option[String] = {
    value.split("::").toList match {
      case signature :: value :: Nil if sign(value) == signature => Some(value)
      case _                                                     => None
    }
  }

  def signPrivateSessionId(id: String): String = {
    val signature = sign(id)
    s"$signature::$id"
  }

  def createPrivateSessionCookies(host: String,
                                  id: String,
                                  desc: ServiceDescriptor,
                                  authConfig: AuthModuleConfig): Seq[play.api.mvc.Cookie] = {
    createPrivateSessionCookiesWithSuffix(host, id, authConfig.cookieSuffix(desc), authConfig.sessionMaxAge)
  }

  def createPrivateSessionCookiesWithSuffix(host: String,
                                            id: String,
                                            suffix: String,
                                            sessionMaxAge: Int): Seq[play.api.mvc.Cookie] = {
    if (host.endsWith(sessionDomain)) {
      Seq(
        play.api.mvc.Cookie(
          name = "oto-papps-" + suffix,
          value = signPrivateSessionId(id),
          maxAge = Some(sessionMaxAge),
          path = "/",
          domain = Some(sessionDomain),
          httpOnly = false
        )
      )
    } else {
      Seq(
        play.api.mvc.Cookie(
          name = "oto-papps-" + suffix,
          value = signPrivateSessionId(id),
          maxAge = Some(sessionMaxAge),
          path = "/",
          domain = Some(host),
          httpOnly = false
        ),
        play.api.mvc.Cookie(
          name = "oto-papps-" + suffix,
          value = signPrivateSessionId(id),
          maxAge = Some(sessionMaxAge),
          path = "/",
          domain = Some(sessionDomain),
          httpOnly = false
        )
      )
    }
  }

  def removePrivateSessionCookies(host: String,
                                  desc: ServiceDescriptor,
                                  authConfig: AuthModuleConfig): Seq[play.api.mvc.DiscardingCookie] = {
    removePrivateSessionCookiesWithSuffix(host, authConfig.cookieSuffix(desc))
  }

  def removePrivateSessionCookiesWithSuffix(host: String, suffix: String): Seq[play.api.mvc.DiscardingCookie] =
    Seq(
      play.api.mvc.DiscardingCookie(
        name = "oto-papps-" + suffix,
        path = "/",
        domain = Some(host)
      ),
      play.api.mvc.DiscardingCookie(
        name = "oto-papps-" + suffix,
        path = "/",
        domain = Some(sessionDomain)
      )
    )
}
