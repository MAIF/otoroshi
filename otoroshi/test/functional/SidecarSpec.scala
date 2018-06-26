package functional

import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.typesafe.config.ConfigFactory
import models.{ApiKey, ServiceDescriptor, Target}
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import play.api.Configuration

class SidecarSpec(name: String, configurationSpec: => Configuration)
  extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with OtoroshiSpecHelper
    with IntegrationPatience {

  lazy val serviceHost = "sidecar.foo.bar"
  lazy val ws          = otoroshiComponents.wsClient
  implicit val system  = ActorSystem("otoroshi-test")
  lazy val fakePort = TargetService.freePort

  override def getConfiguration(configuration: Configuration) = configuration ++ configurationSpec ++ Configuration(
    ConfigFactory
      .parseString(s"""
                      |{
                      |  http.port=$port
                      |  play.server.http.port=$port
                      |  app.sidecar.serviceId = "sidecar-service1-test"
                      |  app.sidecar.target = "http://127.0.0.1:$fakePort"
                      |  app.sidecar.from = "127.0.0.1"
                      |  app.sidecar.apikey.clientId = "sidecar-apikey-test"
                      |}
       """.stripMargin)
      .resolve()
  )

  s"[$name] Otoroshi Sidecar" should {

    val callCounter1          = new AtomicInteger(0)
    val basicTestExpectedBody = """{"message":"hello world"}"""
    val basicTestServer1 = TargetService.withPort(fakePort, Some(serviceHost), "/api", "application/json", { _ =>
      callCounter1.incrementAndGet()
      basicTestExpectedBody
    }).await()

    val callCounter2          = new AtomicInteger(0)
    val basicTestExpectedBody2 = """{"message":"bye world"}"""
    val basicTestServer2 = TargetService(Some(serviceHost), "/api", "application/json", { _ =>
      callCounter2.incrementAndGet()
      basicTestExpectedBody2
    }).await()

    val service1 = ServiceDescriptor(
      id = "sidecar-service1-test",
      name = "sidecar-service1-test",
      env = "prod",
      subdomain = "sidecar",
      domain = "foo.bar",
      targets = Seq(
        Target(
          host = s"127.0.0.1:${basicTestServer2.port}",
          scheme = "http"
        )
      ),
      forceHttps = false,
      enforceSecureCommunication = false
    )

    val apiKey = ApiKey(
      clientId = "sidecar-apikey-test",
      clientSecret = "1234",
      clientName = "apikey-test",
      authorizedGroup = "default"
    )

    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
      createOtoroshiApiKey(apiKey).futureValue
    }

    "Allow access to local service from outside" in {
      createOtoroshiService(service1).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost,
          "Otoroshi-Client-Id" -> apiKey.clientId,
          "Otoroshi-Client-Secret" -> apiKey.clientSecret,
          "X-Forwarded-For" -> "99.99.99.99"
        )
        .get()
        .futureValue

      resp.status mustBe 200
      resp.body mustBe basicTestExpectedBody
      callCounter1.get() mustBe 1

      deleteOtoroshiService(service1).futureValue
    }

    "Allow access to outside service from inside without apikey" in {
      createOtoroshiService(service1).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost,
          "X-Forwarded-For" -> "127.0.0.1"
        )
        .get()
        .futureValue

      resp.status mustBe 200
      resp.body mustBe basicTestExpectedBody2
      callCounter1.get() mustBe 1

      deleteOtoroshiService(service1).futureValue
    }

    "stop servers" in {
      basicTestServer1.stop()
      basicTestServer2.stop()
      system.terminate()
    }
  }
}
