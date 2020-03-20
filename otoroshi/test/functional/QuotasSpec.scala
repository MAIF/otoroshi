package functional

import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import models.{ApiKey, ServiceDescriptor, Target}
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import play.api.Configuration

import scala.concurrent.duration._

class QuotasSpec(name: String, configurationSpec: => Configuration)
    extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with OtoroshiSpecHelper
    with IntegrationPatience {

  lazy val serviceHost = "quotas.oto.tools"
  lazy val ws          = otoroshiComponents.wsClient
  implicit val system  = ActorSystem("otoroshi-test")

  override def getTestConfiguration(configuration: Configuration) = Configuration(
    ConfigFactory
      .parseString(s"""
                      |{
                      |  http.port=$port
                      |  play.server.http.port=$port
                      |  throttlingWindow = 2
                      |}
       """.stripMargin)
      .resolve()
  ).withFallback(configurationSpec).withFallback(configuration)

  s"[$name] Otoroshi Quotas" should {

    val counter = new AtomicInteger(0)
    val body    = """{"message":"hello world"}"""
    val server = TargetService(None, "/api", "application/json", { _ =>
      counter.incrementAndGet()
      body
    }).await()
    val service = ServiceDescriptor(
      id = "1-quotas-test",
      name = "quotas-test",
      env = "prod",
      subdomain = "quotas",
      domain = "oto.tools",
      targets = Seq(
        Target(
          host = s"127.0.0.1:${server.port}",
          scheme = "http"
        )
      ),
      forceHttps = false,
      enforceSecureCommunication = false
    )
    val apiKeyLowThrottlingQuota = ApiKey(
      clientId = "1-apikey-throttling",
      clientSecret = "1234",
      clientName = "apikey-test",
      authorizedGroup = "default",
      throttlingQuota = 3
      // dailyQuota = 3
      // monthlyQuota = 3
    )
    val apiKeyLowDailyQuota = ApiKey(
      clientId = "1-apikey-daily",
      clientSecret = "1234",
      clientName = "apikey-test",
      authorizedGroup = "default",
      // throttlingQuota = 3
      dailyQuota = 3
      // monthlyQuota = 3
    )
    val apiKeyLowDMonthlyQuota = ApiKey(
      clientId = "1-apikey-monthly",
      clientSecret = "1234",
      clientName = "apikey-test",
      authorizedGroup = "default",
      // throttlingQuota = 3
      // dailyQuota = 3
      monthlyQuota = 3
    )
    val basicAuthThrottling = Base64.getUrlEncoder.encodeToString(s"1-apikey-throttling:1234".getBytes)
    val basicAuthDaily      = Base64.getUrlEncoder.encodeToString(s"1-apikey-daily:1234".getBytes)
    val basicAuthMonthly    = Base64.getUrlEncoder.encodeToString(s"1-apikey-monthly:1234".getBytes)

    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
      createOtoroshiService(service).futureValue
      createOtoroshiApiKey(apiKeyLowThrottlingQuota).futureValue
      createOtoroshiApiKey(apiKeyLowDailyQuota).futureValue
      createOtoroshiApiKey(apiKeyLowDMonthlyQuota).futureValue
    }

    "prevent too many calls per second" in {
      def call(auth: String) = {
        ws.url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host"                   -> serviceHost,
            "Otoroshi-Authorization" -> s"Basic $auth"
          )
          .get()
          .futureValue
      }

      val counter200 = new AtomicInteger(0)
      val counter429 = new AtomicInteger(0)

      (0 to 50).foreach { _ =>
        val resp = call(basicAuthThrottling)
        if (resp.status == 200) counter200.incrementAndGet()
        if (resp.status == 429) counter429.incrementAndGet()
      }

      counter200.get() > 0 mustBe true
      counter429.get() > 0 mustBe true
    }

    "prevent too many calls per day" in {
      def call(auth: String) = {
        ws.url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host"                   -> serviceHost,
            "Otoroshi-Authorization" -> s"Basic $auth"
          )
          .get()
          .futureValue
      }

      val resp1 = call(basicAuthDaily)
      val resp2 = call(basicAuthDaily)
      val resp3 = call(basicAuthDaily)
      // val resp4 = call(basicAuthDaily)
      val resp5 = call(basicAuthDaily)

      resp1.status mustBe 200
      resp2.status mustBe 200
      resp3.status mustBe 200
      // resp4.status mustBe 200
      resp5.status mustBe 429
      resp5.body.contains("") mustBe true
    }

    "prevent too many calls per month" in {
      def call(auth: String) = {
        ws.url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host"                   -> serviceHost,
            "Otoroshi-Authorization" -> s"Basic $auth"
          )
          .get()
          .futureValue
      }

      val resp1 = call(basicAuthMonthly)
      val resp2 = call(basicAuthMonthly)
      val resp3 = call(basicAuthMonthly)
      // val resp4 = call(basicAuthMonthly)
      val resp5 = call(basicAuthMonthly)

      resp1.status mustBe 200
      resp2.status mustBe 200
      resp3.status mustBe 200
      // resp4.status mustBe 200
      resp5.status mustBe 429
      resp5.body.contains("") mustBe true
    }

    "stop servers" in {
      server.stop()
      system.terminate()
    }
  }
}
