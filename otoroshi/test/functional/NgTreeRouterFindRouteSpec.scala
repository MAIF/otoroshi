package functional

import org.apache.pekko.http.scaladsl.model.HttpRequest
import otoroshi.next.models.*
import otoroshi.next.models.NgTreeRouter_Test.NgFakeRoute
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{AdditionalHeadersIn, NgHeaderValuesConfig, OverrideHost}
import otoroshi.utils.TypedMap
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.{Cookie, RequestHeader}
import play.api.test.FakeRequest

import scala.concurrent.Future
import scala.util.Random

// the tree router with the route level checks of NgRoute.matches, as documented in topics/engine (routing): hostname,
// path, exact or starting with, header, query and cookie values, plus the method. then the routing of real requests
// through the proxy, where the matched path is stripped and the path params feed the expression language.
// sbt "testOnly functional.NgTreeRouterFindRouteSpec"
class NgTreeRouterFindRouteSpec extends PluginsTestSpecBase {

  private val domain = "find-route.oto.tools"

  private def route(id: String, path: String, frontend: NgFrontend => NgFrontend = identity): NgRoute =
    NgFakeRoute.route(id).copy(frontend = frontend(NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(domain + path)))))

  private def request(
      path: String,
      method: String = "GET",
      host: String = domain,
      headers: Seq[(String, String)] = Seq.empty,
      cookies: Seq[Cookie] = Seq.empty
  ): RequestHeader = FakeRequest(method, path).withHeaders((("Host" -> host) +: headers)*).withCookies(cookies*)

  private def found(router: NgTreeRouter, req: RequestHeader): Option[String] =
    router.findRoute(req, TypedMap.empty).map(_.route.id)

  "NgTreeRouter.findRoute" should {

    "select a route by method" in {
      val r = NgTreeRouter.build(
        Seq(
          route("get", "/api/things", _.copy(methods = Seq("GET"))),
          route("post", "/api/things", _.copy(methods = Seq("POST")))
        )
      )
      found(r, request("/api/things")) mustBe Some("get")
      found(r, request("/api/things", method = "POST")) mustBe Some("post")
      found(r, request("/api/things", method = "DELETE")) mustBe None
    }

    "select a route by header value, with every documented kind of value" in {
      val r = NgTreeRouter.build(
        Seq(
          route("exact", "/api/exact", _.copy(headers = Map("X-Env" -> "prod"))),
          route("regex", "/api/regex", _.copy(headers = Map("x-version" -> "Regex(v[0-9]+)"))),
          route("wildcard", "/api/wildcard", _.copy(headers = Map("x-app" -> "Wildcard(app-*)"))),
          route("exists", "/api/exists", _.copy(headers = Map("x-token" -> "Exists()"))),
          route("defined", "/api/defined", _.copy(headers = Map("x-token" -> "IsDefined()"))),
          route("notdefined", "/api/notdefined", _.copy(headers = Map("x-token" -> "NotDefined()")))
        )
      )
      found(r, request("/api/exact", headers = Seq("x-env" -> "prod"))) mustBe Some("exact")
      found(r, request("/api/exact", headers = Seq("x-env" -> "staging"))) mustBe None
      found(r, request("/api/regex", headers = Seq("x-version" -> "v12"))) mustBe Some("regex")
      found(r, request("/api/regex", headers = Seq("x-version" -> "vx"))) mustBe None
      found(r, request("/api/wildcard", headers = Seq("x-app" -> "app-front"))) mustBe Some("wildcard")
      found(r, request("/api/wildcard", headers = Seq("x-app" -> "web-front"))) mustBe None
      found(r, request("/api/exists", headers = Seq("x-token" -> "a"))) mustBe Some("exists")
      found(r, request("/api/exists")) mustBe None
      found(r, request("/api/defined", headers = Seq("x-token" -> "a"))) mustBe Some("defined")
      found(r, request("/api/defined")) mustBe None
      found(r, request("/api/notdefined")) mustBe Some("notdefined")
      found(r, request("/api/notdefined", headers = Seq("x-token" -> "a"))) mustBe None
    }

    "select a route by query param value, with every documented kind of value" in {
      val r = NgTreeRouter.build(
        Seq(
          route("exact", "/api/exact", _.copy(query = Map("env" -> "prod"))),
          route("regex", "/api/regex", _.copy(query = Map("version" -> "Regex(v[0-9]+)"))),
          route("wildcard", "/api/wildcard", _.copy(query = Map("app" -> "Wildcard(app-*)"))),
          route("exists", "/api/exists", _.copy(query = Map("token" -> "Exists()"))),
          route("notdefined", "/api/notdefined", _.copy(query = Map("token" -> "NotDefined()")))
        )
      )
      found(r, request("/api/exact?env=prod")) mustBe Some("exact")
      found(r, request("/api/exact?env=staging")) mustBe None
      found(r, request("/api/regex?version=v3")) mustBe Some("regex")
      found(r, request("/api/regex?version=three")) mustBe None
      found(r, request("/api/wildcard?app=app-back")) mustBe Some("wildcard")
      found(r, request("/api/wildcard?app=back")) mustBe None
      found(r, request("/api/exists?token=a")) mustBe Some("exists")
      found(r, request("/api/exists")) mustBe None
      found(r, request("/api/notdefined")) mustBe Some("notdefined")
      found(r, request("/api/notdefined?token=a")) mustBe None
    }

    "select a route by cookie value, with every documented kind of value" in {
      val r = NgTreeRouter.build(
        Seq(
          route("exact", "/api/exact", _.copy(cookies = Map("env" -> "prod"))),
          route("regex", "/api/regex", _.copy(cookies = Map("version" -> "Regex(v[0-9]+)"))),
          route("wildcard", "/api/wildcard", _.copy(cookies = Map("app" -> "Wildcard(app-*)"))),
          route("exists", "/api/exists", _.copy(cookies = Map("session" -> "Exists()"))),
          route("notdefined", "/api/notdefined", _.copy(cookies = Map("session" -> "NotDefined()")))
        )
      )
      found(r, request("/api/exact", cookies = Seq(Cookie("env", "prod")))) mustBe Some("exact")
      found(r, request("/api/exact", cookies = Seq(Cookie("env", "dev")))) mustBe None
      found(r, request("/api/regex", cookies = Seq(Cookie("version", "v2")))) mustBe Some("regex")
      found(r, request("/api/regex", cookies = Seq(Cookie("version", "two")))) mustBe None
      found(r, request("/api/wildcard", cookies = Seq(Cookie("app", "app-x")))) mustBe Some("wildcard")
      found(r, request("/api/wildcard", cookies = Seq(Cookie("app", "x")))) mustBe None
      found(r, request("/api/exists", cookies = Seq(Cookie("session", "s")))) mustBe Some("exists")
      found(r, request("/api/exists")) mustBe None
      found(r, request("/api/notdefined")) mustBe Some("notdefined")
      found(r, request("/api/notdefined", cookies = Seq(Cookie("session", "s")))) mustBe None
    }

    "try the most constrained route of a path first, whatever the declaration order" in {
      val r = NgTreeRouter.build(
        Seq(
          route("plain", "/api/versioned"),
          route("v2", "/api/versioned", _.copy(headers = Map("x-version" -> "2"))),
          route("v2beta", "/api/versioned", _.copy(headers = Map("x-version" -> "2"), query = Map("beta" -> "true")))
        )
      )
      found(r, request("/api/versioned")) mustBe Some("plain")
      found(r, request("/api/versioned", headers = Seq("x-version" -> "2"))) mustBe Some("v2")
      found(r, request("/api/versioned?beta=true", headers = Seq("x-version" -> "2"))) mustBe Some("v2beta")
    }

    // "matches /api/foo with /api/foo and not with /api/foo/bar" for exact, both for starting with
    "match paths exactly or starting with the declared one, as documented" in {
      val exact    = NgTreeRouter.build(Seq(route("exact", "/api/foo", _.copy(exact = true))))
      val starting = NgTreeRouter.build(Seq(route("starting", "/api/foo")))
      found(exact, request("/api/foo")) mustBe Some("exact")
      found(exact, request("/api/foo/")) mustBe Some("exact")
      found(exact, request("/api/foo/bar")) mustBe None
      found(exact, request("/api/foobar")) mustBe None
      found(starting, request("/api/foo")) mustBe Some("starting")
      found(starting, request("/api/foo/bar")) mustBe Some("starting")
      found(starting, request("/api/foobar")) mustBe Some("starting")
    }

    "not let an exact route take a deeper path through a sibling of its children" in {
      val r = NgTreeRouter.build(
        Seq(route("exact", "/api", _.copy(exact = true)), route("admin", "/api/users/admin"))
      )
      found(r, request("/api")) mustBe Some("exact")
      found(r, request("/api/users")) mustBe None
      found(r, request("/api/users/other")) mustBe None
      found(r, request("/api/other/")) mustBe None
      found(r, request("/api/users/admin")) mustBe Some("admin")
    }

    "skip disabled routes" in {
      val r = NgTreeRouter.build(
        Seq(
          route("disabled", "/api/switch", _.copy(headers = Map("x-flag" -> "on"))).copy(enabled = false),
          route("enabled", "/api/switch")
        )
      )
      found(r, request("/api/switch", headers = Seq("x-flag" -> "on"))) mustBe Some("enabled")
      found(NgTreeRouter.build(Seq(route("disabled", "/api/off").copy(enabled = false))), request("/api/off")) mustBe None
    }

    "not take a route bound to another listener on the standard one" in {
      val r = NgTreeRouter.build(
        Seq(route("bound", "/api/listened").copy(boundListeners = Seq("my-listener")), route("free", "/api/listened"))
      )
      found(r, request("/api/listened")) mustBe Some("free")
      found(NgTreeRouter.build(Seq(route("bound", "/api/only").copy(boundListeners = Seq("x")))), request("/api/only")) mustBe None
    }

    "match the host whatever its case, and without its port" in {
      val r = NgTreeRouter.build(Seq(route("host", "/api/host")))
      found(r, request("/api/host", host = s"$domain:8080")) mustBe Some("host")
      found(r, request("/api/host", host = domain.toUpperCase)) mustBe Some("host")
    }

    "match wildcard hosts, as documented" in {
      val r = NgTreeRouter.build(
        Seq(
          NgFakeRoute.route("sub").copy(frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("*.find-wild.tld/api")))),
          NgFakeRoute.route("mid").copy(frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("api.*.tld/api"))))
        )
      )
      found(r, request("/api", host = "a.find-wild.tld")) mustBe Some("sub")
      found(r, request("/api", host = "api.anything.tld")) mustBe Some("mid")
      found(r, request("/api", host = "web.anything.tld")) mustBe None
    }

    "take the route whose segment covers the longest part of the request, whatever the declaration order" in {
      def on(id: String, path: String) =
        NgFakeRoute.route(id).copy(frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"a.b.c$path"))))
      Seq(
        NgTreeRouter.build(Seq(on("99999", "/api/99999"), on("999", "/api/999"))),
        NgTreeRouter.build(Seq(on("999", "/api/999"), on("99999", "/api/99999")))
      ).foreach { r =>
        found(r, request("/api/99999", host = "a.b.c")) mustBe Some("99999")
        found(r, request("/api/99999/foo", host = "a.b.c")) mustBe Some("99999")
        found(r, request("/api/999999", host = "a.b.c")) mustBe Some("99999")
        found(r, request("/api/999", host = "a.b.c")) mustBe Some("999")
        found(r, request("/api/999/foo", host = "a.b.c")) mustBe Some("999")
        found(r, request("/api/9999", host = "a.b.c")) mustBe Some("999")
      }
    }

    "give each request its own path params and matched path" in {
      val r = NgTreeRouter.build(Seq(route("orders", "/api/:tenant/orders"), route("export", "/api/:tenant/orders/export")))
      Seq("acme", "globex", "initech").foreach { tenant =>
        val matched = r.findRoute(request(s"/api/$tenant/orders/42"), TypedMap.empty).value
        matched.route.id mustBe "orders"
        matched.path mustBe s"/api/$tenant/orders"
        matched.pathParams.toMap mustBe Map("tenant" -> tenant)
      }
    }
  }

  // real routes and a backend that echoes what it received: the path after strip path or rewrite, and a header
  // built from the path params with the expression language
  "The proxy" should {

    val echo = TargetService
      .jsonFull(
        None,
        "/",
        (r: HttpRequest) =>
          (
            200,
            Json.obj(
              "path"   -> r.uri.path.toString,
              "tenant" -> r.headers.find(_.lowercaseName == "x-tenant").map(_.value).getOrElse("--")
            ),
            List.empty
          )
      )
      .await()

    def proxied(id: String, path: String, rewriteTo: Option[String] = None, tenantHeader: Boolean = false): NgRoute = {
      val base     = NgFakeRoute.route(id)
      val tenantIn = NgPluginInstance(
        plugin = NgPluginHelper.pluginId[AdditionalHeadersIn],
        config = NgPluginInstanceConfig(
          NgHeaderValuesConfig(Map("x-tenant" -> "${req.pathparams.tenant}")).json.as[JsObject]
        )
      )
      val plugins  = Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost])) ++
        (if (tenantHeader) Seq(tenantIn) else Seq.empty)
      base.copy(
        frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"router-e2e.oto.tools$path")), stripPath = true),
        backend = base.backend.copy(
          targets = Seq(NgTarget(id = "echo", hostname = "127.0.0.1", port = echo.port, tls = false)),
          root = rewriteTo.getOrElse("/"),
          rewrite = rewriteTo.isDefined
        ),
        plugins = NgPlugins(plugins)
      )
    }

    def call(path: String, host: String = "router-e2e.oto.tools"): (Int, JsValue) = {
      val resp = ws.url(s"http://127.0.0.1:$port$path").withHttpHeaders("Host" -> host).get().futureValue
      (resp.status, if (resp.status == 200) resp.json else Json.obj())
    }

    "strip the matched path of each request and give it its own path params" in {
      val routes = Seq(
        proxied("e2e-orders", "/api/:tenant/orders", tenantHeader = true),
        proxied("e2e-export", "/api/:tenant/orders/export", tenantHeader = true)
      )
      routes.foreach(r => createOtoroshiRoute(r).futureValue)
      Seq("acme", "globex", "initech", "acme").foreach { tenant =>
        call(s"/api/$tenant/orders/42") mustBe (200, Json.obj("path" -> "/42", "tenant" -> tenant))
      }
      call("/api/globex/orders/export") mustBe (200, Json.obj("path" -> "/", "tenant" -> "globex"))
      routes.foreach(r => deleteOtoroshiRoute(r).futureValue)
    }

    // the example of the documentation, next to a sibling route so that the lookup goes through the node caches
    "rewrite the target path with the path params of each request" in {
      val routes = Seq(
        proxied(
          "e2e-bills",
          "/api/users/$id<[0-9]+>/bills",
          rewriteTo = Some("/apis/v1/basic_users/${req.pathparams.id}/all_bills")
        ),
        proxied("e2e-bills-export", "/api/users/$id<[0-9]+>/bills/export")
      )
      routes.foreach(r => createOtoroshiRoute(r).futureValue)
      Seq("1", "2", "3").foreach { id =>
        call(s"/api/users/$id/bills/2024") mustBe (200, Json.obj(
          "path"   -> s"/apis/v1/basic_users/$id/all_bills",
          "tenant" -> "--"
        ))
      }
      call("/api/users/abc/bills")._1 mustBe 404
      routes.foreach(r => deleteOtoroshiRoute(r).futureValue)
    }

    "route concurrent requests of different tenants each to its own path params" in {
      val routes  = Seq(
        proxied("e2e-c-orders", "/api/:tenant/orders", tenantHeader = true),
        proxied("e2e-c-export", "/api/:tenant/orders/export", tenantHeader = true)
      )
      routes.foreach(r => createOtoroshiRoute(r).futureValue)
      val tenants = (1 to 10).map(i => s"tenant-$i")
      val calls   = (0 until 60).map { _ =>
        val tenant = tenants(Random.nextInt(tenants.size))
        ws.url(s"http://127.0.0.1:$port/api/$tenant/orders/42")
          .withHttpHeaders("Host" -> "router-e2e.oto.tools")
          .get()
          .map(resp => (tenant, resp.status, if (resp.status == 200) resp.json else Json.obj()))
      }
      val results = Future.sequence(calls).futureValue
      results.filterNot { case (tenant, status, body) =>
        status == 200 && body == Json.obj("path" -> "/42", "tenant" -> tenant)
      } mustBe empty
      routes.foreach(r => deleteOtoroshiRoute(r).futureValue)
    }

    // each route sends to its own backend root, so the echoed path says which route took the request. the two rounds
    // declare the routes in opposite orders, both in ids and in creation order
    "send a request to the route whose segment covers the longest part of its path, whatever the declaration order" in {
      def lengthRoute(id: String, segment: String): NgRoute = {
        val r = proxied(id, s"/api/$segment")
        r.copy(backend = r.backend.copy(root = s"/r-$segment"))
      }
      Seq(
        Seq(lengthRoute("e2e-len-a", "99999"), lengthRoute("e2e-len-b", "999")),
        Seq(lengthRoute("e2e-len-c", "999"), lengthRoute("e2e-len-d", "99999"))
      ).foreach { routes =>
        routes.foreach(r => createOtoroshiRoute(r).futureValue)
        def routedTo(path: String): String = {
          val (status, body) = call(path)
          status mustBe 200
          (body \ "path").as[String].split("/").filter(_.nonEmpty).headOption.getOrElse("")
        }
        routedTo("/api/99999") mustBe "r-99999"
        routedTo("/api/99999/foo") mustBe "r-99999"
        routedTo("/api/999999") mustBe "r-99999"
        routedTo("/api/999") mustBe "r-999"
        routedTo("/api/999/foo") mustBe "r-999"
        routedTo("/api/9999") mustBe "r-999"
        routes.foreach(r => deleteOtoroshiRoute(r).futureValue)
      }
    }

    "route a request whatever the case of its host" in {
      val r = proxied("e2e-case", "/api/case")
      createOtoroshiRoute(r).futureValue
      call("/api/case", host = "ROUTER-E2E.OTO.TOOLS")._1 mustBe 200
      deleteOtoroshiRoute(r).futureValue
    }

    "stop the echo backend" in {
      echo.stop()
    }
  }
}
