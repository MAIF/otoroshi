package functional

import com.typesafe.config.ConfigFactory
import org.apache.pekko.http.scaladsl.model.HttpRequest
import otoroshi.next.models.*
import otoroshi.next.models.NgTreeRouter_Test.NgFakeRoute
import otoroshi.next.plugins.{ContextValidation, ContextValidationConfig, OverrideHost}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.JsonPathValidator
import play.api.Configuration
import play.api.libs.json.{JsObject, JsString, Json}
import play.api.libs.ws.WSResponse

// the checks the gateway handler runs on every request, on real requests: the size limits of otoroshi.requests.*,
// lower here than the limits of the parser so that they are the ones that refuse, the counters a node reports to its
// leader, and no request timer from the pekko server by default. sbt "testOnly functional.GatewayRequestChecksProxySpec"
class GatewayRequestChecksProxySpec extends PluginsTestSpecBase {

  // the synthetic Raw-Request-URI header is checked like the others, so the header limit stays above the url one
  override def configurationSpec: Configuration = Configuration(
    ConfigFactory
      .parseString("""
          |otoroshi.requests.maxUrlLength = 2048
          |otoroshi.requests.maxCookieLength = 512
          |otoroshi.requests.maxHeaderNameLength = 64
          |otoroshi.requests.maxHeaderValueLength = 4096
          |""".stripMargin)
      .resolve()
  )

  private def route(id: String, backendPort: Int, plugins: Seq[NgPluginInstance] = Seq.empty): NgRoute = {
    val base = NgFakeRoute.route(id)
    base.copy(
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"$id.oto.tools"))),
      backend = base.backend.copy(
        targets = Seq(NgTarget(id = "echo", hostname = "127.0.0.1", port = backendPort, tls = false)),
        root = "/"
      ),
      plugins = NgPlugins(NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]) +: plugins)
    )
  }

  private def get(host: String, path: String = "/", headers: Seq[(String, String)] = Seq.empty): WSResponse =
    ws.url(s"http://127.0.0.1:$port$path").withHttpHeaders((("Host" -> host) +: headers)*).get().futureValue

  private def contextValidation(validators: JsonPathValidator*): NgPluginInstance =
    NgPluginInstance(
      plugin = NgPluginHelper.pluginId[ContextValidation],
      config = NgPluginInstanceConfig(ContextValidationConfig(validators).json.as[JsObject])
    )

  "The request checks of the gateway" should {

    "let a request within every limit through and refuse the ones over a limit" in {
      val echo   = TargetService.jsonFull(None, "/", (_: HttpRequest) => (200, Json.obj("ok" -> true), List.empty)).await()
      val limits = route("request-limits", echo.port)
      createOtoroshiRoute(limits).futureValue
      val host   = "request-limits.oto.tools"
      // the url is measured whole, protocol and host included
      val path   = "/" + "a" * (2048 - "http://".length - host.length - 1)
      // for each part: a request at the limit, one a byte over it, and the refusal expected for the second one
      val parts  = Seq(
        ("url", get(host, path), get(host, path + "a"), "URL should be smaller"),
        (
          "cookie",
          get(host, headers = Seq("Cookie" -> s"c=${"v" * 512}")),
          get(host, headers = Seq("Cookie" -> s"c=${"v" * 513}")),
          "Cookies should be smaller"
        ),
        (
          "header name",
          get(host, headers = Seq(("X-" + "n" * 62) -> "v")),
          get(host, headers = Seq(("X-" + "n" * 63) -> "v")),
          "Headers should be smaller"
        ),
        (
          "header value",
          get(host, headers = Seq("X-Value" -> "v" * 4096)),
          get(host, headers = Seq("X-Value" -> "v" * 4097)),
          "Headers should be smaller"
        )
      ).map { case (part, atLimit, overLimit, refusal) =>
        (part, atLimit.status, overLimit.status, overLimit.body.contains(refusal))
      }
      deleteOtoroshiRoute(limits).futureValue
      echo.stop()
      parts mustBe Seq(
        ("url", 200, 400, true),
        ("cookie", 200, 400, true),
        ("header name", 200, 400, true),
        ("header value", 200, 400, true)
      )
    }

    "count every request, and under its protocol, in the counters reported to the leader" in {
      val echo     = TargetService.jsonFull(None, "/", (_: HttpRequest) => (200, Json.obj("ok" -> true), List.empty)).await()
      val counted  = route("request-counters", echo.port)
      createOtoroshiRoute(counted).futureValue
      val requests = env.clusterAgent.counter("requests")
      val http     = env.clusterAgent.counter("http")
      val before   = (requests.sum(), http.sum())
      val statuses = (1 to 3).map(_ => get("request-counters.oto.tools").status)
      val after    = (requests.sum(), http.sum())
      deleteOtoroshiRoute(counted).futureValue
      echo.stop()
      statuses mustBe Seq(200, 200, 200)
      // any other plain http/1.1 call reaching the instance meanwhile moves both counters alike
      (after._1 - before._1) must be >= 3L
      (after._2 - before._2) mustBe (after._1 - before._1)
    }

    "not get a request timer from the pekko server, whose synthetic headers the context shows" in {
      val echo    = TargetService.jsonFull(None, "/", (_: HttpRequest) => (200, Json.obj("ok" -> true), List.empty)).await()
      val noTimer = route(
        "request-no-timer",
        echo.port,
        Seq(
          contextValidation(
            JsonPathValidator("$.request.headers.Remote-Address", JsString("IsDefined()")),
            JsonPathValidator("$.request.headers.Timeout-Access", JsString("NotDefined()"))
          )
        )
      )
      val control = route(
        "request-no-timer-control",
        echo.port,
        Seq(contextValidation(JsonPathValidator("$.request.headers.Remote-Address", JsString("NotDefined()"))))
      )
      createOtoroshiRoute(noTimer).futureValue
      createOtoroshiRoute(control).futureValue
      val statuses = (get("request-no-timer.oto.tools").status, get("request-no-timer-control.oto.tools").status)
      deleteOtoroshiRoute(noTimer).futureValue
      deleteOtoroshiRoute(control).futureValue
      echo.stop()
      statuses mustBe (200, 403)
    }
  }
}
