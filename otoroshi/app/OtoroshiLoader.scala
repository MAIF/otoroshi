import actions._
import cluster.ClusterController
import com.softwaremill.macwire._
import controllers._
import env.Env
import gateway._
import modules._
import otoroshi.script.ScriptApiController
import play.api.ApplicationLoader.Context
import play.api._
import play.api.http.{DefaultHttpFilters, HttpErrorHandler, HttpRequestHandler}
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.EssentialFilter
import play.api.routing.Router
import play.filters.HttpFiltersComponents
import router.Routes
import ssl.ClientValidatorsController
import utils.Metrics
import otoroshi.tcp.TcpServiceApiController
import play.api.inject.ApplicationLifecycle
import play.api.libs.ws.WSClient

class OtoroshiLoader extends ApplicationLoader {

  def load(context: Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment, context.initialConfiguration, Map.empty)
    }
    new OtoroshiComponentsInstances(context, None, None, false).application
  }
}

package object modules {

  class OtoroshiComponentsInstances(context: Context, getHttpPort: => Option[Int], getHttpsPort: => Option[Int], testing: Boolean)
      extends BuiltInComponentsFromContext(context)
      with AssetsComponents
      with HttpFiltersComponents
      with AhcWSComponents {

    // lazy val gzipFilterConfig                           = GzipFilterConfig.fromConfiguration(configuration)
    // lazy val gzipFilter                                 = wire[GzipFilter]
    override lazy val httpFilters: Seq[EssentialFilter] = Seq()

    lazy val circuitBreakersHolder: CircuitBreakersHolder = wire[CircuitBreakersHolder]

    implicit lazy val env: Env = new Env(
      configuration = configuration,
      environment = environment,
      lifecycle = applicationLifecycle,
      wsClient = wsClient,
      circuitBeakersHolder = circuitBreakersHolder,
      getHttpPort = getHttpPort,
      getHttpsPort = getHttpsPort,
      testing = testing
    )

    lazy val reverseProxyAction: ReverseProxyAction = wire[ReverseProxyAction]
    lazy val httpHandler: HttpHandler = wire[HttpHandler]
    lazy val webSocketHandler: WebSocketHandler = wire[WebSocketHandler]
    lazy val filters                            = new DefaultHttpFilters(httpFilters: _*)

    override lazy val httpRequestHandler: HttpRequestHandler = wire[GatewayRequestHandler]
    override lazy val httpErrorHandler: HttpErrorHandler     = wire[ErrorHandler]

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

    lazy val serviceGroupController    = wire[ServiceGroupController]

    override lazy val assets: Assets = wire[Assets]
    lazy val router: Router = {
      // add the prefix string in local scope for the Routes constructor
      val prefix: String = "/"
      wire[Routes]
    }
  }
}
