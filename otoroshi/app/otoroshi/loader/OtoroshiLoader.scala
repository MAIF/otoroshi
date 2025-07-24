package otoroshi.loader

import com.softwaremill.macwire._
import controllers.{Assets, AssetsComponents}
import otoroshi.actions._
import otoroshi.api.OtoroshiLoaderHelper.EnvContainer
import otoroshi.api.{GenericApiController, OtoroshiEnvHolder, OtoroshiLoaderHelper}
import otoroshi.controllers._
import otoroshi.controllers.adminapi._
import otoroshi.env.Env
import otoroshi.gateway._
import otoroshi.loader.modules._
import otoroshi.netty.ReactorNettyServer
import otoroshi.next.controllers.adminapi._
import otoroshi.next.controllers.{NgPluginsController, TryItController}
import otoroshi.next.tunnel.TunnelController
import play.api.ApplicationLoader.Context
import play.api.http.{DefaultHttpFilters, HttpErrorHandler, HttpRequestHandler}
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.EssentialFilter
import play.api.routing.Router
import play.api.{Application, ApplicationLoader, BuiltInComponentsFromContext, LoggerConfigurator}
import play.filters.HttpFiltersComponents
import router.Routes

import java.util.concurrent.atomic.AtomicReference

class OtoroshiLoader extends ApplicationLoader {

  def load(context: Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach { lc =>
      if (lc.loggerFactory.getClass.getName != "org.slf4j.helpers.NOPLoggerFactory") {
        lc.configure(context.environment, context.initialConfiguration, Map.empty)
      }
    }
    val components = new OtoroshiComponentsInstances(context, None, None, false)
    OtoroshiLoaderHelper.initOpenTelemetryLogger(context.initialConfiguration, components.env)
    otoroshi.utils.CustomizePekkoMediaTypesParser.hook(components.env)
    components.handlerRef.set(components.httpRequestHandler)
    components.env.handlerRef.set(components.httpRequestHandler)
    components.env.beforeListening()
    OtoroshiLoaderHelper.waitForReadiness(components)
    components.env.afterListening()
    ReactorNettyServer.classic(components.env).start(components.httpRequestHandler)
    components.application
  }
}

