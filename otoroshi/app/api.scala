package otoroshi.api

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.{Appender, Context}
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply
import ch.qos.logback.core.status.Status
import com.softwaremill.macwire.wire
import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
import otoroshi.controllers.adminapi.InfosApiController
import controllers.{Assets, AssetsComponents}
import org.slf4j.LoggerFactory
import otoroshi.netty.ReactorNettyServer
import otoroshi.actions._
import otoroshi.api.OtoroshiLoaderHelper.EnvContainer
import otoroshi.cluster.{ClusterConfig, ClusterMode}
import otoroshi.controllers._
import otoroshi.controllers.adminapi._
import otoroshi.env._
import otoroshi.gateway._
import otoroshi.metrics.Metrics
import otoroshi.metrics.opentelemetry.OtlpSettings
import otoroshi.next.controllers.{NgPluginsController, TryItController}
import otoroshi.next.controllers.adminapi._
import otoroshi.next.proxy.NgProxyStateLoaderJob
import otoroshi.next.tunnel.TunnelController
import otoroshi.ssl.DynamicSSLEngineProvider
import otoroshi.storage.DataStores
import otoroshi.utils.syntax.implicits._
import play.api.http.{DefaultHttpFilters, HttpErrorHandler, HttpRequestHandler}
import play.api.inject.Injector
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.WSClient
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.{ControllerComponents, DefaultControllerComponents, EssentialFilter}
import play.api.routing.Router
import play.api.{BuiltInComponents, Configuration, Logger, LoggerConfigurator}
import play.core.server.{AkkaHttpServerComponents, ServerConfig}
import play.filters.HttpFiltersComponents
import router.Routes

import java.util
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.Try

trait SubSystemInitializationState  {
  def isSuccessful: Boolean
  def duration: Long
  def task: String
}
object SubSystemInitializationState {
  case class NoWait(task: String)                                 extends SubSystemInitializationState {
    def isSuccessful: Boolean = true
    def duration: Long        = 0L
  }
  case class Successful(task: String, duration: Long)             extends SubSystemInitializationState {
    def isSuccessful: Boolean = true
  }
  case class Failed(task: String, err: Throwable, duration: Long) extends SubSystemInitializationState {
    def isSuccessful: Boolean = false
  }
  case class Timeout(task: String, duration: Long)                extends SubSystemInitializationState {
    def isSuccessful: Boolean = false
  }
}

object OtoroshiLoaderHelper {

  trait EnvContainer {
    def env: Env
  }

  private val logger = Logger("otoroshi-loader")

