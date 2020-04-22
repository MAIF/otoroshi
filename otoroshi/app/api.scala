package otoroshi.api

import actions._
import akka.actor.ActorSystem
import akka.stream.Materializer
import com.softwaremill.macwire.wire
import com.typesafe.config.{Config, ConfigFactory}
import controllers._
import controllers.adminapi.{ApiKeysFromGroupController, _}
import env._
import gateway._
import otoroshi.storage.DataStores
import play.api.http.{DefaultHttpFilters, HttpErrorHandler, HttpRequestHandler}
import play.api.inject.Injector
import play.api.libs.ws.WSClient
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.{ControllerComponents, DefaultControllerComponents, EssentialFilter}
import play.api.routing.Router
import play.api.{BuiltInComponents, Configuration, LoggerConfigurator}
import play.core.server.{AkkaHttpServerComponents, ServerConfig}
import play.filters.HttpFiltersComponents
import router.Routes
import utils.Metrics

import scala.concurrent.ExecutionContext

class ProgrammaticOtoroshiComponents(_serverConfig: play.core.server.ServerConfig, _configuration: Config)
    extends AkkaHttpServerComponents
    with BuiltInComponents
    with AssetsComponents
    with AhcWSComponents
    with HttpFiltersComponents {

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
    server.httpPort.get + 1
    this
  }

  def startAndStopOnShutdown(): Otoroshi = {
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
