package functional

import otoroshi.next.models.*
import otoroshi.next.models.NgTreeRouter_Test.NgFakeRoute

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}
import scala.util.Random

// the tree router alone, below NgRoute.matches: which routes a domain and a path select, the matched path that strip
// path removes from the request, and the path params that ${req.pathparams.*} reads. pure logic, no otoroshi
// instance. the route level checks (methods, headers, query, cookies, exact) are in NgTreeRouterFindRouteSpec
class NgTreeRouterMatchingSpec
    extends org.scalatest.wordspec.AnyWordSpec
    with org.scalatest.matchers.must.Matchers
    with org.scalatest.OptionValues {

  private val domain = "router.oto.tools"

  // a route served on each given path, relative to `domain` when it starts with a slash, a full domain/path otherwise
  private def route(id: String, paths: String*): NgRoute =
    NgFakeRoute
      .route(id)
      .copy(frontend =
        NgFrontend.empty.copy(domains = paths.map(p => NgDomainAndPath(if (p.startsWith("/")) domain + p else p)))
      )

  private def router(routes: NgRoute*): NgTreeRouter = NgTreeRouter.build(routes)

  case class Found(routes: Seq[String], path: String, params: Map[String, String], noMoreSegments: Boolean)

  private def find(
      router: NgTreeRouter,
      path: String,
      host: String = domain,
      exactSegments: Boolean = true
  ): Option[Found] =
    router
      .find(host, path, exactSegments)
      .map(m => Found(m.routes.map(_.id), m.path, m.pathParams.toMap, m.noMoreSegments))

  "NgTreeRouter.find on literal paths" should {

    "match a path segment by segment" in {
      find(router(route("users", "/api/users")), "/api/users") mustBe Some(
        Found(Seq("users"), "/api/users", Map.empty, noMoreSegments = true)
      )
    }

    "match deeper paths as a prefix, without marking them as exact" in {
      find(router(route("users", "/api/users")), "/api/users/42/items") mustBe Some(
        Found(Seq("users"), "/api/users", Map.empty, noMoreSegments = false)
      )
    }

    "not match a sibling path, nor a parent path" in {
      val r = router(route("users", "/api/users"))
      find(r, "/api/other") mustBe None
      find(r, "/api") mustBe None
      find(r, "/") mustBe None
    }

    "prefer the longest declared path" in {
      val r = router(route("api", "/api"), route("users", "/api/users"))
      find(r, "/api/users/42") mustBe Some(Found(Seq("users"), "/api/users", Map.empty, noMoreSegments = false))
      find(r, "/api/other") mustBe Some(Found(Seq("api"), "/api", Map.empty, noMoreSegments = false))
      find(r, "/api") mustBe Some(Found(Seq("api"), "/api", Map.empty, noMoreSegments = true))
    }

    "serve the whole domain from a root route" in {
      val r = router(route("root", "/"), route("users", "/api/users"))
      find(r, "/") mustBe Some(Found(Seq("root"), "", Map.empty, noMoreSegments = true))
      find(r, "/anything/deep") mustBe Some(Found(Seq("root"), "", Map.empty, noMoreSegments = false))
      find(r, "/api/users") mustBe Some(Found(Seq("users"), "/api/users", Map.empty, noMoreSegments = true))
    }

    "serve the whole domain from a route declared on the domain alone" in {
      find(router(route("bare", domain)), "/foo") mustBe Some(Found(Seq("bare"), "", Map.empty, noMoreSegments = false))
    }

    "ignore empty segments" in {
      find(router(route("users", "/api/users")), "/api//users") mustBe Some(
        Found(Seq("users"), "/api/users", Map.empty, noMoreSegments = true)
      )
    }

    "keep every route declared on the same path, in declaration order" in {
      find(router(route("a", "/api/same"), route("b", "/api/same")), "/api/same").map(_.routes) mustBe Some(
        Seq("a", "b")
      )
    }

    "serve a route on each of its domains and paths" in {
      val r = router(route("multi", "/api/one", "other.oto.tools/two"))
      find(r, "/api/one").map(_.routes) mustBe Some(Seq("multi"))
      find(r, "/two", host = "other.oto.tools").map(_.routes) mustBe Some(Seq("multi"))
      find(r, "/two") mustBe None
      find(r, "/api/one", host = "other.oto.tools") mustBe None
    }

    "keep domains apart" in {
      val r = router(route("d1", "d1.oto.tools/api"), route("d2", "d2.oto.tools/api"))
      find(r, "/api", host = "d1.oto.tools").map(_.routes) mustBe Some(Seq("d1"))
      find(r, "/api", host = "d2.oto.tools").map(_.routes) mustBe Some(Seq("d2"))
      find(r, "/api", host = "d3.oto.tools") mustBe None
    }

    "lower case the declared domains" in {
      find(router(route("case", "Router-Case.OTO.tools/api")), "/api", host = "router-case.oto.tools")
        .map(_.routes) mustBe Some(Seq("case"))
    }

    "match a request path ending with a slash" in {
      val r = router(route("users", "/api/users"))
      find(r, "/api/users/") mustBe Some(Found(Seq("users"), "/api/users", Map.empty, noMoreSegments = true))
      find(r, "/api/users/42/") mustBe Some(Found(Seq("users"), "/api/users", Map.empty, noMoreSegments = false))
    }

    // the request has one more segment than the matched path: an exact route must not take it
    "not mark a deeper path ending with a slash as exact when the parent has other children" in {
      val r = router(route("api", "/api"), route("users", "/api/users"))
      find(r, "/api/other/") mustBe Some(Found(Seq("api"), "/api", Map.empty, noMoreSegments = false))
      find(r, "/api/other/more/") mustBe Some(Found(Seq("api"), "/api", Map.empty, noMoreSegments = false))
    }

    // the request went down a child that has no route for it: the parent route takes it with its own matched path,
    // the one strip path removes, and with the child segment still ahead of it
    "fall back to the parent route with its own matched path when a deeper path does not match" in {
      val r = router(route("api", "/api"), route("admin", "/api/users/admin"))
      find(r, "/api/users/other") mustBe Some(Found(Seq("api"), "/api", Map.empty, noMoreSegments = false))
      find(r, "/api/users") mustBe Some(Found(Seq("api"), "/api", Map.empty, noMoreSegments = false))
      find(r, "/api/users/admin") mustBe Some(Found(Seq("admin"), "/api/users/admin", Map.empty, noMoreSegments = true))
      val withRoot = router(route("root", "/"), route("users", "/api/users"))
      find(withRoot, "/api/other") mustBe Some(Found(Seq("root"), "", Map.empty, noMoreSegments = false))
    }

    "fall back to the parent route with its own matched path behind a segment prefix" in {
      val r = router(route("api", "/api"), route("v1", "/api/v1/"))
      find(r, "/api/v1beta") mustBe Some(Found(Seq("api"), "/api", Map.empty, noMoreSegments = false))
    }

    "find nothing in an empty router" in {
      find(router(), "/api") mustBe None
    }
  }

  "NgTreeRouter.find on path params" should {

    "extract a named param" in {
      find(router(route("orders", "/api/:tenant/orders")), "/api/acme/orders") mustBe Some(
        Found(Seq("orders"), "/api/acme/orders", Map("tenant" -> "acme"), noMoreSegments = true)
      )
    }

    "extract several named params" in {
      find(router(route("project", "/api/:org/projects/:project")), "/api/maif/projects/oto") mustBe Some(
        Found(Seq("project"), "/api/maif/projects/oto", Map("org" -> "maif", "project" -> "oto"), noMoreSegments = true)
      )
    }

    "extract a named param on deeper paths too" in {
      find(router(route("orders", "/api/:tenant/orders")), "/api/acme/orders/42") mustBe Some(
        Found(Seq("orders"), "/api/acme/orders", Map("tenant" -> "acme"), noMoreSegments = false)
      )
    }

    "prefer a literal segment over a named param" in {
      val r = router(route("me", "/api/users/me"), route("byid", "/api/users/:id"))
      find(r, "/api/users/me") mustBe Some(Found(Seq("me"), "/api/users/me", Map.empty, noMoreSegments = true))
      find(r, "/api/users/42") mustBe Some(
        Found(Seq("byid"), "/api/users/42", Map("id" -> "42"), noMoreSegments = true)
      )
    }

    "extract a regex param when the segment matches the regex" in {
      val r = router(route("items", "/api/items"), route("item", "/api/items/$id<[0-9]+>"))
      find(r, "/api/items/123") mustBe Some(
        Found(Seq("item"), "/api/items/123", Map("id" -> "123"), noMoreSegments = true)
      )
    }

    // a segment matching no regex param belongs to the request path left after the parent route, exactly as when the
    // parent has no param child at all
    "fall back to the parent route when the segment matches no regex param" in {
      val r = router(route("items", "/api/items"), route("item", "/api/items/$id<[0-9]+>"))
      find(r, "/api/items/abc") mustBe Some(Found(Seq("items"), "/api/items", Map.empty, noMoreSegments = false))
      find(router(route("item", "/api/items/$id<[0-9]+>")), "/api/items/abc") mustBe None
    }

    "find the route of sibling named params declared under different names" in {
      val r = router(route("items", "/api/contracts/:id/items"), route("fifou", "/api/contracts/:contract/fifou"))
      val items = find(r, "/api/contracts/1/items").value
      items.routes mustBe Seq("items")
      items.params.get("id") mustBe Some("1")
      val fifou = find(r, "/api/contracts/2/fifou").value
      fifou.routes mustBe Seq("fifou")
      fifou.params.get("contract") mustBe Some("2")
    }
  }

  // the path patterns listed in the routing section of the documentation (topics/engine)
  "NgTreeRouter.find on the documented path patterns" should {

    "match a plain old path" in {
      find(router(route("plain", "/api/users")), "/api/users").map(_.routes) mustBe Some(Seq("plain"))
    }

    "match a wildcard path" in {
      find(router(route("wild", "/api/users/*/bills")), "/api/users/42/bills") mustBe Some(
        Found(Seq("wild"), "/api/users/42/bills", Map.empty, noMoreSegments = true)
      )
    }

    "match named path params" in {
      find(router(route("named", "/api/users/:id/bills")), "/api/users/42/bills") mustBe Some(
        Found(Seq("named"), "/api/users/42/bills", Map("id" -> "42"), noMoreSegments = true)
      )
    }

    "match named regex path params" in {
      val r = router(route("regex", "/api/users/$id<[0-9]+>/bills"))
      find(r, "/api/users/42/bills") mustBe Some(
        Found(Seq("regex"), "/api/users/42/bills", Map("id" -> "42"), noMoreSegments = true)
      )
      find(r, "/api/users/abc/bills") mustBe None
    }
  }

  "NgTreeRouter.find on wildcards" should {

    "match a whole wildcard segment" in {
      find(router(route("details", "/api/*/details")), "/api/foo/details") mustBe Some(
        Found(Seq("details"), "/api/foo/details", Map.empty, noMoreSegments = true)
      )
    }

    "match a wildcard inside a segment" in {
      val r = router(route("versioned", "/api/v*/users"))
      find(r, "/api/v2/users").map(_.routes) mustBe Some(Seq("versioned"))
      find(r, "/api/x2/users") mustBe None
    }

    "match a wildcard domain" in {
      val r = router(route("wild", "*.wild.oto.tools/api"))
      find(r, "/api", host = "foo.wild.oto.tools").map(_.routes) mustBe Some(Seq("wild"))
      find(r, "/api", host = "foo.other.tools") mustBe None
    }

    "match a wildcard in the middle of a domain" in {
      val r = router(route("middle", "subdomain.*.tld/api"))
      find(r, "/api", host = "subdomain.domain.tld").map(_.routes) mustBe Some(Seq("middle"))
      find(r, "/api", host = "subdomain.other.tld").map(_.routes) mustBe Some(Seq("middle"))
      find(r, "/api", host = "other.domain.tld") mustBe None
    }

    "prefer the most specific wildcard domain, whatever the order of the routes" in {
      Seq(
        router(route("generic", "*.oto.tools/api"), route("specific", "*.wild.oto.tools/api")),
        router(route("specific", "*.wild.oto.tools/api"), route("generic", "*.oto.tools/api"))
      ).foreach { r =>
        find(r, "/api", host = "foo.wild.oto.tools").map(_.routes) mustBe Some(Seq("specific"))
        find(r, "/api", host = "foo.oto.tools").map(_.routes) mustBe Some(Seq("generic"))
      }
    }

    "say whether it serves a domain" in {
      val r = router(route("exact", "exact.oto.tools/api"), route("wild", "*.wild.oto.tools/api"))
      r.servesDomain("exact.oto.tools") mustBe true
      r.servesDomain("EXACT.oto.tools") mustBe true
      r.servesDomain("foo.wild.oto.tools") mustBe true
      r.servesDomain("foo.oto.tools") mustBe false
    }
  }

  "NgTreeRouter.find on segment prefixes" should {

    // "starting with value" in the documentation: the segment only starts with the declared one, so the request is
    // never the exact route path
    "match a longer segment on a route declared without a trailing slash, never as exact" in {
      find(router(route("999", "/api/999")), "/api/999-foo") mustBe Some(
        Found(Seq("999"), "/api/999-foo", Map.empty, noMoreSegments = false)
      )
    }

    "prefer the longest segment prefix" in {
      val r = router(route("99", "/api/99"), route("999", "/api/999"))
      find(r, "/api/9999").map(_.routes) mustBe Some(Seq("999"))
      find(r, "/api/99x").map(_.routes) mustBe Some(Seq("99"))
    }

    "not match a longer segment on a route declared with a trailing slash when trailing slashes mean exact segments" in {
      val r = router(route("999", "/api/999/"))
      find(r, "/api/999-foo") mustBe None
      find(r, "/api/999-foo", exactSegments = false).map(_.routes) mustBe Some(Seq("999"))
      find(r, "/api/999/bar").map(_.routes) mustBe Some(Seq("999"))
    }

    // two routes whose segments start alike: the route whose segment covers the longest part of the request wins,
    // exact or not, whatever the order of their declaration
    "take the route whose segment covers the longest part of the request, whatever the declaration order" in {
      Seq(
        router(route("99999", "a.b.c/api/99999"), route("999", "a.b.c/api/999")),
        router(route("999", "a.b.c/api/999"), route("99999", "a.b.c/api/99999"))
      ).foreach { r =>
        def on(path: String) = find(r, path, host = "a.b.c").map(_.routes)
        on("/api/99999") mustBe Some(Seq("99999"))
        on("/api/99999/foo") mustBe Some(Seq("99999"))
        on("/api/999999") mustBe Some(Seq("99999"))
        on("/api/99999-bar") mustBe Some(Seq("99999"))
        on("/api/999") mustBe Some(Seq("999"))
        on("/api/999/foo") mustBe Some(Seq("999"))
        on("/api/9999") mustBe Some(Seq("999"))
        on("/api/99") mustBe None
      }
    }

    "fall back to the parent route when no segment prefix matches" in {
      find(router(route("api", "/api"), route("999", "/api/999")), "/api/abc") mustBe Some(
        Found(Seq("api"), "/api", Map.empty, noMoreSegments = false)
      )
    }
  }

  // matching results must only depend on the request being routed. nodes keep caches that are filled by the requests
  // they see, and none of what they return may leak the path or the params of an earlier request
  "NgTreeRouter.find across requests" should {

    "give each request its own named params and matched path" in {
      val r = router(route("orders", "/api/:tenant/orders"), route("export", "/api/:tenant/orders/export"))
      Seq("acme", "globex", "initech", "acme").foreach { tenant =>
        find(r, s"/api/$tenant/orders/42") mustBe Some(
          Found(Seq("orders"), s"/api/$tenant/orders", Map("tenant" -> tenant), noMoreSegments = false)
        )
      }
      find(r, "/api/globex/orders/export") mustBe Some(
        Found(Seq("export"), "/api/globex/orders/export", Map("tenant" -> "globex"), noMoreSegments = true)
      )
    }

    "give each request its own matched path behind a wildcard segment" in {
      val r = router(route("orders", "/api/*/orders"), route("export", "/api/*/orders/export"))
      Seq("a", "b", "c").foreach { segment =>
        find(r, s"/api/$segment/orders/42") mustBe Some(
          Found(Seq("orders"), s"/api/$segment/orders", Map.empty, noMoreSegments = false)
        )
      }
    }

    "give each request its own regex params" in {
      val r = router(route("orders", "/api/$id<[0-9]+>/orders"), route("export", "/api/$id<[0-9]+>/orders/export"))
      Seq("1", "2", "3").foreach { id =>
        find(r, s"/api/$id/orders/x") mustBe Some(
          Found(Seq("orders"), s"/api/$id/orders", Map("id" -> id), noMoreSegments = false)
        )
      }
    }

    "give each request its own params behind a segment prefix" in {
      val r = router(route("v1", "/api/:tenant/orders/v1"))
      Seq("acme", "globex").foreach { tenant =>
        find(r, s"/api/$tenant/orders/v1beta") mustBe Some(
          Found(Seq("v1"), s"/api/$tenant/orders/v1beta", Map("tenant" -> tenant), noMoreSegments = false)
        )
      }
    }

    "not share the params of one request with another" in {
      val r      = router(route("orders", "/api/:tenant/orders"), route("export", "/api/:tenant/orders/export"))
      val first  = r.find(domain, "/api/acme/orders/42", true).value
      val second = r.find(domain, "/api/globex/orders/42", true).value
      (first.pathParams eq second.pathParams) mustBe false
      second.pathParams.put("tenant", "mutated")
      first.pathParams.get("tenant") mustBe Some("acme")
    }

    "stay right when many threads route at once" in {
      val r       = router(route("orders", "/api/:tenant/orders"), route("export", "/api/:tenant/orders/export"))
      val tenants = (1 to 50).map(i => s"tenant-$i")
      val results = (0 until 8).map { _ =>
        Future {
          (0 until 2000).map { _ =>
            val tenant = tenants(Random.nextInt(tenants.size))
            (tenant, find(r, s"/api/$tenant/orders/42"))
          }
        }
      }
      val routed  = Await.result(Future.sequence(results), 60.seconds).flatten
      val wrong   = routed.filterNot { case (tenant, found) =>
        found.contains(Found(Seq("orders"), s"/api/$tenant/orders", Map("tenant" -> tenant), noMoreSegments = false))
      }
      wrong mustBe empty
    }

    "give the same answer to the same request again" in {
      val r = router(route("api", "/api"), route("999", "/api/999"), route("orders", "/api/:tenant/orders"))
      Seq("/api/999-foo", "/api/abc", "/api/acme/orders/42", "/api/abc/def").foreach { path =>
        val answers = (0 until 3).map(_ => find(r, path))
        answers.distinct.size mustBe 1
      }
    }
  }
}