  def waitForReadiness(components: EnvContainer): Unit = {

    import scala.concurrent.duration._

    implicit val ec        = components.env.otoroshiExecutionContext
    implicit val scheduler = components.env.otoroshiScheduler
    implicit val mat       = components.env.otoroshiMaterializer

    val failOnTimeout                        =
      components.env.configuration.betterGetOptional[Boolean]("app.boot.failOnTimeout").getOrElse(false)
    val globalWait                           = components.env.configuration.betterGetOptional[Boolean]("app.boot.globalWait").getOrElse(true)
    val waitForTlsInitEnabled                =
      components.env.configuration.betterGetOptional[Boolean]("app.boot.waitForTlsInit").getOrElse(true)
    val waitForPluginsSearch                 =
      components.env.configuration.betterGetOptional[Boolean]("app.boot.waitForPluginsSearch").getOrElse(true)
    val waitForScriptsCompilation            =
      components.env.scriptingEnabled && components.env.configuration
        .betterGetOptional[Boolean]("app.boot.waitForScriptsCompilation")
        .getOrElse(true)
    val waitForFirstClusterFetchEnabled      =
      components.env.configuration.betterGetOptional[Boolean]("app.boot.waitForFirstClusterFetch").getOrElse(true)
    val waitForFirstClusterStateCacheEnabled =
      components.env.configuration.betterGetOptional[Boolean]("app.boot.waitForFirstClusterStateCache").getOrElse(true)
    val waitProxyStateSync                   =
      components.env.configuration.betterGetOptional[Boolean]("app.boot.waitProxyStateSync").getOrElse(true)

    val globalWaitTimeout: Long                    =
      components.env.configuration.betterGetOptional[Long]("app.boot.waitTimeout").getOrElse(60000)
    val waitForPluginsSearchTimeout: Long          =
      components.env.configuration.betterGetOptional[Long]("app.boot.waitForPluginsSearchTimeout").getOrElse(20000)
    val waitForScriptsCompilationTimeout: Long     =
      components.env.configuration.betterGetOptional[Long]("app.boot.waitForScriptsCompilationTimeout").getOrElse(30000)
    val waitForTlsInitTimeout: Long                =
      components.env.configuration.betterGetOptional[Long]("app.boot.waitForTlsInitTimeout").getOrElse(10000)
    val waitForFirstClusterFetchTimeout: Long      =
      components.env.configuration.betterGetOptional[Long]("app.boot.waitForFirstClusterFetchTimeout").getOrElse(10000)
    val waitForFirstClusterStateCacheTimeout: Long =
      components.env.configuration
        .betterGetOptional[Long]("app.boot.waitForFirstClusterStateCacheTimeout")
        .getOrElse(10000)
    val waitProxyStateSyncTimeout: Long            =
      components.env.configuration.betterGetOptional[Long]("app.boot.waitProxyStateSyncTimeout").getOrElse(10000)

    def timeout(task: String, duration: FiniteDuration): Future[SubSystemInitializationState] = {
      val promise = Promise[SubSystemInitializationState]
      scheduler.scheduleOnce(duration) {
        promise.trySuccess(SubSystemInitializationState.Timeout(task, duration.toMillis))
      }
      promise.future
    }

    def waitForFirstClusterStateCache(): Future[SubSystemInitializationState] = {
      val task  = "first-cluster-state-extraction"
      val start = System.currentTimeMillis()
      if (
        components.env.clusterConfig.mode == ClusterMode.Leader /*&& components.env.clusterConfig.autoUpdateState*/ && waitForFirstClusterStateCacheEnabled
      ) {
        logger.info("waiting for first cluster state extraction ...")
        Future.firstCompletedOf(
          Seq(
            timeout(task, waitForFirstClusterStateCacheTimeout.millis),
            Source
              .tick(1.second, 1.second, ())
              .map { _ =>
                if (
                  components.env.clusterConfig.mode == ClusterMode.Leader /* && components.env.clusterConfig.autoUpdateState*/
                )
                  components.env.clusterLeaderAgent.cachedTimestamp > 0L
                else true
              }
              .filter(identity)
              .take(1)
              .runWith(Sink.head)(mat)
              .map(_ => SubSystemInitializationState.Successful(task, System.currentTimeMillis() - start))
              .recover { case e: Throwable =>
                SubSystemInitializationState.Failed(task, e, System.currentTimeMillis() - start)
              }
          )
        )
      } else {
        FastFuture.successful(SubSystemInitializationState.NoWait(task))
      }
    }

    def waitForFirstClusterFetch(): Future[SubSystemInitializationState] = {
      val task  = "first-cluster-fetch"
      val start = System.currentTimeMillis()
      if (components.env.clusterConfig.mode == ClusterMode.Worker && waitForFirstClusterFetchEnabled) {
        logger.info("waiting for first cluster fetch ...")
        Future
          .firstCompletedOf(
            Seq(
              timeout(task, waitForFirstClusterFetchTimeout.millis),
              Source
                .tick(1.second, 1.second, ())
                .map { _ =>
                  if (components.env.clusterConfig.mode == ClusterMode.Worker)
                    !components.env.clusterAgent.cannotServeRequests()
                  else true
                }
                .filter(identity)
                .take(1)
                .runWith(Sink.head)(mat)
                .map(_ => SubSystemInitializationState.Successful(task, System.currentTimeMillis() - start))
                .recover { case e: Throwable =>
                  SubSystemInitializationState.Failed(task, e, System.currentTimeMillis() - start)
                }
            )
          )
          .flatMap {
            case SubSystemInitializationState.Failed(_, er, _) =>
              components.env.clusterAgent.loadStateFromBackup() map {
                case true  => SubSystemInitializationState.Successful(task, System.currentTimeMillis() - start)
                case false =>
                  SubSystemInitializationState.Failed(
                    task,
                    new RuntimeException(
                      s"failed to fetch cluster state (${er.getMessage}) and failed to load state from backup"
                    ),
                    System.currentTimeMillis() - start
                  )
              }
            case SubSystemInitializationState.Timeout(_, _)    =>
              components.env.clusterAgent.loadStateFromBackup() map {
                case true  => SubSystemInitializationState.Successful(task, System.currentTimeMillis() - start)
                case false =>
                  SubSystemInitializationState.Failed(
                    task,
                    new RuntimeException(
                      "failed to fetch cluster state (timeout) and failed to load state from backup"
                    ),
                    System.currentTimeMillis() - start
                  )
              }
            case r                                             =>
              components.env.proxyState.sync().map { _ =>
                r
              }
          }
      } else {
        FastFuture.successful(SubSystemInitializationState.NoWait(task))
      }
    }

    def waitForPluginSearch(): Future[SubSystemInitializationState] = {
      val task  = "plugins-search"
      val start = System.currentTimeMillis()
      if (waitForPluginsSearch) {
        logger.info("waiting for plugins search and start ...")
        Future.firstCompletedOf(
          Seq(
            timeout(task, waitForPluginsSearchTimeout.millis),
            Source
              .tick(1.second, 1.second, ())
              .map { _ =>
                components.env.scriptManager.firstPluginsSearchDone()
              }
              .filter(identity)
              .take(1)
              .runWith(Sink.head)(mat)
              .map(_ => SubSystemInitializationState.Successful(task, System.currentTimeMillis() - start))
              .recover { case e: Throwable =>
                SubSystemInitializationState.Failed(task, e, System.currentTimeMillis() - start)
              }
          )
        )
      } else {
        FastFuture.successful(SubSystemInitializationState.NoWait(task))
      }
    }

    def waitForTlsInit(): Future[SubSystemInitializationState] = {
      val task  = "tls-init"
      val start = System.currentTimeMillis()
      if (waitForTlsInitEnabled) {
        logger.info("waiting for TLS initialization ...")
        Future.firstCompletedOf(
          Seq(
            timeout(task, waitForTlsInitTimeout.millis),
            Source
              .tick(1.second, 1.second, ())
              .map { _ =>
                DynamicSSLEngineProvider.isFirstSetupDone &&
                DynamicSSLEngineProvider.getCurrentEnv() != null
              }
              .filter(identity)
              .take(1)
              .runWith(Sink.head)(mat)
              .map(_ => SubSystemInitializationState.Successful(task, System.currentTimeMillis() - start))
              .recover { case e: Throwable =>
                SubSystemInitializationState.Failed(task, e, System.currentTimeMillis() - start)
              }
          )
        )
      } else {
        FastFuture.successful(SubSystemInitializationState.NoWait(task))
      }
    }

    def waitForPluginsCompilation(): Future[SubSystemInitializationState] = {
      val task  = "plugins-compilation"
      val start = System.currentTimeMillis()
      if (waitForScriptsCompilation) {
        logger.info("waiting for scripts initialization ...")
        Future.firstCompletedOf(
          Seq(
            timeout(task, waitForScriptsCompilationTimeout.millis),
            Source
              .tick(1.second, 1.second, ())
              .mapAsync(1) { _ =>
                components.env.scriptManager.state()
              }
              .map(_.initialized)
              .filter(identity)
              .take(1)
              .runWith(Sink.head)(mat)
              .map(_ => SubSystemInitializationState.Successful(task, System.currentTimeMillis() - start))
              .recover { case e: Throwable =>
                SubSystemInitializationState.Failed(task, e, System.currentTimeMillis() - start)
              }
          )
        )
      } else {
        FastFuture.successful(SubSystemInitializationState.NoWait(task))
      }
    }

    def waitForFirstProxyStateSync(): Future[SubSystemInitializationState] = {
      val task  = "first-proxy-state-sync"
      val start = System.currentTimeMillis()
      if (waitProxyStateSync) {
        logger.info("waiting for proxy-state initialization ...")
        Future.firstCompletedOf(
          Seq(
            timeout(task, waitProxyStateSyncTimeout.millis),
            Source
              .tick(1.second, 1.second, ())
              .map { _ =>
                NgProxyStateLoaderJob.firstSync.get()
              }
              .filter(identity)
              .take(1)
              .runWith(Sink.head)(mat)
              .map(_ => SubSystemInitializationState.Successful(task, System.currentTimeMillis() - start))
              .recover { case e: Throwable =>
                SubSystemInitializationState.Failed(task, e, System.currentTimeMillis() - start)
              }
          )
        )
      } else {
        FastFuture.successful(SubSystemInitializationState.NoWait(task))
      }
    }

    if (globalWait) {
      val start     = System.currentTimeMillis()
      logger.info("waiting for subsystems initialization ...")
      val waiting   = for {
        task1 <- waitForFirstClusterStateCache()
        task2 <- waitForFirstClusterFetch()
        task3 <- waitForTlsInit()
        task4 <- waitForPluginSearch()
        task5 <- waitForPluginsCompilation()
        task6 <- waitForFirstProxyStateSync()
      } yield Seq(task1, task2, task3, task4, task5, task6)
      // AWAIT: valid
      val tasks     = Try(Await.result(waiting, globalWaitTimeout.millis))
        .getOrElse(Seq(SubSystemInitializationState.Timeout("global-timeout", globalWaitTimeout)))
      logger.info(s"subsystems initialization done in ${System.currentTimeMillis() - start} ms.")
      val errors    = tasks.filter(!_.isSuccessful)
      val successes = tasks.filter(_.isSuccessful)
      successes.foreach { task =>
        logger.debug(s"${task.task} success in ${task.duration} ms.")
      }
      errors.foreach {
        case SubSystemInitializationState.Failed(task, err, duration) =>
          logger.error(s"${task} failed in ${duration} ms.", err)
        case SubSystemInitializationState.Timeout(task, duration)     =>
          logger.error(s"${task} timeout after ${duration} ms.")
        case _                                                        =>
      }
      if (errors.nonEmpty && failOnTimeout) {
        logger.error(s"stopping because of subsystem${if (errors.size > 1) "s" else ""} initialization failure")
        System.exit(-1)
      }
    } else {
      ()
    }
  }

