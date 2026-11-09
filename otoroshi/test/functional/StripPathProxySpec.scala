package functional

import org.apache.pekko.http.scaladsl.model.HttpRequest
import otoroshi.next.models.*
import otoroshi.next.models.NgTreeRouter_Test.NgFakeRoute
import otoroshi.next.plugins.{EchoBackend, OverrideHost}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.UrlSanitizer
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

    // (strip path, backend root, request, expected relative uri on the backend side)
    val rootCases = Seq(
      (true, "/", "/a/hello", "/hello"),
      (true, "/", "/a", "/"),
      (true, "/base", "/a/hello", "/base/hello"),
      (true, "/base", "/a", "/base"),
      (true, "/base", "/a?q=1", "/base?q=1"),
      (true, "/base/", "/a/hello", "/base/hello"),
      (true, "", "/a/hello", "/hello"),
      (false, "/", "/a/hello", "/a/hello"),
      (false, "/", "/a", "/a"),
      (false, "/base", "/a/hello", "/base/a/hello"),
      (false, "/base/", "/a/hello", "/base/a/hello"),
      (false, "", "/a/hello", "/a/hello")
    )

    def rootRoute(id: String, strip: Boolean, root: String, port: Int, plugins: Seq[NgPluginInstance]): NgRoute = {
      val base = NgFakeRoute.route(id)
      base.copy(
        frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"$id.oto.tools/a")), stripPath = strip),
        backend = base.backend.copy(
          targets = Seq(NgTarget(id = "echo", hostname = "127.0.0.1", port = port, tls = false)),
          root = root
        ),
        plugins = NgPlugins(plugins)
      )
    }

    // the plugins read the relative uri of the request to the backend: a double slash between the root and the uri
    // makes it throw (#2918)
    "join the backend root and the uri with one slash for the plugins" in {
      val results = rootCases.zipWithIndex.map { case ((strip, root, path, expected), idx) =>
        val route = rootRoute(
          s"strip-root-plugin-$idx",
          strip,
          root,
          80,
          Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[EchoBackend]))
        )
        createOtoroshiRoute(route).futureValue
        val resp  = ws
          .url(s"http://127.0.0.1:$port$path")
          .withHttpHeaders("Host" -> s"${route.id}.oto.tools")
          .get()
          .futureValue
        deleteOtoroshiRoute(route).futureValue
        val got   = if (resp.status == 200) (resp.json \ "raw_path").asOpt[String] else None
        (strip, root, path, expected, resp.status, got)
      }
      results.filterNot { case (_, _, _, expected, status, got) => status == 200 && got.contains(expected) } mustBe empty
    }

    "join the backend root and the uri with one slash for the backend" in {
      val echo    = TargetService
        .jsonFull(None, "/", (r: HttpRequest) => (200, Json.obj("path" -> r.uri.path.toString), List.empty))
        .await()
      val results = rootCases.zipWithIndex.map { case ((strip, root, path, expected), idx) =>
        val route = rootRoute(
          s"strip-root-backend-$idx",
          strip,
          root,
          echo.port,
          Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]))
        )
        createOtoroshiRoute(route).futureValue
        val resp  = ws
          .url(s"http://127.0.0.1:$port$path")
          .withHttpHeaders("Host" -> s"${route.id}.oto.tools")
          .get()
          .futureValue
        deleteOtoroshiRoute(route).futureValue
        val got   = if (resp.status == 200) (resp.json \ "path").asOpt[String] else None
        (strip, root, path, expected.takeWhile(_ != '?'), resp.status, got)
      }
      echo.stop()
      results.filterNot { case (_, _, _, expected, status, got) => status == 200 && got.contains(expected) } mustBe empty
    }

    "collapse only the slashes starting the path of a url" in {
      Seq(
        "https://host//hello"                 -> "https://host/hello",
        "https://host///hello?a=1"            -> "https://host/hello?a=1",
        "https://host:8443//"                 -> "https://host:8443/",
        "https://host/hello"                  -> "https://host/hello",
        "https://host"                        -> "https://host",
        "https://host/files//archive"         -> "https://host/files//archive",
        "https://host/login?next=//app/home"  -> "https://host/login?next=//app/home",
        "https://host?next=//app/home"        -> "https://host?next=//app/home",
        "https://host/a#//frag"               -> "https://host/a#//frag",
        "https://host/a?next=https://app/home" -> "https://host/a?next=https://app/home"
      ).filterNot { case (url, expected) => UrlSanitizer.sanitize(url) == expected } mustBe empty
    }

    // (request, expected relative uri on the backend side): a double slash inside the path or in the query string
    // carries meaning and reaches the backend, a double slash starting the path is collapsed (#2919)
    val doubleSlashCases = Seq(
      ("/a/login?next=//app/home", "/login?next=//app/home"),
      ("/a/files//archive", "/files//archive"),
      ("/a//x", "/x"),
      ("/a//x?next=//app", "/x?next=//app")
    )

    "keep the meaningful double slashes for the plugins" in {
      val route   = rootRoute(
        "double-slash-plugin",
        strip = true,
        root = "/",
        80,
        Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[EchoBackend]))
      )
      createOtoroshiRoute(route).futureValue
      val results = doubleSlashCases.map { case (path, expected) =>
        val resp = ws
          .url(s"http://127.0.0.1:$port$path")
          .withHttpHeaders("Host" -> s"${route.id}.oto.tools")
          .get()
          .futureValue
        (path, expected, resp.status, if (resp.status == 200) (resp.json \ "raw_path").asOpt[String] else None)
      }
      deleteOtoroshiRoute(route).futureValue
      results.filterNot { case (_, expected, status, got) => status == 200 && got.contains(expected) } mustBe empty
    }

    "keep the meaningful double slashes for the backend" in {
      val echo    = TargetService
        .jsonFull(
          None,
          "/",
          (r: HttpRequest) =>
            (200, Json.obj("uri" -> (r.uri.path.toString + r.uri.rawQueryString.map("?" + _).getOrElse(""))), List.empty)
        )
        .await()
      val route   = rootRoute(
        "double-slash-backend",
        strip = true,
        root = "/",
        echo.port,
        Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]))
      )
      createOtoroshiRoute(route).futureValue
      val results = doubleSlashCases.map { case (path, expected) =>
        val resp = ws
          .url(s"http://127.0.0.1:$port$path")
          .withHttpHeaders("Host" -> s"${route.id}.oto.tools")
          .get()
          .futureValue
        (path, expected, resp.status, if (resp.status == 200) (resp.json \ "uri").asOpt[String] else None)
      }
      deleteOtoroshiRoute(route).futureValue
      echo.stop()
      results.filterNot { case (_, expected, status, got) => status == 200 && got.contains(expected) } mustBe empty
    }
  }
}
