package otoroshi.api

import actions._
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import otoroshi.cluster.ClusterMode
import com.softwaremill.macwire.wire
import com.typesafe.config.{Config, ConfigFactory}
import controllers._
import controllers.adminapi.{ApiKeysFromGroupController, _}
import env._
import gateway._
import otoroshi.modules.OtoroshiComponentsInstances
import otoroshi.api.OtoroshiLoaderHelper.EnvContainer
import otoroshi.storage.DataStores
import otoroshi.utils.metrics.Metrics
import play.api.http.{DefaultHttpFilters, HttpErrorHandler, HttpRequestHandler}
import play.api.inject.Injector
import play.api.libs.ws.WSClient
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.{ControllerComponents, DefaultControllerComponents, EssentialFilter}
import play.api.routing.Router
import play.api.{BuiltInComponents, Configuration, Logger, LoggerConfigurator}
import play.core.server.{AkkaHttpServerComponents, ServerConfig}
import play.filters.HttpFiltersComponents
import router.Routes
import otoroshi.ssl.DynamicSSLEngineProvider

import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._

object OtoroshiLoaderHelper {

  trait EnvContainer {
    def env: Env
  }

  private val logger = Logger("otoroshi-loader")

  def waitForReadiness(components: EnvContainer): Unit = {

    import scala.concurrent.duration._

    implicit val ec = components.env.otoroshiExecutionContext
    implicit val scheduler = components.env.otoroshiScheduler
    implicit val mat = components.env.otoroshiMaterializer

    val globalWait = components.env.configuration.getOptional[Boolean]("app.boot.globalWait").getOrElse(true)
    val waitForTlsInitEnabled = components.env.configuration.getOptional[Boolean]("app.boot.waitForTlsInit").getOrElse(true)
    val waitForPluginsSearch = components.env.configuration.getOptional[Boolean]("app.boot.waitForPluginsSearch").getOrElse(true)
    val waitForScriptsCompilation = components.env.configuration.getOptional[Boolean]("app.boot.waitForScriptsCompilation").getOrElse(true)
    val waitForFirstClusterFetchEnabled = components.env.configuration.getOptional[Boolean]("app.boot.waitForFirstClusterFetch").getOrElse(true)

    val globalWaitTimeout: Long = components.env.configuration.getOptional[Long]("app.boot.waitTimeout").getOrElse(60000)
    val waitForPluginsSearchTimeout: Long = components.env.configuration.getOptional[Long]("app.boot.waitForPluginsSearchTimeout").getOrElse(20000)
    val waitForScriptsCompilationTimeout: Long = components.env.configuration.getOptional[Long]("app.boot.waitForScriptsCompilationTimeout").getOrElse(30000)
    val waitForTlsInitTimeout: Long = components.env.configuration.getOptional[Long]("app.boot.waitForTlsInitTimeout").getOrElse(10000)
    val waitForFirstClusterFetchTimeout: Long = components.env.configuration.getOptional[Long]("app.boot.waitForFirstClusterFetchTimeout").getOrElse(10000)

    def timeout(duration: FiniteDuration): Future[Unit] = {
      val promise = Promise[Unit]
      scheduler.scheduleOnce(duration) {
        promise.trySuccess(())
      }
      promise.future
    }

    def waitForFirstClusterFetch(): Future[Unit] = {
      if (components.env.clusterConfig.mode == ClusterMode.Worker && waitForFirstClusterFetchEnabled) {
        logger.info("waiting for first cluster fetch ...")
        Future.firstCompletedOf(Seq(
          timeout(waitForFirstClusterFetchTimeout.millis),
          Source.tick(1.second, 1.second, ())
            .map { _ =>
              if (components.env.clusterConfig.mode == ClusterMode.Worker) !components.env.clusterAgent.cannotServeRequests() else true
            }
            .filter(identity)
            .take(1)
            .runWith(Sink.head)(mat)
            .map(_ => ())
        ))
      } else {
        FastFuture.successful(())
      }
    }

    def waitForPluginSearch(): Future[Unit] = {
      if (waitForPluginsSearch) {
        logger.info("waiting for plugins search and start ...")
        Future.firstCompletedOf(Seq(
          timeout(waitForPluginsSearchTimeout.millis),
          Source.tick(1.second, 1.second, ())
            .map { _ =>
              components.env.scriptManager.firstPluginsSearchDone()
            }
            .filter(identity)
            .take(1)
            .runWith(Sink.head)(mat)
            .map(_ => ())
        ))
      } else {
        FastFuture.successful(())
      }
    }

    def waitForTlsInit(): Future[Unit] = {
      if (waitForTlsInitEnabled) {
        logger.info("waiting for TLS initialization ...")
        Future.firstCompletedOf(Seq(
          timeout(waitForTlsInitTimeout.millis),
          Source.tick(1.second, 1.second, ())
            .map { _ =>
              DynamicSSLEngineProvider.isFirstSetupDone &&
                DynamicSSLEngineProvider.getCurrentEnv() != null
            }
            .filter(identity)
            .take(1)
            .runWith(Sink.head)(mat)
            .map(_ => ())
        ))
      } else {
        FastFuture.successful(())
      }
    }

    def waitForPluginsCompilation(): Future[Unit] = {
      if (waitForScriptsCompilation) {
        logger.info("waiting for scripts initialization ...")
        Future.firstCompletedOf(Seq(
          timeout(waitForScriptsCompilationTimeout.millis),
          Source.tick(1.second, 1.second, ())
            .mapAsync(1) { _ =>
              components.env.scriptManager.state()
            }
            .map(_.initialized)
            .filter(identity)
            .take(1)
            .runWith(Sink.head)(mat)
            .map(_ => ())
        ))
      } else {
        FastFuture.successful(())
      }
    }

    if (globalWait) {
      val start = System.currentTimeMillis()
      logger.info("waiting for subsystems initialization ...")
      val waiting = for {
        _ <- waitForFirstClusterFetch()
        _ <- waitForTlsInit()
        _ <- waitForPluginSearch()
        _ <- waitForPluginsCompilation()
      } yield ()
      Await.result(waiting, globalWaitTimeout.millis)
      logger.info(s"subsystems initialization done in ${System.currentTimeMillis() - start} ms.")
    } else {
      ()
    }
  }
}

