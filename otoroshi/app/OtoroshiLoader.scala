package otoroshi.loader

import com.softwaremill.macwire._
import otoroshi.controllers.adminapi.InfosApiController
import controllers.{Assets, AssetsComponents}
import otoroshi.netty.ReactorNettyServer
import otoroshi.actions._
import otoroshi.api.OtoroshiLoaderHelper.EnvContainer
import otoroshi.api.{GenericApiController, OtoroshiEnvHolder, OtoroshiLoaderHelper}
import otoroshi.models.DraftsApiController
import otoroshi.controllers._
import otoroshi.controllers.adminapi._
import otoroshi.env.Env
import otoroshi.gateway._
import otoroshi.loader.modules._
import otoroshi.next.controllers.{NgPluginsController, TryItController}
import otoroshi.next.controllers.adminapi._
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
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment, context.initialConfiguration, Map.empty)
    }
    val components = new OtoroshiComponentsInstances(context, None, None, false)
    OtoroshiLoaderHelper.initOpenTelemetryLogger(context.initialConfiguration, components.env)
    otoroshi.utils.CustomizeAkkaMediaTypesParser.hook(components.env)
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
    lazy val draftsApiController           = wire[DraftsApiController]
    lazy val infosApiController            = wire[InfosApiController]

    override lazy val assets: Assets = wire[Assets]
    lazy val router: Router = {
      // add the prefix string in local scope for the Routes constructor
      val prefix: String = "/"
      wire[Routes]
    }
  }
}
