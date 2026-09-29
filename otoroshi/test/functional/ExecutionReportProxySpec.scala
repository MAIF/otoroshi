package functional

import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.stream.Materializer
import org.scalatest.concurrent.Eventually
import otoroshi.env.Env
import otoroshi.next.models.*
import otoroshi.next.models.NgTreeRouter_Test.NgFakeRoute
import otoroshi.next.plugins.OverrideHost
import otoroshi.next.plugins.api.*
import otoroshi.next.proxy.NgExecutionReport
import play.api.libs.json.{JsArray, JsNull, JsObject, Json}
import play.api.mvc.Result

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext

// keeps the report of the last request it transformed, to look at it once the response is there
class ReportProbe extends NgRequestTransformer {
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgInternal
  override def categories: Seq[NgPluginCategory]           = Seq.empty
  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest)
  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def isTransformRequestAsync: Boolean            = false

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    ReportProbe.last.set(ctx.report)
    Right(ctx.otoroshiRequest)
  }
}

object ReportProbe {
  val last = new AtomicReference[NgExecutionReport](null)
}

// the execution report of real requests: the timers of each plugin come from the typed plugin sequences, a report
// that something reads keeps every context, one that nothing reads keeps the sequences only.
// sbt "testOnly functional.ExecutionReportProxySpec"
class ExecutionReportProxySpec extends PluginsTestSpecBase with Eventually {

  private val probe = NgPluginHelper.pluginId[ReportProbe]

  private def withRoute[A](id: String)(f: String => A): A = {
    val echo    = TargetService.jsonFull(None, "/", (_: HttpRequest) => (200, Json.obj("ok" -> true), List.empty)).await()
    val base    = NgFakeRoute.route(id)
    val created = base.copy(
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"$id.oto.tools"))),
      backend = base.backend.copy(
        targets = Seq(NgTarget(id = "echo", hostname = "127.0.0.1", port = echo.port, tls = false)),
        root = "/"
      ),
      plugins = NgPlugins(
        Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]), NgPluginInstance(plugin = probe))
      )
    )
    createOtoroshiRoute(created).futureValue
    try f(s"$id.oto.tools")
    finally {
      deleteOtoroshiRoute(created).futureValue
      echo.stop()
    }
  }

  private def get(host: String, headers: (String, String)*): Int =
    ws.url(s"http://127.0.0.1:$port/").withHttpHeaders((("Host" -> host) +: headers)*).get().futureValue.status

  // a timer is exported under its key followed by its tags
  private def timerCount(key: String): Long = (env.metrics.jsonRawExport() \ "timers")
    .asOpt[JsObject]
    .flatMap(_.value.collectFirst {
      case (name, timer) if name.takeWhile(_ != ' ') == key => (timer \ "count").as[Long]
    })
    .getOrElse(0L)

  "The execution report of a request" should {

    "time each plugin of a phase" in {
      withRoute("report-timers") { host =>
        val timer  = s"ng-report-transform-request-$probe"
        val before = timerCount(timer)
        (1 to 3).map(_ => get(host)) mustBe Seq(200, 200, 200)
        eventually {
          timerCount(timer) must be >= (before + 3)
        }
      }
    }

    "keep every context when something reads it" in {
      withRoute("report-read") { host =>
        val id      = s"report-read-${System.nanoTime()}"
        env.proxyState.enableReportFor(id)
        get(host, "Otoroshi-Try-It-Request-Id" -> id) mustBe 200
        val json    = env.proxyState.report(id).map(_.json).getOrElse(JsNull)
        val steps   = (json \ "steps").as[Seq[JsObject]].map(step => (step \ "task").as[String] -> step).toMap
        (steps("find-route") \ "ctx" \ "found_route" \ "id").asOpt[String] mustBe Some("report-read")
        val plugins = (steps("transform-request") \ "ctx" \ "plugins").as[JsArray].value
        plugins.map(plugin => (plugin \ "plugin").as[String]) must contain(probe)
        plugins.map(plugin => (plugin \ "execution_debug" \ "out" \ "result" \ "kind").asOpt[String]) must contain(
          Some("successful")
        )
      }
    }

    "keep the plugin sequences only when nothing reads it" in {
      withRoute("report-unread") { host =>
        ReportProbe.last.set(null)
        get(host) mustBe 200
        val report   = ReportProbe.last.get()
        val sequence = report.getStep("transform-request").flatMap(_.sequence)
        report.getStep("find-route").map(_.ctx) mustBe Some(JsNull)
        sequence.map(_.plugins.map(_.plugin)).getOrElse(Seq.empty) must contain(probe)
        sequence.map(_.plugins.map(_.out)).getOrElse(Seq.empty).distinct mustBe Seq(JsNull)
      }
    }
  }
}