class ProgrammaticOtoroshiComponents(_serverConfig: play.core.server.ServerConfig, _configuration: Config)
    extends AkkaHttpServerComponents
    with BuiltInComponents
    with AssetsComponents
    with AhcWSComponents
    with HttpFiltersComponents
    with EnvContainer {

  override lazy val configuration: Configuration = {
    val sslConfig = serverConfig.sslPort
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

  implicit lazy val env: Env = new Env(
    configuration = configuration,
    environment = environment,
    lifecycle = applicationLifecycle,
    wsClient = wsClient,
    circuitBeakersHolder = circuitBreakersHolder,
    getHttpPort = None,
    getHttpsPort = None,
    testing = false
  )

  override lazy val httpFilters: Seq[EssentialFilter] = Seq()

  lazy val filters = new DefaultHttpFilters(httpFilters: _*)

  lazy val reverseProxyAction: ReverseProxyAction = wire[ReverseProxyAction]
  lazy val httpHandler: HttpHandler = wire[HttpHandler]
  lazy val webSocketHandler: WebSocketHandler = wire[WebSocketHandler]

  override lazy val httpRequestHandler: HttpRequestHandler = wire[GatewayRequestHandler]
  override lazy val httpErrorHandler: HttpErrorHandler     = wire[ErrorHandler]
  override lazy val serverConfig                           = _serverConfig

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

  lazy val healthController          = wire[HealthController]
  lazy val eventsController          = wire[EventsController]
  lazy val statsController           = wire[StatsController]

  lazy val servicesController           = wire[ServicesController]
  lazy val serviceGroupController       = wire[ServiceGroupController]
  lazy val apiKeysController            = wire[ApiKeysController]
  lazy val ApiKeysFromGroupController   = wire[ApiKeysFromGroupController]
  lazy val ApiKeysFromServiceController = wire[ApiKeysFromServiceController]
  lazy val jwtVerifierController        = wire[JwtVerifierController]
  lazy val authModulesController        = wire[AuthModulesController]
  lazy val importExportController       = wire[ImportExportController]
  lazy val snowMonkeyController         = wire[SnowMonkeyController]
  lazy val canaryController             = wire[CanaryController]
  lazy val certificatesController       = wire[CertificatesController]
  lazy val globalConfigController       = wire[GlobalConfigController]
  lazy val teamsController              = wire[TeamsController]
  lazy val tenantsController            = wire[TenantsController]
  lazy val dataExporterConfigController = wire[DataExporterConfigController]

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
    OtoroshiLoaderHelper.waitForReadiness(components)
    server.httpPort.get + 1
    this
  }

  def startAndStopOnShutdown(): Otoroshi = {
    OtoroshiLoaderHelper.waitForReadiness(components)
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
