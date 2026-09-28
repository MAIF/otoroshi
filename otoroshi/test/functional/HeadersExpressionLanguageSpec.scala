package functional

import org.apache.pekko.http.scaladsl.model.HttpRequest
import otoroshi.next.models.*
import otoroshi.next.models.NgTreeRouter_Test.NgFakeRoute
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{AdditionalHeadersIn, NgHeaderValuesConfig, OtoroshiHeadersIn, OverrideHost}
import play.api.libs.json.{JsObject, JsValue, Json}

// the expression language is evaluated on values that come from the configuration of a route, never on data sent by
// the client: a request header holding ${...} reaches the backend as it was sent, whether it is forwarded as is or
// copied into another header by the configuration. ${req.method} stands for any expression.
// sbt "testOnly functional.HeadersExpressionLanguageSpec"
class HeadersExpressionLanguageSpec extends PluginsTestSpecBase {

  private val echo = TargetService
    .jsonFull(
      None,
      "/",
      (r: HttpRequest) => (200, JsObject(r.headers.map(h => h.lowercaseName -> Json.toJson(h.value))), List.empty)
    )
    .await()

  private def headerRoute(id: String, plugins: Seq[NgPluginInstance]): NgRoute = {
    val base = NgFakeRoute.route(id)
    base.copy(
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"$id.oto.tools/"))),
      backend = base.backend.copy(
        targets = Seq(NgTarget(id = "echo", hostname = "127.0.0.1", port = echo.port, tls = false)),
        root = "/"
      ),
      plugins = NgPlugins(NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]) +: plugins)
    )
  }

  private def receivedBy(route: NgRoute, headers: (String, String)*): JsValue = {
    val resp = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders((("Host" -> route.frontend.domains.head.domain) +: headers)*)
      .get()
      .futureValue
    resp.status mustBe 200
    resp.json
  }

  "The expression language" should {

    "not be evaluated on the request headers forwarded by the Otoroshi headers plugin" in {
      val route = headerRoute("el-headers-in", Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[OtoroshiHeadersIn])))
      createOtoroshiRoute(route).futureValue
      val received = receivedBy(route, "x-client" -> "${req.method}")
      (received \ "x-client").asOpt[String] mustBe Some("${req.method}")
      (received \ "otoroshi-proxied-host").asOpt[String] mustBe Some("el-headers-in.oto.tools")
      (received \ "otoroshi-request-id").asOpt[String].exists(_.nonEmpty) mustBe true
      deleteOtoroshiRoute(route).futureValue
    }

    "evaluate a configured header, and insert a request header it reads as it was sent" in {
      val route = headerRoute(
        "el-additional-in",
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[AdditionalHeadersIn],
            config = NgPluginInstanceConfig(
              NgHeaderValuesConfig(
                Map("x-method" -> "${req.method}", "x-copy" -> "${req.headers.x-source}")
              ).json.as[JsObject]
            )
          )
        )
      )
      createOtoroshiRoute(route).futureValue
      val received = receivedBy(route, "x-source" -> "${req.method}")
      (received \ "x-method").asOpt[String] mustBe Some("GET")
      (received \ "x-copy").asOpt[String] mustBe Some("${req.method}")
      deleteOtoroshiRoute(route).futureValue
    }

    "stop the echo backend" in {
      echo.stop()
    }
  }
}
