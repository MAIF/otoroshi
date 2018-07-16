package functional

import java.security.KeyFactory
import java.security.interfaces.{ECPrivateKey, ECPublicKey}
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.util.concurrent.atomic.AtomicInteger

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.typesafe.config.ConfigFactory
import models._
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import play.api.Configuration

import org.apache.commons.codec.binary.{Base64 => ApacheBase64}

class JWTVerification2Spec(name: String, configurationSpec: => Configuration) extends PlaySpec {
  "blah" should {
    "very blah" in {
      def getPublicKey(value: String): ECPublicKey = {
        val publicBytes = ApacheBase64.decodeBase64(
          value.replace("-----BEGIN PUBLIC KEY-----\n", "").replace("\n-----END PUBLIC KEY-----", "").trim()
        )
        val keySpec = new X509EncodedKeySpec(publicBytes)
        val keyFactory = KeyFactory.getInstance("EC")
        keyFactory.generatePublic(keySpec).asInstanceOf[ECPublicKey]
      }

      def getPrivateKey(value: String): ECPrivateKey = {
        val publicBytes = ApacheBase64.decodeBase64(
          value.replace("-----BEGIN PRIVATE KEY-----\n", "").replace("\n-----END PRIVATE KEY-----", "").trim()
        )
        val keySpec = new PKCS8EncodedKeySpec(publicBytes)
        val keyFactory = KeyFactory.getInstance("EC")
        keyFactory.generatePrivate(keySpec).asInstanceOf[ECPrivateKey]
      }

      val algo1 = Algorithm.ECDSA512(getPublicKey(
        """MIGbMBAGByqGSM49AgEGBSuBBAAjA4GGAAQAmG8JrpLz14+qUs7oxFX0pCoe90Ah
          |MMB/9ZENy8KZ+us26i/6PiBBc7XaiEi6Q8Icz2tiazwSpyLPeBrFVPFkPgIADyLa
          |T0fp7D2JKHWpdrWQvGLLMwGqYCaaDi79KugPo6V4bnpLBlVtbH4ogg0Hqv89BVyI
          |ZfwWPCBH+Zssei1VlgM=""".stripMargin), getPrivateKey(
        """MIHtAgEAMBAGByqGSM49AgEGBSuBBAAjBIHVMIHSAgEBBEHzl1DpZSQJ8YhCbN/u
          |vo5SOu0BjDDX9Gub6zsBW6B2TxRzb5sBeQaWVscDUZha4Xr1HEWpVtua9+nEQU/9
          |Aq9Pl6GBiQOBhgAEAJhvCa6S89ePqlLO6MRV9KQqHvdAITDAf/WRDcvCmfrrNuov
          |+j4gQXO12ohIukPCHM9rYms8Eqciz3gaxVTxZD4CAA8i2k9H6ew9iSh1qXa1kLxi
          |yzMBqmAmmg4u/SroD6OleG56SwZVbWx+KIINB6r/PQVciGX8FjwgR/mbLHotVZYD""".stripMargin))
      val algo2 = Algorithm.ECDSA512(getPublicKey(
        """MIGbMBAGByqGSM49AgEGBSuBBAAjA4GGAAQAmG8JrpLz14+qUs7oxFX0pCoe90Ah
          |MMB/9ZENy8KZ+us26i/6PiBBc7XaiEi6Q8Icz2tiazwSpyLPeBrFVPFkPgIADyLa
          |T0fp7D2JKHWpdrWQvGLLMwGqYCaaDi79KugPo6V4bnpLBlVtbH4ogg0Hqv89BVyI
          |ZfwWPCBH+Zssei1VlgM=""".stripMargin), null)


      import com.auth0.jwt.JWT

      val token1 = JWT.create.withIssuer("auth0").sign(algo1)

      val verifier1 = JWT.require(algo1)
        .withIssuer("auth0")
        .build()

      val verifier2 = JWT.require(algo2)
        .withIssuer("auth0")
        .build()

      println(verifier1.verify(token1))
      println(verifier2.verify(token1))
    }
  }
}

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
