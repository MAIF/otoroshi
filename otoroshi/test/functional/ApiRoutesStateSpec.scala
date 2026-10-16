package functional

import com.typesafe.config.ConfigFactory
import otoroshi.env.Env
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.security.IdGenerator
import play.api.Configuration
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import scala.concurrent.duration.*

// the routes of an api are built by the proxy state job: a route of the api that is disabled must not reach the
// router, and each route of the api has to reach it once
class ApiRoutesStateSpec extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration =
    Configuration(
      ConfigFactory
        .parseString(s"""
          |otoroshi.next.state-sync-interval = 5
          |""".stripMargin)
        .resolve()
    ).withFallback(configuration)

  private val domain = "api-routes-state.oto.tools"

  "The routes of an api" should {
    "warm up" in {
      startOtoroshi()
    }
    "reach the router once each, and not when they are disabled" in {
      given env: Env = otoroshiComponents.env
      val backendServer = TargetService(None, "/", "application/json", _ => """{"ok":true}""").await()
      val backend       = ApiBackend(
        id = s"backend_${IdGenerator.uuid}",
        name = "backend",
        backend = NgBackend.empty.copy(
          targets = Seq(NgTarget(id = "target_1", hostname = "127.0.0.1", port = backendServer.port, tls = false))
        ),
        client = "default_backend_client"
      )
      val flow          = ApiFlows.empty
      def route(id: String, path: String, enabled: Boolean) = ApiRoute(
        id = id,
        enabled = enabled,
        frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"$domain$path"))),
        flowRef = flow.id,
        backend = backend.id
      )
      val enabledRoute  = route(s"apiroute_enabled_${IdGenerator.uuid}", "/enabled", enabled = true)
      val disabledRoute = route(s"apiroute_disabled_${IdGenerator.uuid}", "/disabled", enabled = false)
      val api           = Api(
        location = EntityLocation.default,
        id = s"api_${IdGenerator.uuid}",
        name = "api-routes-state",
        description = "",
        domain = domain,
        contextPath = "",
        version = "0.0.1",
        debugFlow = false,
        capture = false,
        exportReporting = false,
        groups = Seq.empty,
        state = ApiPublished,
        blueprint = ApiBlueprint.REST,
        testing = ApiTesting(),
        backends = Seq(backend),
        flows = Seq(flow),
        plans = Seq(
          ApiPlan(
            Json.obj(
              "id"                             -> "keyless",
              "name"                           -> "keyless",
              "status"                         -> "published",
              "access_mode_configuration_type" -> "keyless",
              "access_mode_configuration"      -> Json.obj("expr" -> "${req.ip}", "create_if_missing" -> true)
            )
          )
        ),
        routes = Seq(enabledRoute, disabledRoute)
      )
      env.datastores.apiDataStore.set(api).futureValue
      await(8.seconds) // let the proxy state job build the routes of the api

      def call(path: String): Int = ws
        .url(s"http://127.0.0.1:$port$path")
        .withHttpHeaders("Host" -> domain)
        .get()
        .futureValue
        .status

      try {
        call("/enabled") mustBe 200
        call("/disabled") mustBe 404
        val routesOfTheApi = env.proxyState.allRoutes().map(_.id).filter(id => id == enabledRoute.id || id == disabledRoute.id)
        routesOfTheApi mustBe Seq(enabledRoute.id)
      } finally {
        env.datastores.apiDataStore.delete(api).futureValue
        backendServer.stop()
      }
    }
    "reach the router with the secrets of the api resolved" in {
      given env: Env = otoroshiComponents.env
      val expected      = Option(System.getenv("HOME")).getOrElse("")
      val backendServer = TargetService
        .jsonFull(
          None,
          "/",
          r => (200, Json.obj("secret" -> r.headers.find(_.name().equalsIgnoreCase("X-Secret")).map(_.value()).getOrElse("none")), List.empty)
        )
        .await()
      val backend       = ApiBackend(
        id = s"backend_${IdGenerator.uuid}",
        name = "backend",
        backend = NgBackend.empty.copy(
          targets = Seq(NgTarget(id = "target_1", hostname = "127.0.0.1", port = backendServer.port, tls = false))
        ),
        client = "default_backend_client"
      )
      // a vault reference in the plugins of a flow of the api, resolved by the proxy state job
      val flow          = ApiFlows.empty.copy(
        plugins = NgPlugins(
          ApiFlows.empty.plugins.slots :+ NgPluginInstance(
            plugin = otoroshi.next.plugins.api.NgPluginHelper.pluginId[otoroshi.next.plugins.AdditionalHeadersIn],
            config = NgPluginInstanceConfig(Json.obj("headers" -> Json.obj("X-Secret" -> "${vault://env/HOME}")))
          )
        )
      )
      val apiRoute      = ApiRoute(
        id = s"apiroute_secret_${IdGenerator.uuid}",
        frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"$domain/secret"))),
        flowRef = flow.id,
        backend = backend.id
      )
      val api           = Api(
        location = EntityLocation.default,
        id = s"api_${IdGenerator.uuid}",
        name = "api-routes-secrets",
        description = "",
        domain = domain,
        contextPath = "",
        version = "0.0.1",
        debugFlow = false,
        capture = false,
        exportReporting = false,
        groups = Seq.empty,
        state = ApiPublished,
        blueprint = ApiBlueprint.REST,
        testing = ApiTesting(),
        backends = Seq(backend),
        flows = Seq(flow),
        plans = Seq(
          ApiPlan(
            Json.obj(
              "id"                             -> "keyless",
              "name"                           -> "keyless",
              "status"                         -> "published",
              "access_mode_configuration_type" -> "keyless",
              "access_mode_configuration"      -> Json.obj("expr" -> "${req.ip}", "create_if_missing" -> true)
            )
          )
        ),
        routes = Seq(apiRoute)
      )
      env.datastores.apiDataStore.set(api).futureValue
      await(8.seconds)
      try {
        val resp = ws.url(s"http://127.0.0.1:$port/secret").withHttpHeaders("Host" -> domain).get().futureValue
        withClue(resp.body)(resp.status mustBe 200)
        (resp.json \ "secret").as[String] mustBe expected
        // the route the proxy state holds for the api route is the one with its secrets resolved too
        val stored = env.proxyState.allRoutes().find(_.id == apiRoute.id).map(_.json.stringify).getOrElse("")
        stored must not include "vault://env/HOME"
      } finally {
        env.datastores.apiDataStore.delete(api).futureValue
        backendServer.stop()
      }
    }
    "shutdown" in {
      stopAll()
    }
  }
}