package object modules {

  class OtoroshiComponentsInstances(
      context: Context,
      getHttpPort: => Option[Int],
      getHttpsPort: => Option[Int],
      testing: Boolean
  ) extends BuiltInComponentsFromContext(context)
      with AssetsComponents
      with HttpFiltersComponents
      with AhcWSComponents
      with EnvContainer {

    // lazy val gzipFilterConfig                           = GzipFilterConfig.fromConfiguration(configuration)
    // lazy val gzipFilter                                 = wire[GzipFilter]
    override lazy val httpFilters: Seq[EssentialFilter] = Seq()

    lazy val circuitBreakersHolder: CircuitBreakersHolder = wire[CircuitBreakersHolder]

    implicit lazy val env: Env = OtoroshiEnvHolder.set(
      new Env(
        _configuration = configuration,
        environment = environment,
        lifecycle = applicationLifecycle,
        httpConfiguration = httpConfiguration,
        wsClient = wsClient,
        circuitBeakersHolder = circuitBreakersHolder,
        getHttpPort = getHttpPort,
        getHttpsPort = getHttpsPort,
        testing = testing
      )
    )

    lazy val reverseProxyAction: ReverseProxyAction = wire[ReverseProxyAction]
    lazy val httpHandler: HttpHandler               = wire[HttpHandler]
    lazy val webSocketHandler: WebSocketHandler     = wire[WebSocketHandler]
    lazy val filters                                = new DefaultHttpFilters(httpFilters: _*)

    override lazy val httpRequestHandler: HttpRequestHandler = wire[GatewayRequestHandler]
    override lazy val httpErrorHandler: HttpErrorHandler     = wire[ErrorHandler]

    lazy val handlerRef = new AtomicReference[HttpRequestHandler]()

    lazy val snowMonkey: SnowMonkey                     = wire[SnowMonkey]
    lazy val unAuthApiAction: UnAuthApiAction           = wire[UnAuthApiAction]
    lazy val apiAction: ApiAction                       = wire[ApiAction]
    lazy val backOfficeAction: BackOfficeAction         = wire[BackOfficeAction]
    lazy val backOfficeAuthAction: BackOfficeActionAuth = wire[BackOfficeActionAuth]
    lazy val privateAppsAction: PrivateAppsAction       = wire[PrivateAppsAction]

    lazy val swaggerController: SwaggerController                  = wire[SwaggerController]
    lazy val apiController: ApiController                          = wire[ApiController]
    lazy val analyticsController: AnalyticsController              = wire[AnalyticsController]
    lazy val auth0Controller: AuthController                       = wire[AuthController]
    lazy val backOfficeController: BackOfficeController            = wire[BackOfficeController]
    lazy val privateAppsController: PrivateAppsController          = wire[PrivateAppsController]
    lazy val u2fController: U2FController                          = wire[U2FController]
    lazy val clusterController: ClusterController                  = wire[ClusterController]
    lazy val clientValidatorController: ClientValidatorsController = wire[ClientValidatorsController]
    lazy val scriptApiController: ScriptApiController              = wire[ScriptApiController]
    lazy val tcpServiceApiController: TcpServiceApiController      = wire[TcpServiceApiController]
    lazy val pkiController: PkiController                          = wire[PkiController]
    lazy val usersController: UsersController                      = wire[UsersController]
    lazy val templatesController: TemplatesController              = wire[TemplatesController]

    lazy val healthController: HealthController = wire[HealthController]
    lazy val eventsController: EventsController = wire[EventsController]
    lazy val statsController: StatsController   = wire[StatsController]

    lazy val servicesController: ServicesController                       = wire[ServicesController]
    lazy val serviceGroupController: ServiceGroupController               = wire[ServiceGroupController]
    lazy val apiKeysController: ApiKeysController                         = wire[ApiKeysController]
    lazy val ApiKeysFromGroupController: ApiKeysFromGroupController       = wire[ApiKeysFromGroupController]
    lazy val ApiKeysFromServiceController: ApiKeysFromServiceController   = wire[ApiKeysFromServiceController]
    lazy val ApiKeysFromRouteController: ApiKeysFromRouteController       = wire[ApiKeysFromRouteController]
    lazy val jwtVerifierController: JwtVerifierController                 = wire[JwtVerifierController]
    lazy val authModulesController: AuthModulesController                 = wire[AuthModulesController]
    lazy val importExportController: ImportExportController               = wire[ImportExportController]
    lazy val snowMonkeyController: SnowMonkeyController                   = wire[SnowMonkeyController]
    lazy val canaryController: CanaryController                           = wire[CanaryController]
    lazy val certificatesController: CertificatesController               = wire[CertificatesController]
    lazy val globalConfigController: GlobalConfigController               = wire[GlobalConfigController]
    lazy val teamsController: TeamsController                             = wire[TeamsController]
    lazy val tenantsController: TenantsController                         = wire[TenantsController]
    lazy val dataExporterConfigController: DataExporterConfigController   = wire[DataExporterConfigController]
    lazy val routesController: NgRoutesController                         = wire[NgRoutesController]
    lazy val ngRouteCompositionsController: NgRouteCompositionsController = wire[NgRouteCompositionsController]
    lazy val backendsController: NgBackendsController                     = wire[NgBackendsController]
    lazy val pluginsController: NgPluginsController                       = wire[NgPluginsController]
    lazy val tryItController: TryItController                             = wire[TryItController]
    lazy val tunnelController: TunnelController                           = wire[TunnelController]
    lazy val entitiesController: EntitiesController                       = wire[EntitiesController]
    lazy val errorTemplatesController: ErrorTemplatesController           = wire[ErrorTemplatesController]
    lazy val genericApiController: GenericApiController                   = wire[GenericApiController]
    lazy val infosApiController: InfosApiController                       = wire[InfosApiController]
    lazy val apisController: ApisController                               = wire[ApisController]

    override lazy val assets: Assets = wire[Assets]
    lazy val router: Router = {
      // add the prefix string in local scope for the Routes constructor
      val prefix: String = "/"
      wire[Routes]
    }
  }
}
