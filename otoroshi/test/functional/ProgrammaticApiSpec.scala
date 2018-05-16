package functional

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import models.{ServiceDescriptor, Target}
import org.apache.commons.io.FileUtils
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import otoroshi.api.Otoroshi
import play.api.Configuration
import play.core.server.ServerConfig

class ProgrammaticApiSpec(name: String, configurationSpec: => Configuration)
  extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with OtoroshiSpecHelper
    with IntegrationPatience {

  lazy val serviceHost = "basictest.foo.bar"
  lazy val ws = otoroshiComponents.wsClient

  override def getConfiguration(configuration: Configuration) = configuration ++ configurationSpec ++ Configuration(
    ConfigFactory
      .parseString(s"""
        |http.port=$port
        |play.server.http.port=$port
       """.stripMargin).resolve()
  )

  s"[$name] Otoroshi Programmatic API" should {

    "just works" in {

      import scala.concurrent.duration._

      implicit val system = ActorSystem("otoroshi-prog-api-test")
      val dir = Files.createTempDirectory("otoroshi-prog-api-test").toFile

      val callCounter = new AtomicInteger(0)
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

      val otoroshi = Otoroshi(
        ServerConfig(
          address = "0.0.0.0",
          port = Some(8888),
          rootDir = dir
        )
      ).start()

      awaitF(3.seconds).futureValue

      val services = getOtoroshiServices(Some(8888), otoroshi.components.wsClient).futureValue

      services.size mustBe 1

      val (_, status) = createOtoroshiService(initialDescriptor, Some(8888), otoroshi.components.wsClient).futureValue

      status mustBe 200

      val basicTestResponse1 = otoroshi.components.wsClient.url(s"http://127.0.0.1:8888/api").withHttpHeaders(
        "Host" -> serviceHost
      ).get().futureValue

      basicTestResponse1.status mustBe 200
      basicTestResponse1.body mustBe basicTestExpectedBody
      callCounter.get() mustBe 1

      basicTestServer.stop()
      otoroshi.stop()
      system.terminate()
      FileUtils.deleteDirectory(dir)
    }
  }
}