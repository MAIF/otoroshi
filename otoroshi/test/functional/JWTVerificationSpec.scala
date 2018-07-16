package functional

import java.security.KeyFactory
import java.security.interfaces.{ECPrivateKey, ECPublicKey}
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.typesafe.config.ConfigFactory
import models._
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import org.apache.commons.codec.binary.{Base64 => ApacheBase64}
import play.api.libs.json.Json

import scala.util.Try

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

object Implicit {
  implicit class BetterOptional[A](val opt: Optional[A]) extends AnyVal {
    def asOption: Option[A] = {
      if (opt.isPresent) {
        Option(opt.get())
      } else {
        None
      }
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
        jwtVerifier = LocalJwtVerifier(
          enabled = true,
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

    "Re-sign JWT token" in {

      import Implicit._

      import com.auth0.jwt.algorithms.Algorithm
      val key = "very secret"
      val algorithm = Algorithm.HMAC512("secret")
      val algorithm2 = Algorithm.HMAC512(key)

      val goodJwt = JWT.create()
        .withIssuer("foo")
        .withClaim("bar", "yo")
        .sign(algorithm)

      val goodJwtResigned = JWT.create()
        .withIssuer("foo")
        .withClaim("bar", "yo")
        .sign(algorithm2)

      val callCounter1           = new AtomicInteger(0)
      val basicTestExpectedBody1 = """{"message":"hello world 1"}"""
      val basicTestServer1 = TargetService(Some(serviceHost), "/api", "application/json", { r =>
        r.getHeader("X-JWT-Token").asOption.map(a => a.value()).foreach { a =>
          val v = JWT
            .require(algorithm2)
            .withIssuer("foo")
            .build()
          val verified = Try { v.verify(a) }.map(_ => true).getOrElse(false)
          verified mustEqual true
        }
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
        jwtVerifier = LocalJwtVerifier(
          enabled = true,
          strict = true,
          source = InHeader(name = "X-JWT-Token"),
          algoSettings = HSAlgoSettings(512, "secret"),
          strategy = Sign(
            verificationSettings = VerificationSettings(Map("iss" -> "foo", "bar" -> "yo")),
            algoSettings = HSAlgoSettings(512, key)
          )
        )
      )

      createOtoroshiService(service).futureValue

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
            "X-JWT-Token" -> goodJwt
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

    "transform JWT token" in {

      import Implicit._

      import com.auth0.jwt.algorithms.Algorithm
      val key = "very secret"
      val algorithm = Algorithm.HMAC512("secret")
      val algorithm2 = Algorithm.HMAC512(key)

      val goodJwt = JWT.create()
        .withIssuer("foo")
        .withClaim("bar", "yo")
        .withClaim("foo", "bar")
        .sign(algorithm)

      val callCounter1           = new AtomicInteger(0)
      val basicTestExpectedBody1 = """{"message":"hello world 1"}"""
      val basicTestServer1 = TargetService(Some(serviceHost), "/api", "application/json", { r =>
        r.getHeader("X-Barrr").asOption.map(a => a.value()).foreach { a =>
          import collection.JavaConverters._
          val v = JWT
            .require(algorithm2)
            .withIssuer("foo")
            .withClaim("x-bar", "yo")
            .withClaim("x-yo", "foo")
            .build()
          val verified = Try {
            val dec = v.verify(a)
            println(dec.getClaims.asScala.mapValues(v => v.asString()))
            dec
          }.map(_ => true).getOrElse(false)
          verified mustEqual true
        }
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
        jwtVerifier = LocalJwtVerifier(
          enabled = true,
          strict = true,
          source = InHeader(name = "X-JWT-Token"),
          algoSettings = HSAlgoSettings(512, "secret"),
          strategy = Transform(
            verificationSettings = VerificationSettings(Map("iss" -> "foo", "bar" -> "yo")),
            algoSettings = HSAlgoSettings(512, key),
            transformSettings = TransformSettings(
              location = InHeader("X-Barrr"),
              mappingSettings = MappingSettings(
                map = Map("bar" -> "x-bar"),
                values = Json.obj("x-yo" -> "foo"),
                remove = Seq("foo")
              )
            )
          )
        )
      )

      createOtoroshiService(service).futureValue

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
            "X-JWT-Token" -> goodJwt
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
