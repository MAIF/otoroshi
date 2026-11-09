package functional

import org.apache.pekko.http.scaladsl.model.HttpRequest
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.next.models.NgTreeRouter_Test.NgFakeRoute
import otoroshi.next.plugins.{EchoBackend, OverrideHost}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.wasm.proxywasm.{CorazaWafConfig, NgCorazaWAF, NgCorazaWAFConfig}
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.Await
import scala.concurrent.duration.*

// the coraza waf reads the relative uri of the request to the backend: a stripped path joined to the default root
// must reach it with a single slash, whatever its position in the request transformers (#2918).
// sbt "testOnly functional.StripPathCorazaSpec"
class StripPathCorazaSpec extends PluginsTestSpecBase {

  "Strip path with the coraza waf" should {

    "let the waf read the stripped uri joined to the default root" in {
      val coraza = CorazaWafConfig(
        location = EntityLocation.default,
        id = IdGenerator.uuid,
        name = "Coraza",
        description = "Coraza",
        tags = Seq.empty,
        metadata = Map.empty,
        inspectInputBody = true,
        inspectOutputBody = false,
        includeOwaspCRS = true,
        isBlockingMode = true,
        directives = Seq.empty,
        poolCapacity = 2
      )
      createOtoroshiWAF(coraza).futureValue
      val echo   = TargetService
        .jsonFull(None, "/", (r: HttpRequest) => (200, Json.obj("path" -> r.uri.path.toString), List.empty))
        .await()

      val waf          = NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgCorazaWAF],
        config = NgPluginInstanceConfig(NgCorazaWAFConfig(ref = coraza.id).json.as[JsObject])
      )
      val overrideHost = NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost])
      val echoBackend  = NgPluginInstance(plugin = NgPluginHelper.pluginId[EchoBackend])

      // (name, plugins): the waf alone in the request transformers, after another one, before another one
      val setups = Seq(
        ("waf-alone", Seq(echoBackend, waf)),
        ("waf-second", Seq(overrideHost, waf)),
        ("waf-first", Seq(waf, overrideHost))
      )
      // (request, expected status)
      val calls  = Seq(("/a/hello", 200), ("/a", 200), ("/a/hello?q=%3Cscript%3Ealert(1)%3C%2Fscript%3E", 403))

      val results = setups.flatMap { case (name, plugins) =>
        val base  = NgFakeRoute.route(s"strip-coraza-$name")
        val route = base.copy(
          frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"${base.id}.oto.tools/a")), stripPath = true),
          backend = base.backend.copy(
            targets = Seq(NgTarget(id = "echo", hostname = "127.0.0.1", port = echo.port, tls = false)),
            root = "/"
          ),
          plugins = NgPlugins(plugins)
        )
        createOtoroshiRoute(route).futureValue
        val res = calls.map { case (path, expected) =>
          val start = System.currentTimeMillis()
          val resp  = Await.result(
            ws.url(s"http://127.0.0.1:$port$path")
              .withHttpHeaders("Host" -> s"${route.id}.oto.tools")
              .withRequestTimeout(60.seconds)
              .get(),
            70.seconds
          )
          (name, path, expected, resp.status, System.currentTimeMillis() - start)
        }
        deleteOtoroshiRoute(route).futureValue
        res
      }
      results.foreach { case (name, path, expected, status, took) =>
        logger.info(s"strip-coraza $name $path => $status (expected $expected) in $took ms")
      }
      echo.stop()
      deleteOtoroshiWAF(coraza).futureValue
      results.filterNot { case (_, _, expected, status, _) => status == expected } mustBe empty
    }
  }
}
