package functional

import com.typesafe.config.ConfigFactory
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.scalatest.concurrent.Eventually
import otoroshi.next.models.*
import otoroshi.next.models.NgTreeRouter_Test.NgFakeRoute
import otoroshi.next.plugins.OverrideHost
import otoroshi.next.plugins.api.NgPluginHelper
import play.api.Configuration
import play.api.libs.json.{JsObject, Json}

import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit
import javax.management.ObjectName
import scala.concurrent.Future

// the timers of the metrics: each one is a single instance, seen by the exports and by JMX alike, and the timers of
// each step and each plugin of a request can be turned off with otoroshi.metrics.detailed.
// sbt "testOnly functional.MetricsTimersSpec functional.MetricsTimersUndetailedSpec"
trait MetricsTimersHelpers { self: PluginsTestSpecBase =>

  // a timer is exported under its key followed by its tags
  def exportedCount(key: String): Long = (env.metrics.jsonRawExport() \ "timers")
    .asOpt[JsObject]
    .flatMap(_.value.collectFirst {
      case (name, timer) if name.takeWhile(_ != ' ') == key => (timer \ "count").as[Long]
    })
    .getOrElse(0L)

  def jmxCount(name: String): Long = ManagementFactory.getPlatformMBeanServer
    .getAttribute(new ObjectName("metrics", new java.util.Hashtable(java.util.Map.of("type", "timers", "name", name))), "Count")
    .asInstanceOf[Long]

  // a route and three requests through it
  def withRoute[A](id: String)(f: String => A): A = {
    val echo    = TargetService.jsonFull(None, "/", (_: HttpRequest) => (200, Json.obj("ok" -> true), List.empty)).await()
    val base    = NgFakeRoute.route(id)
    val created = base.copy(
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"$id.oto.tools"))),
      backend = base.backend.copy(
        targets = Seq(NgTarget(id = "echo", hostname = "127.0.0.1", port = echo.port, tls = false)),
        root = "/"
      ),
      plugins = NgPlugins(Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost])))
    )
    createOtoroshiRoute(created).futureValue
    try {
      (1 to 3).map(_ => ws.url(s"http://127.0.0.1:$port/").withHttpHeaders("Host" -> s"$id.oto.tools").get().futureValue.status) mustBe
      Seq(200, 200, 200)
      f(s"$id.oto.tools")
    } finally {
      deleteOtoroshiRoute(created).futureValue
      echo.stop()
    }
  }
}

class MetricsTimersSpec extends PluginsTestSpecBase with MetricsTimersHelpers with Eventually {

  "The timers of the metrics" should {

    "count every update once, in the exports and in JMX" in {
      (1 to 3).foreach(_ => env.metrics.timerUpdate("metrics-timers-update", 5L, TimeUnit.MILLISECONDS))
      (exportedCount("metrics-timers-update"), jmxCount("metrics-timers-update")) mustBe (3L, 3L)
    }

    "time a block and an async block once each" in {
      env.metrics.withTimer("metrics-timers-block")(1 + 1) mustBe 2
      env.metrics.withTimerAsync("metrics-timers-async")(Future.successful(42)).futureValue mustBe 42
      eventually {
        (exportedCount("metrics-timers-block"), jmxCount("metrics-timers-block")) mustBe (1L, 1L)
        (exportedCount("metrics-timers-async"), jmxCount("metrics-timers-async")) mustBe (1L, 1L)
      }
    }

    "time each step of a request" in {
      val before = exportedCount("ng-report-request-step-call-backend")
      withRoute("metrics-timers-detailed") { _ =>
        eventually {
          exportedCount("ng-report-request-step-call-backend") must be >= (before + 3)
        }
      }
    }
  }
}

class MetricsTimersUndetailedSpec extends PluginsTestSpecBase with MetricsTimersHelpers with Eventually {

  override def configurationSpec: Configuration = Configuration(
    ConfigFactory.parseString("otoroshi.metrics.detailed = false").resolve()
  )

  "The timers of the metrics, with otoroshi.metrics.detailed off" should {

    "time a request, but none of its steps" in {
      val duration = exportedCount("ng-report-request-duration")
      withRoute("metrics-timers-undetailed") { _ =>
        eventually {
          exportedCount("ng-report-request-duration") must be >= (duration + 3)
        }
        exportedCount("ng-report-request-step-call-backend") mustBe 0L
      }
    }
  }
}
