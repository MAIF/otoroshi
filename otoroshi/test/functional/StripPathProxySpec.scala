package functional

import org.apache.pekko.http.scaladsl.model.HttpRequest
import otoroshi.next.models.*
import otoroshi.next.models.NgTreeRouter_Test.NgFakeRoute
import otoroshi.next.plugins.OverrideHost
import otoroshi.next.plugins.api.NgPluginHelper
import play.api.libs.json.Json

// strip path through the proxy: a path param holding characters that mean something in a regex is stripped like any
// other one, and the request reaches the backend. sbt "testOnly functional.StripPathProxySpec"
class StripPathProxySpec extends PluginsTestSpecBase {

  "Strip path" should {

    "remove a matched path holding regex metacharacters and reach the backend" in {
      val echo  = TargetService
        .jsonFull(None, "/", (r: HttpRequest) => (200, Json.obj("path" -> r.uri.path.toString), List.empty))
        .await()
      val base  = NgFakeRoute.route("strip-path-e2e")
      val route = base.copy(
        frontend = NgFrontend.empty.copy(
          domains = Seq(NgDomainAndPath("strip-path-e2e.oto.tools/api/:tenant/orders")),
          stripPath = true
        ),
        backend = base.backend.copy(
          targets = Seq(NgTarget(id = "echo", hostname = "127.0.0.1", port = echo.port, tls = false)),
          root = "/"
        ),
        plugins = NgPlugins(Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost])))
      )
      createOtoroshiRoute(route).futureValue
      val results = Seq("acme", "ac(me", "ac)me", "ac+me", "ac$me", "ac*me", "ac.me").map { tenant =>
        val resp = ws
          .url(s"http://127.0.0.1:$port/api/$tenant/orders/42")
          .withHttpHeaders("Host" -> "strip-path-e2e.oto.tools")
          .get()
          .futureValue
        (tenant, resp.status, if (resp.status == 200) (resp.json \ "path").asOpt[String] else None)
      }
      deleteOtoroshiRoute(route).futureValue
      echo.stop()
      results.filterNot { case (_, status, path) => status == 200 && path.contains("/42") } mustBe empty
    }
  }
}
