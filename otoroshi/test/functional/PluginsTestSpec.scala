package functional

import com.typesafe.config.ConfigFactory
import functional.Implicits.BetterFuture
import otoroshi.models.{ApiKey, EntityLocation, RoundRobin, RouteIdentifier, ServiceGroupIdentifier}
import otoroshi.next.models.{NgBackend, NgClientConfig, NgDomainAndPath, NgFrontend, NgPluginInstance, NgPluginInstanceConfig, NgPlugins, NgRoute, NgTarget}
import otoroshi.next.plugins.{AdditionalHeadersIn, AllowHttpMethods, ApikeyCalls, NgAllowedMethodsConfig, NgApikeyCallsConfig, NgHeaderValuesConfig, OverrideHost, SnowMonkeyChaos}
import otoroshi.next.plugins.api.{NgPluginConfig, NgPluginHelper}
import otoroshi.utils.syntax.implicits.BetterJsValue
import otoroshi.utils.workflow.{WorkFlow, WorkFlowRequest, WorkFlowSpec}
import play.api.Configuration
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.duration.DurationInt

class PluginsTestSpec extends OtoroshiSpec {

  implicit lazy val mat = otoroshiComponents.materializer
  implicit lazy val env = otoroshiComponents.env

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString(s"""
           |{
           |}
       """.stripMargin)
        .resolve()
    ).withFallback(configuration)

  s"plugins" should {

    "warm up" in {
      startOtoroshi()
      getOtoroshiRoutes().futureValue // WARM UP
    }

    val PLUGINS_ROUTE_ID = "plugins-route"
    val PLUGINS_HOST = "plugins.oto.tools"

    def createRoute(plugins: Seq[NgPluginInstance] = Seq.empty, domain: String = "plugins.oto.tools") = {
      val newRoute = NgRoute(
        location = EntityLocation.default,
        id = PLUGINS_ROUTE_ID,
        name = "plugins-route",
        description = "plugins-route",
        enabled = true,
        debugFlow = false,
        capture = false,
        exportReporting = false,
        frontend = NgFrontend(
          domains = Seq(NgDomainAndPath(domain)),
          headers = Map(),
          cookies = Map(),
          query = Map(),
          methods = Seq(),
          stripPath = true,
          exact = false
        ),
        backend = NgBackend(
          targets = Seq(
            NgTarget(
              hostname = "request.otoroshi.io",
              port = 443,
              id = "request.otoroshi.io.target",
              tls = true
            )
          ),
          root = "/",
          rewrite = false,
          loadBalancing = RoundRobin,
          client = NgClientConfig.default
        ),
        plugins = NgPlugins(plugins),
        tags = Seq.empty,
        metadata = Map.empty
      )

      val result = createOtoroshiRoute(newRoute)
        .await()

      if (result._2 == 201) {
        newRoute
      } else {
        throw new RuntimeException("failed to create a new route")
      }
    }

    def createApiKeys() = {
      createOtoroshiApiKey(getValidApiKeyForPluginsRoute).await()
    }

    def deleteApiKeys() = {
      deleteOtoroshiApiKey(getValidApiKeyForPluginsRoute).await()
    }

    def getValidApiKeyForPluginsRoute = {
      ApiKey(
        clientId = "apikey-test",
        clientSecret = "1234",
        clientName = "apikey-test",
        authorizedEntities = Seq(RouteIdentifier(PLUGINS_ROUTE_ID))
      )
    }

    "Allowed HTTP methods" in {
      val route = createRoute(Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[AllowHttpMethods],
          config = NgPluginInstanceConfig(
            NgAllowedMethodsConfig(allowed = Seq("GET"), forbidden = Seq("POST")).json.as[JsObject]
          )
        )))

      val resp =  ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST
        )
        .get()
        .futureValue

      resp.status mustBe 200

      val resp2 =  ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST
        )
        .post(Json.obj())
        .futureValue

      resp2.status mustBe 405

      deleteOtoroshiRoute(route).await()
    }

    // FIX: test not complete
    "Apikeys" in {
      val route = createRoute(Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ApikeyCalls],
          config = NgPluginInstanceConfig(
            NgApikeyCallsConfig(

            ).json.as[JsObject]
          )
        )))

      createApiKeys()

      val unknownCaller =  ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST
        )
        .get()
        .futureValue

      unknownCaller.status mustBe 400

      val authorizedCall =  ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST,
          "Otoroshi-Client-Id"     -> getValidApiKeyForPluginsRoute.clientId,
          "Otoroshi-Client-Secret" -> getValidApiKeyForPluginsRoute.clientSecret
        )
        .get()
        .futureValue

      authorizedCall.status mustBe 200

      deleteApiKeys()
      deleteOtoroshiRoute(route).await()
    }

    "Additional headers in" in {
      val route = createRoute(Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[AdditionalHeadersIn],
          config = NgPluginInstanceConfig(
            NgHeaderValuesConfig(
              headers = Map("foo" -> "bar")
            ).json.as[JsObject]
          )
        )))

      createApiKeys()

      val resp =  ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST
        )
        .get()
        .futureValue

      resp.status mustBe 200
      val headers = Json.parse(resp.body).as[JsValue].select("headers").as[Map[String, String]]
      headers.get("foo") mustBe Some("bar")

      deleteApiKeys()
      deleteOtoroshiRoute(route).await()
    }

    "shutdown" in {
      stopAll()
    }
  }
}
