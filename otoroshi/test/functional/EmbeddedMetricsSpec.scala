package functional

import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.scalatest.concurrent.Eventually
import otoroshi.next.models.*
import otoroshi.next.models.NgTreeRouter_Test.NgFakeRoute
import otoroshi.next.plugins.OverrideHost
import otoroshi.next.plugins.api.NgPluginHelper
import play.api.libs.json.Json

// the embedded metrics of the routes (calls, data, durations, overheads), gathered in memory by each instance and
// written to the datastore once per second, and the global throttling counter. sbt "testOnly functional.EmbeddedMetricsSpec"
class EmbeddedMetricsSpec extends PluginsTestSpecBase with Eventually {

  private def store  = env.datastores.serviceDescriptorDataStore
  private def config = env.datastores.globalConfigDataStore.latest()

  "The embedded metrics" should {

    "gather the calls, the data, the durations and the overheads of a route, and write them within a few seconds" in {
      val id           = s"embedded-metrics-${System.nanoTime()}"
      val globalBefore = store.globalCalls().futureValue
      (1 to 10).foreach(_ => store.updateMetrics(id, 40L, 4L, 100L, 1000L, 30L, config).futureValue)
      eventually {
        (store.calls(id).futureValue, store.dataInFor(id).futureValue, store.dataOutFor(id).futureValue) mustBe
        (10L, 1000L, 10000L)
      }
      store.globalCalls().futureValue must be >= (globalBefore + 10L)
      (store.callsDuration(id).futureValue, store.callsOverhead(id).futureValue) mustBe (40.0, 4.0)
      store.callsPerSec(id).futureValue must be > 0.0
      store.dataInPerSecFor(id).futureValue must be > 0.0
    }

    "count the requests of a route" in {
      val echo  = TargetService.jsonFull(None, "/", (_: HttpRequest) => (200, Json.obj("ok" -> true), List.empty)).await()
      val base  = NgFakeRoute.route("embedded-metrics-route")
      val route = base.copy(
        frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("embedded-metrics-route.oto.tools"))),
        backend = base.backend.copy(
          targets = Seq(NgTarget(id = "echo", hostname = "127.0.0.1", port = echo.port, tls = false)),
          root = "/"
        ),
        plugins = NgPlugins(Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost])))
      )
      createOtoroshiRoute(route).futureValue
      val statuses = (1 to 5).map(_ =>
        ws.url(s"http://127.0.0.1:$port/").withHttpHeaders("Host" -> "embedded-metrics-route.oto.tools").get().futureValue.status
      )
      try {
        statuses mustBe Seq.fill(5)(200)
        eventually {
          store.calls(route.id).futureValue mustBe 5L
        }
        store.callsDuration(route.id).futureValue must be > 0.0
      } finally {
        deleteOtoroshiRoute(route).futureValue
        echo.stop()
      }
    }

    "start the global throttling window with its first call" in {
      val key = s"${env.storageRoot}:throttling:global"
      env.datastores.rawDataStore.del(Seq(key)).futureValue
      env.datastores.globalConfigDataStore.updateQuotas(config).futureValue
      env.datastores.rawDataStore.pttl(key).futureValue must be > 0L
    }
  }
}
