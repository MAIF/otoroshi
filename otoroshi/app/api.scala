package otoroshi.api

import actions._
import com.softwaremill.macwire.wire
import controllers._
import env._
import gateway.{CircuitBreakersHolder, ErrorHandler, GatewayRequestHandler, WebSocketHandler}
import play.api.http.{DefaultHttpFilters, HttpErrorHandler, HttpRequestHandler}
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.{ControllerComponents, DefaultControllerComponents}
import play.api.routing.Router
import play.api.{BuiltInComponents, Logger, LoggerConfigurator}
import play.core.server.{AkkaHttpServerComponents, ServerConfig}
import play.filters.HttpFiltersComponents
import router.Routes

private class ProgrammaticComponents(_serverConfig: play.core.server.ServerConfig)
    extends AkkaHttpServerComponents
    with BuiltInComponents
    with AssetsComponents
    with AhcWSComponents
    with HttpFiltersComponents {

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

  implicit lazy val env: Env = wire[Env]

  lazy val filters = new DefaultHttpFilters()

  lazy val webSocketHandler: WebSocketHandler = wire[WebSocketHandler]

  override lazy val httpRequestHandler: HttpRequestHandler = wire[GatewayRequestHandler]
  override lazy val httpErrorHandler: HttpErrorHandler     = wire[ErrorHandler]
  override lazy val serverConfig                           = _serverConfig

  lazy val apiAction            = wire[ApiAction]
  lazy val backOfficeAction     = wire[BackOfficeAction]
  lazy val backOfficeAuthAction = wire[BackOfficeActionAuth]
  lazy val privateAppsAction    = wire[PrivateAppsAction]

  lazy val swaggerController     = wire[SwaggerController]
  lazy val apiController         = wire[ApiController]
  lazy val auth0Controller       = wire[Auth0Controller]
  lazy val backOfficeController  = wire[BackOfficeController]
  lazy val privateAppsController = wire[PrivateAppsController]
  lazy val u2fController         = wire[U2FController]

  override lazy val assets: Assets = wire[Assets]

  lazy val router: Router = {
    val prefix: String = "/"
    wire[Routes]
  }
}

class Otoroshi(serverConfig: ServerConfig) {

  private lazy val components = new ProgrammaticComponents(serverConfig)

  private lazy val server = components.server

  def start(): Otoroshi = {
    server.httpPort.get + 1
    this
  }

  def stop(): Unit = server.stop()

  def stopOnShutdown(): Otoroshi = {
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      server.stop()
    }))
    this
  }
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
