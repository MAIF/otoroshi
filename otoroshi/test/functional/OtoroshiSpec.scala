package functional

import com.typesafe.config.ConfigFactory
import models.Target
import org.scalatest.Suites
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.libs.json.{JsArray, Json}
import play.api.libs.ws.WSAuthScheme

object OtoroshiSpec {
  val configuration = Configuration(
    ConfigFactory
      .parseString("""
      |{
      |
      |}
    """.stripMargin).resolve()
  )
}

class OtoroshiSpec(name: String, configurationSpec: Configuration)
  extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with IntegrationPatience {

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

  s"Otoroshi admin API" should {

    "return only one service descriptor (for admin API)" in {

      val response = ws.url(s"http://localhost:$port/api/services").withHttpHeaders(
        "Host" -> "otoroshi-api.foo.bar",
        "Accept" -> "application/json"
      ).withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC).get().futureValue

      response.status mustBe 200
      response.json.as[JsArray].value.size mustBe 1
    }

    "route a basic http call" in {

      val basicTestExpectedBody = """{"message":"hello world"}"""
      val basicTestServer = TargetService(None, "/api", "application/json", _ => basicTestExpectedBody).await()
      val basicTestPort = basicTestServer.port

      val creationResponse = ws.url(s"http://localhost:$port/api/services").withHttpHeaders(
        "Host" -> "otoroshi-api.foo.bar",
        "Content-Type" -> "application/json"
      ).withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
        .post(Json.stringify(models.ServiceDescriptor(
          id = "basic-test",
          name = "basic-test",
          env = "prod",
          subdomain = "basictest",
          domain = "foo.bar",
          targets = Seq(
            Target(
              host = s"127.0.0.1:$basicTestPort",
              scheme = "http"
            )
          ),
          localHost = s"127.0.0.1:$basicTestPort",
          forceHttps = false,
          sendOtoroshiHeadersBack = true,
          enforceSecureCommunication = false,
          publicPatterns = Seq("/.*")
        ).toJson)).futureValue

      creationResponse.status mustBe 200

      val basicTestResponse1 = ws.url(s"http://127.0.0.1:$port/api").withHttpHeaders(
        "Host" -> "basictest.foo.bar"
      ).get().futureValue

      basicTestResponse1.status mustBe 200
      basicTestResponse1.body mustBe basicTestExpectedBody

      basicTestServer.stop()

    }
  }
}

class OtoroshiTests
  extends Suites(
    new OtoroshiSpec("InMemory", OtoroshiSpec.configuration)
  )