  def initOpenTelemetryLogger(configuration: Configuration, env: Env): Unit = {
    val jsonConfig = configuration.json
    jsonConfig.select("otoroshi").select("open-telemetry").select("server-logs").asOpt[JsObject].foreach { config =>
      val enabled = config.select("enabled").asOpt[Boolean].getOrElse(false)
      if (enabled) {
        val clusterConfig = ClusterConfig.fromRoot(configuration, env)
        val otlpConfig    = OtlpSettings.format.reads(config).get
        val lc            = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
        val rootLogger    = lc.getLogger("root")
        val appender      = new io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender()
        val sdk           = OtlpSettings.sdkFor("root-server-logs", clusterConfig.name, otlpConfig, OtoroshiEnvHolder.get())
        appender.setOpenTelemetry(sdk.sdk)
        appender.start()
        rootLogger.addAppender(appender)
      }
    }
  }
}

object OtoroshiEnvHolder {
  private val ref = new AtomicReference[Env]()
  def set(env: Env): Env = {
    ref.set(env)
    env
  }
  def get(): Env  = ref.get()
}

class ProgrammaticOtoroshiComponents(_serverConfig: play.core.server.ServerConfig, _configuration: Config)
    extends AkkaHttpServerComponents
    with BuiltInComponents
    with AssetsComponents
    with AhcWSComponents
    with HttpFiltersComponents
    with EnvContainer {

  override lazy val configuration: Configuration = {
    val sslConfig  = serverConfig.sslPort
      .map { sslPort =>
        s"""
        |https.port=$sslPort
        |play.server.https.port=$sslPort
      """.stripMargin
      }
      .getOrElse("")
    val httpConfig = serverConfig.port
      .map { httpPort =>
        s"""
         |http.port=$httpPort
         |play.server.http.port=$httpPort
      """.stripMargin
      }
      .getOrElse("")

    // Configuration(ConfigFactory.load()) ++ Configuration(_configuration) ++ Configuration(
    //   ConfigFactory.parseString(httpConfig + sslConfig)
    // )

    Configuration(
      ConfigFactory.parseString(httpConfig + sslConfig)
    ).withFallback(Configuration(_configuration)).withFallback(Configuration(ConfigFactory.load()))
  }

  LoggerConfigurator(environment.classLoader).foreach {
    _.configure(environment, configuration, Map.empty)
  }

  lazy val controllerComponents: ControllerComponents = DefaultControllerComponents(
    defaultActionBuilder,
    playBodyParsers,
    messagesApi,
    langs,
    fileMimeTypes,
    executionContext
  )

  lazy val circuitBreakersHolder: CircuitBreakersHolder = wire[CircuitBreakersHolder]

  implicit lazy val env: Env = OtoroshiEnvHolder
    .set(
      new Env(
        _configuration = configuration,
        environment = environment,
        lifecycle = applicationLifecycle,
        httpConfiguration = httpConfiguration,
        wsClient = wsClient,
        circuitBeakersHolder = circuitBreakersHolder,
        getHttpPort = None,
        getHttpsPort = None,
        testing = false
      )
    )
    .seffectOn(ev => OtoroshiLoaderHelper.initOpenTelemetryLogger(configuration, ev))

  override lazy val httpFilters: Seq[EssentialFilter] = Seq()

  lazy val filters = new DefaultHttpFilters(httpFilters: _*)

  lazy val reverseProxyAction: ReverseProxyAction = wire[ReverseProxyAction]
  lazy val httpHandler: HttpHandler               = wire[HttpHandler]
  lazy val webSocketHandler: WebSocketHandler     = wire[WebSocketHandler]

  override lazy val httpRequestHandler: HttpRequestHandler = wire[GatewayRequestHandler]
  override lazy val httpErrorHandler: HttpErrorHandler     = wire[ErrorHandler]
  override lazy val serverConfig                           = _serverConfig

  lazy val handlerRef = new AtomicReference[HttpRequestHandler]()

  lazy val metrics              = wire[Metrics]
  lazy val snowMonkey           = wire[SnowMonkey]
  lazy val unAuthApiAction      = wire[UnAuthApiAction]
  lazy val apiAction            = wire[ApiAction]
  lazy val backOfficeAction     = wire[BackOfficeAction]
  lazy val backOfficeAuthAction = wire[BackOfficeActionAuth]
  lazy val privateAppsAction    = wire[PrivateAppsAction]

  lazy val swaggerController         = wire[SwaggerController]
  lazy val apiController             = wire[ApiController]
  lazy val analyticsController       = wire[AnalyticsController]
  lazy val auth0Controller           = wire[AuthController]
  lazy val backOfficeController      = wire[BackOfficeController]
  lazy val privateAppsController     = wire[PrivateAppsController]
  lazy val u2fController             = wire[U2FController]
  lazy val clusterController         = wire[ClusterController]
  lazy val clientValidatorController = wire[ClientValidatorsController]
  lazy val scriptApiController       = wire[ScriptApiController]
  lazy val tcpServiceApiController   = wire[TcpServiceApiController]
  lazy val pkiController             = wire[PkiController]
  lazy val usersController           = wire[UsersController]
  lazy val templatesController       = wire[TemplatesController]

  lazy val healthController = wire[HealthController]
  lazy val eventsController = wire[EventsController]
  lazy val statsController  = wire[StatsController]

  lazy val servicesController            = wire[ServicesController]
  lazy val serviceGroupController        = wire[ServiceGroupController]
  lazy val apiKeysController             = wire[ApiKeysController]
  lazy val ApiKeysFromGroupController    = wire[ApiKeysFromGroupController]
  lazy val ApiKeysFromServiceController  = wire[ApiKeysFromServiceController]
  lazy val ApiKeysFromRouteController    = wire[ApiKeysFromRouteController]
  lazy val jwtVerifierController         = wire[JwtVerifierController]
  lazy val authModulesController         = wire[AuthModulesController]
  lazy val importExportController        = wire[ImportExportController]
  lazy val snowMonkeyController          = wire[SnowMonkeyController]
  lazy val canaryController              = wire[CanaryController]
  lazy val certificatesController        = wire[CertificatesController]
  lazy val globalConfigController        = wire[GlobalConfigController]
  lazy val teamsController               = wire[TeamsController]
  lazy val tenantsController             = wire[TenantsController]
  lazy val dataExporterConfigController  = wire[DataExporterConfigController]
  lazy val routesController              = wire[NgRoutesController]
  lazy val ngRouteCompositionsController = wire[NgRouteCompositionsController]
  lazy val backendsController            = wire[NgBackendsController]
  lazy val frontendsController           = wire[NgFrontendsController]
  lazy val pluginsController             = wire[NgPluginsController]
  lazy val tryItController               = wire[TryItController]
  lazy val tunnelController              = wire[TunnelController]
  lazy val entitiesController            = wire[EntitiesController]
  lazy val errorTemplatesController      = wire[ErrorTemplatesController]
  lazy val genericApiController          = wire[GenericApiController]
  lazy val infosApiController            = wire[InfosApiController]

  override lazy val assets: Assets = wire[Assets]

  lazy val router: Router = {
    val prefix: String = "/"
    wire[Routes]
  }
}

