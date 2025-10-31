package functional

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.{Host, HttpCookie, RawHeader, `Content-Type`, `Set-Cookie`}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, PeerClosedConnectionException, TextMessage, WebSocketRequest}
import akka.http.scaladsl.model.{HttpHeader, HttpRequest}
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.typesafe.config.ConfigFactory
import functional.Implicits.BetterFuture
import org.scalatest.BeforeAndAfterAll
import ch.qos.logback.classic.{Level, Logger => LogbackLogger}
import org.joda.time.DateTime
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time.{Minutes, Seconds, Span}
import org.slf4j.LoggerFactory
import otoroshi.models._
import otoroshi.next.models._
import otoroshi.next.plugins.api.{NgPluginHelper, YesWebsocketBackend}
import otoroshi.next.plugins.{RejectHeaderOutTooLong, _}
import otoroshi.plugins.hmac.HMACUtils
import otoroshi.security.IdGenerator
import otoroshi.utils.crypto.Signatures
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterJsValueReader, BetterSyntax}
import play.api.http.Status
import play.api.libs.json.{JsObject, JsString, JsValue, Json}
import play.api.libs.ws.{DefaultWSCookie, WSRequest}
import play.api.{Configuration, Logger}

import java.util.Base64
import org.apache.commons.codec.binary.{Base64 => ApacheBase64}
import otoroshi.auth.{BasicAuthModuleConfig, BasicAuthUser, SessionCookieValues}
import otoroshi.utils.JsonPathValidator

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Failure, Success}

class PluginsTestSpec extends OtoroshiSpec with BeforeAndAfterAll {

  implicit lazy val mat = otoroshiComponents.materializer
  implicit lazy val env = otoroshiComponents.env

  def configurationSpec: Configuration = Configuration.empty

  val logger = Logger("otoroshi-tests-plugins")
  implicit val system  = ActorSystem("otoroshi-test")

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString("{}".stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  override def beforeAll(): Unit = {
    startOtoroshi()
    getOtoroshiRoutes().futureValue // WARM UP
  }

  override def afterAll(): Unit = {
    system.terminate()
    stopAll()
  }

  s"plugins" should {
    val PLUGINS_ROUTE_ID = "plugins-route"
    val PLUGINS_HOST = "plugins.oto.tools"

    val LOCAL_HOST = "local.oto.tools"

    def createRequestOtoroshiIORoute(
                                      plugins: Seq[NgPluginInstance] = Seq.empty,
                                      domain: String = "plugins.oto.tools",
                                      id: String = PLUGINS_ROUTE_ID
                                    ) = {
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
        .futureValue

      if (result._2 == Status.CREATED) {
        newRoute
      } else {
        throw new RuntimeException("failed to create a new route")
      }
    }

    def createLocalRoute(
                          plugins: Seq[NgPluginInstance] = Seq.empty,
                          responseStatus: Int = Status.OK,
                          result: HttpRequest => JsValue = _ => Json.obj(),
                          responseHeaders: List[HttpHeader] = List.empty[HttpHeader],
                          domain: String = "local.oto.tools",
                          https: Boolean = false,
                          frontendPath: String = "/api",
                          jsonAPI: Boolean = true,
                          responseContentType: String = "application/json",
                          stringResult: HttpRequest => String = _ => "",
                          target: Option[NgTarget] = None
                        ) = {

      var _target: Option[TargetService] = None

      if (target.isEmpty)
        _target = (if (jsonAPI) TargetService
        .jsonFull(
          Some(domain),
          frontendPath,
          r => (responseStatus, result(r), responseHeaders)
        ) else TargetService
        .full(
          Some(domain),
          frontendPath,
          contentType = responseContentType,
          r => (responseStatus, stringResult(r), responseHeaders)
        ))
          .await()
          .some

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
            target.getOrElse(NgTarget(
              hostname = "127.0.0.1",
              port = _target.get.port,
              id = "local.target",
              tls = https
            ))
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
        .futureValue

      if (resp._2 == Status.CREATED) {
        newRoute
      } else {
        throw new RuntimeException("failed to create a new local route")
      }
    }

    def createApiKeys() = {
      createOtoroshiApiKey(getValidApiKeyForPluginsRoute).futureValue
    }

    def deleteApiKeys() = {
      deleteOtoroshiApiKey(getValidApiKeyForPluginsRoute).futureValue
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

    "Allowed HTTP methods" in {
      val route = createRequestOtoroshiIORoute(
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

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK

      val resp2 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST
        )
        .post(Json.obj())
        .futureValue

      resp2.status mustBe 405

      deleteOtoroshiRoute(route).futureValue
    }
    // FIX: test not complete
    "Apikeys" in {
      val route = createRequestOtoroshiIORoute(
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
          "Host" -> PLUGINS_HOST,
          "Otoroshi-Client-Id" -> getValidApiKeyForPluginsRoute.clientId,
          "Otoroshi-Client-Secret" -> getValidApiKeyForPluginsRoute.clientSecret
        )
        .get()
        .futureValue

      authorizedCall.status mustBe Status.OK

      deleteApiKeys()
      deleteOtoroshiRoute(route).futureValue
    }

    "Additional headers in" in {
      val route = createRequestOtoroshiIORoute(
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

      resp.status mustBe Status.OK
      getInHeader(resp, "foo") mustBe Some("bar")

      deleteApiKeys()
      deleteOtoroshiRoute(route).futureValue
    }

    "Additional headers out" in {
      val route = createRequestOtoroshiIORoute(
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

      resp.status mustBe Status.OK
      getOutHeader(resp, "foo") mustBe Some("bar")

      deleteApiKeys()
      deleteOtoroshiRoute(route).futureValue
    }

    "Headers validation" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
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
          "Host" -> PLUGINS_HOST,
          "foo" -> "bar",
          "bar" -> "bar",
          "raw_header" -> "raw_value"
        )
        .get()
        .futureValue

      resp2.status mustBe Status.OK

      val resp3 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST,
          "foo" -> "bar",
          "raw_value" -> "bar"
        )
        .get()
        .futureValue

      resp3.status mustBe 400

      deleteOtoroshiRoute(route).futureValue
    }

    "Missing headers in" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
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

      resp.status mustBe Status.OK
      getInHeader(resp, "foo") mustBe Some("foo_value")
      getInHeader(resp, "foo2") mustBe Some("client_value")

      deleteOtoroshiRoute(route).futureValue
    }

    "Missing headers out" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
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

      resp.status mustBe Status.OK
      getOutHeader(resp, "foo") mustBe Some("foo_value")

      deleteOtoroshiRoute(route).futureValue
    }

    "Override Host Header" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
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

      resp.status mustBe Status.OK
      getInHeader(resp, "host") mustBe Some("request.otoroshi.io")

