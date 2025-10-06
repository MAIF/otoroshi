package functional

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.{Host, RawHeader}
import akka.http.scaladsl.model.ws.{Message, WebSocketRequest}
import akka.http.scaladsl.model.{HttpHeader, HttpRequest}
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import com.typesafe.config.ConfigFactory
import functional.Implicits.BetterFuture
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatest.time.{Minutes, Span}
import otoroshi.models._
import otoroshi.next.models._
import otoroshi.next.plugins.api.{NgPluginHelper, YesWebsocketBackend}
import otoroshi.next.plugins._
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterJsValueReader}
import play.api.http.Status
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.WSRequest
import play.api.{Configuration, Logger}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future, Promise}

class PluginsTestSpec extends OtoroshiSpec with BeforeAndAfterAll {

  implicit lazy val mat = otoroshiComponents.materializer
  implicit lazy val env = otoroshiComponents.env

  val logger = Logger("otoroshi-tests-plugins")

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString(s"""
           |{
           |}
       """.stripMargin)
        .resolve()
    ).withFallback(configuration)

  override def beforeAll(): Unit = {
    startOtoroshi()
    getOtoroshiRoutes().futureValue // WARM UP
  }

  override def afterAll(): Unit = {
    stopAll()
  }

  s"plugins" should {
    val PLUGINS_ROUTE_ID = "plugins-route"
    val PLUGINS_HOST = "plugins.oto.tools"

    val LOCAL_HOST = "local.oto.tools"

    def createRequestOtoroshiIORoute(
                                      plugins: Seq[NgPluginInstance] = Seq.empty,
                                      domain: String = "plugins.oto.tools",
                                      id: String = PLUGINS_ROUTE_ID) = {
      val newRoute = NgRoute(
        location = EntityLocation.default,
        id = id,
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

      if (result._2 == Status.CREATED) {
        newRoute
      } else {
        throw new RuntimeException("failed to create a new route")
      }
    }

    def createLocalRoute(plugins: Seq[NgPluginInstance] = Seq.empty,
                         responseStatus: Int = Status.OK,
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

      if (resp._2 == Status.CREATED) {
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
      val headers = Json.parse(resp.body)
        .as[JsValue]
        .select("headers").as[Map[String, String]]
      headers.get(headerName)
    }

    "Allowed HTTP methods" in {
      val route = createRequestOtoroshiIORoute(Seq(
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

      resp.status mustBe Status.OK

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
      val route = createRequestOtoroshiIORoute(Seq(
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

      authorizedCall.status mustBe Status.OK

      deleteApiKeys()
      deleteOtoroshiRoute(route).await()
    }

    "Additional headers in" in {
      val route = createRequestOtoroshiIORoute(Seq(
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

      resp.status mustBe Status.OK
      getInHeader(resp, "foo") mustBe Some("bar")

      deleteApiKeys()
      deleteOtoroshiRoute(route).await()
    }

    "Additional headers out" in {
      val route = createRequestOtoroshiIORoute(Seq(
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
        )))

      createApiKeys()

      val resp =  ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK
      getOutHeader(resp, "foo") mustBe Some("bar")

      deleteApiKeys()
      deleteOtoroshiRoute(route).await()
    }

    "Headers validation" in {
      val route = createRequestOtoroshiIORoute(Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[HeadersValidation],
          config = NgPluginInstanceConfig(
            NgHeaderValuesConfig(
              headers = Map(
                "foo" -> "${req.headers.bar}",
                "raw_header" -> "raw_value"
              )
            ).json.as[JsObject]
          ))
      ))

      val resp =  ws
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
          "Host" -> PLUGINS_HOST,
          "foo"  -> "bar",
          "bar"  -> "bar",
          "raw_header" -> "raw_value"
        )
        .get()
        .futureValue

      resp2.status mustBe Status.OK

      val resp3 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST,
          "foo"  -> "bar",
          "raw_value" -> "bar"
        )
        .get()
        .futureValue

      resp3.status mustBe 400

       deleteOtoroshiRoute(route).await()
    }

    "Missing headers in" in {
      val route = createRequestOtoroshiIORoute(Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[MissingHeadersIn],
          config = NgPluginInstanceConfig(
            NgHeaderValuesConfig(
              headers = Map(
                "foo" -> "foo_value",
                "foo2" -> "foo2_value"
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

      resp.status mustBe Status.OK
      getInHeader(resp, "foo") mustBe Some("foo_value")
      getInHeader(resp, "foo2") mustBe Some("client_value")

      deleteOtoroshiRoute(route).await()
    }

    "Missing headers out" in {
      val route = createRequestOtoroshiIORoute(Seq(
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

      resp.status mustBe Status.OK
      getOutHeader(resp, "foo") mustBe Some("foo_value")

      deleteOtoroshiRoute(route).await()
    }

    "Override Host Header" in {
      val route = createRequestOtoroshiIORoute(Seq(
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

      resp.status mustBe Status.OK
      getInHeader(resp, "host") mustBe Some("request.otoroshi.io")

      deleteOtoroshiRoute(route).await()
    }

    "Override Location Header: redirect to relative path" in {
      val route = createLocalRoute(Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideLocationHeader]
        )
      ),
        responseStatus = Status.CREATED,
        result = _ => {
          Json.obj("message" -> "creation done")
        },
        responseHeaders = List(RawHeader("Location", "/foo")))

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> LOCAL_HOST,
        )
        .get()
        .futureValue

      resp.status mustBe Status.CREATED
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
        responseStatus = Status.FOUND,
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

      resp.status mustBe Status.OK

      deleteOtoroshiRoute(route).await()
      deleteOtoroshiRoute(finalTargetRoute).await()
    }

    "Security Txt" in {
      def test(config: NgSecurityTxtConfig, expected: Seq[String]) = {
        val route = createRequestOtoroshiIORoute(Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[NgSecurityTxt],
            config = NgPluginInstanceConfig(config.json.as[JsObject])
          )
        ),
          id = IdGenerator.uuid)

        val resp =  ws
          .url(s"http://127.0.0.1:$port/.well-known/security.txt")
          .withHttpHeaders(
            "Host" -> route.frontend.domains.head.domain
          )
          .get()
          .futureValue

        resp.status mustBe Status.OK
        expected.foreach(str => resp.body.contains(str) mustBe true)

        deleteOtoroshiRoute(route).await()
      }

      test(NgSecurityTxtConfig(
        contact = Seq("mailto:security@example.com")
      ), Seq("mailto:security@example.com"))

      test(NgSecurityTxtConfig(
        contact = Seq("mailto:security@example.com", "https://example.com/security-contact"),
        expires = Some("2026-12-31T23:59:59Z"),
        policy = Some("https://example.com/security-policy")
      ), Seq(
        "mailto:security@example.com",
        "https://example.com/security-contact",
        "https://example.com/security-policy"
      ))

      test(NgSecurityTxtConfig(
        contact = Seq(
          "mailto:security@example.com",
          "https://example.com/security"
        ),
        expires = Some("2026-01-01T00:00:00Z"),
        acknowledgments = Some("https://example.com/hall-of-fame"),
        preferredLanguages = Some("en, fr, es"),
        policy = Some("https://example.com/security-policy"),
        hiring = Some("https://example.com/jobs/security"),
        encryption = Some("https://example.com/pgp-key.txt"),
        csaf = Some("https://example.com/.well-known/csaf/provider-metadata.json")
      ), Seq(
        "https://example.com/hall-of-fame",
        "https://example.com/security-policy",
        "https://example.com/jobs/security",
        "https://example.com/pgp-key.txt",
        "https://example.com/.well-known/csaf/provider-metadata.json",
      ))
    }

    "Yes Websocket plugin: send 'y' messages periodically to websocket clients" in {
        val route = createRequestOtoroshiIORoute(Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[YesWebsocketBackend],
          )
        ),
          id = IdGenerator.uuid)

        implicit val system: ActorSystem = ActorSystem("otoroshi-test")
        implicit val mat: Materializer = Materializer(system)
        implicit val http: HttpExt = Http()(system)

        val yesCounter = new AtomicInteger(0)
        val messagesPromise = Promise[Int]()

        val printSink: Sink[Message, Future[Done]] = Sink.foreach { message =>
          yesCounter.incrementAndGet()

          if (yesCounter.get() == 3)
            messagesPromise.trySuccess(yesCounter.get)
        }

        val clientSource: Source[Message, Promise[Option[Message]]] = Source.maybe[Message]

        val (_, _) = http.singleWebSocketRequest(
          WebSocketRequest(s"ws://127.0.0.1:$port/api")
            .copy(extraHeaders = List(Host(route.frontend.domains.head.domainLowerCase))),
          Flow
            .fromSinkAndSourceMat(printSink, clientSource)(Keep.both)
            .alsoTo(Sink.onComplete { _ => })
        )

        val yesMessagesCounter = messagesPromise.future.futureValue(Timeout(Span(1, Minutes)))
        yesMessagesCounter >= 3 mustBe true

        deleteOtoroshiRoute(route).await()
        system.terminate()
    }

    "Yes Websocket plugin: reject connection with fail=yes query parameter" in {
        val route = createRequestOtoroshiIORoute(Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[YesWebsocketBackend],
          )
        ),
          id = IdGenerator.uuid)

        implicit val system: ActorSystem = ActorSystem("otoroshi-test")
        implicit val mat: Materializer = Materializer(system)
        implicit val http: HttpExt = Http()(system)


        val printSink: Sink[Message, Future[Done]] = Sink.foreach { _ => }

        val clientSource: Source[Message, Promise[Option[Message]]] = Source.maybe[Message]

        val (upgradeResponse, _) = http.singleWebSocketRequest(
          WebSocketRequest(s"ws://127.0.0.1:$port/api?fail=yes")
            .copy(extraHeaders = List(Host(route.frontend.domains.head.domainLowerCase))),
          Flow
            .fromSinkAndSourceMat(printSink, clientSource)(Keep.both)
            .alsoTo(Sink.onComplete { _ => })
        )

        upgradeResponse.futureValue.response.status.intValue() mustBe 500

        deleteOtoroshiRoute(route).await()
        system.terminate()
    }

    "Remove headers in" in {
      val route = createRequestOtoroshiIORoute(Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[RemoveHeadersIn],
          config = NgPluginInstanceConfig(
            NgHeaderNamesConfig(
              names = Seq("foo")
            ).json.as[JsObject]
          ))
      ))

      val resp =  ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST,
          "foo2" -> "client_value",
          "foo" -> "bar"
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK
      getInHeader(resp, "foo") mustBe None
      getInHeader(resp, "foo2") mustBe Some("client_value")

      deleteOtoroshiRoute(route).await()
    }

    "Remove headers out" in {
      val route = createLocalRoute(Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[RemoveHeadersOut],
          config = NgPluginInstanceConfig(
            NgHeaderNamesConfig(
              names = Seq("foo")
            ).json.as[JsObject]
          )),
      ),
        result = req => {
          Json.obj()
        }, responseHeaders = List(RawHeader("foo", "bar"), RawHeader("foo2", "baz"))
      )

      val resp =  ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> LOCAL_HOST
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK

      getOutHeader(resp, "foo") mustBe None
      getOutHeader(resp, "foo2") mustBe Some("baz")

      deleteOtoroshiRoute(route).await()
    }

    "Build mode" in {
      val route = createRequestOtoroshiIORoute(Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[BuildMode],
        ),
      ))

      val resp =  ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST
        )
        .get()
        .futureValue

      resp.status mustBe Status.SERVICE_UNAVAILABLE
      resp.body.contains("Service under construction") mustBe true

      deleteOtoroshiRoute(route).await()
    }

    "Maintenance mode" in {
      val route = createRequestOtoroshiIORoute(Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[MaintenanceMode],
        ),
      ))

      val resp =  ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST
        )
        .get()
        .futureValue

      resp.status mustBe Status.SERVICE_UNAVAILABLE
      resp.body.contains("Service in maintenance mode") mustBe true

      deleteOtoroshiRoute(route).await()
    }

    "Custom error template" in {
      val route = createRequestOtoroshiIORoute(Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[BuildMode],
        ),
      ))

      val maintenanceRoute = createRequestOtoroshiIORoute(Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[MaintenanceMode],
        ),
      ),
        domain = "maintenance.oto.tools",
        id = "maintenance route"
      )

      val error = ErrorTemplate(
        location =  EntityLocation.default,
        serviceId = "global",
        name = "global error template",
        description = "global error template description",
        template50x = "",
        templateBuild = "build mode enabled, bye",
        template40x = "",
        templateMaintenance = "maintenance mode enabled, bye",
        genericTemplates = Map.empty,
        messages = Map(
          "errors.service.under.construction" -> "build mode enabled",
          "errors.service.in.maintenance" -> "maintenance mode enabled"
        ),
        tags = Seq.empty,
        metadata = Map.empty
      )

      createOtoroshiErrorTemplate(error).await()

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> PLUGINS_HOST,
            "Accept" -> "text/html"
          )
          .get()
          .futureValue

        resp.status mustBe Status.SERVICE_UNAVAILABLE
        resp.body mustEqual "build mode enabled, bye"

        val resp2 = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> PLUGINS_HOST
          )
          .get()
          .futureValue

        resp2.status mustBe Status.SERVICE_UNAVAILABLE

        Json.parse(resp2.body).selectAsString("otoroshi-cause") mustEqual "build mode enabled"
        Json.parse(resp2.body).selectAsString("otoroshi-error") mustEqual "Service under construction"
      }

      {
        val resp =  ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> maintenanceRoute.frontend.domains.head.domain,
            "Accept" -> "text/html"
          )
          .get()
          .futureValue

        resp.status mustBe Status.SERVICE_UNAVAILABLE
        resp.body mustEqual "maintenance mode enabled, bye"

        val resp2 =  ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> maintenanceRoute.frontend.domains.head.domain
          )
          .get()
          .futureValue

        resp2.status mustBe Status.SERVICE_UNAVAILABLE

        Json.parse(resp2.body).selectAsString("otoroshi-cause") mustEqual "maintenance mode enabled"
        Json.parse(resp2.body).selectAsString("otoroshi-error") mustEqual "Service in maintenance mode"
      }

      deleteOtoroshiErrorTemplate(error).await()
      deleteOtoroshiRoute(route).await()
      deleteOtoroshiRoute(maintenanceRoute).await()
    }
  }
}
