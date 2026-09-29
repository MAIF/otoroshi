package functional

import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.scalatest.concurrent.Eventually
import otoroshi.next.models.*
import otoroshi.next.models.NgTreeRouter_Test.NgFakeRoute
import otoroshi.next.plugins.OverrideHost
import otoroshi.next.plugins.api.NgPluginHelper
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.WSResponse

// the accounting of a request once its result is there: what a client may rely on is done by the time it gets the
// response (the request in flight released, the report of the try it console), the rest (the timers of the report, the
// high overhead alert, the exported report) runs aside. sbt "testOnly functional.ProxyAccountingSpec"
class ProxyAccountingSpec extends PluginsTestSpecBase with Eventually {

  private def route(id: String, backendPort: Int): NgRoute = {
    val base = NgFakeRoute.route(id)
    base.copy(
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"$id.oto.tools"))),
      backend = base.backend.copy(
        targets = Seq(NgTarget(id = "echo", hostname = "127.0.0.1", port = backendPort, tls = false)),
        root = "/"
      ),
      plugins = NgPlugins(Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost])))
    )
  }

  private def get(host: String, headers: Seq[(String, String)] = Seq.empty): WSResponse =
    ws.url(s"http://127.0.0.1:$port/").withHttpHeaders((("Host" -> host) +: headers)*).get().futureValue

  // a timer is exported under its key followed by its tags
  private def timerCount(key: String): Long = (env.metrics.jsonRawExport() \ "timers")
    .asOpt[JsObject]
    .flatMap(_.value.collectFirst {
      case (name, timer) if name.takeWhile(_ != ' ') == key => (timer \ "count").as[Long]
    })
    .getOrElse(0L)

  private def withRoute[A](id: String)(f: String => A): A = {
    val echo    = TargetService.jsonFull(None, "/", (_: HttpRequest) => (200, Json.obj("ok" -> true), List.empty)).await()
    val created = route(id, echo.port)
    createOtoroshiRoute(created).futureValue
    try f(s"$id.oto.tools")
    finally {
      deleteOtoroshiRoute(created).futureValue
      echo.stop()
    }
  }

  "The accounting of a request" should {

    "have released the request in flight by the time the client gets the response" in {
      withRoute("accounting-in-flight") { host =>
        val before   = env.datastores.requestsDataStore.asyncGetHandledRequests().futureValue
        val statuses = (1 to 5).map(_ => get(host).status)
        val after    = env.datastores.requestsDataStore.asyncGetHandledRequests().futureValue
        (statuses, after) mustBe (Seq.fill(5)(200), before)
      }
    }

    "give the try it console the report of a request as soon as it gets the response" in {
      withRoute("accounting-try-it") { host =>
        val id     = s"accounting-try-it-${System.nanoTime()}"
        env.proxyState.enableReportFor(id)
        val status = get(host, Seq("Otoroshi-Try-It-Request-Id" -> id)).status
        val tasks  = env.proxyState.report(id).map(_.steps.map(_.task)).getOrElse(Seq.empty)
        status mustBe 200
        tasks must contain("call-backend")
      }
    }

    "update the timers of the report aside from the response" in {
      withRoute("accounting-timers") { host =>
        val before = timerCount("ng-report-request-duration")
        (1 to 3).map(_ => get(host).status) mustBe Seq(200, 200, 200)
        eventually {
          timerCount("ng-report-request-duration") must be >= (before + 3)
        }
      }
    }
  }
}
