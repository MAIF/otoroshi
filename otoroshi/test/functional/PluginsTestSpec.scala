package functional

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpHeader, HttpRequest}
import com.typesafe.config.ConfigFactory
import functional.Implicits.BetterFuture
import otoroshi.models.{ApiKey, EntityLocation, RoundRobin, RouteIdentifier}
import otoroshi.next.models._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins._
import otoroshi.utils.syntax.implicits.BetterJsValue
import play.api.Configuration
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.WSRequest

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
    val PLUGINS_HOST     = "plugins.oto.tools"

    val LOCAL_HOST = "local.oto.tools"

    def createRequestOtoroshiIORoute(plugins: Seq[NgPluginInstance] = Seq.empty, domain: String = "plugins.oto.tools") = {
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

    def createLocalRoute(plugins: Seq[NgPluginInstance] = Seq.empty,
                         responseStatus: Int = 200,
                         result: HttpRequest => JsValue,
                         responseHeaders: List[HttpHeader] = List.empty[HttpHeader],
                         domain: String = "local.oto.tools") = {
      val target = TargetService.jsonFull(
        Some(domain),
        "/api",
        r => (responseStatus, result(r), responseHeaders)
      ).await()

      val newRoute = NgRoute(
        location = EntityLocation.default,
        id = s"route_${IdGenerator.uuid}",
        name = "local-route",
        description = "local-route",
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
              hostname = "127.0.0.1",
              port = target.port,
              id = "local.target",
              tls = false
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

      val resp = createOtoroshiRoute(newRoute)
        .await()

      if (resp._2 == 201) {
        newRoute
      } else {
        throw new RuntimeException("failed to create a new local route")
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

    def getOutHeader(resp: WSRequest#Self#Response, headerName: String) = {
      resp.headers.find { case (k, _) => k.equalsIgnoreCase(headerName) }.map(_._2).flatMap(_.headOption)
    }

    def getInHeader(resp: WSRequest#Self#Response, headerName: String) = {
      val headers = Json
        .parse(resp.body)
        .as[JsValue]
        .select("headers")
        .as[Map[String, String]]
      headers.get(headerName)
    }

<<<<<<< HEAD
    "Allowed HTTP methods" in {
      val route = createRoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[AllowHttpMethods],
            config = NgPluginInstanceConfig(
              NgAllowedMethodsConfig(allowed = Seq("GET"), forbidden = Seq("POST")).json.as[JsObject]
            )
          )
        )
      )
=======
    //    "Allowed HTTP methods" in {
    //      val route = createRequestOtoroshiIORoute(Seq(
    //        NgPluginInstance(
    //          plugin = NgPluginHelper.pluginId[OverrideHost]
    //        ),
    //        NgPluginInstance(
    //          plugin = NgPluginHelper.pluginId[AllowHttpMethods],
    //          config = NgPluginInstanceConfig(
    //            NgAllowedMethodsConfig(allowed = Seq("GET"), forbidden = Seq("POST")).json.as[JsObject]
    //          )
    //        )))
    //
    //      val resp =  ws
    //        .url(s"http://127.0.0.1:$port/api")
    //        .withHttpHeaders(
    //          "Host" -> PLUGINS_HOST
    //        )
    //        .get()
    //        .futureValue
    //
    //      resp.status mustBe 200
    //
    //      val resp2 =  ws
    //        .url(s"http://127.0.0.1:$port/api")
    //        .withHttpHeaders(
    //          "Host" -> PLUGINS_HOST
    //        )
    //        .post(Json.obj())
    //        .futureValue
    //
    //      resp2.status mustBe 405
    //
    //      deleteOtoroshiRoute(route).await()
    //    }
    //    // FIX: test not complete
    //    "Apikeys" in {
    //      val route = createRequestOtoroshiIORoute(Seq(
    //        NgPluginInstance(
    //          plugin = NgPluginHelper.pluginId[OverrideHost]
    //        ),
    //        NgPluginInstance(
    //          plugin = NgPluginHelper.pluginId[ApikeyCalls],
    //          config = NgPluginInstanceConfig(
    //            NgApikeyCallsConfig(
    //
    //            ).json.as[JsObject]
    //          )
    //        )))
    //
    //      createApiKeys()
    //
    //      val unknownCaller =  ws
    //        .url(s"http://127.0.0.1:$port/api")
    //        .withHttpHeaders(
    //          "Host" -> PLUGINS_HOST
    //        )
    //        .get()
    //        .futureValue
    //
    //      unknownCaller.status mustBe 400
    //
    //      val authorizedCall =  ws
    //        .url(s"http://127.0.0.1:$port/api")
    //        .withHttpHeaders(
    //          "Host" -> PLUGINS_HOST,
    //          "Otoroshi-Client-Id"     -> getValidApiKeyForPluginsRoute.clientId,
    //          "Otoroshi-Client-Secret" -> getValidApiKeyForPluginsRoute.clientSecret
    //        )
    //        .get()
    //        .futureValue
    //
    //      authorizedCall.status mustBe 200
    //
    //      deleteApiKeys()
    //      deleteOtoroshiRoute(route).await()
    //    }
    //
    //    "Additional headers in" in {
    //      val route = createRequestOtoroshiIORoute(Seq(
    //        NgPluginInstance(
    //          plugin = NgPluginHelper.pluginId[OverrideHost]
    //        ),
    //        NgPluginInstance(
    //          plugin = NgPluginHelper.pluginId[AdditionalHeadersIn],
    //          config = NgPluginInstanceConfig(
    //            NgHeaderValuesConfig(
    //              headers = Map("foo" -> "bar")
    //            ).json.as[JsObject]
    //          )
    //        )))
    //
    //      createApiKeys()
    //
    //      val resp =  ws
    //        .url(s"http://127.0.0.1:$port/api")
    //        .withHttpHeaders(
    //          "Host" -> PLUGINS_HOST
    //        )
    //        .get()
    //        .futureValue
    //
    //      resp.status mustBe 200
    //      getInHeader(resp, "foo") mustBe Some("bar")
    //
    //      deleteApiKeys()
    //      deleteOtoroshiRoute(route).await()
    //    }
    //
    //    "Additional headers out" in {
    //      val route = createRequestOtoroshiIORoute(Seq(
    //        NgPluginInstance(
    //          plugin = NgPluginHelper.pluginId[OverrideHost]
    //        ),
    //        NgPluginInstance(
    //          plugin = NgPluginHelper.pluginId[AdditionalHeadersOut],
    //          config = NgPluginInstanceConfig(
    //            NgHeaderValuesConfig(
    //              headers = Map("foo" -> "bar")
    //            ).json.as[JsObject]
    //          )
    //        )))
    //
    //      createApiKeys()
    //
    //      val resp =  ws
    //        .url(s"http://127.0.0.1:$port/api")
    //        .withHttpHeaders(
    //          "Host" -> PLUGINS_HOST
    //        )
    //        .get()
    //        .futureValue
    //
    //      resp.status mustBe 200
    //      getOutHeader(resp, "foo") mustBe Some("bar")
    //
    //      deleteApiKeys()
    //      deleteOtoroshiRoute(route).await()
    //    }
    //
    //    "Headers validation" in {
    //      val route = createRequestOtoroshiIORoute(Seq(
    //        NgPluginInstance(
    //          plugin = NgPluginHelper.pluginId[OverrideHost]
    //        ),
    //        NgPluginInstance(
    //          plugin = NgPluginHelper.pluginId[HeadersValidation],
    //          config = NgPluginInstanceConfig(
    //            NgHeaderValuesConfig(
    //              headers = Map(
    //                "foo" -> "${req.headers.bar}",
    //                "raw_header" -> "raw_value"
    //              )
    //            ).json.as[JsObject]
    //          ))
    //      ))
    //
    //      val resp =  ws
    //        .url(s"http://127.0.0.1:$port/api")
    //        .withHttpHeaders(
    //          "Host" -> PLUGINS_HOST
    //        )
    //        .get()
    //        .futureValue
    //
    //      resp.status mustBe 400
    //
    //      val resp2 = ws
    //        .url(s"http://127.0.0.1:$port/api")
    //        .withHttpHeaders(
    //          "Host" -> PLUGINS_HOST,
    //          "foo"  -> "bar",
    //          "bar"  -> "bar",
    //          "raw_header" -> "raw_value"
    //        )
    //        .get()
    //        .futureValue
    //
    //      resp2.status mustBe 200
    //
    //      val resp3 = ws
    //        .url(s"http://127.0.0.1:$port/api")
    //        .withHttpHeaders(
    //          "Host" -> PLUGINS_HOST,
    //          "foo"  -> "bar",
    //          "raw_value" -> "bar"
    //        )
    //        .get()
    //        .futureValue
    //
    //      resp3.status mustBe 400
    //
    //       deleteOtoroshiRoute(route).await()
    //    }
    //
    //    "Missing headers in" in {
    //      val route = createRequestOtoroshiIORoute(Seq(
    //        NgPluginInstance(
    //          plugin = NgPluginHelper.pluginId[OverrideHost]
    //        ),
    //        NgPluginInstance(
    //          plugin = NgPluginHelper.pluginId[MissingHeadersIn],
    //          config = NgPluginInstanceConfig(
    //            NgHeaderValuesConfig(
    //              headers = Map(
    //                "foo" -> "foo_value",
    //                "foo2" -> "foo2_value"
    //              )
    //            ).json.as[JsObject]
    //          ))
    //      ))
    //
    //      val resp =  ws
    //        .url(s"http://127.0.0.1:$port/api")
    //        .withHttpHeaders(
    //          "Host" -> PLUGINS_HOST,
    //          "foo2" -> "client_value"
    //        )
    //        .get()
    //        .futureValue
    //
    //      resp.status mustBe 200
    //      getInHeader(resp, "foo") mustBe Some("foo_value")
    //      getInHeader(resp, "foo2") mustBe Some("client_value")
    //
    //      deleteOtoroshiRoute(route).await()
    //    }
    //
    //    "Missing headers out" in {
    //      val route = createRequestOtoroshiIORoute(Seq(
    //        NgPluginInstance(
    //          plugin = NgPluginHelper.pluginId[OverrideHost]
    //        ),
    //        NgPluginInstance(
    //          plugin = NgPluginHelper.pluginId[MissingHeadersOut],
    //          config = NgPluginInstanceConfig(
    //            NgHeaderValuesConfig(
    //              headers = Map(
    //                "foo" -> "foo_value"
    //              )
    //            ).json.as[JsObject]
    //          ))
    //      ))
    //
    //      val resp =  ws
    //        .url(s"http://127.0.0.1:$port/api")
    //        .withHttpHeaders(
    //          "Host" -> PLUGINS_HOST,
    //          "foo2" -> "client_value"
    //        )
    //        .get()
    //        .futureValue
    //
    //      resp.status mustBe 200
    //      getOutHeader(resp, "foo") mustBe Some("foo_value")
    //
    //      deleteOtoroshiRoute(route).await()
    //    }
    //
    //    "Override Host Header" in {
    //      val route = createRequestOtoroshiIORoute(Seq(
    //        NgPluginInstance(
    //          plugin = NgPluginHelper.pluginId[OverrideHost]
    //        )
    //      ))
    //
    //      val resp =  ws
    //        .url(s"http://127.0.0.1:$port/api")
    //        .withHttpHeaders(
    //          "Host" -> PLUGINS_HOST,
    //          "foo2" -> "client_value"
    //        )
    //        .get()
    //        .futureValue
    //
    //      resp.status mustBe 200
    //      getInHeader(resp, "host") mustBe Some("request.otoroshi.io")
    //
    //      deleteOtoroshiRoute(route).await()
    //    }

    "Override Location Header: redirect to relative path" in {
      val route = createLocalRoute(Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideLocationHeader]
        )
      ),
        responseStatus = 201,
        result = _ => {
          Json.obj("message" -> "creation done")
        },
        responseHeaders = List(RawHeader("Location", "/foo")))
