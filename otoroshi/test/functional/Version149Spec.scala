package functional

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.RawHeader
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.typesafe.config.ConfigFactory
import models._
import org.joda.time.DateTime
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.libs.json.Json
import security.IdGenerator

import scala.util.Try

class Version149Spec(name: String, configurationSpec: => Configuration)
  extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with OtoroshiSpecHelper
    with IntegrationPatience {

  lazy val serviceHost = "quotas.foo.bar"
  lazy val ws          = otoroshiComponents.wsClient
  implicit val system  = ActorSystem("otoroshi-test")

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

    val counter1 = new AtomicInteger(0)
    val counter2 = new AtomicInteger(0)
    val counter3 = new AtomicInteger(0)
    val body    = """{"message":"hello world"}"""
    val server1 = TargetService(None, "/api", "application/json", { r =>
      counter1.incrementAndGet()
      body
    }).await()
    val server2 = TargetService(None, "/api", "application/json", { r =>
      counter2.incrementAndGet()
      body
    }).await()
    val server3 = TargetService(None, "/api", "application/json", { r =>
      if (r.getHeader("X-Api-Key-Name").get().value().startsWith("apikey-service3")) {
        counter3.incrementAndGet()
      }
      body
    }).await()

    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }

    "provide routing based on apikey tags and metadata (#307)" in {
      val service1 = ServiceDescriptor(
        id = "service-apk-routing-1",
        name = "service1",
        env = "prod",
        subdomain = "service",
        domain = "oto.tools",
        useAkkaHttpClient = true,
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server1.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        apiKeyConstraints = ApiKeyConstraints.apply(
          routing = ApiKeyRouteMatcher(
            oneTagIn = Seq("user")
          )
        )
      )
      val service2 = ServiceDescriptor(
        id = "service-apk-routing-2",
        name = "service2",
        env = "prod",
        subdomain = "service",
        domain = "oto.tools",
        useAkkaHttpClient = true,
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server2.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        apiKeyConstraints = ApiKeyConstraints.apply(
          routing = ApiKeyRouteMatcher(
            oneTagIn = Seq("admin")
          )
        )
      )
      val apikey1 = ApiKey(
        clientName = "apikey1",
        authorizedGroup = "default",
        tags = Seq("user", "foo")
      )
      val apikey2 = ApiKey(
        clientName = "apikey2",
        authorizedGroup = "default",
        tags = Seq("admin", "bar", "foo")
      )
      createOtoroshiService(service1).futureValue
      createOtoroshiService(service2).futureValue
      createOtoroshiApiKey(apikey1).futureValue
      createOtoroshiApiKey(apikey2).futureValue

      val resp1 = ws.url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "service.oto.tools",
          "Otoroshi-Client-Id" -> apikey1.clientId,
          "Otoroshi-Client-Secret" -> apikey1.clientSecret
        )
        .get()
        .futureValue

      resp1.status mustBe 200
      counter1.get() mustBe 1
      counter2.get() mustBe 0

      val resp2 = ws.url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "service.oto.tools",
          "Otoroshi-Client-Id" -> apikey2.clientId,
          "Otoroshi-Client-Secret" -> apikey2.clientSecret
        )
        .get()
        .futureValue

      resp2.status mustBe 200
      counter1.get() mustBe 1
      counter2.get() mustBe 1

      deleteOtoroshiService(service1).futureValue
      deleteOtoroshiService(service2).futureValue
      deleteOtoroshiApiKey(apikey1).futureValue
      deleteOtoroshiApiKey(apikey2).futureValue
    }

    "support el in headers manipulation (#308)" in {
      val service = ServiceDescriptor(
        id = "service-el",
        name = "service-el",
        env = "prod",
        subdomain = "service-el",
        domain = "oto.tools",
        useAkkaHttpClient = true,
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server3.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        additionalHeaders = Map(
          "X-Api-Key-Name" -> "${apikey.name}"
        )
      )
      val apikey = ApiKey(
        clientName = "apikey-service3",
        authorizedGroup = "default"
      )
      createOtoroshiService(service).futureValue
      createOtoroshiApiKey(apikey).futureValue

      val resp = ws.url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "service-el.oto.tools",
          "Otoroshi-Client-Id" -> apikey.clientId,
          "Otoroshi-Client-Secret" -> apikey.clientSecret
        )
        .get()
        .futureValue

      resp.status mustBe 200
      counter3.get() mustBe 1

      deleteOtoroshiService(service).futureValue
      deleteOtoroshiApiKey(apikey).futureValue
    }

    "stop servers" in {
      server1.stop()
      server2.stop()
      server3.stop()
    }
  }

  s"[$name] Otoroshi global config" should {

    val counter1 = new AtomicInteger(0)
    val body    = """{"message":"hello world"}"""
    val server1 = TargetService(None, "/api", "application/json", { r =>
      // println(r.getHeaders())
      counter1.incrementAndGet()
      body
    }).await()

    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }

    "allow switching http client live (#300)" in {
      val service = ServiceDescriptor(
        id = "service-switch",
        name = "service-switch",
        env = "prod",
        subdomain = "service-switch",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server1.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*")
      )
      createOtoroshiService(service).futureValue
      val config = getOtoroshiConfig().futureValue

      val resp1 = ws.url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "service-switch.oto.tools"
        )
        .get()
        .futureValue

      resp1.status mustBe 200
      counter1.get() mustBe 1

      updateOtoroshiConfig(config.copy(useAkkaHttpClient = true)).futureValue

      await(10.seconds)

      val resp2 = ws.url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "service-switch.oto.tools"
        )
        .get()
        .futureValue

      resp2.status mustBe 200
      counter1.get() mustBe 2

      updateOtoroshiConfig(config.copy(useAkkaHttpClient = false)).futureValue
      deleteOtoroshiService(service).futureValue
    }

    "stop servers" in {
      server1.stop()
    }
  }

  s"[$name] Otoroshi jwt verifier" should {
    "verify items in arrays (#316)" in {

      val counter           = new AtomicInteger(0)
      val body = """{"message":"hello world 1"}"""
      val server = TargetService(None, "/api", "application/json", { r =>
        // println(r.getHeaders())
        counter.incrementAndGet()
        body
      }).await()

      val service = ServiceDescriptor(
        id = "array-jwt-test",
        name = "array-jwt-test",
        env = "prod",
        subdomain = "array-jwt",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server.port}",
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
          strategy = PassThrough(verificationSettings = VerificationSettings(
            arrayFields = Map("roles" -> "user")
          ))
        )
      )

      createOtoroshiService(service).futureValue

      import com.auth0.jwt.algorithms.Algorithm
      val algorithm = Algorithm.HMAC512("secret")

      val tok = JWT
        .create()
        .withIssuer("foo")
        .withArrayClaim("roles", Array("yo", "foo", "user"))

      val signedTok = tok.sign(algorithm)

      def callServerWithJWT() = {
        val r = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> "array-jwt.oto.tools",
            "X-JWT-Token" -> signedTok
          )
          .get()
          .futureValue
        (r.status, r.body)
      }
      def callServerWithBadJWT1() = {
        val r = ws
          .url(s"http://127.0.0.1:$port/api")
          .withHttpHeaders(
            "Host" -> "array-jwt.oto.tools",
            "X-JWT-Token" -> JWT
              .create()
              .withIssuer("mathieu")
              .withArrayClaim("roles", Array("yo", "foo", "admin"))
              .sign(algorithm)
          )
          .get()
          .futureValue
        (r.status, r.body)
      }

      val (status1, body1) = callServerWithJWT()
      val (status2, body2) = callServerWithBadJWT1()

      status1 mustBe 200
      body1.contains("hello world 1") mustBe true
      status2 mustBe 400
      body2.contains("error.bad.token") mustBe true

      deleteOtoroshiService(service).futureValue

      server.stop()
    }
  }

  s"[$name] Otoroshi exchange protocol V2" should {
    "enforce token TTL (#290)" in {
      import org.apache.commons.codec.binary.{Base64 => ApacheBase64}
      val counter = new AtomicInteger(0)
      val body    = """{"message":"hello world"}"""
      val server = TargetService
        .full(
          None,
          "/api",
          "application/json", { r =>
            val state = r.getHeader("Otoroshi-State").get()
            val tokenBody =
              Try(Json.parse(ApacheBase64.decodeBase64(state.value().split("\\.")(1)))).getOrElse(Json.obj())
            val stateValue = (tokenBody \ "state").as[String]
            val respToken: String = JWT
              .create()
              .withJWTId(IdGenerator.uuid)
              .withAudience("Otoroshi")
              .withClaim("state-resp", stateValue)
              .withIssuedAt(DateTime.now().toDate)
              .withExpiresAt(DateTime.now().plusSeconds(30).toDate)
              .sign(Algorithm.HMAC512("secret"))
            counter.incrementAndGet()
            (200, body, List(RawHeader("Otoroshi-State-Resp", respToken)))
          }
        )
        .await()
      val service = ServiceDescriptor(
        id = "seccom-v1-test",
        name = "seccom-v1-test",
        env = "prod",
        subdomain = "seccom",
        domain = "foo.bar",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = true,
        secComVersion = SecComVersion.V2,
        publicPatterns = Seq("/.*")
      )
      createOtoroshiService(service).futureValue

      val resp1 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "seccom.foo.bar"
        )
        .get()
        .futureValue

      resp1.status mustBe 200
      resp1.body mustBe body

      deleteOtoroshiService(service).futureValue
      server.stop()
    }
  }

  s"[$name] Otoroshi new targets" should {

    val counter = new AtomicInteger(0)
    val body    = """{"message":"hello world"}"""
    val server = TargetService(None, "/api", "application/json", { r =>
      if (r.getHeader("Host").get().value().startsWith("www.google.fr:")) {
        counter.incrementAndGet()
      }
      counter.incrementAndGet()
      body
    }).await()

    val service = ServiceDescriptor(
      id = "target-test",
      name = "target-test",
      env = "prod",
      subdomain = "target-test",
      domain = "oto.tools",
      useAkkaHttpClient = true,
      targets = Seq(
        Target(
          host = s"www.google.fr:${server.port}",
          scheme = "http",
          ipAddress = Some("127.0.0.1")
        )
      ),
      publicPatterns = Seq("/.*"),
      forceHttps = false,
      enforceSecureCommunication = false
    )

    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }

    "allow weighting (#309, #77)" in {
      // TODO
    }

    "allow better timeout management (#301)" in {
      // TODO
    }

    "support random load balancing (#79)" in {
      // TODO
    }

    "support sticky session load balancing (#79)" in {
      // TODO
    }

    "support ip address hash load balancing (#309)" in {
      // TODO
    }

    "support best response time load balancing (#309)" in {
      // TODO
    }

    "support target predicates based on zones (#309)" in {
      // TODO
    }

    "support target predicates based on regions (#309)" in {
      // TODO
    }

    "support target predicates based on regions and zones (#309)" in {
      // TODO
    }

    "allow manual DNS resolution (#309, #310)" in {
      createOtoroshiService(service).futureValue
      val resp = ws.url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "target-test.oto.tools"
        )
        .get()
        .futureValue

      resp.status mustBe 200
      counter.get() mustBe 2
      deleteOtoroshiService(service).futureValue
    }

    "stop servers" in {
      server.stop()
      system.terminate()
    }
  }
}

