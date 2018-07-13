package functional

import java.util.concurrent.atomic.AtomicInteger

import com.auth0.jwt.JWT
import com.typesafe.config.ConfigFactory
import models._
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import play.api.Configuration

class JWTVerificationSpec(name: String, configurationSpec: => Configuration)
  extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with OtoroshiSpecHelper
    with IntegrationPatience {

  lazy val serviceHost = "jwt.foo.bar"
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

  s"[$name] Otoroshi JWT Verifier" should {

    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }

    "Verify JWT token" in {

      val callCounter1           = new AtomicInteger(0)
      val basicTestExpectedBody1 = """{"message":"hello world 1"}"""
      val basicTestServer1 = TargetService(Some(serviceHost), "/api", "application/json", { _ =>
        callCounter1.incrementAndGet()
        basicTestExpectedBody1
      }).await()

      val service = ServiceDescriptor(
        id = "jwt-test",
        name = "jwt-test",
        env = "prod",
        subdomain = "jwt",
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
        jwtVerifier = JwtVerifier(
          enabled = true,
          id = "test",
          name = "test",
          strict = true,
          source = InHeader(name = "X-JWT-Token"),
          algoSettings = HSAlgoSettings(512, "secret"),
          strategy = PassThrough(verificationSettings = VerificationSettings(Map("iss" -> "foo", "bar" -> "yo")))
        )
      )

      createOtoroshiService(service).futureValue

      import com.auth0.jwt.algorithms.Algorithm
      val algorithm = Algorithm.HMAC512("secret")

      def callServerWithoutJWT() = {
        val r = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> serviceHost
          )
          .get()
          .futureValue
        (r.status, r.body)
      }
      def callServerWithJWT() = {
        val r = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> serviceHost,
            "X-JWT-Token" -> JWT.create()
              .withIssuer("foo")
              .withClaim("bar", "yo")
              .sign(algorithm)
          )
          .get()
          .futureValue
        (r.status, r.body)
      }
      def callServerWithBadJWT1() = {
        val r = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> serviceHost,
            "X-JWT-Token" -> JWT.create()
              .withIssuer("mathieu")
              .withClaim("bar", "yo")
              .sign(algorithm)
          )
          .get()
          .futureValue
        (r.status, r.body)
      }
      def callServerWithBadJWT2() = {
        val r = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> serviceHost,
            "X-JWT-Token" -> JWT.create()
              .withIssuer("foo")
              .withClaim("bar", "foo")
              .sign(algorithm)
          )
          .get()
          .futureValue
        (r.status, r.body)
      }

      val (status0, body0) = callServerWithoutJWT()
      val (status1, body1) = callServerWithJWT()
      val (status2, body2) = callServerWithBadJWT1()
      val (status3, body3) = callServerWithBadJWT2()
      status0 mustBe 400
      body0.contains("error.expected.token.not.found") mustBe true
      status1 mustBe 200
      body1.contains("hello world 1") mustBe true
      status2 mustBe 400
      body2.contains("error.bad.token") mustBe true
      status3 mustBe 400
      body3.contains("error.bad.token") mustBe true

      deleteOtoroshiService(service).futureValue

      basicTestServer1.stop()
    }
  }
}