>>>>>>> f8507a236 (task: create test for Override Location Header)

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> LOCAL_HOST,
        )
        .get()
        .futureValue

      resp.status mustBe 201
      getOutHeader(resp, "Location") mustBe Some("/foo")

      deleteOtoroshiRoute(route).await()
    }

    "Override Location Header: redirect to domain + path" in {
      val route = createLocalRoute(Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideLocationHeader]
        )
      ),
        responseStatus = 302,
        result = _ => {
          Json.obj("message" -> "creation done")
        },
        domain = "foo.oto.tools",
        responseHeaders = List(RawHeader("Location", s"http://location.oto.tools:$port/api"))
      )

      val finalTargetRoute = createLocalRoute(Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
      ),
        result = _ => {
          Json.obj("message" -> "reached the target route")
        },
        domain = "location.oto.tools")

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "foo.oto.tools",
        )
        .get()
        .futureValue

      resp.status mustBe 200

<<<<<<< HEAD
      val resp2 = ws
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
      val route = createRoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[ApikeyCalls],
            config = NgPluginInstanceConfig(
              NgApikeyCallsConfig(
              ).json.as[JsObject]
            )
          )
        )
      )

      createApiKeys()

      val unknownCaller = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST
        )
        .get()
        .futureValue

      unknownCaller.status mustBe 400

      val authorizedCall = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"                   -> PLUGINS_HOST,
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
      val route = createRoute(
        Seq(
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
          )
        )
      )

      createApiKeys()

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST
        )
        .get()
        .futureValue

      resp.status mustBe 200
      getInHeader(resp, "foo") mustBe Some("bar")

      deleteApiKeys()
      deleteOtoroshiRoute(route).await()
    }

    "Additional headers out" in {
      val route = createRoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[AdditionalHeadersOut],
            config = NgPluginInstanceConfig(
              NgHeaderValuesConfig(
                headers = Map("foo" -> "bar")
              ).json.as[JsObject]
            )
          )
        )
      )

      createApiKeys()

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST
        )
        .get()
        .futureValue

      resp.status mustBe 200
      getOutHeader(resp, "foo") mustBe Some("bar")

      deleteApiKeys()
      deleteOtoroshiRoute(route).await()
    }

    "Headers validation" in {
      val route = createRoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[HeadersValidation],
            config = NgPluginInstanceConfig(
              NgHeaderValuesConfig(
                headers = Map(
                  "foo"        -> "${req.headers.bar}",
                  "raw_header" -> "raw_value"
                )
              ).json.as[JsObject]
            )
          )
        )
      )

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST
        )
        .get()
        .futureValue

      resp.status mustBe 400

      val resp2 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"       -> PLUGINS_HOST,
          "foo"        -> "bar",
          "bar"        -> "bar",
          "raw_header" -> "raw_value"
        )
        .get()
        .futureValue

      resp2.status mustBe 200

      val resp3 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"      -> PLUGINS_HOST,
          "foo"       -> "bar",
          "raw_value" -> "bar"
        )
        .get()
        .futureValue

      resp3.status mustBe 400

      deleteOtoroshiRoute(route).await()
    }

    "Missing headers in" in {
      val route = createRoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[MissingHeadersIn],
            config = NgPluginInstanceConfig(
              NgHeaderValuesConfig(
                headers = Map(
                  "foo"  -> "foo_value",
                  "foo2" -> "foo2_value"
                )
              ).json.as[JsObject]
            )
          )
        )
      )

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST,
          "foo2" -> "client_value"
        )
        .get()
        .futureValue

      resp.status mustBe 200
      getInHeader(resp, "foo") mustBe Some("foo_value")
      getInHeader(resp, "foo2") mustBe Some("client_value")

      deleteOtoroshiRoute(route).await()
    }

    "Missing headers out" in {
      val route = createRoute(Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[MissingHeadersOut],
          config = NgPluginInstanceConfig(
            NgHeaderValuesConfig(
              headers = Map(
                "foo" -> "foo_value"
              )
            ).json.as[JsObject]
          ))
      ))

      val resp =  ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST,
          "foo2" -> "client_value"
        )
        .get()
        .futureValue

      resp.status mustBe 200
      getOutHeader(resp, "foo") mustBe Some("foo_value")

      deleteOtoroshiRoute(route).await()
    }

    "Override Host Header" in {
      val route = createRoute(Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        )
      ))

      val resp =  ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST,
          "foo2" -> "client_value"
        )
        .get()
        .futureValue

      resp.status mustBe 200
      getInHeader(resp, "host") mustBe Some("request.otoroshi.io")

=======
>>>>>>> f8507a236 (task: create test for Override Location Header)
      deleteOtoroshiRoute(route).await()
      deleteOtoroshiRoute(finalTargetRoute).await()
    }

    "shutdown" in {
      stopAll()
    }
  }
}
