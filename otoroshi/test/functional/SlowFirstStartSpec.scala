package functional

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpServer
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually
import org.slf4j.LoggerFactory
import otoroshi.next.analytics.queries.AnalyticsRuntime
import play.api.Configuration

import java.net.InetSocketAddress
import scala.jdk.CollectionConverters.*

// the first start of an empty datastore whose global config is written late, here by an initial import answered
// after 3 seconds: the subsystems started one second after the env must all start, the analytics runtime included
// sbt "testOnly functional.SlowFirstStartSpec"
class SlowFirstStartSpec extends OtoroshiSpec with Eventually {

  private val importServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  importServer.createContext(
    "/export.json",
    exchange => {
      Thread.sleep(3000)
      val body = "{}".getBytes("UTF-8")
      exchange.getResponseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, body.length.toLong)
      exchange.getResponseBody.write(body)
      exchange.close()
    }
  )
  importServer.start()

  override def getTestConfiguration(configuration: Configuration): Configuration =
    Configuration(
      ConfigFactory.parseString(
        s"""app.importFrom = "http://127.0.0.1:${importServer.getAddress.getPort}/export.json""""
      )
    ).withFallback(configuration)

  private val envLogger = LoggerFactory.getLogger("otoroshi-env").asInstanceOf[ch.qos.logback.classic.Logger]
  private val envLogs   = new ListAppender[ILoggingEvent]()

  "The first start of an otoroshi whose global config is written late" should {
    // an analytics runtime left by another instance of the same jvm must not count
    val runtimeBefore = AnalyticsRuntime.executor
    "start" in {
      envLogs.start()
      envLogger.addAppender(envLogs)
      startOtoroshi()
    }
    "initialize its analytics runtime" in {
      eventually {
        val runtime = AnalyticsRuntime.executor
        runtime must not be empty
        runtimeBefore.foreach(before => (runtime.get eq before) mustBe false)
      }
    }
    "start every subsystem without error" in {
      envLogs.list.asScala.map(_.getFormattedMessage).filter(_.startsWith("error while starting")) mustBe empty
    }
    "shutdown" in {
      envLogger.detachAppender(envLogs)
      stopAll()
      importServer.stop(0)
    }
  }
}
