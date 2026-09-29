package functional

import otoroshi.next.models.*
import otoroshi.next.models.NgTreeRouter_Test.NgFakeRoute
import otoroshi.next.proxy.ProxyEngine
import otoroshi.utils.TypedMap
import play.api.test.FakeRequest

import scala.util.{Success, Try}

// strip path removes the path a route matched from the uri sent to the backend. that path holds the segments of the
// request as they were sent, so it is text: a segment with a regex metacharacter is removed like any other one.
// the uri the engine passes is the relative uri without its leading slash
class StripPathSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers {

  private val engine = new ProxyEngine()

  private def route(stripPath: Boolean, paths: String*): NgRoute =
    NgFakeRoute
      .route("strip")
      .copy(frontend =
        NgFrontend.empty.copy(domains = paths.map(p => NgDomainAndPath("strip.oto.tools" + p)), stripPath = stripPath)
      )

  // the uri sent to the backend, and the stripped path recorded in the attributes
  private def strip(route: NgRoute, requestUri: String, matchedPath: Option[String]): (String, Option[String]) = {
    val attrs = TypedMap.empty
    matchedPath.foreach(p => attrs.put(otoroshi.next.plugins.Keys.MatchedRouteKey -> NgMatchedRoute(route, path = p)))
    val uri   = engine.maybeStrippedUri(FakeRequest("GET", requestUri), requestUri.substring(1), route, attrs)
    (uri, attrs.get(otoroshi.plugins.Keys.StrippedPathKey))
  }

  "Strip path, with the path matched by the router" should {

    "remove the matched path and keep the rest of the uri, query string included" in {
      strip(route(true, "/api/users"), "/api/users/42?x=1", Some("/api/users")) mustBe (
        "/42?x=1",
        Some("/api/users")
      )
    }

    "remove a matched path holding regex metacharacters as text" in {
      val r        = route(true, "/api/:tenant/orders")
      val outcomes = Seq("acme", "ac(me", "ac)me", "a[b", "a{2}", "a|b", "ac+me", "ac$me", "ac*me", "ac.me").map {
        tenant => (tenant, Try(strip(r, s"/api/$tenant/orders/42", Some(s"/api/$tenant/orders"))._1))
      }
      outcomes.filterNot(_._2 == Success("/42")) mustBe empty
    }

    "keep the uri of a route that does not strip" in {
      strip(route(false, "/api/users"), "/api/users/42", Some("/api/users"))._1 mustBe "api/users/42"
    }

    "keep the uri of a root route" in {
      strip(route(true, "/"), "/api/users/42", Some("/api/users"))._1 mustBe "api/users/42"
      strip(route(true, "/api"), "/api/users/42", Some(""))._1 mustBe "api/users/42"
    }
  }

  "Strip path, without a path matched by the router" should {

    "remove the declared path the request starts with, as text" in {
      val r = route(true, "/api(v2)", "/api/v1.0")
      strip(r, "/api(v2)/x", None) mustBe ("/x", Some("/api(v2)"))
      strip(r, "/api/v1.0/x", None) mustBe ("/x", Some("/api/v1.0"))
    }
  }
}
