package functional

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.RawHeader
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.common.hash.Hashing
import com.typesafe.config.ConfigFactory
import models._
import org.joda.time.DateTime
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.libs.json.Json
import security.IdGenerator

import scala.math.BigDecimal.RoundingMode
import scala.util.Try

class Version1410Spec(name: String, configurationSpec: => Configuration)
    extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with OtoroshiSpecHelper
    with IntegrationPatience {

  implicit lazy val ws = otoroshiComponents.wsClient
  implicit val system  = ActorSystem("otoroshi-test")
  implicit val env     = otoroshiComponents.env

  import scala.concurrent.duration._

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

  s"[$name] Otoroshi service descriptors" should {

    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }

    "allow to remove headers from incoming request (#326)" in {
      val (_, port1, counter1, call) = testServer("removeincomingheaders.oto.tools", port, validate = (req) => {
        if (req.getHeader("X-Foo").isPresent) {
          false
        } else {
          req.getHeader("X-Bar").isPresent
        }
      })
      val service1 = ServiceDescriptor(
        id = "removeincomingheaders",
        name = "removeincomingheaders",
        env = "prod",
        subdomain = "removeincomingheaders",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http"
          )
        ),
        publicPatterns = Seq("/.*"),
        forceHttps = false,
        enforceSecureCommunication = false,
        removeHeadersIn = Seq("X-Foo")
      )

      createOtoroshiService(service1).futureValue

      val resp1 = call(Map("X-Foo" -> "Bar", "X-Bar" -> "Foo"))

      resp1.status mustBe 200
      counter1.get() mustBe 1
      deleteOtoroshiService(service1).futureValue

      stopServers()
    }
  }

  "allow to remove headers from outgoing response (#326)" in {
    val additionalHeadersOut = List(RawHeader("X-Foo", "Bar"))
    val (_, port1, counter1, call1) =
      testServer("removeoutgoingheaders.oto.tools", port, additionalHeadersOut = additionalHeadersOut)
    val service1 = ServiceDescriptor(
      id = "removeoutgoingheaders",
      name = "removeoutgoingheaders",
      env = "prod",
      subdomain = "removeoutgoingheaders",
      domain = "oto.tools",
      targets = Seq(
        Target(
          host = s"127.0.0.1:${port1}",
          scheme = "http"
        )
      ),
      publicPatterns = Seq("/.*"),
      forceHttps = false,
      enforceSecureCommunication = false,
      removeHeadersOut = Seq("X-Foo")
    )

    createOtoroshiService(service1).futureValue

    val resp1 = call1(Map.empty)

    resp1.status mustBe 200
    counter1.get() mustBe 1

    resp1.header("X-Foo").isEmpty mustBe true

    deleteOtoroshiService(service1).futureValue

    stopServers()
  }

  "allow apikeys to have ttl (#328)" in {
    val (_, port1, counter1, call1) = testServer("apikeyswithttl.oto.tools", port)
    val service1 = ServiceDescriptor(
      id = "apikeyswithttl",
      name = "apikeyswithttl",
      env = "prod",
      subdomain = "apikeyswithttl",
      domain = "oto.tools",
      targets = Seq(
        Target(
          host = s"127.0.0.1:${port1}",
          scheme = "http"
        )
      ),
      forceHttps = false,
      enforceSecureCommunication = false,
    )
    val validApiKey = ApiKey(
      clientName = "apikey1",
      authorizedGroup = "default",
      validUntil = Some(DateTime.now().plusDays(1))
    )
    val invalidApiKey = ApiKey(
      clientName = "apikey2",
      authorizedGroup = "default",
      validUntil = Some(DateTime.now().minusDays(1))
    )

    createOtoroshiService(service1).futureValue
    createOtoroshiApiKey(validApiKey).futureValue
    createOtoroshiApiKey(invalidApiKey).futureValue

    val resp1 = call1(
      Map(
        "Otoroshi-Client-Id"     -> validApiKey.clientId,
        "Otoroshi-Client-Secret" -> validApiKey.clientSecret
      )
    )

    val resp2 = call1(
      Map(
        "Otoroshi-Client-Id"     -> invalidApiKey.clientId,
        "Otoroshi-Client-Secret" -> invalidApiKey.clientSecret
      )
    )

    resp1.status mustBe 200
    counter1.get() mustBe 1

    resp2.status mustBe 400
    counter1.get() mustBe 1

    deleteOtoroshiService(service1).futureValue
    deleteOtoroshiApiKey(validApiKey).futureValue
    deleteOtoroshiApiKey(invalidApiKey).futureValue

    stopServers()
  }
}
