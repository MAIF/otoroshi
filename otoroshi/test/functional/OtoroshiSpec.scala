package functional

import java.util.concurrent.atomic.AtomicInteger

import com.typesafe.config.ConfigFactory
import models.{ServiceDescriptor, Target}
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import play.api.Configuration

class OtoroshiBasicSpec(name: String, configurationSpec: Configuration)
  extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with OtoroshiSpecHelper
    with IntegrationPatience {

  lazy val serviceHost = "basictest.foo.bar"
  lazy val ws = otoroshiComponents.wsClient

  override def getConfiguration(configuration: Configuration) = configuration ++ configurationSpec ++ Configuration(
    ConfigFactory
      .parseString(s"""
         |{
         |  http.port=$port
         |  play.server.http.port=$port
         |}
       """.stripMargin).resolve()
  )

  s"[$name] Otoroshi" should {

    val callCounter = new AtomicInteger(0)
    val basicTestExpectedBody = """{"message":"hello world"}"""
    val basicTestServer = TargetService(Some(serviceHost), "/api", "application/json", { _ =>
      callCounter.incrementAndGet()
      basicTestExpectedBody
    }).await()
    val initialDescriptor = ServiceDescriptor(
      id = "basic-test",
      name = "basic-test",
      env = "prod",
      subdomain = "basictest",
      domain = "foo.bar",
      targets = Seq(
        Target(
          host = s"127.0.0.1:${basicTestServer.port}",
          scheme = "http"
        )
      ),
      localHost = s"127.0.0.1:${basicTestServer.port}",
      forceHttps = false,
      enforceSecureCommunication = false,
      publicPatterns = Seq("/.*")
    )

    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }

    s"return only one service descriptor after startup (for admin API)" in {
      val services = getOtoroshiServices().futureValue
      services.size mustBe 1
    }

    s"route a basic http call" in {

      val (_, creationStatus) = createOtoroshiService(initialDescriptor).futureValue

      creationStatus mustBe 200

      val basicTestResponse1 = ws.url(s"http://127.0.0.1:$port/api").withHttpHeaders(
        "Host" -> serviceHost
      ).get().futureValue

      basicTestResponse1.status mustBe 200
      basicTestResponse1.body mustBe basicTestExpectedBody
      callCounter.get() mustBe 1
    }

    "provide a way to disable a service descriptor" in {

      updateOtoroshiService(initialDescriptor.copy(enabled = false)).futureValue

      val basicTestResponse2 = ws.url(s"http://127.0.0.1:$port/api").withHttpHeaders(
        "Host" -> serviceHost
      ).get().futureValue

      basicTestResponse2.status mustBe 404
      callCounter.get() mustBe 1

      updateOtoroshiService(initialDescriptor.copy(enabled = true)).futureValue

      val basicTestResponse3 = ws.url(s"http://127.0.0.1:$port/api").withHttpHeaders(
        "Host" -> serviceHost
      ).get().futureValue

      basicTestResponse3.status mustBe 200
      basicTestResponse3.body mustBe basicTestExpectedBody
      callCounter.get() mustBe 2
    }

    "provide a way to pass a service descriptor in maintenance mode" in {

      updateOtoroshiService(initialDescriptor.copy(maintenanceMode = true)).futureValue

      val basicTestResponse2 = ws.url(s"http://127.0.0.1:$port/api").withHttpHeaders(
        "Host" -> serviceHost
      ).get().futureValue

      basicTestResponse2.status mustBe 503
      basicTestResponse2.body.contains("Service in maintenance mode") mustBe true
      callCounter.get() mustBe 2

      updateOtoroshiService(initialDescriptor.copy(maintenanceMode = false)).futureValue

      val basicTestResponse3 = ws.url(s"http://127.0.0.1:$port/api").withHttpHeaders(
        "Host" -> serviceHost
      ).get().futureValue

      basicTestResponse3.status mustBe 200
      basicTestResponse3.body mustBe basicTestExpectedBody
      callCounter.get() mustBe 3
    }

    "provide a way to pass a service descriptor in build mode" in {

      updateOtoroshiService(initialDescriptor.copy(buildMode = true)).futureValue

      val basicTestResponse2 = ws.url(s"http://127.0.0.1:$port/api").withHttpHeaders(
        "Host" -> serviceHost
      ).get().futureValue

      basicTestResponse2.status mustBe 503
      basicTestResponse2.body.contains("Service under construction") mustBe true
      callCounter.get() mustBe 3

      updateOtoroshiService(initialDescriptor.copy(buildMode = false)).futureValue

      val basicTestResponse3 = ws.url(s"http://127.0.0.1:$port/api").withHttpHeaders(
        "Host" -> serviceHost
      ).get().futureValue

      basicTestResponse3.status mustBe 200
      basicTestResponse3.body mustBe basicTestExpectedBody
      callCounter.get() mustBe 4
    }

    "provide a way to force https for a service descriptor" in {

      updateOtoroshiService(initialDescriptor.copy(forceHttps = true)).futureValue

      val basicTestResponse2 = ws.url(s"http://127.0.0.1:$port/api").withFollowRedirects(false).withHttpHeaders(
        "Host" -> serviceHost
      ).get().futureValue

      basicTestResponse2.status mustBe 303
      basicTestResponse2.header("Location") mustBe Some("https://basictest.foo.bar/api")
      callCounter.get() mustBe 4

      updateOtoroshiService(initialDescriptor.copy(forceHttps = false)).futureValue

      val basicTestResponse3 = ws.url(s"http://127.0.0.1:$port/api").withHttpHeaders(
        "Host" -> serviceHost
      ).get().futureValue

      basicTestResponse3.status mustBe 200
      basicTestResponse3.body mustBe basicTestExpectedBody
      callCounter.get() mustBe 5
    }

    "send specific headers back" in {

      val basicTestResponse2 = ws.url(s"http://127.0.0.1:$port/api").withHttpHeaders(
        "Host" -> serviceHost
      ).get().futureValue

      basicTestResponse2.status mustBe 200
      basicTestResponse2.header("Otoroshi-Request-Id").isDefined mustBe true
      basicTestResponse2.header("Otoroshi-Proxy-Latency").isDefined mustBe true
      basicTestResponse2.header("Otoroshi-Upstream-Latency").isDefined mustBe true
      callCounter.get() mustBe 6

      updateOtoroshiService(initialDescriptor.copy(sendOtoroshiHeadersBack = false)).futureValue

      val basicTestResponse3 = ws.url(s"http://127.0.0.1:$port/api").withHttpHeaders(
        "Host" -> serviceHost
      ).get().futureValue

      basicTestResponse3.status mustBe 200
      basicTestResponse3.header("Otoroshi-Request-Id").isEmpty mustBe true
      basicTestResponse3.header("Otoroshi-Proxy-Latency").isEmpty mustBe true
      basicTestResponse3.header("Otoroshi-Upstream-Latency").isEmpty mustBe true
      callCounter.get() mustBe 7

      updateOtoroshiService(initialDescriptor.copy(sendOtoroshiHeadersBack = true)).futureValue
    }

    "stop servers" in {
      deleteOtoroshiService(initialDescriptor).futureValue
      basicTestServer.stop()
    }
  }
}