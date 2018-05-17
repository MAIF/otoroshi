package functional

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import models.{ClientConfig, ServiceDescriptor, Target}
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import play.api.Configuration

import scala.concurrent.duration._

class CircuitBreakerSpec(name: String, configurationSpec: => Configuration)
  extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with OtoroshiSpecHelper
    with IntegrationPatience {

  lazy val serviceHost = "cb.foo.bar"
  lazy val ws = otoroshiComponents.wsClient
  implicit val system = ActorSystem("otoroshi-test")

  override def getConfiguration(configuration: Configuration) = configuration ++ configurationSpec ++ Configuration(
    ConfigFactory
      .parseString(s"""
                      |{
                      |  http.port=$port
                      |  play.server.http.port=$port
                      |}
       """.stripMargin).resolve()
  )

  s"[$name] Otoroshi Circuit Breaker" should {

    val callCounter1 = new AtomicInteger(0)
    val basicTestExpectedBody = """{"message":"hello world"}"""
    val basicTestServer1 = TargetService(Some(serviceHost), "/api", "application/json", { _ =>
      callCounter1.incrementAndGet()
      basicTestExpectedBody
    }).await()

    val callCounter2 = new AtomicInteger(0)
    val basicTestServer2 = TargetService(Some(serviceHost), "/api", "application/json", { _ =>
      callCounter2.incrementAndGet()
      basicTestExpectedBody
    }).await()

    val callCounter3 = new AtomicInteger(0)
    val basicTestServer3 = TargetService(Some(serviceHost), "/api", "application/json", { _ =>
      awaitF(2.seconds).futureValue
      callCounter3.incrementAndGet()
      basicTestExpectedBody
    }).await()

    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }

    "Open if too many failures" in {
      val fakePort = TargetService.freePort
      val service = ServiceDescriptor(
        id = "cb-test",
        name = "cb-test",
        env = "prod",
        subdomain = "cb",
        domain = "foo.bar",
        targets = Seq(
          Target(
            host = s"127.0.0.1:$fakePort",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*"),
        clientConfig = ClientConfig(
          maxErrors = 3,
          sampleInterval = 500
        )
      )
      createOtoroshiService(service).futureValue


      def callServer() = {
        ws.url(s"http://127.0.0.1:$port/api").withHttpHeaders(
          "Host" -> "cb.foo.bar"
        ).get().futureValue
      }

      val basicTestResponse1 = callServer()

      basicTestResponse1.status mustBe 502
      basicTestResponse1.body.contains("the connection to downstream service was refused") mustBe true

      callServer()
      callServer()
      callServer()

      val basicTestResponse2 = callServer()
      basicTestResponse2.status mustBe 502
      basicTestResponse2.body.contains("the downstream service seems a little bit overwhelmed") mustBe true

      deleteOtoroshiService(service).futureValue
    }

    "Open if too many failures and close back" in {
      val fakePort = TargetService.freePort
      val service = ServiceDescriptor(
        id = "cb-test",
        name = "cb-test",
        env = "prod",
        subdomain = "cb",
        domain = "foo.bar",
        targets = Seq(
          Target(
            host = s"127.0.0.1:$fakePort",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*"),
        clientConfig = ClientConfig(
          maxErrors = 3,
          sampleInterval = 500
        )
      )
      createOtoroshiService(service).futureValue

      def callServer() = {
        ws.url(s"http://127.0.0.1:$port/api").withHttpHeaders(
          "Host" -> "cb.foo.bar"
        ).get().futureValue
      }

      val basicTestResponse1 = callServer()
      basicTestResponse1.status mustBe 502
      basicTestResponse1.body.contains("the connection to downstream service was refused") mustBe true

      callServer()
      callServer()
      callServer()

      val basicTestResponse2 = callServer()
      basicTestResponse2.status mustBe 502
      basicTestResponse2.body.contains("the downstream service seems a little bit overwhelmed") mustBe true

      awaitF(1.seconds).futureValue

      val basicTestResponse3 = callServer()
      basicTestResponse3.status mustBe 502
      basicTestResponse3.body.contains("the connection to downstream service was refused") mustBe true

      deleteOtoroshiService(service).futureValue
    }

    "Retry on failures" in {
      val fakePort = TargetService.freePort
      val service = ServiceDescriptor(
        id = "cb-test",
        name = "cb-test",
        env = "prod",
        subdomain = "cb1",
        domain = "foo.bar",
        targets = Seq(
          Target(
            host = s"127.0.0.1:$fakePort",
            scheme = "http"
          ),
          Target(
            host = s"127.0.0.1:${basicTestServer1.port}",
            scheme = "http"
          ),
          Target(
            host = s"127.0.0.1:${basicTestServer2.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*"),
        clientConfig = ClientConfig(
          retries = 2,
          maxErrors = 3,
          sampleInterval = 500
        )
      )
      createOtoroshiService(service).futureValue


      def callServer() = {
        ws.url(s"http://127.0.0.1:$port/api").withHttpHeaders(
          "Host" -> "cb.foo.bar"
        ).get().futureValue
      }

      val basicTestResponse1 = callServer()

      basicTestResponse1.status mustBe 200
      callCounter1.get() mustBe 1

      callServer().status mustBe 200
      callServer().status mustBe 200
      callServer().status mustBe 200

      callCounter1.get() mustBe 2
      callCounter2.get() mustBe 2

      deleteOtoroshiService(service).futureValue
    }

    "Timeout on long calls" in {
      val service = ServiceDescriptor(
        id = "cb-test",
        name = "cb-test",
        env = "prod",
        subdomain = "cb",
        domain = "foo.bar",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${basicTestServer3.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*"),
        clientConfig = ClientConfig(
          callTimeout = 200
        )
      )
      createOtoroshiService(service).futureValue


      def callServer() = {
        ws.url(s"http://127.0.0.1:$port/api").withHttpHeaders(
          "Host" -> "cb.foo.bar"
        ).get().futureValue
      }

      val basicTestResponse1 = callServer()
      basicTestResponse1.status mustBe 502
      basicTestResponse1.body.contains("Something went wrong, the downstream service does not respond quickly enough, you should try later. Thanks for your understanding") mustBe true

      deleteOtoroshiService(service).futureValue
    }

    "Timeout on long calls with retries" in {
      val service = ServiceDescriptor(
        id = "cb-test",
        name = "cb-test",
        env = "prod",
        subdomain = "cb",
        domain = "foo.bar",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${basicTestServer3.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*"),
        clientConfig = ClientConfig(
          retries = 3,
          callTimeout = 800,
          globalTimeout = 2000
        )
      )
      createOtoroshiService(service).futureValue


      def callServer() = {
        ws.url(s"http://127.0.0.1:$port/api").withHttpHeaders(
          "Host" -> "cb.foo.bar"
        ).get().futureValue
      }

      val basicTestResponse1 = callServer()
      println(basicTestResponse1.body)

      basicTestResponse1.status mustBe 502
      basicTestResponse1.body.contains("Something went wrong, the downstream service does not respond quickly enough, you should try later. Thanks for your understanding") mustBe true

      deleteOtoroshiService(service).futureValue
    }

    "stop servers" in {
      basicTestServer1.stop()
      basicTestServer2.stop()
      system.terminate()
    }
  }
}
