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
import otoroshi.security.IdGenerator

import scala.math.BigDecimal.RoundingMode
import scala.util.Try

class Version149Spec(name: String, configurationSpec: => Configuration)
    extends OtoroshiSpec {

  implicit val system  = ActorSystem("otoroshi-test")
  implicit val env     = otoroshiComponents.env

  import scala.concurrent.duration._

  override def getTestConfiguration(configuration: Configuration) = Configuration(
    ConfigFactory
      .parseString(s"""
           |{
           |  app.instance.region=eu-west-1
           |  app.instance.zone=dc1
           |}
       """.stripMargin)
      .resolve()
  ).withFallback(configurationSpec).withFallback(configuration)

  startOtoroshi()

  s"[$name] Otoroshi service descriptors" should {

    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }

    "provide routing based on apikey tags and metadata (#307)" in {
      val (_, port1, counter1, call) = testServer("service.oto.tools", port)
      val (_, port2, counter2, _)    = testServer("service.oto.tools", port)
      val (_, port3, counter3, _)    = testServer("service.oto.tools", port)
      val (_, port4, counter4, _)    = testServer("service.oto.tools", port)
      val (_, port5, counter5, _)    = testServer("service.oto.tools", port)
      val service1 = ServiceDescriptor(
        id = "service-apk-routing-1",
        name = "service1",
        env = "prod",
        subdomain = "service",
        domain = "oto.tools",
        useAkkaHttpClient = true,
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
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
            host = s"127.0.0.1:${port2}",
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
      val service3 = ServiceDescriptor(
        id = "service-apk-routing-3",
        name = "service3",
        env = "prod",
        subdomain = "service",
        domain = "oto.tools",
        useAkkaHttpClient = true,
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port3}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        apiKeyConstraints = ApiKeyConstraints.apply(
          routing = ApiKeyRouteMatcher(
            oneMetaIn = Map("level" -> "1") // apikey1
          )
        )
      )
      val service4 = ServiceDescriptor(
        id = "service-apk-routing-4",
        name = "service4",
        env = "prod",
        subdomain = "service",
        domain = "oto.tools",
        useAkkaHttpClient = true,
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port4}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        apiKeyConstraints = ApiKeyConstraints.apply(
          routing = ApiKeyRouteMatcher(
            allMetaIn = Map("level" -> "2", "root" -> "true") // apikey1
          )
        )
      )
      val service5 = ServiceDescriptor(
        id = "service-apk-routing-5",
        name = "service5",
        env = "prod",
        subdomain = "service",
        domain = "oto.tools",
        useAkkaHttpClient = true,
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port5}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        apiKeyConstraints = ApiKeyConstraints.apply(
          routing = ApiKeyRouteMatcher(
            allTagsIn = Seq("leveled", "root")
          )
        )
      )
      val apikey1 = ApiKey(
        clientName = "apikey1",
        authorizedEntities = Seq(ServiceGroupIdentifier("default")),
        tags = Seq("user", "foo")
      )
      val apikey2 = ApiKey(
        clientName = "apikey2",
        authorizedEntities = Seq(ServiceGroupIdentifier("default")),
        tags = Seq("admin", "bar", "foo")
      )
      val apikey3 = ApiKey(
        clientName = "apikey3",
        authorizedEntities = Seq(ServiceGroupIdentifier("default")),
        metadata = Map("level" -> "1")
      )
      val apikey4 = ApiKey(
        clientName = "apikey4",
        authorizedEntities = Seq(ServiceGroupIdentifier("default")),
        metadata = Map("level" -> "2", "root" -> "true")
      )
      val apikey5 = ApiKey(
        clientName = "apikey5",
        authorizedEntities = Seq(ServiceGroupIdentifier("default")),
        tags = Seq("lkj", "leveled", "root")
      )
      createOtoroshiService(service1).futureValue
      createOtoroshiService(service2).futureValue
      createOtoroshiService(service3).futureValue
      createOtoroshiService(service4).futureValue
      createOtoroshiService(service5).futureValue
      createOtoroshiApiKey(apikey1).futureValue
      createOtoroshiApiKey(apikey2).futureValue
      createOtoroshiApiKey(apikey3).futureValue
      createOtoroshiApiKey(apikey4).futureValue
      createOtoroshiApiKey(apikey5).futureValue

      val resp1 = call(
        Map(
          "Otoroshi-Client-Id"     -> apikey1.clientId,
          "Otoroshi-Client-Secret" -> apikey1.clientSecret
        )
      )

      resp1.status mustBe 200
      counter1.get() mustBe 1
      counter2.get() mustBe 0
      counter3.get() mustBe 0
      counter4.get() mustBe 0
      counter5.get() mustBe 0

      val resp2 = call(
        Map(
          "Otoroshi-Client-Id"     -> apikey2.clientId,
          "Otoroshi-Client-Secret" -> apikey2.clientSecret
        )
      )

      resp2.status mustBe 200
      counter1.get() mustBe 1
      counter2.get() mustBe 1
      counter3.get() mustBe 0
      counter4.get() mustBe 0
      counter5.get() mustBe 0

      val resp3 = call(
        Map(
          "Otoroshi-Client-Id"     -> apikey3.clientId,
          "Otoroshi-Client-Secret" -> apikey3.clientSecret
        )
      )

      resp3.status mustBe 200
      counter1.get() mustBe 1
      counter2.get() mustBe 1
      counter3.get() mustBe 1
      counter4.get() mustBe 0
      counter5.get() mustBe 0

      val resp5 = call(
        Map(
          "Otoroshi-Client-Id"     -> apikey5.clientId,
          "Otoroshi-Client-Secret" -> apikey5.clientSecret
        )
      )

      resp5.status mustBe 200
      counter1.get() mustBe 1
      counter2.get() mustBe 1
      counter3.get() mustBe 1
      counter4.get() mustBe 0
      counter5.get() mustBe 1

      val resp4 = call(
        Map(
          "Otoroshi-Client-Id"     -> apikey4.clientId,
          "Otoroshi-Client-Secret" -> apikey4.clientSecret
        )
      )

      resp4.status mustBe 200
      counter1.get() mustBe 1
      counter2.get() mustBe 1
      counter3.get() mustBe 1
      counter4.get() mustBe 1
      counter5.get() mustBe 1

      deleteOtoroshiService(service1).futureValue
      deleteOtoroshiService(service2).futureValue
      deleteOtoroshiService(service3).futureValue
      deleteOtoroshiService(service4).futureValue
      deleteOtoroshiService(service5).futureValue
      deleteOtoroshiApiKey(apikey1).futureValue
      deleteOtoroshiApiKey(apikey2).futureValue
      deleteOtoroshiApiKey(apikey3).futureValue
      deleteOtoroshiApiKey(apikey4).futureValue
      deleteOtoroshiApiKey(apikey5).futureValue

      stopServers()
    }

    "support el in headers manipulation (#308)" in {
      val (_, port1, counter1, call) = testServer("service-el.oto.tools", port, validate = r => {
        r.getHeader("X-Api-Key-Name").get().value().startsWith("apikey-service3")
      })
      val service = ServiceDescriptor(
        id = "service-el",
        name = "service-el",
        env = "prod",
        subdomain = "service-el",
        domain = "oto.tools",
        useAkkaHttpClient = true,
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
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
        authorizedEntities = Seq(ServiceGroupIdentifier("default"))
      )
      createOtoroshiService(service).futureValue
      createOtoroshiApiKey(apikey).futureValue

      val resp = call(
        Map(
          "Otoroshi-Client-Id"     -> apikey.clientId,
          "Otoroshi-Client-Secret" -> apikey.clientSecret
        )
      )

      resp.status mustBe 200
      counter1.get() mustBe 1

      deleteOtoroshiService(service).futureValue
      deleteOtoroshiApiKey(apikey).futureValue
      stopServers()
    }
  }

  s"[$name] Otoroshi global config" should {

    val counter1 = new AtomicInteger(0)
    val body     = """{"message":"hello world"}"""
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

      val resp1 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "service-switch.oto.tools"
        )
        .get()
        .futureValue

      resp1.status mustBe 200
      counter1.get() mustBe 1

      updateOtoroshiConfig(config.copy(useAkkaHttpClient = true)).futureValue

      await(10.seconds)

      val resp2 = ws
        .url(s"http://127.0.0.1:$port/api")
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

      val counter = new AtomicInteger(0)
      val body    = """{"message":"hello world 1"}"""
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
          strategy = PassThrough(
            verificationSettings = VerificationSettings(
              arrayFields = Map("roles" -> "user")
            )
          )
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
            "Host"        -> "array-jwt.oto.tools",
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
              .withExpiresAt(DateTime.now().plusSeconds(10).toDate)
              .sign(Algorithm.HMAC512("secret"))
            counter.incrementAndGet()
            (200, body, List(RawHeader("Otoroshi-State-Resp", respToken)))
          }
        )
        .await()
      val server2 = TargetService
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
              .withExpiresAt(DateTime.now().plusSeconds(20).toDate)
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
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = true,
        secComTtl = 10.seconds,
        secComVersion = SecComVersion.V2,
        publicPatterns = Seq("/.*")
      )
      val service2 = ServiceDescriptor(
        id = "seccom-v2-test",
        name = "seccom-v2-test",
        env = "prod",
        subdomain = "seccomv2",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server2.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = true,
        secComTtl = 10.seconds,
        secComVersion = SecComVersion.V2,
        publicPatterns = Seq("/.*")
      )
      createOtoroshiService(service).futureValue
      createOtoroshiService(service2).futureValue

      val resp1 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "seccom.oto.tools"
        )
        .get()
        .futureValue

      resp1.status mustBe 200
      resp1.body mustBe body
      counter.get() mustBe 1

      val resp2 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "seccomv2.oto.tools"
        )
        .get()
        .futureValue

      resp2.status mustBe 502
      counter.get() mustBe 2

      deleteOtoroshiService(service).futureValue
      deleteOtoroshiService(service2).futureValue
      server.stop()
      server2.stop()
    }
    "allow disabling token info (#320)" in {
      val (_, port1, counter1, call) = testServer("service-disabled-info.oto.tools", port, validate = r => {
        !r.getHeader("Otoroshi-Claim").isPresent
      })
      val service = ServiceDescriptor(
        id = "service-disabled-info",
        name = "service-disabled-info",
        env = "prod",
        subdomain = "service-disabled-info",
        domain = "oto.tools",
        useAkkaHttpClient = true,
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http"
          )
        ),
        publicPatterns = Seq("/.*"),
        forceHttps = false,
        enforceSecureCommunication = true,
        sendStateChallenge = true,
        sendInfoToken = false
      )
      createOtoroshiService(service).futureValue
      call(Map.empty)
      counter1.get() mustBe 1
      deleteOtoroshiService(service).futureValue
      stopServers()
    }
    "allow latest version of info token (#320)" in {
      import org.apache.commons.codec.binary.{Base64 => ApacheBase64}
      val alg = HSAlgoSettings(
        512,
        "secret"
      )
      val apikey1 = ApiKey(
        clientName = "apikey1",
        authorizedEntities = Seq(ServiceGroupIdentifier("default"))
      )
      val (_, port1, counter1, call) = testServer(
        "service-disabled-info.oto.tools",
        port,
        validate = r => {
          val token = r.getHeader("Otoroshi-Claim").get().value()
          val valid =
            Try(JWT.require(alg.asAlgorithm(OutputMode).get).build().verify(token)).map(_ => true).getOrElse(false)
          val tokenBody = Try(Json.parse(ApacheBase64.decodeBase64(token.split("\\.")(1)))).getOrElse(Json.obj())
          val valid2    = (tokenBody \ "apikey" \ "clientId").as[String] == apikey1.clientId
          println(Json.prettyPrint(tokenBody))
          valid && valid2
        }
      )
      val service = ServiceDescriptor(
        id = "service-disabled-info",
        name = "service-disabled-info",
        env = "prod",
        subdomain = "service-disabled-info",
        domain = "oto.tools",
        useAkkaHttpClient = true,
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = true,
        sendStateChallenge = false,
        sendInfoToken = true,
        secComSettings = alg,
        secComInfoTokenVersion = SecComInfoTokenVersion.Latest
      )

      createOtoroshiService(service).futureValue
      createOtoroshiApiKey(apikey1).futureValue
      call(Map("Otoroshi-Client-Id" -> apikey1.clientId, "Otoroshi-Client-Secret" -> apikey1.clientSecret))
      counter1.get() mustBe 1
      deleteOtoroshiService(service).futureValue
      deleteOtoroshiApiKey(apikey1).futureValue
      stopServers()
    }
    "allow custom header names (#320)" in {
      val (_, port1, counter1, call) = testServer("service-custom-headers.oto.tools", port, validate = r => {
        r.getHeader("claimRequestName").isPresent && r.getHeader("stateRequestName").isPresent
      })
      val service = ServiceDescriptor(
        id = "service-custom-headers",
        name = "service-custom-headers",
        env = "prod",
        subdomain = "service-custom-headers",
        domain = "oto.tools",
        useAkkaHttpClient = true,
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http"
          )
        ),
        publicPatterns = Seq("/.*"),
        forceHttps = false,
        enforceSecureCommunication = true,
        sendStateChallenge = true,
        sendInfoToken = true,
        secComHeaders = SecComHeaders(
          claimRequestName = Some("claimRequestName"),
          stateRequestName = Some("stateRequestName"),
          stateResponseName = Some("stateResponseName")
        )
      )
      createOtoroshiService(service).futureValue
      val r = call(Map.empty)
      counter1.get() mustBe 1
      r.status mustBe 502
      deleteOtoroshiService(service).futureValue
      stopServers()
    }
  }

  s"[$name] Otoroshi new targets" should {

    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }

    "allow weighting (#309, #77)" in {
      val (server1, port1, counter1, call1) = testServer("weighting.oto.tools", port)
      val (server2, port2, counter2, call2) = testServer("weighting.oto.tools", port)
      val (server3, port3, counter3, call3) = testServer("weighting.oto.tools", port)
      val serviceweight = ServiceDescriptor(
        id = "weighting-test",
        name = "weighting-test",
        env = "prod",
        subdomain = "weighting",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http",
            weight = 3
          ),
          Target(
            host = s"127.0.0.1:${port2}",
            scheme = "http",
            weight = 2
          ),
          Target(
            host = s"127.0.0.1:${port3}",
            scheme = "http",
            weight = 1
          )
        ),
        publicPatterns = Seq("/.*"),
        forceHttps = false,
        enforceSecureCommunication = false
      )
      createOtoroshiService(serviceweight).futureValue
      call1(Map.empty)
      call1(Map.empty)
      call1(Map.empty)
      call1(Map.empty)
      call1(Map.empty)
      call1(Map.empty)
      counter1.get() mustBe 3
      counter2.get() mustBe 2
      counter3.get() mustBe 1
      deleteOtoroshiService(serviceweight).futureValue
      stopServers()
    }

    "allow better timeout management : callTimeout (#301)" in {
      val (_, port1, _, call1) = testServer("calltimeout1.oto.tools", port, 2000.millis)
      val (_, port2, _, call2) = testServer("calltimeout2.oto.tools", port, 200.millis)
      val service1 = ServiceDescriptor(
        id = "callTimeout1-test",
        name = "callTimeout1-test",
        env = "prod",
        subdomain = "calltimeout1",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http"
          )
        ),
        publicPatterns = Seq("/.*"),
        useAkkaHttpClient = false,
        forceHttps = false,
        enforceSecureCommunication = false,
        targetsLoadBalancing = RoundRobin,
        clientConfig = ClientConfig(
          callTimeout = 1000
        )
      )
      val service2 = ServiceDescriptor(
        id = "callTimeout2-test",
        name = "callTimeout2-test",
        env = "prod",
        subdomain = "calltimeout2",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port2}",
            scheme = "http"
          )
        ),
        publicPatterns = Seq("/.*"),
        useAkkaHttpClient = false,
        forceHttps = false,
        enforceSecureCommunication = false,
        targetsLoadBalancing = RoundRobin,
        clientConfig = ClientConfig(
          callTimeout = 1000
        )
      )
      createOtoroshiService(service1).futureValue
      createOtoroshiService(service2).futureValue
      val resp1 = call1(Map.empty)
      val resp2 = call2(Map.empty)
      // counter1.get() mustBe 1
      // counter2.get() mustBe 1
      resp1.status mustBe 504
      resp2.status mustBe 200
      deleteOtoroshiService(service1).futureValue
      deleteOtoroshiService(service2).futureValue
      stopServers()
    }

    "allow better timeout management : callTimeout with akka-http (#301)" in {
      val (_, port1, counter1, call1) = testServer("calltimeoutakka1.oto.tools", port, 2000.millis)
      val (_, port2, counter2, call2) = testServer("calltimeoutakka2.oto.tools", port, 200.millis)
      val serviceweight1 = ServiceDescriptor(
        id = "calltimeoutakka1-test",
        name = "calltimeoutakka1-test",
        env = "prod",
        subdomain = "calltimeoutakka1",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http"
          )
        ),
        publicPatterns = Seq("/.*"),
        useAkkaHttpClient = true,
        forceHttps = false,
        enforceSecureCommunication = false,
        targetsLoadBalancing = RoundRobin,
        clientConfig = ClientConfig(
          callTimeout = 1000
        )
      )
      val serviceweight2 = ServiceDescriptor(
        id = "calltimeoutakka2-test",
        name = "calltimeoutakka2-test",
        env = "prod",
        subdomain = "calltimeoutakka2",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port2}",
            scheme = "http"
          )
        ),
        publicPatterns = Seq("/.*"),
        useAkkaHttpClient = true,
        forceHttps = false,
        enforceSecureCommunication = false,
        targetsLoadBalancing = RoundRobin,
        clientConfig = ClientConfig(
          callTimeout = 1000
        )
      )
      createOtoroshiService(serviceweight1).futureValue
      createOtoroshiService(serviceweight2).futureValue
      val resp1 = call1(Map.empty)
      val resp2 = call2(Map.empty)
      // counter1.get() mustBe 0
      // counter2.get() mustBe 1
      resp1.status mustBe 504
      resp2.status mustBe 200
      deleteOtoroshiService(serviceweight1).futureValue
      deleteOtoroshiService(serviceweight2).futureValue
      stopServers()
    }

    "allow better timeout management : idleTimeout (#301)" in {
      val (_, port1, counter1, call1) = testServer("idletimeout1.oto.tools", port, 2000.millis)
      val (_, port2, counter2, call2) = testServer("idletimeout2.oto.tools", port, 200.millis)
      val serviceweight1 = ServiceDescriptor(
        id = "idletimeout1-test",
        name = "idletimeout1-test",
        env = "prod",
        subdomain = "idletimeout1",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http"
          )
        ),
        publicPatterns = Seq("/.*"),
        forceHttps = false,
        useAkkaHttpClient = true,
        enforceSecureCommunication = false,
        targetsLoadBalancing = RoundRobin,
        clientConfig = ClientConfig(
          idleTimeout = 1000
        )
      )
      val serviceweight2 = ServiceDescriptor(
        id = "idletimeout2-test",
        name = "idletimeout2-test",
        env = "prod",
        subdomain = "idletimeout2",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port2}",
            scheme = "http"
          )
        ),
        publicPatterns = Seq("/.*"),
        forceHttps = false,
        useAkkaHttpClient = true,
        enforceSecureCommunication = false,
        targetsLoadBalancing = RoundRobin,
        clientConfig = ClientConfig(
          idleTimeout = 1000
        )
      )
      createOtoroshiService(serviceweight1).futureValue
      createOtoroshiService(serviceweight2).futureValue
      val resp1 = call1(Map.empty)
      val resp2 = call2(Map.empty)
      // counter1.get() mustBe 1
      // counter2.get() mustBe 1
      resp1.status mustBe 504
      resp2.status mustBe 200
      deleteOtoroshiService(serviceweight1).futureValue
      deleteOtoroshiService(serviceweight2).futureValue
      stopServers()
    }

    "allow better timeout management : callAndStreamTimeout (#301)" in {
      val (_, port1, counter1, call1) = testServer("callandstreamtimeout1.oto.tools", port, 0.millis, 2000.millis)
      val (_, port2, counter2, call2) = testServer("callandstreamtimeout2.oto.tools", port, 0.millis)
      val serviceweight1 = ServiceDescriptor(
        id = "callandstreamtimeout1-test",
        name = "callandstreamtimeout1-test",
        env = "prod",
        subdomain = "callandstreamtimeout1",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http"
          )
        ),
        publicPatterns = Seq("/.*"),
        forceHttps = false,
        useAkkaHttpClient = true,
        enforceSecureCommunication = false,
        targetsLoadBalancing = RoundRobin,
        clientConfig = ClientConfig(
          callAndStreamTimeout = 1000
        )
      )
      val serviceweight2 = ServiceDescriptor(
        id = "callandstreamtimeout2-test",
        name = "callandstreamtimeout2-test",
        env = "prod",
        subdomain = "callandstreamtimeout2",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port2}",
            scheme = "http"
          )
        ),
        publicPatterns = Seq("/.*"),
        forceHttps = false,
        useAkkaHttpClient = false,
        enforceSecureCommunication = false,
        targetsLoadBalancing = RoundRobin,
        clientConfig = ClientConfig(
          callAndStreamTimeout = 1000
        )
      )
      createOtoroshiService(serviceweight1).futureValue
      createOtoroshiService(serviceweight2).futureValue
      val resp1 = call1(Map.empty)
      val resp2 = call2(Map.empty)
      // counter1.get() mustBe 1
      // counter2.get() mustBe 1
      resp1.status mustBe 200
      resp2.status mustBe 200
      resp1.body == "{" mustBe true
      deleteOtoroshiService(serviceweight1).futureValue
      deleteOtoroshiService(serviceweight2).futureValue
      stopServers()
    }

    "allow better timeout management : callAndStreamTimeout with akka-http (#301)" in {
      val (_, port1, counter1, call1) = testServer("callandstreamtimeoutakka1.oto.tools", port, 0.millis, 2000.millis)
      val (_, port2, counter2, call2) = testServer("callandstreamtimeoutakka2.oto.tools", port, 0.millis)
      val serviceweight1 = ServiceDescriptor(
        id = "callandstreamtimeoutakka1-test",
        name = "callandstreamtimeoutakka1-test",
        env = "prod",
        subdomain = "callandstreamtimeoutakka1",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http"
          )
        ),
        publicPatterns = Seq("/.*"),
        forceHttps = false,
        useAkkaHttpClient = true,
        enforceSecureCommunication = false,
        targetsLoadBalancing = RoundRobin,
        clientConfig = ClientConfig(
          callAndStreamTimeout = 1000
        )
      )
      val serviceweight2 = ServiceDescriptor(
        id = "callandstreamtimeoutakka2-test",
        name = "callandstreamtimeoutakka2-test",
        env = "prod",
        subdomain = "callandstreamtimeoutakka2",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port2}",
            scheme = "http"
          )
        ),
        publicPatterns = Seq("/.*"),
        forceHttps = false,
        useAkkaHttpClient = true,
        enforceSecureCommunication = false,
        targetsLoadBalancing = RoundRobin,
        clientConfig = ClientConfig(
          callAndStreamTimeout = 1000
        )
      )
      createOtoroshiService(serviceweight1).futureValue
      createOtoroshiService(serviceweight2).futureValue
      val resp1 = call1(Map.empty)
      val resp2 = call2(Map.empty)
      // counter1.get() mustBe 1
      // counter2.get() mustBe 1
      resp1.status mustBe 200
      resp2.status mustBe 200
      resp1.body == "{" mustBe true
      deleteOtoroshiService(serviceweight1).futureValue
      deleteOtoroshiService(serviceweight2).futureValue
      stopServers()
    }

    "support random load balancing (#79)" in {
      val (_, port1, counter1, call1) = testServer("random.oto.tools", port)
      val (_, port2, counter2, _)     = testServer("random.oto.tools", port)
      val (_, port3, counter3, _)     = testServer("random.oto.tools", port)
      val serviceweight = ServiceDescriptor(
        id = "random-test",
        name = "random-test",
        env = "prod",
        subdomain = "random",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http"
          ),
          Target(
            host = s"127.0.0.1:${port2}",
            scheme = "http"
          ),
          Target(
            host = s"127.0.0.1:${port3}",
            scheme = "http"
          )
        ),
        publicPatterns = Seq("/.*"),
        forceHttps = false,
        enforceSecureCommunication = false,
        targetsLoadBalancing = Random
      )
      createOtoroshiService(serviceweight).futureValue
      (0 to 32).foreach { _ =>
        call1(Map.empty)
        await(100.millis)
      }
      // println(counter1.get(), counter2.get(), counter3.get())
      (counter1.get() == 11 && counter2.get() == 11 && counter3.get() == 11) mustBe false
      deleteOtoroshiService(serviceweight).futureValue
      stopServers()
    }

    "support sticky session load balancing (#79)" in {
      val (_, port1, counter1, call1) = testServer("sticky.oto.tools", port)
      val (_, port2, counter2, _)     = testServer("sticky.oto.tools", port)
      val (_, port3, counter3, _)     = testServer("sticky.oto.tools", port)
      val serviceweight = ServiceDescriptor(
        id = "sticky-test",
        name = "sticky-test",
        env = "prod",
        subdomain = "sticky",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http"
          ),
          Target(
            host = s"127.0.0.1:${port2}",
            scheme = "http"
          ),
          Target(
            host = s"127.0.0.1:${port3}",
            scheme = "http"
          )
        ),
        publicPatterns = Seq("/.*"),
        forceHttps = false,
        enforceSecureCommunication = false,
        targetsLoadBalancing = Sticky
      )
      createOtoroshiService(serviceweight).futureValue

      val resp1 = call1(Map.empty)
      val resp2 = call1(Map.empty)
      val resp3 = call1(Map.empty)

      val sessionId1Opt = resp1.cookie("otoroshi-tracking").map(_.value)
      val sessionId2Opt = resp2.cookie("otoroshi-tracking").map(_.value)
      val sessionId3Opt = resp3.cookie("otoroshi-tracking").map(_.value)
      // println(sessionId1Opt, sessionId2Opt, sessionId3Opt)

      sessionId1Opt.isDefined mustBe true
      sessionId2Opt.isDefined mustBe true
      sessionId3Opt.isDefined mustBe true
      (sessionId1Opt == sessionId2Opt && sessionId1Opt == sessionId3Opt) mustBe false
      (sessionId2Opt == sessionId1Opt && sessionId2Opt == sessionId3Opt) mustBe false
      (sessionId3Opt == sessionId1Opt && sessionId3Opt == sessionId2Opt) mustBe false

      (counter1.get() + counter2.get() + counter3.get()) mustBe 3

      counter1.set(0)
      counter2.set(0)
      counter3.set(0)

      def findNiceTrackingId(expected: Int): String = {
        var counter    = 0
        var index      = -1
        var trackingId = IdGenerator.uuid
        while (index != expected) {
          if (counter > 100) {
            throw new RuntimeException("Too much iterations ...")
          }
          trackingId = IdGenerator.uuid
          val hash: Int = Math.abs(scala.util.hashing.MurmurHash3.stringHash(trackingId))
          index = Hashing.consistentHash(hash, 3)
          counter = counter + 1
        }
        trackingId
      }

      val sessionId1 = findNiceTrackingId(0)
      val sessionId2 = findNiceTrackingId(1)
      val sessionId3 = findNiceTrackingId(2)

      (0 to 9).foreach { _ =>
        call1(Map("Cookie" -> s"otoroshi-tracking=$sessionId1"))
        await(100.millis)
      }
      // println(counter1.get(), counter2.get(), counter3.get())
      counter1.get() mustBe 10
      counter2.get() mustBe 0
      counter3.get() mustBe 0
      (0 to 9).foreach { _ =>
        call1(Map("Cookie" -> s"otoroshi-tracking=$sessionId2"))
        await(100.millis)
      }
      // println(counter1.get(), counter2.get(), counter3.get())
      counter1.get() mustBe 10
      counter2.get() mustBe 10
      counter3.get() mustBe 0
      (0 to 9).foreach { _ =>
        call1(Map("Cookie" -> s"otoroshi-tracking=$sessionId3"))
        await(100.millis)
      }
      // println(counter1.get(), counter2.get(), counter3.get())
      counter1.get() mustBe 10
      counter2.get() mustBe 10
      counter3.get() mustBe 10

      deleteOtoroshiService(serviceweight).futureValue
      stopServers()
    }

    "support ip address hash load balancing (#309)" in {
      val (_, port1, counter1, call1) = testServer("iphash.oto.tools", port)
      val (_, port2, counter2, _)     = testServer("iphash.oto.tools", port)
      val (_, port3, counter3, _)     = testServer("iphash.oto.tools", port)
      val serviceweight = ServiceDescriptor(
        id = "iphash-test",
        name = "iphash-test",
        env = "prod",
        subdomain = "iphash",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http"
          ),
          Target(
            host = s"127.0.0.1:${port2}",
            scheme = "http"
          ),
          Target(
            host = s"127.0.0.1:${port3}",
            scheme = "http"
          )
        ),
        publicPatterns = Seq("/.*"),
        forceHttps = false,
        enforceSecureCommunication = false,
        targetsLoadBalancing = IpAddressHash
      )
      createOtoroshiService(serviceweight).futureValue
      (0 to 9).foreach { _ =>
        call1(Map("X-Forwarded-For" -> "1.1.1.1"))
        await(100.millis)
      }
      // println(counter1.get(), counter2.get(), counter3.get())
      counter1.get() mustBe 10
      counter2.get() mustBe 0
      counter3.get() mustBe 0
      (0 to 9).foreach { _ =>
        call1(Map("X-Forwarded-For" -> "2.2.2.2"))
        await(100.millis)
      }
      // println(counter1.get(), counter2.get(), counter3.get())
      counter1.get() mustBe 10
      counter2.get() mustBe 10
      counter3.get() mustBe 0
      (0 to 9).foreach { _ =>
        call1(Map("X-Forwarded-For" -> "3.3.3.3"))
        await(100.millis)
      }
      // println(counter1.get(), counter2.get(), counter3.get())
      counter1.get() mustBe 10
      counter2.get() mustBe 10
      counter3.get() mustBe 10
      // (counter1.get() == 10 && counter2.get() == 10 &&counter3.get() == 10) mustBe false
      deleteOtoroshiService(serviceweight).futureValue
      stopServers()
    }

    "support best response time load balancing (#309)" in {
      val (_, port1, counter1, call1) = testServer("bestresponsetime.oto.tools", port, 200.millis)
      val (_, port2, counter2, _)     = testServer("bestresponsetime.oto.tools", port, 300.millis)
      val (_, port3, counter3, _)     = testServer("bestresponsetime.oto.tools", port, 100.millis)
      val serviceweight = ServiceDescriptor(
        id = "bestresponsetime-test",
        name = "bestresponsetime-test",
        env = "prod",
        subdomain = "bestresponsetime",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http"
          ),
          Target(
            host = s"127.0.0.1:${port2}",
            scheme = "http"
          ),
          Target(
            host = s"127.0.0.1:${port3}",
            scheme = "http"
          )
        ),
        publicPatterns = Seq("/.*"),
        forceHttps = false,
        enforceSecureCommunication = false,
        targetsLoadBalancing = BestResponseTime
      )
      createOtoroshiService(serviceweight).futureValue
      (0 to 29).foreach { _ =>
        call1(Map.empty)
        await(100.millis)
      }
      counter1.get() mustBe 1
      counter2.get() mustBe 1
      counter3.get() mustBe 28

      deleteOtoroshiService(serviceweight).futureValue
      stopServers()
    }

    "support weighted best response time load balancing (#309)" in {
      val (_, port1, counter1, call1) = testServer("wbestresponsetime.oto.tools", port, 200.millis)
      val (_, port2, counter2, _)     = testServer("wbestresponsetime.oto.tools", port, 300.millis)
      val (_, port3, counter3, _)     = testServer("wbestresponsetime.oto.tools", port, 100.millis)
      val serviceweight = ServiceDescriptor(
        id = "wbestresponsetime-test",
        name = "wbestresponsetime-test",
        env = "prod",
        subdomain = "wbestresponsetime",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http"
          ),
          Target(
            host = s"127.0.0.1:${port2}",
            scheme = "http"
          ),
          Target(
            host = s"127.0.0.1:${port3}",
            scheme = "http"
          )
        ),
        publicPatterns = Seq("/.*"),
        forceHttps = false,
        enforceSecureCommunication = false,
        targetsLoadBalancing = WeightedBestResponseTime(0.8)
      )
      createOtoroshiService(serviceweight).futureValue
      (0 to 29).foreach { _ =>
        call1(Map.empty)
        await(10.millis)
      }

      val computedRatio = BigDecimal(counter3.get() / 30.0).setScale(1, RoundingMode.HALF_EVEN).toDouble
      computedRatio >= 0.7 mustBe true
      computedRatio <= 0.9 mustBe true
      val computedInvertRatio =
        BigDecimal((counter1.get() + counter2.get()) / 30.0).setScale(1, RoundingMode.HALF_EVEN).toDouble
      computedInvertRatio >= 0.1 mustBe true
      computedInvertRatio <= 0.3 mustBe true

      deleteOtoroshiService(serviceweight).futureValue
      stopServers()
    }

    "support target predicates based on zones (#309)" in {
      val (_, port1, counter1, call1) = testServer("zones-test.oto.tools", port)
      val (_, port2, counter2, _)     = testServer("zones-test.oto.tools", port)
      val (_, port3, counter3, _)     = testServer("zones-test.oto.tools", port)
      val serviceweight = ServiceDescriptor(
        id = "zones-test",
        name = "zones-test",
        env = "prod",
        subdomain = "zones-test",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http",
            predicate = NetworkLocationMatch(zone = "dc1")
          ),
          Target(
            host = s"127.0.0.1:${port2}",
            scheme = "http",
            predicate = NetworkLocationMatch(zone = "dc2")
          ),
          Target(
            host = s"127.0.0.1:${port3}",
            scheme = "http",
            predicate = NetworkLocationMatch(zone = "dc3")
          )
        ),
        publicPatterns = Seq("/.*"),
        forceHttps = false,
        enforceSecureCommunication = false,
        targetsLoadBalancing = RoundRobin
      )
      createOtoroshiService(serviceweight).futureValue
      counter1.get() mustBe 0
      counter2.get() mustBe 0
      counter3.get() mustBe 0
      (0 to 9).foreach { _ =>
        call1(Map.empty)
        await(100.millis)
      }
      // println(counter1.get(), counter2.get(), counter3.get())
      counter1.get() mustBe 10
      counter2.get() mustBe 0
      counter3.get() mustBe 0
      deleteOtoroshiService(serviceweight).futureValue
      stopServers()
    }

    "support target predicates based on regions (#309)" in {
      val (_, port1, counter1, call1) = testServer("regions.oto.tools", port)
      val (_, port2, counter2, _)     = testServer("regions.oto.tools", port)
      val (_, port3, counter3, _)     = testServer("regions.oto.tools", port)
      val serviceweight = ServiceDescriptor(
        id = "regions-test",
        name = "regions-test",
        env = "prod",
        subdomain = "regions",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http",
            predicate = NetworkLocationMatch(region = "eu-west-1")
          ),
          Target(
            host = s"127.0.0.1:${port2}",
            scheme = "http",
            predicate = NetworkLocationMatch(region = "eu-west-2")
          ),
          Target(
            host = s"127.0.0.1:${port3}",
            scheme = "http",
            predicate = NetworkLocationMatch(region = "eu-west-3")
          )
        ),
        publicPatterns = Seq("/.*"),
        forceHttps = false,
        enforceSecureCommunication = false,
        targetsLoadBalancing = RoundRobin
      )
      createOtoroshiService(serviceweight).futureValue
      counter1.get() mustBe 0
      counter2.get() mustBe 0
      counter3.get() mustBe 0
      (0 to 9).foreach { _ =>
        call1(Map.empty)
        await(100.millis)
      }
      // println(counter1.get(), counter2.get(), counter3.get())
      counter1.get() mustBe 10
      counter2.get() mustBe 0
      counter3.get() mustBe 0
      deleteOtoroshiService(serviceweight).futureValue
      stopServers()
    }

    "support target predicates based on regions and zones (#309)" in {
      val (_, port1, counter1, call1) = testServer("regionsandzones.oto.tools", port)
      val (_, port2, counter2, _)     = testServer("regionsandzones.oto.tools", port)
      val (_, port3, counter3, _)     = testServer("regionsandzones.oto.tools", port)
      val (_, port4, counter4, _)     = testServer("regionsandzones.oto.tools", port)
      val (_, port5, counter5, _)     = testServer("regionsandzones.oto.tools", port)
      val (_, port6, counter6, _)     = testServer("regionsandzones.oto.tools", port)
      val serviceweight = ServiceDescriptor(
        id = "regionsandzones-test",
        name = "regionsandzones-test",
        env = "prod",
        subdomain = "regionsandzones",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http",
            predicate = NetworkLocationMatch(region = "eu-west-1", zone = "dc1")
          ),
          Target(
            host = s"127.0.0.1:${port2}",
            scheme = "http",
            predicate = NetworkLocationMatch(region = "eu-west-1", zone = "dc2")
          ),
          Target(
            host = s"127.0.0.1:${port3}",
            scheme = "http",
            predicate = NetworkLocationMatch(region = "eu-west-1", zone = "dc3")
          ),
          Target(
            host = s"127.0.0.1:${port4}",
            scheme = "http",
            predicate = NetworkLocationMatch(region = "eu-west-2", zone = "dc1")
          ),
          Target(
            host = s"127.0.0.1:${port5}",
            scheme = "http",
            predicate = NetworkLocationMatch(region = "eu-west-3", zone = "dc1")
          ),
          Target(
            host = s"127.0.0.1:${port6}",
            scheme = "http",
            predicate = NetworkLocationMatch(region = "eu-west-4", zone = "dc1")
          )
        ),
        publicPatterns = Seq("/.*"),
        forceHttps = false,
        enforceSecureCommunication = false,
        targetsLoadBalancing = RoundRobin
      )
      createOtoroshiService(serviceweight).futureValue
      counter1.get() mustBe 0
      counter2.get() mustBe 0
      counter3.get() mustBe 0
      counter4.get() mustBe 0
      counter5.get() mustBe 0
      counter6.get() mustBe 0
      (0 to 9).foreach { _ =>
        call1(Map.empty)
        await(100.millis)
      }
      // println(counter1.get(), counter2.get(), counter3.get())
      counter1.get() mustBe 10
      counter2.get() mustBe 0
      counter3.get() mustBe 0
      counter4.get() mustBe 0
      counter5.get() mustBe 0
      counter6.get() mustBe 0
      deleteOtoroshiService(serviceweight).futureValue
      stopServers()
    }

    "allow manual DNS resolution (#309, #310)" in {
      val counter = new AtomicInteger(0)
      val body    = """{"message":"hello world"}"""
      val server = TargetService(
        None,
        "/api",
        "application/json", { r =>
          if (r.getHeader("Host").get().value().startsWith("www.google.fr:")) {
            counter.incrementAndGet()
          }
          counter.incrementAndGet()
          body
        }
      ).await()

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
      createOtoroshiService(service).futureValue
      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "target-test.oto.tools"
        )
        .get()
        .futureValue

      resp.status mustBe 200
      counter.get() mustBe 2
      deleteOtoroshiService(service).futureValue
      server.stop()
    }

    "stop servers" in {
      system.terminate()
    }
  }

  s"[$name] Otoroshi ip address features" should {
    "block blacklisted ip addresses (#318)" in {
      val (_, port1, counter1, call1) = testServer("blockblackip.oto.tools", port)
      val service1 = ServiceDescriptor(
        id = "blockblackip-test",
        name = "blockblackip-test",
        env = "prod",
        subdomain = "blockblackip",
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
        ipFiltering = IpFiltering(
          blacklist = Seq("1.1.1.1")
        )
      )
      createOtoroshiService(service1).futureValue
      val resp1 = call1(Map("X-Forwarded-For" -> "1.1.1.1"))
      val resp2 = call1(Map("X-Forwarded-For" -> "1.1.1.2"))
      resp1.status mustBe 403
      resp2.status mustBe 200
      counter1.get() mustBe 1
      deleteOtoroshiService(service1).futureValue
      stopServers()
    }
    "block blacklisted ip addresses with wildcard (#318)" in {
      val (_, port1, counter1, call1) = testServer("blockblackipwild.oto.tools", port)
      val service1 = ServiceDescriptor(
        id = "blockblackipwild-test",
        name = "blockblackipwild-test",
        env = "prod",
        subdomain = "blockblackipwild",
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
        ipFiltering = IpFiltering(
          blacklist = Seq("1.1.1.*")
        )
      )
      createOtoroshiService(service1).futureValue
      val resp1 = call1(Map("X-Forwarded-For" -> "1.1.1.1"))
      val resp2 = call1(Map("X-Forwarded-For" -> "1.1.1.2"))
      val resp3 = call1(Map("X-Forwarded-For" -> "1.1.2.2"))
      resp1.status mustBe 403
      resp2.status mustBe 403
      resp3.status mustBe 200
      counter1.get() mustBe 1
      deleteOtoroshiService(service1).futureValue
      stopServers()
    }
    "block blacklisted ip addresses from range (#318)" in {
      val (_, port1, counter1, call1) = testServer("blockblackiprange.oto.tools", port)
      val service1 = ServiceDescriptor(
        id = "blockblackiprange-test",
        name = "blockblackiprange-test",
        env = "prod",
        subdomain = "blockblackiprange",
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
        ipFiltering = IpFiltering(
          blacklist = Seq("1.1.1.128/26")
        )
      )
      createOtoroshiService(service1).futureValue
      val resp1 = call1(Map("X-Forwarded-For" -> "1.1.1.128"))
      val resp2 = call1(Map("X-Forwarded-For" -> "1.1.1.191"))
      val resp3 = call1(Map("X-Forwarded-For" -> "1.1.1.192"))
      resp1.status mustBe 403
      resp2.status mustBe 403
      resp3.status mustBe 200
      counter1.get() mustBe 1
      deleteOtoroshiService(service1).futureValue
      stopServers()
    }
    "allow whitelisted ip addresses (#318)" in {
      val (_, port1, counter1, call1) = testServer("allowwhiteip.oto.tools", port)
      val service1 = ServiceDescriptor(
        id = "allowwhiteip-test",
        name = "allowwhiteip-test",
        env = "prod",
        subdomain = "allowwhiteip",
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
        ipFiltering = IpFiltering(
          whitelist = Seq("1.1.1.1")
        )
      )
      createOtoroshiService(service1).futureValue
      val resp1 = call1(Map("X-Forwarded-For" -> "1.1.1.1"))
      val resp2 = call1(Map("X-Forwarded-For" -> "1.1.1.2"))
      val resp3 = call1(Map("X-Forwarded-For" -> "1.1.1.3"))
      resp1.status mustBe 200
      resp2.status mustBe 403
      resp3.status mustBe 403
      counter1.get() mustBe 1
      deleteOtoroshiService(service1).futureValue
      stopServers()
    }
    "allow whitelisted ip addresses with wildcard (#318)" in {
      val (_, port1, counter1, call1) = testServer("allowwhiteipwild.oto.tools", port)
      val service1 = ServiceDescriptor(
        id = "allowwhiteipwild-test",
        name = "allowwhiteipwild-test",
        env = "prod",
        subdomain = "allowwhiteipwild",
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
        ipFiltering = IpFiltering(
          whitelist = Seq("1.1.1.*")
        )
      )
      createOtoroshiService(service1).futureValue
      val resp1 = call1(Map("X-Forwarded-For" -> "1.1.1.1"))
      val resp2 = call1(Map("X-Forwarded-For" -> "1.1.1.2"))
      val resp3 = call1(Map("X-Forwarded-For" -> "1.1.2.3"))
      resp1.status mustBe 200
      resp2.status mustBe 200
      resp3.status mustBe 403
      counter1.get() mustBe 2
      deleteOtoroshiService(service1).futureValue
      stopServers()
    }
    "allow whitelisted ip addresses from range (#318)" in {
      val (_, port1, counter1, call1) = testServer("allowwhiteiprange.oto.tools", port)
      val service1 = ServiceDescriptor(
        id = "allowwhiteiprange-test",
        name = "allowwhiteiprange-test",
        env = "prod",
        subdomain = "allowwhiteiprange",
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
        ipFiltering = IpFiltering(
          whitelist = Seq("1.1.1.128/26")
        )
      )
      createOtoroshiService(service1).futureValue
      val resp1 = call1(Map("X-Forwarded-For" -> "1.1.1.128"))
      val resp2 = call1(Map("X-Forwarded-For" -> "1.1.1.191"))
      val resp3 = call1(Map("X-Forwarded-For" -> "1.1.2.192"))
      resp1.status mustBe 200
      resp2.status mustBe 200
      resp3.status mustBe 403
      counter1.get() mustBe 2
      deleteOtoroshiService(service1).futureValue
      stopServers()
    }
  }

  s"[$name] Otoroshi Restrictions" should {
    "restrict service access when enabled (#315)" in {
      val (_, port1, counter1, call1) = testServer("restrictionserviceenabled.oto.tools", port)
      val service1 = ServiceDescriptor(
        id = "restrictionserviceenabled",
        name = "restrictionserviceenabled",
        env = "prod",
        subdomain = "restrictionserviceenabled",
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
        restrictions = Restrictions(enabled = true)
      )
      createOtoroshiService(service1).futureValue
      val resp1 = call1(Map.empty)
      resp1.status mustBe 404
      counter1.get() mustBe 0
      deleteOtoroshiService(service1).futureValue
      stopServers()
    }
    "restrict some routes and allow the rest on a service (#315)" in {
      val (_, port1, counter1, call1) = testServerWithClientPath("restrictionservicesome.oto.tools", port)
      val service1 = ServiceDescriptor(
        id = "restrictionservicesome",
        name = "restrictionservicesome",
        env = "prod",
        subdomain = "restrictionservicesome",
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
        restrictions = Restrictions(
          enabled = true,
          allowed = Seq(
            RestrictionPath("GET", "/.*")
          ),
          forbidden = Seq(
            RestrictionPath("*", "/forbidden/.*")
          ),
          notFound = Seq(
            RestrictionPath("*", "/notfound/.*")
          )
        )
      )
      createOtoroshiService(service1).futureValue

      val resp1 = call1("/api")(Map.empty)
      resp1.status mustBe 200
      counter1.get() mustBe 1

      val resp11 = call1("/api/fooo")(Map.empty)
      resp11.status mustBe 200
      counter1.get() mustBe 2

      val resp111 = call1("/apo/bar/foo")(Map.empty)
      resp111.status mustBe 200
      counter1.get() mustBe 3

      val resp1111 = ws
        .url(s"http://127.0.0.1:${port}/api/bar/foo")
        .withHttpHeaders("Host" -> "restrictionservicesome.oto.tools")
        .delete()
        .futureValue
      resp1111.status mustBe 404
      counter1.get() mustBe 3

      val resp2 = call1("/notfound/api")(Map.empty)
      resp2.status mustBe 404
      counter1.get() mustBe 3

      val resp3 = call1("/forbidden/api")(Map.empty)
      resp3.status mustBe 403
      counter1.get() mustBe 3

      deleteOtoroshiService(service1).futureValue
      stopServers()
    }
    "allow some routes and restrict the rest on a service (#315)" in {
      val (_, port1, counter1, call1) = testServerWithClientPath("restrictionserviceallowsome.oto.tools", port)
      val service1 = ServiceDescriptor(
        id = "restrictionserviceallowsome",
        name = "restrictionserviceallowsome",
        env = "prod",
        subdomain = "restrictionserviceallowsome",
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
        restrictions = Restrictions(
          enabled = true,
          allowLast = false,
          allowed = Seq(
            RestrictionPath("GET", "/api/.*")
          ),
          forbidden = Seq(
            RestrictionPath("*", "/.*")
          )
        )
      )
      createOtoroshiService(service1).futureValue

      val resp1 = call1("/api/a")(Map.empty)
      resp1.status mustBe 200
      counter1.get() mustBe 1

      val resp11 = call1("/api/fooo")(Map.empty)
      resp11.status mustBe 200
      counter1.get() mustBe 2

      val resp111 = call1("/api/bar/foo")(Map.empty)
      resp111.status mustBe 200
      counter1.get() mustBe 3

      val resp1111 = ws
        .url(s"http://127.0.0.1:${port}/api/bar/foo")
        .withHttpHeaders("Host" -> "restrictionserviceallowsome.oto.tools")
        .delete()
        .futureValue
      resp1111.status mustBe 403
      counter1.get() mustBe 3

      val resp2 = call1("/notfound/api")(Map.empty)
      resp2.status mustBe 403
      counter1.get() mustBe 3

      val resp3 = call1("/forbidden/api")(Map.empty)
      resp3.status mustBe 403
      counter1.get() mustBe 3

      deleteOtoroshiService(service1).futureValue
      stopServers()
    }
    "restrict some routes and allow the rest on an apikey (#315)" in {
      val (_, port1, counter1, call1) = testServerWithClientPath("restrictionservicesapikey.oto.tools", port)
      val service1 = ServiceDescriptor(
        id = "restrictionservicesapikey",
        name = "restrictionservicesapikey",
        env = "prod",
        subdomain = "restrictionservicesapikey",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false
      )
      val apikey1 = ApiKey(
        clientName = "apikey1",
        authorizedEntities = Seq(ServiceGroupIdentifier("default"))
      )
      val apikey2 = ApiKey(
        clientName = "apikey2",
        authorizedEntities = Seq(ServiceGroupIdentifier("default")),
        restrictions = Restrictions(
          enabled = true,
          allowed = Seq(
            RestrictionPath("GET", "/.*")
          ),
          forbidden = Seq(
            RestrictionPath("*", "/forbidden/.*")
          ),
          notFound = Seq(
            RestrictionPath("*", "/notfound/.*")
          )
        )
      )
      createOtoroshiService(service1).futureValue
      createOtoroshiApiKey(apikey1).futureValue
      createOtoroshiApiKey(apikey2).futureValue

      val resp1 =
        call1("/api")(Map("Otoroshi-Client-Id" -> apikey2.clientId, "Otoroshi-Client-Secret" -> apikey2.clientSecret))
      resp1.status mustBe 200
      counter1.get() mustBe 1

      val resp11 = call1("/api/fooo")(
        Map("Otoroshi-Client-Id" -> apikey2.clientId, "Otoroshi-Client-Secret" -> apikey2.clientSecret)
      )
      resp11.status mustBe 200
      counter1.get() mustBe 2

      val resp111 = call1("/api/bar/foo")(
        Map("Otoroshi-Client-Id" -> apikey2.clientId, "Otoroshi-Client-Secret" -> apikey2.clientSecret)
      )
      resp111.status mustBe 200
      counter1.get() mustBe 3

      val resp1111 = ws
        .url(s"http://127.0.0.1:${port}/api/bar/foo")
        .withHttpHeaders("Host"                   -> "restrictionservicesome.oto.tools",
                         "Otoroshi-Client-Id"     -> apikey2.clientId,
                         "Otoroshi-Client-Secret" -> apikey2.clientSecret)
        .delete()
        .futureValue
      resp1111.status mustBe 404
      counter1.get() mustBe 3

      val resp2 = call1("/notfound/api")(
        Map("Otoroshi-Client-Id" -> apikey2.clientId, "Otoroshi-Client-Secret" -> apikey2.clientSecret)
      )
      resp2.status mustBe 404
      counter1.get() mustBe 3

      val resp3 = call1("/forbidden/api")(
        Map("Otoroshi-Client-Id" -> apikey2.clientId, "Otoroshi-Client-Secret" -> apikey2.clientSecret)
      )
      resp3.status mustBe 403
      counter1.get() mustBe 3

      val resp1_1 =
        call1("/api")(Map("Otoroshi-Client-Id" -> apikey1.clientId, "Otoroshi-Client-Secret" -> apikey1.clientSecret))
      resp1_1.status mustBe 200
      counter1.get() mustBe 4

      val resp1_11 = call1("/api/fooo")(
        Map("Otoroshi-Client-Id" -> apikey1.clientId, "Otoroshi-Client-Secret" -> apikey1.clientSecret)
      )
      resp1_11.status mustBe 200
      counter1.get() mustBe 5

      val resp1_111 = call1("/api/bar/foo")(
        Map("Otoroshi-Client-Id" -> apikey1.clientId, "Otoroshi-Client-Secret" -> apikey1.clientSecret)
      )
      resp1_111.status mustBe 200
      counter1.get() mustBe 6

      val resp1_1111 = ws
        .url(s"http://127.0.0.1:${port}/api/bar/foo")
        .withHttpHeaders("Host"                   -> "restrictionservicesapikey.oto.tools",
                         "Otoroshi-Client-Id"     -> apikey1.clientId,
                         "Otoroshi-Client-Secret" -> apikey1.clientSecret)
        .delete()
        .futureValue
      resp1_1111.status mustBe 200
      counter1.get() mustBe 7

      val resp1_2 = call1("/notfound/api")(
        Map("Otoroshi-Client-Id" -> apikey1.clientId, "Otoroshi-Client-Secret" -> apikey1.clientSecret)
      )
      resp1_2.status mustBe 200
      counter1.get() mustBe 8

      val resp1_3 = call1("/forbidden/api")(
        Map("Otoroshi-Client-Id" -> apikey1.clientId, "Otoroshi-Client-Secret" -> apikey1.clientSecret)
      )
      resp1_3.status mustBe 200
      counter1.get() mustBe 9

      deleteOtoroshiService(service1).futureValue
      deleteOtoroshiApiKey(apikey1).futureValue
      deleteOtoroshiApiKey(apikey2).futureValue
      stopServers()
    }
    "allow some routes and restrict the rest on an apikey (#315)" in {
      val (_, port1, counter1, call1) = testServerWithClientPath("restrictionservicesapikeyallow.oto.tools", port)
      val service1 = ServiceDescriptor(
        id = "restrictionservicesapikeyallow",
        name = "restrictionservicesapikeyallow",
        env = "prod",
        subdomain = "restrictionservicesapikeyallow",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false
      )
      val apikey1 = ApiKey(
        clientName = "apikey1",
        authorizedEntities = Seq(ServiceGroupIdentifier("default"))
      )
      val apikey2 = ApiKey(
        clientName = "apikey2",
        authorizedEntities = Seq(ServiceGroupIdentifier("default")),
        restrictions = Restrictions(
          enabled = true,
          allowLast = false,
          allowed = Seq(
            RestrictionPath("GET", "/api/.*")
          ),
          forbidden = Seq(
            RestrictionPath("*", "/.*")
          )
        )
      )
      createOtoroshiService(service1).futureValue
      createOtoroshiApiKey(apikey1).futureValue
      createOtoroshiApiKey(apikey2).futureValue

      val resp1 =
        call1("/api/a")(Map("Otoroshi-Client-Id" -> apikey2.clientId, "Otoroshi-Client-Secret" -> apikey2.clientSecret))
      resp1.status mustBe 200
      counter1.get() mustBe 1

      val resp11 = call1("/api/fooo")(
        Map("Otoroshi-Client-Id" -> apikey2.clientId, "Otoroshi-Client-Secret" -> apikey2.clientSecret)
      )
      resp11.status mustBe 200
      counter1.get() mustBe 2

      val resp111 = call1("/api/bar/foo")(
        Map("Otoroshi-Client-Id" -> apikey2.clientId, "Otoroshi-Client-Secret" -> apikey2.clientSecret)
      )
      resp111.status mustBe 200
      counter1.get() mustBe 3

      val resp1111 = ws
        .url(s"http://127.0.0.1:${port}/api/bar/foo")
        .withHttpHeaders("Host"                   -> "restrictionservicesapikeyallow.oto.tools",
                         "Otoroshi-Client-Id"     -> apikey2.clientId,
                         "Otoroshi-Client-Secret" -> apikey2.clientSecret)
        .delete()
        .futureValue
      resp1111.status mustBe 403
      counter1.get() mustBe 3

      val resp2 = call1("/notfound/api")(
        Map("Otoroshi-Client-Id" -> apikey2.clientId, "Otoroshi-Client-Secret" -> apikey2.clientSecret)
      )
      resp2.status mustBe 403
      counter1.get() mustBe 3

      val resp3 = call1("/forbidden/api")(
        Map("Otoroshi-Client-Id" -> apikey2.clientId, "Otoroshi-Client-Secret" -> apikey2.clientSecret)
      )
      resp3.status mustBe 403
      counter1.get() mustBe 3

      val resp1_1 =
        call1("/api")(Map("Otoroshi-Client-Id" -> apikey1.clientId, "Otoroshi-Client-Secret" -> apikey1.clientSecret))
      resp1_1.status mustBe 200
      counter1.get() mustBe 4

      val resp1_11 = call1("/api/fooo")(
        Map("Otoroshi-Client-Id" -> apikey1.clientId, "Otoroshi-Client-Secret" -> apikey1.clientSecret)
      )
      resp1_11.status mustBe 200
      counter1.get() mustBe 5

      val resp1_111 = call1("/api/bar/foo")(
        Map("Otoroshi-Client-Id" -> apikey1.clientId, "Otoroshi-Client-Secret" -> apikey1.clientSecret)
      )
      resp1_111.status mustBe 200
      counter1.get() mustBe 6

      val resp1_1111 = ws
        .url(s"http://127.0.0.1:${port}/api/bar/foo")
        .withHttpHeaders("Host"                   -> "restrictionservicesapikeyallow.oto.tools",
                         "Otoroshi-Client-Id"     -> apikey1.clientId,
                         "Otoroshi-Client-Secret" -> apikey1.clientSecret)
        .delete()
        .futureValue
      resp1_1111.status mustBe 200
      counter1.get() mustBe 7

      val resp1_2 = call1("/notfound/api")(
        Map("Otoroshi-Client-Id" -> apikey1.clientId, "Otoroshi-Client-Secret" -> apikey1.clientSecret)
      )
      resp1_2.status mustBe 200
      counter1.get() mustBe 8

      val resp1_3 = call1("/forbidden/api")(
        Map("Otoroshi-Client-Id" -> apikey1.clientId, "Otoroshi-Client-Secret" -> apikey1.clientSecret)
      )
      resp1_3.status mustBe 200
      counter1.get() mustBe 9

      deleteOtoroshiService(service1).futureValue
      deleteOtoroshiApiKey(apikey1).futureValue
      deleteOtoroshiApiKey(apikey2).futureValue
      stopServers()
    }
  }

  "shutdown" in {
    stopAll()
  }
}
