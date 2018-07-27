package functional

import java.util.concurrent.atomic.AtomicInteger

import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import models._
import org.joda.time.LocalTime
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.libs.json.Json

import scala.concurrent.duration._

class SnowMonkeySpec(name: String, configurationSpec: => Configuration)
    extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with OtoroshiSpecHelper
    with IntegrationPatience {

  lazy val serviceHost = "monkey.foo.bar"
  lazy val ws          = otoroshiComponents.wsClient
  implicit val mat     = otoroshiComponents.materializer

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

  s"[$name] Otoroshi Snow Monkey" should {

    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }

    // val ref                   = new AtomicInteger(0)
    // val basicTestExpectedBody = """{"message":"hello world"}"""
    // val basicTestServer = TargetService(Some(serviceHost), "/api", "application/json", { r =>
    //   r.entity.dataBytes
    //     .runFold(ByteString.empty)(_ ++ _)
    //     .map(b => {
    //       ref.set(b.size)
    //     })
    //   basicTestExpectedBody
    // }).await()
    val basicTestServer = new BodySizeService()
    val initialDescriptor = ServiceDescriptor(
      id = "basic-sm-test",
      name = "basic-sm-test",
      env = "prod",
      subdomain = "monkey",
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

    "Setup the monkey" in {
      (for {
        _ <- createOtoroshiService(initialDescriptor)
        _ <- updateSnowMonkey(
              c =>
                SnowMonkeyConfig(
                  enabled = true,
                  dryRun = false,
                  timesPerDay = 1000,
                  includeUserFacingDescriptors = true,
                  outageDurationFrom = 3600000.millis,
                  outageDurationTo = 3600000.millis,
                  startTime = LocalTime.now(), // parse("00:00:00.000"),
                  stopTime = LocalTime.now().plusMillis(10000), //.parse("23:59:59.999"),
                  targetGroups = Seq("default"),
                  chaosConfig = ChaosConfig(
                    enabled = true,
                    badResponsesFaultConfig = None,
                    largeRequestFaultConfig = None,
                    largeResponseFaultConfig = None,
                    latencyInjectionFaultConfig = None
                  )
              )
            )
        _ <- startSnowMonkey()
      } yield ()).futureValue
      ws.url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue
      val outages = getSnowMonkeyOutages().futureValue
      outages.size mustBe 1
    }

    "Inject latency" in {
      updateSnowMonkey(
        c =>
          c.copy(
            chaosConfig = c.chaosConfig.copy(
              latencyInjectionFaultConfig = Some(
                LatencyInjectionFaultConfig(
                  ratio = 1.0,
                  from = 500.millis,
                  to = 500.millis
                )
              )
            )
        )
      ).futureValue
      val start = System.currentTimeMillis()
      ws.url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue
      val stop = System.currentTimeMillis()
      (stop - start) >= 500 mustBe true
      updateSnowMonkey(
        c =>
          c.copy(
            chaosConfig = c.chaosConfig.copy(
              latencyInjectionFaultConfig = None
            )
        )
      ).futureValue
    }

    "Inject bad responses" in {
      updateSnowMonkey(
        c =>
          c.copy(
            chaosConfig = c.chaosConfig.copy(
              badResponsesFaultConfig = Some(
                BadResponsesFaultConfig(
                  ratio = 1.0,
                  responses = Seq(
                    BadResponse(
                      status = 502,
                      body = """{"error":"yes"}""",
                      headers = Map("Content-Type" -> "application/json")
                    )
                  )
                )
              )
            )
        )
      ).futureValue
      val res = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue
      res.status mustBe 502
      res.json mustBe Json.parse("""{"error":"yes"}""")
      updateSnowMonkey(
        c =>
          c.copy(
            chaosConfig = c.chaosConfig.copy(
              badResponsesFaultConfig = None
            )
        )
      ).futureValue
    }

    "Inject big response body" in {
      updateSnowMonkey(
        c =>
          c.copy(
            chaosConfig = c.chaosConfig.copy(
              largeResponseFaultConfig = Some(
                LargeResponseFaultConfig(
                  ratio = 1.0,
                  additionalResponseSize = 1024
                )
              )
            )
        )
      ).futureValue
      val res = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue
      res.bodyAsBytes.size > 1024 mustBe true
      updateSnowMonkey(
        c =>
          c.copy(
            chaosConfig = c.chaosConfig.copy(
              largeResponseFaultConfig = None
            )
        )
      ).futureValue
    }

    "Inject big request body" in {
      updateSnowMonkey(
        c =>
          c.copy(
            chaosConfig = c.chaosConfig.copy(
              largeRequestFaultConfig = Some(
                LargeRequestFaultConfig(
                  ratio = 1.0,
                  additionalRequestSize = 1024
                )
              )
            )
        )
      ).futureValue
      //ref.get() mustBe 0
      val res = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"           -> serviceHost,
          "Content-Type"   -> "text/plain",
          "Content-Length" -> "25"
        )
        .post(Json.stringify(Json.obj("error" -> "one big error")))
        .futureValue
      await(10.millis)
      (res.json \ "bodySize").as[Int] > 1024 mustBe true
      updateSnowMonkey(
        c =>
          c.copy(
            enabled = false,
            chaosConfig = c.chaosConfig.copy(
              largeRequestFaultConfig = None
            )
        )
      ).futureValue
      stopSnowMonkey().futureValue
      deleteOtoroshiService(initialDescriptor).futureValue
    }
  }
}
