package functional

import java.util.concurrent.atomic.AtomicInteger

import com.typesafe.config.ConfigFactory
import models.{ServiceDescriptor, Target}
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import play.api.Configuration

class CanarySpec(name: String, configurationSpec: => Configuration)
    extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with OtoroshiSpecHelper
    with IntegrationPatience {

  lazy val serviceHost = "canary.foo.bar"
  lazy val ws          = otoroshiComponents.wsClient

  override def getConfiguration(configuration: Configuration) = configuration ++ configurationSpec ++ Configuration(
    ConfigFactory
      .parseString(s"""
                      |{
                      |  http.port=$port
                      |  play.server.http.port=$port
                      |}
       """.stripMargin)
      .resolve()
  )

  s"[$name] Otoroshi Canary Mode" should {

    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }

    "Split traffic" in {

      val callCounter1           = new AtomicInteger(0)
      val basicTestExpectedBody1 = """{"message":"hello world 1"}"""
      val basicTestServer1 = TargetService(Some(serviceHost), "/api", "application/json", { _ =>
        callCounter1.incrementAndGet()
        basicTestExpectedBody1
      }).await()

      val callCounter2           = new AtomicInteger(0)
      val basicTestExpectedBody2 = """{"message":"hello world 2"}"""
      val basicTestServer2 = TargetService(Some(serviceHost), "/api", "application/json", { _ =>
        callCounter2.incrementAndGet()
        basicTestExpectedBody2
      }).await()

      val service = ServiceDescriptor(
        id = "cb-test",
        name = "cb-test",
        env = "prod",
        subdomain = "canary",
        domain = "foo.bar",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${basicTestServer1.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*"),
        canary = models.Canary(
          enabled = true,
          targets = Seq(
            Target(
              host = s"127.0.0.1:${basicTestServer2.port}",
              scheme = "http"
            )
          )
        )
      )

      createOtoroshiService(service).futureValue

      def callServer() = {
        val r = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> serviceHost
          )
          .get()
          .futureValue
        (r.status, r.body, r.header("Otoroshi-Canary-Id").getOrElse("--"))
      }

      (0 until 100).foreach { _ =>
        val (status, body, id) = callServer()
        if (status != 200) {
          println(body)
        }
        status mustBe 200
        id == "--" mustBe false
      }

      callCounter2.get() mustBe 20
      callCounter1.get() mustBe 80

      deleteOtoroshiService(service).futureValue

      basicTestServer1.stop()
      basicTestServer2.stop()
    }

    "Always split traffic the same way" in {

      val callCounter1           = new AtomicInteger(0)
      val basicTestExpectedBody1 = """{"message":"hello world 1"}"""
      val basicTestServer1 = new SimpleTargetService(Some(serviceHost), "/api", "application/json", { _ =>
        callCounter1.incrementAndGet()
        basicTestExpectedBody1
      }).await()

      val callCounter2           = new AtomicInteger(0)
      val basicTestExpectedBody2 = """{"message":"hello world 2"}"""
      val basicTestServer2 = new SimpleTargetService(Some(serviceHost), "/api", "application/json", { _ =>
        callCounter2.incrementAndGet()
        basicTestExpectedBody2
      }).await()

      val service = ServiceDescriptor(
        id = "cb-test-2",
        name = "cb-test-2",
        env = "prod",
        subdomain = "canary2",
        domain = "foo.bar",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${basicTestServer1.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*"),
        canary = models.Canary(
          enabled = true,
          targets = Seq(
            Target(
              host = s"127.0.0.1:${basicTestServer2.port}",
              scheme = "http"
            )
          )
        )
      )

      createOtoroshiService(service).futureValue

      def firstCallServer() = {
        val r = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> "canary2.foo.bar"
          )
          .get()
          .futureValue
        (r.status, r.body, r.header("Otoroshi-Canary-Id").getOrElse("--"))
      }

      def callServer(id: String) = {
        val r = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host"               -> "canary2.foo.bar",
            "Otoroshi-Canary-Id" -> id
          )
          .get()
          .futureValue
        //println(r.body)
        (r.status, r.body, r.header("Otoroshi-Canary-Id").getOrElse("--"))
      }

      val (_, _, firstId) = firstCallServer()

      (0 until 100).foreach { _ =>
        val (status, _, id) = callServer(firstId)
        status mustBe 200
        id == "--" mustBe false
      }

      if (callCounter1.get() > 0) {
        callCounter1.get() mustBe 101
        callCounter2.get() mustBe 0
      } else {
        callCounter2.get() mustBe 101
        callCounter1.get() mustBe 0
      }

      deleteOtoroshiService(service).futureValue

      basicTestServer1.stop()
      basicTestServer2.stop()
    }
  }
}