class Otoroshi(serverConfig: ServerConfig, configuration: Config = ConfigFactory.empty) {

  private lazy val components = new ProgrammaticOtoroshiComponents(serverConfig, configuration)

  private lazy val server = components.server

  def start(): Otoroshi = {
    otoroshi.utils.CustomizeAkkaMediaTypesParser.hook(components.env)
    components.env.beforeListening()
    OtoroshiLoaderHelper.waitForReadiness(components)
    components.env.afterListening()
    ReactorNettyServer.classic(components.env).start(components.httpRequestHandler)
    server.httpPort.get + 1
    this
  }

  def startAndStopOnShutdown(): Otoroshi = {
    components.handlerRef.set(components.httpRequestHandler)
    otoroshi.utils.CustomizeAkkaMediaTypesParser.hook(components.env)
    components.env.beforeListening()
    OtoroshiLoaderHelper.waitForReadiness(components)
    components.env.afterListening()
    ReactorNettyServer.classic(components.env).start(components.httpRequestHandler)
    server.httpPort.get + 1
    stopOnShutdown()
  }

  def stop(): Unit = server.stop()

  def stopOnShutdown(): Otoroshi = {
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      server.stop()
    }))
    this
  }

  implicit val materializer: Materializer         = components.materializer
  implicit val executionContext: ExecutionContext = components.executionContext
  implicit val env: Env                           = components.env

  val dataStores: DataStores = components.env.datastores
  val ws: WSClient           = components.wsClient
  val system: ActorSystem    = components.actorSystem
  val injector: Injector     = components.injector
}

object Otoroshi {
  def apply(serverConfig: ServerConfig, configuration: Config = ConfigFactory.empty): Otoroshi =
    new Otoroshi(serverConfig, configuration)
}

object Main {

  def main(args: Array[String]): Unit = {
    new Otoroshi(
      ServerConfig(
        address = "0.0.0.0",
        port = Some(8888)
      )
    ).start().stopOnShutdown()
  }
}
