package functional

import java.util.concurrent.atomic.AtomicInteger

import com.typesafe.config.ConfigFactory
import models.{ServiceDescriptor, Target}
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import play.api.Configuration

import scala.util.Failure

class BasicSpec(name: String, configurationSpec: => Configuration)
    extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with OtoroshiSpecHelper
    with IntegrationPatience {

  lazy val serviceHost = "basictest.foo.bar"
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

  s"[$name] Otoroshi" should {

    val callCounter           = new AtomicInteger(0)
    val basicTestExpectedBody = """{"message":"hello world"}"""
    val basicTestServer = TargetService(Some(serviceHost), "/api", "application/json", { _ =>
      callCounter.incrementAndGet()
      basicTestExpectedBody
    }).await()
    val initialDescriptor = ServiceDescriptor(
      id = "basic-test",
      name = "basic-test",
      env = "prod",
      subdomain = "basictest",
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

    "warm up" in {
      getOtoroshiServices().andThen {
        case Failure(e) => e.printStackTrace()
      }.futureValue // WARM UP
    }

    s"return only one service descriptor after startup (for admin API)" in {
      val services = getOtoroshiServices().futureValue
      services.size mustBe 1
    }

    s"route a basic http call" in {

      val (_, creationStatus) = createOtoroshiService(initialDescriptor).futureValue

      creationStatus mustBe 200

      val basicTestResponse1 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse1.status mustBe 200
      basicTestResponse1.body mustBe basicTestExpectedBody
      callCounter.get() mustBe 1
    }

    "provide a way to disable a service descriptor" in {

      updateOtoroshiService(initialDescriptor.copy(enabled = false)).futureValue

      val basicTestResponse2 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse2.status mustBe 404
      callCounter.get() mustBe 1

      updateOtoroshiService(initialDescriptor.copy(enabled = true)).futureValue

      val basicTestResponse3 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse3.status mustBe 200
      basicTestResponse3.body mustBe basicTestExpectedBody
      callCounter.get() mustBe 2
    }

    "provide a way to pass a service descriptor in maintenance mode" in {

      updateOtoroshiService(initialDescriptor.copy(maintenanceMode = true)).futureValue

      val basicTestResponse2 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse2.status mustBe 503
      basicTestResponse2.body.contains("Service in maintenance mode") mustBe true
      callCounter.get() mustBe 2

      updateOtoroshiService(initialDescriptor.copy(maintenanceMode = false)).futureValue

      val basicTestResponse3 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse3.status mustBe 200
      basicTestResponse3.body mustBe basicTestExpectedBody
      callCounter.get() mustBe 3
    }

    "provide a way to pass a service descriptor in build mode" in {

      updateOtoroshiService(initialDescriptor.copy(buildMode = true)).futureValue

      val basicTestResponse2 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse2.status mustBe 503
      basicTestResponse2.body.contains("Service under construction") mustBe true
      callCounter.get() mustBe 3

      updateOtoroshiService(initialDescriptor.copy(buildMode = false)).futureValue

      val basicTestResponse3 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse3.status mustBe 200
      basicTestResponse3.body mustBe basicTestExpectedBody
      callCounter.get() mustBe 4
    }

    "provide a way to force https for a service descriptor" in {

      updateOtoroshiService(initialDescriptor.copy(forceHttps = true)).futureValue

      val basicTestResponse2 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withFollowRedirects(false)
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse2.status mustBe 303
      basicTestResponse2.header("Location") mustBe Some("https://basictest.foo.bar/api")
      callCounter.get() mustBe 4

      updateOtoroshiService(initialDescriptor.copy(forceHttps = false)).futureValue

      val basicTestResponse3 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse3.status mustBe 200
      basicTestResponse3.body mustBe basicTestExpectedBody
      callCounter.get() mustBe 5
    }

    "send specific headers back" in {

      val basicTestResponse2 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse2.status mustBe 200
      basicTestResponse2.header("Otoroshi-Request-Id").isDefined mustBe true
      basicTestResponse2.header("Otoroshi-Proxy-Latency").isDefined mustBe true
      basicTestResponse2.header("Otoroshi-Upstream-Latency").isDefined mustBe true
      callCounter.get() mustBe 6

      updateOtoroshiService(initialDescriptor.copy(sendOtoroshiHeadersBack = false)).futureValue

      val basicTestResponse3 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      basicTestResponse3.status mustBe 200
      basicTestResponse3.header("Otoroshi-Request-Id").isEmpty mustBe true
      basicTestResponse3.header("Otoroshi-Proxy-Latency").isEmpty mustBe true
      basicTestResponse3.header("Otoroshi-Upstream-Latency").isEmpty mustBe true
      callCounter.get() mustBe 7

      updateOtoroshiService(initialDescriptor.copy(sendOtoroshiHeadersBack = true)).futureValue
    }

    "Send additionnal headers to target" in {
      val counter = new AtomicInteger(0)
      val body    = """{"message":"hello world"}"""
      val server = TargetService(None, "/api", "application/json", { r =>
        r.headers.find(_.name() == "X-Foo").map(_.value()) mustBe Some("Bar")
        counter.incrementAndGet()
        body
      }).await()
      val service = ServiceDescriptor(
        id = "header-test",
        name = "header-test",
        env = "prod",
        subdomain = "header",
        domain = "foo.bar",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*"),
        additionalHeaders = Map("X-Foo" -> "Bar")
      )
      createOtoroshiService(service).futureValue

      val resp1 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "header.foo.bar"
        )
        .get()
        .futureValue

      resp1.status mustBe 200
      resp1.body mustBe body

      deleteOtoroshiService(service).futureValue
      server.stop()
    }

    "Route only if header is present" in {
      val counter = new AtomicInteger(0)
      val body    = """{"message":"hello world"}"""
      val server = TargetService(None, "/api", "application/json", { _ =>
        counter.incrementAndGet()
        body
      }).await()
      val service = ServiceDescriptor(
        id = "match-test",
        name = "match-test",
        env = "prod",
        subdomain = "match",
        domain = "foo.bar",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*"),
        matchingHeaders = Map("X-Foo" -> "Bar")
      )
      createOtoroshiService(service).futureValue

      val resp1 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "match.foo.bar"
        )
        .get()
        .futureValue
      val resp2 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"  -> "match.foo.bar",
          "X-Foo" -> "Bar"
        )
        .get()
        .futureValue

      resp1.status mustBe 404
      resp2.status mustBe 200
      resp2.body mustBe body

      deleteOtoroshiService(service).futureValue
      server.stop()
    }

    "Route only if header is present and matches regex" in {
      val counter = new AtomicInteger(0)
      val body    = """{"message":"hello world"}"""
      val server = TargetService(None, "/api", "application/json", { _ =>
        counter.incrementAndGet()
        body
      }).await()
      val service = ServiceDescriptor(
        id = "match-test",
        name = "match-test",
        env = "prod",
        subdomain = "match",
        domain = "foo.bar",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*"),
        matchingHeaders = Map("X-Session" -> "^(.*?;)?(user=jason)(;.*)?$")
      )
      createOtoroshiService(service).futureValue

      val resp1 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "match.foo.bar"
        )
        .get()
        .futureValue
      val resp2 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"      -> "match.foo.bar",
          "X-Session" -> "user=mathieu"
        )
        .get()
        .futureValue
      val resp3 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"      -> "match.foo.bar",
          "X-Session" -> "user=jason"
        )
        .get()
        .futureValue

      resp1.status mustBe 404
      resp2.status mustBe 404
      resp3.status mustBe 200
      resp3.body mustBe body

      deleteOtoroshiService(service).futureValue
      server.stop()
    }

    "Route only if matching root is present" in {
      val counter = new AtomicInteger(0)
      val body    = """{"message":"hello world"}"""
      val server = TargetService(None, "/api", "application/json", { _ =>
        counter.incrementAndGet()
        body
      }).await()
      val service = ServiceDescriptor(
        id = "matchroot-test",
        name = "matchroot-test",
        env = "prod",
        subdomain = "matchroot",
        domain = "foo.bar",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*"),
        matchingRoot = Some("/foo")
      )
      createOtoroshiService(service).futureValue

      val resp1 = ws
        .url(s"http://127.0.0.1:$port/foo/api")
        .withHttpHeaders(
          "Host" -> "matchroot.foo.bar"
        )
        .get()
        .futureValue

      resp1.status mustBe 200
      resp1.body mustBe body

      deleteOtoroshiService(service).futureValue
      server.stop()
    }

    "Add root to target call" in {
      val counter = new AtomicInteger(0)
      val body    = """{"message":"hello world"}"""
      val server = TargetService(None, "/api", "application/json", { _ =>
        counter.incrementAndGet()
        body
      }).await()
      val service = ServiceDescriptor(
        id = "root-test",
        name = "root-test",
        env = "prod",
        subdomain = "root",
        domain = "foo.bar",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server.port}",
            scheme = "http"
          )
        ),
        root = "/api",
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*")
      )
      createOtoroshiService(service).futureValue

      val resp1 = ws
        .url(s"http://127.0.0.1:$port")
        .withHttpHeaders(
          "Host" -> "root.foo.bar"
        )
        .get()
        .futureValue

      resp1.status mustBe 200
      resp1.body mustBe body

      deleteOtoroshiService(service).futureValue
      server.stop()
    }

    "Match wildcard domains" in {
      val counter = new AtomicInteger(0)
      val body    = """{"message":"hello world"}"""
      val server = TargetService(None, "/api", "application/json", { _ =>
        counter.incrementAndGet()
        body
      }).await()
      val service = ServiceDescriptor(
        id = "wildcard-test",
        name = "wildcard-test",
        env = "prod",
        subdomain = "wild*",
        domain = "foo.bar",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${server.port}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*")
      )
      createOtoroshiService(service).futureValue

      val resp1 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "wildcard.foo.bar"
        )
        .get()
        .futureValue
      val resp2 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "wildbar.foo.bar"
        )
        .get()
        .futureValue
      val resp3 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> "wildfoo.foo.bar"
        )
        .get()
        .futureValue

      resp1.status mustBe 200
      resp1.body mustBe body
      resp2.status mustBe 200
      resp3.body mustBe body
      resp3.status mustBe 200
      resp3.body mustBe body

      deleteOtoroshiService(service).futureValue
      server.stop()
    }

    "stop servers" in {
      deleteOtoroshiService(initialDescriptor).futureValue
      basicTestServer.stop()
    }
  }
}