      deleteOtoroshiRoute(route).futureValue
    }

    "Override Location Header: redirect to relative path" in {
      val route = createLocalRoute(
        Seq(
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
        responseHeaders = List(RawHeader("Location", "/foo"))
      )

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> LOCAL_HOST
        )
        .get()
        .futureValue

      resp.status mustBe Status.CREATED
      getOutHeader(resp, "Location") mustBe Some("/foo")

      deleteOtoroshiRoute(route).futureValue
    }

    "Override Location Header: redirect to domain + path" in {
      val route = createLocalRoute(
        Seq(
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

      val finalTargetRoute = createLocalRoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          )
        ),
        result = _ => {
          Json.obj("message" -> "reached the target route")
        },
        domain = "location.oto.tools"
      )

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "foo.oto.tools"
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK

      deleteOtoroshiRoute(route).futureValue
      deleteOtoroshiRoute(finalTargetRoute).futureValue
    }

    "Security Txt" in {
      def test(config: NgSecurityTxtConfig, expected: Seq[String]) = {
        val route = createRequestOtoroshiIORoute(
          Seq(
            NgPluginInstance(
              plugin = NgPluginHelper.pluginId[OverrideHost]
            ),
            NgPluginInstance(
              plugin = NgPluginHelper.pluginId[NgSecurityTxt],
              config = NgPluginInstanceConfig(config.json.as[JsObject])
            )
          ),
          id = IdGenerator.uuid
        )

        val resp = ws
          .url(s"http://127.0.0.1:$port/.well-known/security.txt")
          .withHttpHeaders(
            "Host" -> route.frontend.domains.head.domain
          )
          .get()
          .futureValue

        resp.status mustBe Status.OK
        expected.foreach(str => resp.body.contains(str) mustBe true)

        deleteOtoroshiRoute(route).futureValue
      }

      test(
        NgSecurityTxtConfig(
          contact = Seq("mailto:security@example.com")
        ),
        Seq("mailto:security@example.com")
      )

      test(
        NgSecurityTxtConfig(
          contact = Seq("mailto:security@example.com", "https://example.com/security-contact"),
          expires = Some("2026-12-31T23:59:59Z"),
          policy = Some("https://example.com/security-policy")
        ),
        Seq(
          "mailto:security@example.com",
          "https://example.com/security-contact",
          "https://example.com/security-policy"
        )
      )

      test(
        NgSecurityTxtConfig(
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
        ),
        Seq(
          "https://example.com/hall-of-fame",
          "https://example.com/security-policy",
          "https://example.com/jobs/security",
          "https://example.com/pgp-key.txt",
          "https://example.com/.well-known/csaf/provider-metadata.json"
        )
      )
    }

    "Yes Websocket plugin: send 'y' messages periodically to websocket clients" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[YesWebsocketBackend]
          )
        ),
        id = IdGenerator.uuid
      )

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

      deleteOtoroshiRoute(route).futureValue
    }

    "Yes Websocket plugin: reject connection with fail=yes query parameter" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[YesWebsocketBackend]
          )
        ),
        id = IdGenerator.uuid
      )

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

      deleteOtoroshiRoute(route).futureValue
    }

    "Remove headers in" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[RemoveHeadersIn],
            config = NgPluginInstanceConfig(
              NgHeaderNamesConfig(
                names = Seq("foo")
              ).json.as[JsObject]
            )
          )
        )
      )

      val resp = ws
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

      deleteOtoroshiRoute(route).futureValue
    }

    "Remove headers out" in {
      val route = createLocalRoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[RemoveHeadersOut],
            config = NgPluginInstanceConfig(
              NgHeaderNamesConfig(
                names = Seq("foo")
              ).json.as[JsObject]
            )
          )
        ),
        result = req => {
          Json.obj()
        },
        responseHeaders = List(RawHeader("foo", "bar"), RawHeader("foo2", "baz"))
      )

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> LOCAL_HOST
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK

      getOutHeader(resp, "foo") mustBe None
      getOutHeader(resp, "foo2") mustBe Some("baz")

      deleteOtoroshiRoute(route).futureValue
    }

    "Build mode" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[BuildMode]
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

      resp.status mustBe Status.SERVICE_UNAVAILABLE
      resp.body.contains("Service under construction") mustBe true

      deleteOtoroshiRoute(route).futureValue
    }

    "Maintenance mode" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[MaintenanceMode]
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

      resp.status mustBe Status.SERVICE_UNAVAILABLE
      resp.body.contains("Service in maintenance mode") mustBe true

      deleteOtoroshiRoute(route).futureValue
    }

    "Custom error template" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[BuildMode]
          )
        )
      )

      val maintenanceRoute = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[MaintenanceMode]
          )
        ),
        domain = "maintenance.oto.tools",
        id = "maintenance route"
      )

      val error = ErrorTemplate(
        location = EntityLocation.default,
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

      createOtoroshiErrorTemplate(error).futureValue

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
        val resp = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> maintenanceRoute.frontend.domains.head.domain,
            "Accept" -> "text/html"
          )
          .get()
          .futureValue

        resp.status mustBe Status.SERVICE_UNAVAILABLE
        resp.body mustEqual "maintenance mode enabled, bye"

        val resp2 = ws
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

      deleteOtoroshiErrorTemplate(error).futureValue
      deleteOtoroshiRoute(route).futureValue
      deleteOtoroshiRoute(maintenanceRoute).futureValue
    }

    "Error response rewrite" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[NgErrorRewriter],
            config = NgPluginInstanceConfig(
              NgErrorRewriterConfig(
                ranges = Seq(ResponseStatusRange(200, 299)),
                templates = Map(
                  "default" -> "custom response",
                  "application/json" -> "custom json response"
                ),
                log = false,
                export = false
              ).json.as[JsObject]
            )
          )
        )
      )

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> PLUGINS_HOST
          )
          .get()
          .futureValue

        resp.status mustBe Status.OK
        resp.body mustEqual "custom response"
      }

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> PLUGINS_HOST,
            "Accept" -> "application/json"
          )
          .get()
          .futureValue

        resp.status mustBe Status.OK
        resp.body mustEqual "custom json response"
      }

      deleteOtoroshiRoute(route).futureValue
    }

    "Reject headers out too long" in {
      val route = createLocalRoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[RejectHeaderOutTooLong],
            config = NgPluginInstanceConfig(
              RejectHeaderConfig(
                value = 15
              ).json.as[JsObject]
            )
          )
        ),
        responseStatus = Status.OK,
        result = _ => Json.obj(),
        responseHeaders = List(RawHeader("foo", "bar"), RawHeader("baz", "very very very long header value"))
      )

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> LOCAL_HOST
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK
      getOutHeader(resp, "foo") mustBe Some("bar")
      getOutHeader(resp, "baz") mustBe None

      deleteOtoroshiRoute(route).futureValue
    }

    "Reject headers in too long" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[RejectHeaderInTooLong],
            config = NgPluginInstanceConfig(
              RejectHeaderConfig(
                value = 30
              ).json.as[JsObject]
            )
          )
        )
      )

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST,
          "foo" -> "bar",
          "baz" -> "very very very very very very very very very long header value"
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK
      getInHeader(resp, "foo") mustBe Some("bar")
      getInHeader(resp, "baz") mustBe None

      deleteOtoroshiRoute(route).futureValue
    }

    "Additional cookies in" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[AdditionalCookieIn],
            config = NgPluginInstanceConfig(
              AdditionalCookieInConfig(
                name = "cookie",
                value = "value"
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

      resp.status mustBe Status.OK
      val cookies = Json
        .parse(resp.body)
        .as[JsValue]
        .select("cookies")
        .as[Map[String, String]]

      cookies.get("cookie") mustBe Some("value")

      deleteOtoroshiRoute(route).futureValue
    }

    "Additional cookies out" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[AdditionalCookieOut],
            config = NgPluginInstanceConfig(
              AdditionalCookieOutConfig(
                name = "cookie",
                value = "value"
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

      resp.status mustBe Status.OK
      resp.cookies.exists(_.name == "cookie") mustBe true
      resp.cookies.find(_.name == "cookie").map(_.value) mustBe Some("value")

      deleteOtoroshiRoute(route).futureValue
    }

    "Limit headers in too long" in {
      val route = createRequestOtoroshiIORoute(Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[LimitHeaderInTooLong],
          config = NgPluginInstanceConfig(
            RejectHeaderConfig(
              value = 25
            ).json.as[JsObject]
          )
        )
      ))

      val logger = LoggerFactory.getLogger("otoroshi-plugin-limit-headers-in-too-long").asInstanceOf[LogbackLogger]

      val events = scala.collection.mutable.ListBuffer.empty[ILoggingEvent]
      val appender = new AppenderBase[ILoggingEvent]() {
        override def append(eventObject: ILoggingEvent): Unit = events += eventObject
      }
      appender.start()
      logger.addAppender(appender)

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST,
          "baz" -> "very very very very very veyr long header value"
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK

      assert(events.exists(_.getMessage.contains("limiting header")))
      assert(events.exists(_.getMessage.contains("baz")))
      assert(events.exists(_.getLevel == Level.ERROR))

      logger.detachAppender(appender)

      deleteOtoroshiRoute(route).futureValue
    }

    "Limit headers out too long" in {
      val route = createLocalRoute(Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[LimitHeaderOutTooLong],
          config = NgPluginInstanceConfig(
            RejectHeaderConfig(
              value = 20
            ).json.as[JsObject]
          )
        )
      ),
        responseStatus = Status.OK,
        result = _ => Json.obj(),
        responseHeaders = List(RawHeader("foo", "bar"), RawHeader("baz", "very very very very very long header value")))

      val logger = LoggerFactory.getLogger("otoroshi-plugin-limit-headers-out-too-long").asInstanceOf[LogbackLogger]

      val events = scala.collection.mutable.ListBuffer.empty[ILoggingEvent]
      val appender = new AppenderBase[ILoggingEvent]() {
        override def append(eventObject: ILoggingEvent): Unit = events += eventObject
      }
      appender.start()
      logger.addAppender(appender)

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> LOCAL_HOST,
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK

      assert(events.exists(_.getMessage.contains("limiting header")))
      assert(events.exists(_.getMessage.contains("baz")))
      assert(events.exists(_.getLevel == Level.ERROR))

      logger.detachAppender(appender)

      deleteOtoroshiRoute(route).futureValue
    }

    "Basic Auth. caller" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[BasicAuthCaller],
            config = NgPluginInstanceConfig(
              BasicAuthCallerConfig(
                username = "foo".some,
                password = "bar".some,
                headerName = "foo",
                headerValueFormat = "Foo %s"
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

      resp.status mustBe Status.OK
      getInHeader(resp, "foo") mustBe Some("Foo Zm9vOmJhcg==")

      deleteOtoroshiRoute(route).futureValue
    }

    "Force HTTPS traffic" in {
      val route = createLocalRoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[ForceHttpsTraffic]
          )
        ),
        result = _ => Json.obj(),
        domain = "force.oto.tools",
        https = true
      )

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withFollowRedirects(false)
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain
        )
        .get()
        .futureValue

      resp.status mustBe Status.SEE_OTHER
      getOutHeader(resp, "Location") mustBe Some("https://force.oto.tools:8443/api")

      deleteOtoroshiRoute(route).futureValue
    }

    "Forwarded header" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[ForwardedHeader]
          )
        ))

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withFollowRedirects(false)
        .withHttpHeaders(
          "X-Forwarded-For" -> "1.1.1.2",
          "Host" -> route.frontend.domains.head.domain
        )
        .get()
        .futureValue

      getInHeader(resp, "x-forwarded-proto") mustBe Some("https")
      getInHeader(resp, "x-forwarded-for").contains("1.1.1.2") mustBe false
      getInHeader(resp, "x-forwarded-port") mustBe Some("443")
      getInHeader(resp, "forwarded").isDefined mustBe true

      deleteOtoroshiRoute(route).futureValue
    }

    "Mock responses" in {
      val route = createLocalRoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[MockResponses],
            config = NgPluginInstanceConfig(
              MockResponsesConfig(
                responses = Seq(
                  MockResponse(
                    headers = Map.empty,
                    body = Json.obj("foo" -> "bar").stringify
                  ),
                  MockResponse(
                    path = "/users/:id",
                    method = "POST",
                    status = 201,
                    headers = Map.empty,
                    body = Json.obj("message" -> "done").stringify
                  )
                )
              ).json.as[JsObject]
            )
          )
        ),
        domain = "mock.oto.tools",
        frontendPath = "/"
      )

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/")
          .withHttpHeaders(
            "Host" -> route.frontend.domains.head.domain
          )
          .get()
          .futureValue

        resp.status mustBe Status.OK
        Json.parse(resp.body) mustBe Json.obj("foo" -> "bar")
      }

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/users/foo")
          .withHttpHeaders(
            "Host" -> route.frontend.domains.head.domain
          )
          .post("")
          .futureValue

        resp.status mustBe Status.CREATED
        Json.parse(resp.body) mustBe Json.obj("message" -> "done")
      }

      deleteOtoroshiRoute(route).futureValue
    }

    "Block non HTTPS traffic" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[ApikeyCalls]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[BlockHttpTraffic],
            config = NgPluginInstanceConfig(
              BlockHttpTrafficConfig(
                revokeApikeys = true,
                message = "you shall not pass".some,
                revokeUserSession = false
              ).json.as[JsObject]
            )
          )
        )
      )

      val apikey = ApiKey(
        clientId = IdGenerator.token(16),
        clientSecret = IdGenerator.token(64),
        clientName = "apikey1",
        authorizedEntities = Seq.empty
      )
      createOtoroshiApiKey(apikey).futureValue

      apikey.enabled mustBe true

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST,
          "Otoroshi-Client-Id" -> apikey.clientId,
          "Otoroshi-Client-Secret" -> apikey.clientSecret
        )
        .get()
        .futureValue

      resp.status mustBe Status.UPGRADE_REQUIRED
      Json.parse(resp.body) mustBe Json.obj("message" -> "you shall not pass")

      awaitF(10.seconds).futureValue
      env.proxyState.apikey(apikey.clientId)
        .map(_.enabled mustBe false)

      deleteOtoroshiApiKey(apikey).futureValue
      deleteOtoroshiRoute(route).futureValue
    }

    "Consumer endpoint with apikey" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[ApikeyCalls]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[ConsumerEndpoint]
          )
        )
      )

      val apikey = ApiKey(
        clientId = IdGenerator.token(16),
        clientSecret = IdGenerator.token(64),
        clientName = "apikey1",
        authorizedEntities = Seq.empty,
        metadata = Map("foo" -> "bar"),
        tags = Seq("foo")
      )
      createOtoroshiApiKey(apikey).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST,
          "Otoroshi-Client-Id" -> apikey.clientId,
          "Otoroshi-Client-Secret" -> apikey.clientSecret
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK

      Json.parse(resp.body).selectAsString("access_type") mustEqual "apikey"
      Json.parse(resp.body).selectAsString("clientId") mustEqual apikey.clientId
      Json.parse(resp.body).selectAsString("clientName") mustEqual apikey.clientName

      deleteOtoroshiApiKey(apikey).futureValue
      deleteOtoroshiRoute(route).futureValue
    }

    "Consumer endpoint without apikey" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[ConsumerEndpoint]
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

      resp.status mustBe Status.OK
      Json.parse(resp.body).selectAsString("access_type") mustEqual "public"

      deleteOtoroshiRoute(route).futureValue
    }

    "Missing cookies in" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[MissingCookieIn],
            config = NgPluginInstanceConfig(
              AdditionalCookieOutConfig(
                name = "foo",
                value = "baz",
                domain = PLUGINS_HOST.some
              ).json.as[JsObject]
            )
          )
        )
      )

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> PLUGINS_HOST
          )
          .get()
          .futureValue

        resp.status mustBe Status.OK
        val cookies = Json
          .parse(resp.body)
          .as[JsValue]
          .select("cookies")
          .as[Map[String, String]]

        cookies.get("foo") mustBe Some("baz")
      }

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/api")
          .withCookies(DefaultWSCookie(
            name = "foo",
            value = "bar",
            domain = PLUGINS_HOST.some
          ))
          .withHttpHeaders(
            "Host" -> PLUGINS_HOST
          )
          .get()
          .futureValue

        resp.status mustBe Status.OK
        val cookies = Json
          .parse(resp.body)
          .as[JsValue]
          .select("cookies")
          .as[Map[String, String]]

        cookies.get("foo") mustBe Some("bar")
      }

      deleteOtoroshiRoute(route).futureValue
    }

    "Missing cookies out" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[MissingCookieOut],
            config = NgPluginInstanceConfig(
              AdditionalCookieOutConfig(
                name = "foo",
                value = "baz",
                domain = PLUGINS_HOST.some
              ).json.as[JsObject]
            )
          )
        )
      )

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> PLUGINS_HOST
          )
          .get()
          .futureValue

        resp.status mustBe Status.OK
        resp.cookies.find(_.name == "foo").get.value mustBe "baz"
      }

      val localRoute = createLocalRoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[MissingCookieOut],
            config = NgPluginInstanceConfig(
              AdditionalCookieOutConfig(
                name = "foo",
                value = "baz",
                domain = "missing.oto.tools".some
              ).json.as[JsObject]
            )
          )
        ),
        domain = "missing.oto.tools",
        responseHeaders = List(`Set-Cookie`(cookie = HttpCookie(
          name = "foo",
          value = "bar",
          domain = "missing.oto.tools".some
        )))
      )

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/api")
          .withCookies(DefaultWSCookie(
            name = "foo",
            value = "bar",
            domain = "missing.oto.tools".some
          ))
          .withHttpHeaders(
            "Host" -> "missing.oto.tools"
          )
          .get()
          .futureValue

        resp.status mustBe Status.OK
        resp.cookies.find(_.name == "foo").get.value mustBe "bar"

        deleteOtoroshiRoute(localRoute).futureValue
        deleteOtoroshiRoute(route).futureValue
      }
    }

    "Default request body" in {
      val localRoute = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[NgDefaultRequestBody],
            config = NgPluginInstanceConfig(
              NgDefaultRequestBodyConfig(
                body = ByteString(Json.obj("foo" -> "bar").stringify),
                contentType = "application/json",
                contentEncoding = None
              ).json.as[JsObject]
            )
          )
        ))

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK
      Json.parse(resp.body).selectAsObject("body") mustEqual Json.obj("foo" -> "bar")

      val resp2 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST
        )
        .post(Json.obj("body_from_client" -> true))
        .futureValue

      resp2.status mustBe Status.OK
      Json.parse(resp2.body).selectAsObject("body") mustEqual Json.obj("body_from_client" -> true)

      deleteOtoroshiRoute(localRoute).futureValue
    }

    "HMAC caller plugin" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[HMACCaller],
            config = NgPluginInstanceConfig(
              HMACCallerConfig(
                secret = "secret".some,
                algo = "HMAC-SHA512",
                authorizationHeader = "foo".some
              ).json.as[JsObject]
            )
          )
        ))

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK
      getInHeader(resp, "foo").get.contains(Base64
        .getEncoder
        .encodeToString(Signatures.hmac(HMACUtils.Algo("HMAC-SHA512"), getInHeader(resp, "date").get, "secret")))

      deleteOtoroshiRoute(route).futureValue
    }

    "HMAC access validator" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[HMACValidator],
            config = NgPluginInstanceConfig(
              HMACValidatorConfig(
                secret = "secret".some,
                authorizationHeader = "foo".some
              ).json.as[JsObject]
            )
          )
        ))

      val base = System.currentTimeMillis().toString
      val signature = Base64.getEncoder.encodeToString(Signatures.hmac(HMACUtils.Algo("HMAC-SHA512"), base, "secret"))

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain,
          "base" -> base,
          "foo"  -> s"""hmac algorithm="HMAC-SHA512", headers="base", signature="$signature""""
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK

      deleteOtoroshiRoute(route).futureValue
    }

    "HMAC access validator with apikey as secret" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[ApikeyCalls]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[HMACValidator],
            config = NgPluginInstanceConfig(
              HMACValidatorConfig(
                authorizationHeader = "foo".some
              ).json.as[JsObject]
            )
          )
        ))

      val apikey = ApiKey(
        clientId = IdGenerator.token(16),
        clientSecret = "apikey secret",
        clientName = "apikey1",
        authorizedEntities = Seq.empty
      )
      createOtoroshiApiKey(apikey).futureValue

      val base = System.currentTimeMillis().toString
      val signature = Base64.getEncoder.encodeToString(Signatures.hmac(HMACUtils.Algo("HMAC-SHA512"), base, apikey.clientSecret))

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain,
          "Otoroshi-Client-Id" -> apikey.clientId,
          "Otoroshi-Client-Secret" -> apikey.clientSecret,
          "base" -> base,
          "foo"  -> s"""hmac algorithm="HMAC-SHA512", headers="base", signature="$signature""""
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK

      deleteOtoroshiApiKey(apikey)
      deleteOtoroshiRoute(route).futureValue
    }

    "Static Response" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[StaticResponse],
            config = NgPluginInstanceConfig(
              StaticResponseConfig(
                status = Status.OK,
                headers = Map("baz" -> "bar"),
                body = Json.obj("foo" -> "${req.headers.foo}").stringify,
                applyEl = true
              ).json.as[JsObject]
            )
          )
        ))

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain,
          "foo" -> "client value"
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK
      getOutHeader(resp, "baz") mustBe Some("bar")
      Json.parse(resp.body) mustEqual Json.obj("foo" -> "client value")

      deleteOtoroshiRoute(route).futureValue
    }

    "Http static asset" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[StaticAssetEndpoint],
            include = Seq("/api/assets/.*"),
            config = NgPluginInstanceConfig(
              StaticAssetEndpointConfiguration(
                url = Some(s"http://static-asset.oto.tools:$port")
              ).json.as[JsObject]
            )
          )
        ))

      val staticAssetRoute = createLocalRoute(
        Seq(),
        domain = "static-asset.oto.tools",
        result = _ => Json.obj("foo" -> "bar_from_child")
      )

      val resp = ws
        .url(s"http://127.0.0.1:$port/api/assets/foo")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK
      Json.parse(resp.body) mustEqual Json.obj("foo" -> "bar_from_child")

      {
        val resp = ws
        .url(s"http://127.0.0.1:$port/api/")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain
        )
        .get()
        .futureValue

        resp.status mustBe Status.OK
        Json.parse(resp.body).selectAsOptString("path").isDefined mustBe true
      }

      deleteOtoroshiRoute(staticAssetRoute).futureValue
      deleteOtoroshiRoute(route).futureValue
    }

    "Disable HTTP/1.0" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[DisableHttp10]
          )
        ))

      import java.io._
      import java.net.Socket
      import scala.util.Using

      def makeHttp10Request(
        host: String,
        port: Int,
        path: String,
        method: String = "GET",
        headers: Map[String, String] = Map()
      ): String = {
        val socket = new Socket(host, port)
        try {
          val out = new PrintWriter(socket.getOutputStream, true)
          val in = new BufferedReader(new InputStreamReader(socket.getInputStream))

          out.println(s"$method $path HTTP/1.0")

          headers.foreach { case (key, value) =>
            out.println(s"$key: $value")
          }

          out.println("Connection: close")
          out.println()
          out.flush()

          val response = new StringBuilder
          var line = in.readLine()
          while (line != null) {
            response.append(line).append("\n")
            line = in.readLine()
          }

          response.toString
        } finally {
          socket.close()
        }
      }

      val resp = makeHttp10Request(
          host = "127.0.0.1",
          port = port,
          path = "/api",
          headers = Map("Host" -> route.frontend.domains.head.domain)
        )

      resp.contains("HTTP/1.0 503 Service Unavailable") mustBe true
      deleteOtoroshiRoute(route).futureValue
    }

    "Query param transformer" in {
      val route = createLocalRoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[QueryTransformer],
            config = NgPluginInstanceConfig(
              QueryTransformerConfig(
                remove = Seq("foo"),
                rename = Map("bar" -> "baz"),
                add = Map("new_query" -> "value")
              ).json.as[JsObject]
            )
          )
        ),
        result = req => {
          Json.obj("query_params" -> req.uri.query().toMap)
        })

      val resp = ws
        .url(s"http://127.0.0.1:$port/api/?foo=bar&bar=foo")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain,
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK
      Json.parse(resp.body).selectAsObject("query_params") mustEqual Json.obj(
        "baz" -> "foo",
        "new_query" -> "value"
      )

      deleteOtoroshiRoute(route).futureValue
    }

    "Read only requests" in {
       val route = createRequestOtoroshiIORoute(
         Seq(
          NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
          NgPluginInstance(NgPluginHelper.pluginId[ReadOnlyCalls])
        )
       )

      def req() = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> route.frontend.domains.head.domain,
          )

      req()
          .get()
          .futureValue
          .status mustBe Status.OK

      req()
          .head()
          .futureValue
          .status mustBe Status.OK

      req()
          .options()
          .futureValue
          .status mustBe Status.NO_CONTENT

      req()
          .post("")
          .futureValue
          .status mustBe Status.METHOD_NOT_ALLOWED

      req()
          .patch("")
          .futureValue
          .status mustBe Status.METHOD_NOT_ALLOWED

      deleteOtoroshiRoute(route).futureValue
    }

    "Response body xml-to-json" in {
      import akka.http.scaladsl.model.{ContentType, MediaTypes, HttpCharsets}
      import akka.http.scaladsl.model.headers.`Content-Type`

      val route = createLocalRoute(
         Seq(
          NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
          NgPluginInstance(NgPluginHelper.pluginId[XmlToJsonResponse],
          config = NgPluginInstanceConfig(
            JsonTransformConfig(

            ).json.as[JsObject]
          ))
        ),
        responseHeaders = List(`Content-Type`(ContentType(MediaTypes.`text/xml`, HttpCharsets.`UTF-8`))),
        stringResult = _ => {
            ByteString("""
            |<?xml version="1.0" encoding="UTF-8" ?>
            |     <book category="web" cover="paperback">
            |         <title lang="en">Learning XML</title>
            |         <author>Erik T. Ray</author>
            |         <year>2003</year>
            |         <price>39.95</price>
            |     </book>
            |""".stripMargin, "utf-8").utf8String
        },
        jsonAPI = false,
        responseContentType = "text/xml"
       )

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain,
        )
        .get()
        .futureValue

      Json.parse(resp.body).selectAsOptObject("book").isDefined mustBe true
      Json.parse(resp.body).selectAsObject("book").selectAsString("category") mustBe "web"
      Json.parse(resp.body).selectAsObject("book").selectAsString("cover") mustBe "paperback"
      Json.parse(resp.body).selectAsObject("book").selectAsOptObject("title").isDefined mustBe true
      Json.parse(resp.body).selectAsObject("book").selectAsString("author") mustBe "Erik T. Ray"

      deleteOtoroshiRoute(route).futureValue
    }

    "User-Agent details extractor" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[NgUserAgentExtractor]
          )
        ))

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain,
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK
      getInHeader(resp, "user-agent").isDefined mustBe true

      deleteOtoroshiRoute(route).futureValue
    }

    "User-Agent details extractor + User-Agent header" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[NgUserAgentExtractor]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[NgUserAgentInfoHeader],
            config = NgPluginInstanceConfig(
              NgUserAgentInfoHeaderConfig(
                headerName = "foo"
              ).json.as[JsObject]
            )
          ),
        ))

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain,
          "User-Agent" -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:109.0) Gecko/20100101 Firefox/119.0"
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK
      getInHeader(resp, "foo").isDefined mustBe true
      getInHeader(resp, "foo").map(foo => Json.parse(foo).selectAsString("browser") mustBe "Firefox")

      deleteOtoroshiRoute(route).futureValue
    }

    "User-Agent details extractor + User-Agent endpoint" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[NgUserAgentExtractor]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[NgUserAgentInfoEndpoint]
          ),
        ),
        id = IdGenerator.uuid)

      val resp = ws
        .url(s"http://127.0.0.1:$port/.well-known/otoroshi/plugins/user-agent")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain,
          "User-Agent" -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:109.0) Gecko/20100101 Firefox/119.0"
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK
      Json.parse(resp.body).selectAsString("browser") mustBe "Firefox"

      deleteOtoroshiRoute(route).futureValue
    }

    "Request body xml-to-json" in {
      val route = createRequestOtoroshiIORoute(
         Seq(
          NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
          NgPluginInstance(NgPluginHelper.pluginId[XmlToJsonRequest]
          ))
      )

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain,
          "Content-Type" -> "text/xml"
        )
        .post(
          """
            |<?xml version="1.0" encoding="UTF-8" ?>
            |<book category="web" cover="paperback">
            |   <title lang="en">Learning XML</title>
            | </book>
            |""".stripMargin)
        .futureValue

      val body = Json.parse(resp.body).selectAsObject("body")

      body.selectAsOptObject("book").isDefined mustBe true
      body.selectAsObject("book").selectAsString("category") mustBe "web"
      body.selectAsObject("book").selectAsString("cover") mustBe "paperback"
      body.selectAsObject("book").selectAsOptObject("title").isDefined mustBe true

      deleteOtoroshiRoute(route).futureValue
    }

    "Jwt signer" in {
      val verifier = GlobalJwtVerifier(
        id = "verifier",
        name = "verifier",
        desc = "verifier",
        strict = true,
        source = InHeader(name = "X-JWT-Token"),
        algoSettings = HSAlgoSettings(512, "secret"),
        strategy = DefaultToken(
          strict = true,
          token = Json.obj("iss" -> "foo")
        )
      )
      createOtoroshiVerifier(verifier).futureValue

      val route = createRequestOtoroshiIORoute(
         Seq(
          NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
          NgPluginInstance(NgPluginHelper.pluginId[JwtSigner],
            config = NgPluginInstanceConfig(
              NgJwtSignerConfig(
                verifier = verifier.id.some,
                replaceIfPresent = true,
                failIfPresent = false
              ).json.as[JsObject]
            )
          ))
      )

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain
        )
        .get()
        .futureValue

      val tokenBody = getInHeader(resp, "x-jwt-token").get.split("\\.")(1)
      Json.parse(ApacheBase64.decodeBase64(tokenBody)).as[JsObject].selectAsString("iss") mustBe "foo"

      deleteOtoroshiVerifier(verifier).futureValue
      deleteOtoroshiRoute(route).futureValue
    }

    "Jwt signer should not replace the incoming token" in {
      val verifier = GlobalJwtVerifier(
        id = IdGenerator.uuid,
        name = "verifier",
        desc = "verifier",
        strict = true,
        source = InHeader(name = "X-JWT-Token"),
        algoSettings = HSAlgoSettings(512, "secret"),
        strategy = DefaultToken(
          strict = true,
          token = Json.obj("iss" -> "bar")
        )
      )
      createOtoroshiVerifier(verifier).futureValue

      val route = createRequestOtoroshiIORoute(
         Seq(
          NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
          NgPluginInstance(NgPluginHelper.pluginId[JwtSigner],
            config = NgPluginInstanceConfig(
              NgJwtSignerConfig(
                verifier = verifier.id.some,
                replaceIfPresent = false,
                failIfPresent = false
              ).json.as[JsObject]
            )
          ))
      )

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain,
          "x-jwt-token" -> "eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJmb28iLCJpYXQiOjE3NjAxMDY2OTZ9.bI7ghu2LG9k0s4QXPBlunwFk8TlHeUVyDF6Kv4Xfa8KF-3WXqORlJdW5o8NcY1tcs9UXvUw4TeRrS_QoZhvooQ"
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK

      val tokenBody = getInHeader(resp, "x-jwt-token").get.split("\\.")(1)
      Json.parse(ApacheBase64.decodeBase64(tokenBody)).as[JsObject].selectAsString("iss") mustBe "foo"

      deleteOtoroshiVerifier(verifier).futureValue
      deleteOtoroshiRoute(route).futureValue
    }

    "Jwt verification only (without verifier)" in {
      val route = createRequestOtoroshiIORoute(
         Seq(
          NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
          NgPluginInstance(NgPluginHelper.pluginId[JwtVerificationOnly],
            config = NgPluginInstanceConfig(
              NgJwtVerificationOnlyConfig(
                verifier = None,
                failIfAbsent = true
              ).json.as[JsObject]
            )
          ))
      )

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> route.frontend.domains.head.domain
          )
          .get()
          .futureValue

        resp.status mustBe Status.BAD_REQUEST
      }

      deleteOtoroshiRoute(route).futureValue
    }

    "Jwt verification only (without token)" in {
      val verifier = GlobalJwtVerifier(
        id = IdGenerator.uuid,
        name = "verifier",
        desc = "verifier",
        strict = true,
        source = InHeader(name = "foo"),
        algoSettings = HSAlgoSettings(512, "secret"),
        strategy = PassThrough(
          verificationSettings = VerificationSettings(Map("iss" -> "foo"))
        )
      )
      createOtoroshiVerifier(verifier).futureValue

      val route = createRequestOtoroshiIORoute(
         Seq(
          NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
          NgPluginInstance(NgPluginHelper.pluginId[JwtVerificationOnly],
            config = NgPluginInstanceConfig(
              NgJwtVerificationOnlyConfig(
                verifier = verifier.id.some,
                failIfAbsent = true
              ).json.as[JsObject]
            )
          ))
      )

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain,
        )
        .get()
        .futureValue

      resp.status mustBe Status.BAD_REQUEST

      deleteOtoroshiVerifier(verifier).futureValue
      deleteOtoroshiRoute(route).futureValue
    }

    "Jwt verification only with token" in {
      val verifier = GlobalJwtVerifier(
        id = IdGenerator.uuid,
        name = "verifier",
        desc = "verifier",
        strict = true,
        source = InHeader(name = "foo"),
        algoSettings = HSAlgoSettings(256, "secret"),
        strategy = PassThrough(
          verificationSettings = VerificationSettings(Map("iss" -> "foo"))
        )
      )
      createOtoroshiVerifier(verifier).futureValue

      val route = createRequestOtoroshiIORoute(
         Seq(
          NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
          NgPluginInstance(NgPluginHelper.pluginId[JwtVerificationOnly],
            config = NgPluginInstanceConfig(
              NgJwtVerificationOnlyConfig(
                verifier = verifier.id.some,
                failIfAbsent = true
              ).json.as[JsObject]
            )
          ))
      )

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> route.frontend.domains.head.domain,
            "foo" -> "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJmb28iLCJpYXQiOjE3NjAxMDM1MzN9.TAj08m-Ax3dUFrZ2NU3oG3tPdIFOGvJdpO3Yhas63rw"
          )
          .get()
          .futureValue

        resp.status mustBe Status.OK
      }

      deleteOtoroshiVerifier(verifier).futureValue
      deleteOtoroshiRoute(route).futureValue
    }

    "Jwt verifiers" in {
      val verifier = GlobalJwtVerifier(
        id = IdGenerator.uuid,
        name = "verifier",
        desc = "verifier",
        strict = true,
        source = InHeader(name = "foo"),
        algoSettings = HSAlgoSettings(256, "secret"),
        strategy = PassThrough(
          verificationSettings = VerificationSettings(Map("iss" -> "foo"))
        )
      )
      
      val verifier2 = GlobalJwtVerifier(
        id = IdGenerator.uuid,
        name = "verifier2",
        desc = "verifier2",
        strict = true,
        source = InHeader(name = "foo"),
        algoSettings = HSAlgoSettings(512, "secret"),
        strategy = PassThrough(
          verificationSettings = VerificationSettings(Map("iss" -> "foo"))
        )
      )
      createOtoroshiVerifier(verifier).futureValue
      createOtoroshiVerifier(verifier2).futureValue

      val route = createRequestOtoroshiIORoute(
         Seq(
          NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
          NgPluginInstance(NgPluginHelper.pluginId[JwtVerification],
            config = NgPluginInstanceConfig(
              NgJwtVerificationConfig(
                verifiers = Seq(verifier2.id, verifier.id)
              ).json.as[JsObject]
            )
          ))
      )

      val token256 = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJmb28iLCJpYXQiOjE3NjAxMDM1MzN9.TAj08m-Ax3dUFrZ2NU3oG3tPdIFOGvJdpO3Yhas63rw"
      val token512 = "eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJmb28iLCJpYXQiOjE3NjAxMDQ1MDN9.EWLHg8HQimFAhKnaUZ1C_1vYEjSbFuLgErRzHQ2tMTeHFoWwIws52GmhXoCBGx37viQcGqRLRtBv2me8oRd6BA"
      val wrongSecret = "eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJmb28iLCJpYXQiOjE3NjAxMDM5NDl9.M0Wc2Vt4-W7bSGzsplXQVu4oqWvqzxQbP5PJIyUVWrMQ6ba4KERzI4MzlONZFx7y95Z49_dISF6xQQr9hpdAGw"

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> route.frontend.domains.head.domain,
            "foo" -> token256
          )
          .get()
          .futureValue

        resp.status mustBe Status.OK
      }

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> route.frontend.domains.head.domain,
            "foo" -> token512
          )
          .get()
          .futureValue

        resp.status mustBe Status.OK
      }

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> route.frontend.domains.head.domain,
            "foo" -> wrongSecret
          )
          .get()
          .futureValue

        resp.status mustBe Status.BAD_REQUEST
      }

      deleteOtoroshiVerifier(verifier).futureValue
      deleteOtoroshiVerifier(verifier2).futureValue
      deleteOtoroshiRoute(route).futureValue
    }

    "Jwt user extractor" in {
      val verifier = GlobalJwtVerifier(
        id = IdGenerator.uuid,
        name = "verifier",
        desc = "verifier",
        strict = true,
        source = InHeader(name = "foo"),
        algoSettings = HSAlgoSettings(256, "secret"),
        strategy = PassThrough(
          verificationSettings = VerificationSettings(Map("iss" -> "foo"))
        )
      )
      createOtoroshiVerifier(verifier).futureValue

      val authenticationModule = BasicAuthModuleConfig(
        id = IdGenerator.namedId("auth_mod", env),
        name = "New auth. module",
        desc = "New auth. module",
        tags = Seq.empty,
        metadata = Map.empty,
        sessionCookieValues = SessionCookieValues(),
        clientSideSessionEnabled = true,
        users = Seq(BasicAuthUser(
          name = "Stefanie Koss",
          password = "$2a$10$uCFLbo3TtK9VJvP5jO4REeN5ccfM/EZ9inPo6H4pNndSGUDCFPRzi",
          email = "stefanie.koss@oto.tools",
          tags = Seq.empty,
          rights = UserRights(
            Seq(
              UserRight(
                TenantAccess("*"),
                Seq(TeamAccess("*"))
              )
            )
          ),
          adminEntityValidators = Map()
        ))
      )

      createAuthModule(authenticationModule).futureValue

      val route = createRequestOtoroshiIORoute(
         Seq(
           NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
           NgPluginInstance(NgPluginHelper.pluginId[NgJwtUserExtractor],
              config = NgPluginInstanceConfig(
                NgJwtUserExtractorConfig(
                  verifier = verifier.id
                ).json.as[JsObject]
              )
            ),
           NgPluginInstance(NgPluginHelper.pluginId[AuthModule],
             config = NgPluginInstanceConfig(
                NgAuthModuleConfig(
                  module = authenticationModule.id.some
                ).json.as[JsObject]
              )
           )
         )
      )

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> route.frontend.domains.head.domain,
            "foo" -> "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJmb28iLCJpYXQiOjE3NjAxMDM1MzN9.TAj08m-Ax3dUFrZ2NU3oG3tPdIFOGvJdpO3Yhas63rw"
          )
          .get()
          .futureValue

        resp.status mustBe Status.OK
      }

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> route.frontend.domains.head.domain,
            "foo" -> "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJmb28iLCJpYXQiOjE3NjAxMDM1MzN9.TAj08m-Ax3dUFrZ2NU3oG3tPdIFOGvJdpOYhas63rw"
          )
          .get()
          .futureValue

        resp.status mustBe Status.UNAUTHORIZED
      }

      deleteAuthModule(authenticationModule).futureValue
      deleteOtoroshiVerifier(verifier).futureValue
      deleteOtoroshiRoute(route).futureValue
    }

    "Otoroshi Health endpoint" in {
      val route = createRequestOtoroshiIORoute(
         Seq(
           NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
           NgPluginInstance(NgPluginHelper.pluginId[OtoroshiHealthEndpoint],
             include = Seq("/health")
            )
         ))

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> route.frontend.domains.head.domain
          )
          .get()
          .futureValue

        resp.status mustBe Status.OK
        Json.parse(resp.body).selectAsString("method") mustEqual "GET"
      }

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/health")
          .withHttpHeaders(
            "Host" -> route.frontend.domains.head.domain
          )
          .get()
          .futureValue

        resp.status mustBe Status.OK
        Json.parse(resp.body).selectAsString("otoroshi") mustEqual "healthy"
        Json.parse(resp.body).selectAsString("datastore") mustEqual "healthy"

        val keys = Json.parse(resp.body).as[JsObject].keys

        keys.contains("proxy")
        keys.contains("storage")
        keys.contains("eventstore")
        keys.contains("certificates")
        keys.contains("scripts")
        keys.contains("cluster")
      }

      deleteOtoroshiRoute(route).futureValue
    }

    "Public/Private paths" in {
      val strictRoute = createRequestOtoroshiIORoute(
         Seq(
           NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
           NgPluginInstance(NgPluginHelper.pluginId[ApikeyCalls],
             config = NgPluginInstanceConfig(
               NgApikeyCallsConfig(
                  mandatory = false
               ).json.as[JsObject]
             )),
           NgPluginInstance(NgPluginHelper.pluginId[PublicPrivatePaths],
             config = NgPluginInstanceConfig(
                NgPublicPrivatePathsConfig(
                  strict = true,
                  publicPatterns = Seq("/public"),
                  privatePatterns = Seq("/private")
                ).json.as[JsObject]
             )
            )
         ),
        id = IdGenerator.uuid)

      val nonStrictRoute = createRequestOtoroshiIORoute(
         Seq(
           NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
           NgPluginInstance(NgPluginHelper.pluginId[ApikeyCalls],
             config = NgPluginInstanceConfig(
               NgApikeyCallsConfig(
                  mandatory = false
               ).json.as[JsObject]
             )),
           NgPluginInstance(NgPluginHelper.pluginId[PublicPrivatePaths],
             config = NgPluginInstanceConfig(
                NgPublicPrivatePathsConfig(
                  strict = true,
                  publicPatterns = Seq("/public"),
                  privatePatterns = Seq("/private")
                ).json.as[JsObject]
             )
            )
         ),
         id = IdGenerator.uuid)

      val apikey = ApiKey(
        clientId = "apikey-test",
        clientSecret = "1234",
        clientName = "apikey-test",
        authorizedEntities = Seq(RouteIdentifier(strictRoute.id), RouteIdentifier(nonStrictRoute.id))
      )

      createOtoroshiApiKey(apikey).futureValue

      def call(route: NgRoute, path: String, addApikey: Boolean = false) = {
        if (addApikey) {
          ws
            .url(s"http://127.0.0.1:$port$path")
            .withHttpHeaders(
              "Host" -> route.frontend.domains.head.domain,
              "Otoroshi-Client-Id" -> getValidApiKeyForPluginsRoute.clientId,
              "Otoroshi-Client-Secret" -> getValidApiKeyForPluginsRoute.clientSecret
            )
            .get()
            .futureValue
        } else {
          ws
            .url(s"http://127.0.0.1:$port$path")
            .withHttpHeaders(
              "Host" -> route.frontend.domains.head.domain
            )
            .get()
            .futureValue
        }
      }

      call(nonStrictRoute, "/public").status mustBe Status.OK
      call(nonStrictRoute, "/private").status mustBe Status.UNAUTHORIZED
      call(nonStrictRoute, "/private", addApikey = true).status mustBe Status.OK

      call(strictRoute, "/private", addApikey = true).status mustBe Status.OK
      call(strictRoute, "/private").status mustBe Status.UNAUTHORIZED

      deleteOtoroshiApiKey(apikey).futureValue
      deleteOtoroshiRoute(strictRoute).futureValue
      deleteOtoroshiRoute(nonStrictRoute).futureValue
    }

    "Remove cookies in" in {
      val route = createRequestOtoroshiIORoute(
         Seq(
           NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
           NgPluginInstance(NgPluginHelper.pluginId[RemoveCookiesIn],
             config = NgPluginInstanceConfig(
               RemoveCookiesInConfig(
                  names = Seq("foo")
               ).json.as[JsObject]
             ))
         ),
        id = IdGenerator.uuid)

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withCookies(Seq(
          DefaultWSCookie(
            name = "foo",
            value = "bar",
            domain = route.frontend.domains.head.domain.some),
          DefaultWSCookie(
              name = "baz",
              value = "bar",
              domain = route.frontend.domains.head.domain.some
          )): _*
        )
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain
        )
        .get()
        .futureValue

      val cookies = Json
        .parse(resp.body)
        .as[JsValue]
        .select("cookies")
        .as[Map[String, String]]

      cookies.get("foo") mustBe None
      cookies.get("baz") mustBe Some("bar")

      deleteOtoroshiRoute(route).futureValue
    }

    "Remove cookies out" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[AdditionalCookieOut],
            config = NgPluginInstanceConfig(
              AdditionalCookieOutConfig(
                name = "foo",
                value = "bar",
                domain = PLUGINS_HOST.some
              ).json.as[JsObject]
            )
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[RemoveCookiesOut],
            config = NgPluginInstanceConfig(
              RemoveCookiesInConfig(
                names = Seq("foo")
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

      resp.status mustBe Status.OK
      resp.cookies.isEmpty mustBe true

      deleteOtoroshiRoute(route).futureValue
    }

    "Basic auth. from auth. module" in {
      val authenticationModule = BasicAuthModuleConfig(
        id = IdGenerator.namedId("auth_mod", env),
        name = "New auth. module",
        desc = "New auth. module",
        tags = Seq.empty,
        metadata = Map.empty,
        sessionCookieValues = SessionCookieValues(),
        clientSideSessionEnabled = true,
        users = Seq(BasicAuthUser(
          name = "Stefanie Koss",
          password = "$2a$10$RtYWagxgvorxpxNIYTi4Be2tU.n8294eHpwle1ad0Tmh7.NiVXOEq",
          email = "user@oto.tools",
          tags = Seq.empty,
          rights = UserRights(
            Seq(
              UserRight(
                TenantAccess("*"),
                Seq(TeamAccess("*"))
              )
            )
          ),
          adminEntityValidators = Map()
        ))
      )

      createAuthModule(authenticationModule).futureValue

      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[BasicAuthWithAuthModule],
            config = NgPluginInstanceConfig(
              BasicAuthWithAuthModuleConfig(
                ref = authenticationModule.id,
                addAuthenticateHeader = true
              ).json.as[JsObject]
            )
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[RemoveCookiesOut],
            config = NgPluginInstanceConfig(
              RemoveCookiesInConfig(
                names = Seq("foo")
              ).json.as[JsObject]
            )
          )
        )
      )

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> PLUGINS_HOST,
          "Authorization" -> "Basic dXNlckBvdG8udG9vbHM6cGFzc3dvcmQ="
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK
      resp.cookies.isEmpty mustBe true

      deleteAuthModule(authenticationModule).futureValue
      deleteOtoroshiRoute(route).futureValue
    }

    "Request Echo" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[EchoBackend],
            config = NgPluginInstanceConfig(
              EchoBackendConfig(
                limit = 12
              ).json.as[JsObject]
            )
          )
        )
      )

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> PLUGINS_HOST,
          )
          .post(Json.obj("f" -> "b"))
          .futureValue

        resp.status mustBe Status.OK
      }

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> PLUGINS_HOST,
          )
          .post(Json.obj("foo" -> "bar"))
          .futureValue

        resp.status mustBe Status.REQUEST_ENTITY_TOO_LARGE
      }


      deleteOtoroshiRoute(route).futureValue
    }

    "Request body Echo" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[RequestBodyEchoBackend],
            config = NgPluginInstanceConfig(
              EchoBackendConfig(
                limit = 12
              ).json.as[JsObject]
            )
          )
        )
      )

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> PLUGINS_HOST,
          )
          .post(Json.obj("f" -> "b"))
          .futureValue

        resp.status mustBe Status.OK
        Json.parse(resp.body).selectAsString("f") mustEqual "b"
      }

      {
        val resp = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> PLUGINS_HOST,
          )
          .post(Json.obj("foo" -> "bar"))
          .futureValue

        resp.status mustBe Status.REQUEST_ENTITY_TOO_LARGE
      }

      deleteOtoroshiRoute(route).futureValue
    }

    "Custom quotas (per route)" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[NgCustomQuotas],
            config = NgPluginInstanceConfig(
              NgCustomQuotasConfig(
                dailyQuota = 1,
                monthlyQuota = 1,
                perRoute = true,
                global = false,
                group = None,
                expression = "${req.headers.foo}"
              ).json.as[JsObject]
            )
          )
        )
      )

      def call(value: String) = {
        ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> PLUGINS_HOST,
            "foo" -> value
          )
          .get()
          .futureValue
      }

      {
        val resp = call("bar")
        resp.status mustBe Status.OK
      }

      {
        val resp = call("bar")
        resp.status mustBe Status.FORBIDDEN
      }

      {
        val resp = call("baz")
        resp.status mustBe Status.OK
      }


      deleteOtoroshiRoute(route).futureValue
    }

    "Custom quotas (global)" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[NgCustomQuotas],
            config = NgPluginInstanceConfig(
              NgCustomQuotasConfig(
                dailyQuota = 2,
                monthlyQuota = 2,
                perRoute = false,
                global = true,
                group = None,
                expression = "${req.headers.foo}"
              ).json.as[JsObject]
            )
          )
        ),
        id = IdGenerator.uuid
      )

      val secondRoute = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[NgCustomQuotas],
            config = NgPluginInstanceConfig(
              NgCustomQuotasConfig(
                dailyQuota = 2,
                monthlyQuota = 2,
                perRoute = false,
                global = true,
                group = None,
                expression = "${req.headers.foo}"
              ).json.as[JsObject]
            )
          )
        ),
        id = IdGenerator.uuid
      )

      def call(route: NgRoute) = {
        ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> route.frontend.domains.head.domain,
            "foo" -> "bar"
          )
          .get()
          .futureValue
      }

      {
        val resp = call(route)
        resp.status mustBe Status.OK
      }

      {
        val resp = call(secondRoute)
        resp.status mustBe Status.OK
      }

      {
        val resp = call(route)
        resp.status mustBe Status.FORBIDDEN
      }

      deleteOtoroshiRoute(secondRoute).futureValue
      deleteOtoroshiRoute(route).futureValue
    }

    "Defer Responses" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[NgDeferPlugin],
            config = NgPluginInstanceConfig(
              NgDeferPluginConfig(
                duration = 2.seconds
              ).json.as[JsObject]
            )
          )
        ),
        id = IdGenerator.uuid
      )

      val lastStart = System.currentTimeMillis()

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK
      System.currentTimeMillis() - lastStart > 1000 mustBe true

      deleteOtoroshiRoute(route).futureValue
    }

    "Otoroshi info. token" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OtoroshiInfos],
            config = NgPluginInstanceConfig(
              NgOtoroshiInfoConfig.apply(
                secComVersion = SecComInfoTokenVersionLatest,
                secComTtl = 30.seconds,
                headerName = Some("foo"),
                addFields = None,
                projection = Json.obj(),
                algo = HSAlgoSettings(512, "secret", base64 = false)
              ).json.as[JsObject]
            )
          )
        ),
        id = IdGenerator.uuid
      )

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK

      val tokenBody = getInHeader(resp, "foo").get.split("\\.")(1)
      Json.parse(ApacheBase64.decodeBase64(tokenBody)).as[JsObject].selectAsString("iss") mustBe "Otoroshi"
      Json.parse(ApacheBase64.decodeBase64(tokenBody)).as[JsObject].selectAsString("access_type") mustBe "public"

      deleteOtoroshiRoute(route).futureValue
    }

    "Otoroshi info. token with apikeys" in {
      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[ApikeyCalls]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OtoroshiInfos],
            config = NgPluginInstanceConfig(
              NgOtoroshiInfoConfig.apply(
                secComVersion = SecComInfoTokenVersionLatest,
                secComTtl = 30.seconds,
                headerName = Some("foo"),
                addFields = None,
                projection = Json.obj(),
                algo = HSAlgoSettings(512, "secret", base64 = false)
              ).json.as[JsObject]
            )
          )
        ),
        id = IdGenerator.uuid
      )

      val apikey = ApiKey(
        clientId = IdGenerator.token(16),
        clientSecret = IdGenerator.token(64),
        clientName = "apikey1",
        authorizedEntities = Seq(RouteIdentifier(route.id))
      )
      createOtoroshiApiKey(apikey).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain,
          "Otoroshi-Client-Id" -> apikey.clientId,
          "Otoroshi-Client-Secret" -> apikey.clientSecret
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK

      val tokenBody = getInHeader(resp, "foo").get.split("\\.")(1)
      val token = Json.parse(ApacheBase64.decodeBase64(tokenBody)).as[JsObject]
      token.selectAsString("iss") mustBe "Otoroshi"
      token.selectAsString("access_type") mustBe "apikey"
      token.selectAsObject("apikey").selectAsString("clientId") mustBe apikey.clientId

      deleteOtoroshiApiKey(apikey).futureValue
      deleteOtoroshiRoute(route).futureValue
    }

    "Otoroshi info. token with user" in {
      val authenticationModule = BasicAuthModuleConfig(
        id = IdGenerator.namedId("auth_mod", env),
        name = "New auth. module",
        desc = "New auth. module",
        tags = Seq.empty,
        metadata = Map.empty,
        sessionCookieValues = SessionCookieValues(),
        clientSideSessionEnabled = true,
        users = Seq(BasicAuthUser(
          name = "Stefanie Koss",
          password = "$2a$10$RtYWagxgvorxpxNIYTi4Be2tU.n8294eHpwle1ad0Tmh7.NiVXOEq",
          email = "user@oto.tools",
          tags = Seq.empty,
          rights = UserRights(
            Seq(
              UserRight(
                TenantAccess("*"),
                Seq(TeamAccess("*"))
              )
            )
          ),
          adminEntityValidators = Map()
        ))
      )

      createAuthModule(authenticationModule).futureValue

      val route = createRequestOtoroshiIORoute(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[BasicAuthWithAuthModule],
            config = NgPluginInstanceConfig(
              BasicAuthWithAuthModuleConfig(ref = authenticationModule.id).json.as[JsObject]
            )
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OtoroshiInfos],
            config = NgPluginInstanceConfig(
              NgOtoroshiInfoConfig.apply(
                secComVersion = SecComInfoTokenVersionLatest,
                secComTtl = 30.seconds,
                headerName = Some("foo"),
                addFields = None,
                projection = Json.obj(),
                algo = HSAlgoSettings(512, "secret", base64 = false)
              ).json.as[JsObject]
            )
          )
        ),
        id = IdGenerator.uuid
      )

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain,
          "Authorization" -> "Basic dXNlckBvdG8udG9vbHM6cGFzc3dvcmQ="
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK

      val tokenBody = getInHeader(resp, "foo").get.split("\\.")(1)
      val token = Json.parse(ApacheBase64.decodeBase64(tokenBody)).as[JsObject]
      token.selectAsString("iss") mustBe "Otoroshi"
      token.selectAsString("access_type") mustBe "user"
      token.selectAsObject("user").selectAsString("email") mustBe "user@oto.tools"

      deleteAuthModule(authenticationModule).futureValue
      deleteOtoroshiRoute(route).futureValue
    }

    "Websocket json format validator (drop)" in {
      implicit val http: HttpExt = Http()(system)

      val backend = new WebsocketBackend().await()

      val route = createLocalRoute(
        frontendPath = "/",
        plugins = Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[WebsocketJsonFormatValidator],
            config = NgPluginInstanceConfig(
              WebsocketJsonFormatValidatorConfig(
                schema = Json.obj("type" -> "object", "required" -> Json.arr("name")).stringify.some,
                specification = "https://json-schema.org/draft/2020-12/schema",
                rejectStrategy = RejectStrategy.Drop
              ).json.as[JsObject]
            )
          )
        ),
        target = NgTarget(
          hostname = "127.0.0.1",
          port = backend.backendPort,
          id = "local.target",
          tls = false
        ).some
      )

      val messagesPromise = Promise[Int]()
      val counter = new AtomicInteger(0)

      val printSink: Sink[Message, Future[Done]] = Sink.foreach { message =>
        counter.incrementAndGet()
        if(counter.get == 2)
          messagesPromise.trySuccess(counter.get)
        else if(counter.get > 2)
          messagesPromise.tryFailure(new RuntimeException("Failure, but lost track of exception :-("))
      }

      val messages = List(
        TextMessage(Json.obj("name" -> "bar").stringify),
        TextMessage(Json.obj("name" -> "bar").stringify),
        TextMessage(Json.obj("foo" -> "bar").stringify),
        TextMessage("foo"),
        TextMessage("bar"),
        TextMessage(Json.obj("foo" -> "bar").stringify),
      )

      val clientSource: Source[TextMessage, NotUsed] = Source(messages)
        .throttle(1, 300.millis)

      val (_, _) = http.singleWebSocketRequest(
        WebSocketRequest(s"ws://127.0.0.1:$port/")
          .copy(extraHeaders = List(Host(route.frontend.domains.head.domainLowerCase))),
        Flow
          .fromSinkAndSourceMat(printSink, clientSource)(Keep.both)
          .alsoTo(Sink.onComplete { _ => })
      )

      val yesMessagesCounter = messagesPromise.future.futureValue(Timeout(Span(20, Seconds)))
      yesMessagesCounter mustBe 2

      backend.await()
      deleteOtoroshiRoute(route).futureValue
    }

    "Websocket json format validator (close connection)" in {
      implicit val http: HttpExt = Http()(system)

      val backend = new WebsocketBackend().await()

      val route = createLocalRoute(
        frontendPath = "/",
        plugins = Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[WebsocketJsonFormatValidator],
            config = NgPluginInstanceConfig(
              WebsocketJsonFormatValidatorConfig(
                schema = Json.obj("type" -> "object", "required" -> Json.arr("name")).stringify.some,
                specification = "https://json-schema.org/draft/2020-12/schema",
                rejectStrategy = RejectStrategy.Close
              ).json.as[JsObject]
            )
          )
        ),
        target = NgTarget(
          hostname = "127.0.0.1",
          port = backend.backendPort,
          id = "local.target",
          tls = false
        ).some
      )

      val messagesPromise = Promise[Int]()
      val counter = new AtomicInteger(0)

      val printSink: Sink[Message, Future[Done]] = Sink.foreach { message =>
        counter.incrementAndGet()

        if(counter.get == 2)
          messagesPromise.trySuccess(counter.get)
      }

      val messages = List(
        TextMessage(Json.obj("name" -> "bar").stringify),
        TextMessage(Json.obj("name" -> "bar").stringify),
        TextMessage(Json.obj("foo" -> "bar").stringify),
        TextMessage(Json.obj("name" -> "bar").stringify)
      )

      val clientSource: Source[TextMessage, NotUsed] = Source(messages)
        .throttle(1, 300.millis)

      val (_, (closed, _)) = http.singleWebSocketRequest(
        WebSocketRequest(s"ws://127.0.0.1:$port/")
          .copy(extraHeaders = List(Host(route.frontend.domains.head.domainLowerCase))),
        Flow
          .fromSinkAndSourceMat(printSink, clientSource)(Keep.both)
      )

      closed.onComplete {
        case Success(_) => println("WebSocket connection closed normally")
        case Failure(ex) => {
          println("fini")
          println(ex.getMessage)
          ex.getMessage.contains("Stopping now") mustBe true
        }
      }
      val yesMessagesCounter = messagesPromise.future.futureValue(Timeout(Span(20, Seconds)))
      yesMessagesCounter mustBe 2

      backend.await()
      deleteOtoroshiRoute(route).futureValue
    }

    "Websocket content validator" in {
      implicit val http: HttpExt = Http()(system)

      val backend = new WebsocketBackend().await()

      val route = createLocalRoute(
        frontendPath = "/",
        plugins = Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[WebsocketContentValidatorIn],
            config = NgPluginInstanceConfig(
              FrameFormatValidatorConfig(
               validator = Some(JsonPathValidator("$.message", JsString("foo"), None)),
               rejectStrategy = RejectStrategy.Drop
              ).json.as[JsObject]
            )
          )
        ),
        target = NgTarget(
          hostname = "127.0.0.1",
          port = backend.backendPort,
          id = "local.target",
          tls = false
        ).some
      )

      val messagesPromise = Promise[Int]()
      val counter = new AtomicInteger(0)

      val printSink: Sink[Message, Future[Done]] = Sink.foreach { message =>
        counter.incrementAndGet()
        if(counter.get == 2)
          messagesPromise.trySuccess(counter.get)
        else if(counter.get > 2)
          messagesPromise.tryFailure(new RuntimeException("Failure, but lost track of exception :-("))
      }

      val messages = List(
        TextMessage("name"),
        TextMessage("name"),
        TextMessage("foo"),
        TextMessage("foo"),
        TextMessage("nothing"),
      )

      val clientSource: Source[TextMessage, NotUsed] = Source(messages)
        .throttle(1, 300.millis)

      val (_, _) = http.singleWebSocketRequest(
        WebSocketRequest(s"ws://127.0.0.1:$port/")
          .copy(extraHeaders = List(Host(route.frontend.domains.head.domainLowerCase))),
        Flow
          .fromSinkAndSourceMat(printSink, clientSource)(Keep.both)
          .watchTermination()(Keep.both)
      )

      val yesMessagesCounter = messagesPromise.future.futureValue(Timeout(Span(20, Seconds)))
      yesMessagesCounter mustBe 2

      backend.await()
      http.shutdownAllConnectionPools()
      deleteOtoroshiRoute(route).futureValue
    }

    "Websocket type validator" in {
      implicit val http: HttpExt = Http()(system)

      val backend = new WebsocketBackend().await()

      val route = createLocalRoute(
        frontendPath = "/",
        plugins = Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          ),
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[WebsocketTypeValidator],
            config = NgPluginInstanceConfig(
              WebsocketTypeValidatorConfig(
               allowedFormat = FrameFormat.Json,
               rejectStrategy = RejectStrategy.Drop
              ).json.as[JsObject]
            )
          )
        ),
        target = NgTarget(
          hostname = "127.0.0.1",
          port = backend.backendPort,
          id = "local.target",
          tls = false
        ).some
      )

      val messagesPromise = Promise[Int]()
      val counter = new AtomicInteger(0)

      val printSink: Sink[Message, Future[Done]] = Sink.foreach { message =>
        counter.incrementAndGet()
        if(counter.get == 2)
          messagesPromise.trySuccess(counter.get)
        else if(counter.get > 2)
          messagesPromise.tryFailure(new RuntimeException("Failure, but lost track of exception :-("))
      }

      val messages = List(
        TextMessage("name"),
        TextMessage("name"),
        TextMessage(Json.obj("foo" -> "bar").stringify),
        TextMessage(Json.obj("foo" -> "bar").stringify),
        TextMessage("nothing"),
      )

      val clientSource: Source[TextMessage, NotUsed] = Source(messages)
        .throttle(1, 300.millis)

      val (_, _) = http.singleWebSocketRequest(
        WebSocketRequest(s"ws://127.0.0.1:$port/")
          .copy(extraHeaders = List(Host(route.frontend.domains.head.domainLowerCase))),
        Flow
          .fromSinkAndSourceMat(printSink, clientSource)(Keep.both)
          .watchTermination()(Keep.both)
      )

      val yesMessagesCounter = messagesPromise.future.futureValue(Timeout(Span(20, Seconds)))
      yesMessagesCounter mustBe 2

      backend.await()
      http.shutdownAllConnectionPools()
      deleteOtoroshiRoute(route).futureValue
    }
  }
}
