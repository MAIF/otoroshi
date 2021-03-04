package functional

import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.typesafe.config.ConfigFactory
import otoroshi.models.{ApiKey, ServiceDescriptor, ServiceGroupIdentifier, Target}
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.libs.ws.WSResponse

class SidecarSpec(name: String, configurationSpec: => Configuration)
    extends OtoroshiSpec {

  lazy val serviceHost = "sidecar.oto.tools"
  implicit val system  = ActorSystem("otoroshi-test")
  lazy val fakePort    = TargetService.freePort

  def debugResponse(resp: WSResponse): WSResponse = {
    if (resp.status != 200) {
      println(resp.status + " => " + resp.body)
    }
    resp
  }

  override def getTestConfiguration(configuration: Configuration) = Configuration(
    ConfigFactory
      .parseString(s"""
                      |{
                      |  app.sidecar.serviceId = "sidecar-service1-test"
                      |  app.sidecar.target = "http://127.0.0.1:$fakePort"
                      |  app.sidecar.from = "127.0.0.1"
                      |  app.sidecar.strict = false
                      |  app.sidecar.apikey.clientId = "sidecar-apikey-test"
                      |}
       """.stripMargin)
      .resolve()
  ).withFallback(configurationSpec).withFallback(configuration)

  s"[$name] Otoroshi Sidecar" should {

    val basicTestExpectedBody = """{"message":"hello world"}"""
    val basicTestServer1 = TargetService
      .withPort(fakePort, Some(serviceHost), "/api", "application/json", { _ =>
        basicTestExpectedBody
      })
      .await()

    val basicTestExpectedBody2 = """{"message":"bye world"}"""
    val basicTestServer2 = TargetService(Some(serviceHost), "/api", "application/json", { _ =>
      basicTestExpectedBody2
    }).await()

    val basicTestExpectedBody3 = """{"message":"yeah world"}"""
    val basicTestServer3 = TargetService(Some("sidecar2.oto.tools"), "/api", "application/json", { _ =>
      basicTestExpectedBody3
    }).await()

    val service1 = ServiceDescriptor(
      id = "sidecar-service1-test",
      name = "sidecar-service1-test",
      env = "prod",
      subdomain = "sidecar",
      domain = "oto.tools",
      targets = Seq(
        Target(
          host = s"127.0.0.1:${basicTestServer2.port}",
          scheme = "http"
        )
      ),
      forceHttps = false,
      enforceSecureCommunication = false
    )

    val service2 = ServiceDescriptor(
      id = "sidecar-service2-test",
      name = "sidecar-service2-test",
      env = "prod",
      subdomain = "sidecar2",
      domain = "oto.tools",
      targets = Seq(
        Target(
          host = s"127.0.0.1:${basicTestServer3.port}",
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
      authorizedEntities = Seq(ServiceGroupIdentifier("default"))
    )

    "warm up" in {
      startOtoroshi()
      getOtoroshiServices().futureValue // WARM UP
      createOtoroshiApiKey(apiKey).futureValue
    }

    "Allow access to local service from outside" in {
      createOtoroshiService(service1).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"                   -> serviceHost,
          "Otoroshi-Client-Id"     -> apiKey.clientId,
          "Otoroshi-Client-Secret" -> apiKey.clientSecret,
          "X-Forwarded-For"        -> "99.99.99.99"
        )
        .get()
        .futureValue

      resp.status mustBe 200
      resp.body mustBe basicTestExpectedBody

      deleteOtoroshiService(service1).futureValue
    }

    "Not allow access to local service from outside without apikey" in {
      createOtoroshiService(service1).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"            -> serviceHost,
          "X-Forwarded-For" -> "99.99.99.99"
        )
        .get()
        .futureValue

      resp.status mustBe 400
      resp.body.contains("No ApiKey provided") mustBe true

      deleteOtoroshiService(service1).futureValue
    }

    "Allow access to outside service from inside without apikey" in {
      createOtoroshiService(service1).futureValue
      createOtoroshiService(service2).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"            -> "sidecar2.oto.tools",
          "X-Forwarded-For" -> "127.0.0.1"
        )
        .get()
        .futureValue

      resp.status mustBe 200
      resp.body mustBe basicTestExpectedBody3

      deleteOtoroshiService(service1).futureValue
      deleteOtoroshiService(service2).futureValue
    }

    "Not allow access to outside service from outside without apikey" in {
      createOtoroshiService(service1).futureValue
      createOtoroshiService(service2).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"            -> "sidecar2.oto.tools",
          "X-Forwarded-For" -> "127.0.0.2"
        )
        .get()
        .futureValue

      resp.status mustBe 502
      resp.body.contains("sidecar.bad.request.origin") mustBe true

      deleteOtoroshiService(service1).futureValue
      deleteOtoroshiService(service2).futureValue
    }

    "stop servers" in {
      basicTestServer1.stop()
      basicTestServer2.stop()
      system.terminate()
    }

    "shutdown" in {
      stopAll()
    }
  }
}
