package functional

import org.apache.pekko.http.scaladsl.model.IllegalUriException
import otoroshi.next.models.NgPluginInstance
import otoroshi.utils.http.RequestImplicits.*
import otoroshi.utils.http.RequestLocation
import play.api.mvc.RequestHeader
import play.api.mvc.request.RequestTarget
import play.api.test.FakeRequest

import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

// the location of a request is resolved once, when it comes in: the helpers read it instead of parsing the uri on each
// call, and a plugin without include nor exclude matches a request without reading its path
class RequestLocationSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers {

  // a request target that counts the reads of its uri
  final class CountingTarget(raw: String) extends RequestTarget {
    val reads                                       = new AtomicInteger(0)
    override def uri: URI                           = { reads.incrementAndGet(); new URI(raw) }
    override def uriString: String                  = { reads.incrementAndGet(); raw }
    override def path: String                       = { reads.incrementAndGet(); raw.takeWhile(_ != '?') }
    override def queryMap: Map[String, Seq[String]] = Map.empty
  }

  private def request(target: RequestTarget): RequestHeader = FakeRequest("GET", "/").withTarget(target)

  "The location of a request" should {

    "not be read by a plugin without include nor exclude, whatever the path" in {
      // an uri that does not even parse
      val target = new CountingTarget("/%zz")
      NgPluginInstance(plugin = "cp:functional.Probe").matches(request(target)) mustBe true
      target.reads.get() mustBe 0
    }

    "give its path to a plugin with include or exclude" in {
      val target  = new CountingTarget("/api/users?page=1")
      val include = NgPluginInstance(plugin = "cp:functional.Probe", include = Seq("/api/.*"))
      include.matches(request(target)) mustBe true
      include.copy(exclude = Seq("/api/users")).matches(request(target)) mustBe false
      NgPluginInstance(plugin = "cp:functional.Probe", include = Seq("/other/.*")).matches(request(target)) mustBe false
      target.reads.get() must be > 0
    }

    "give the path and the relative uri of the request when it carries one" in {
      val target   = new CountingTarget("/raw/path?raw=query")
      val location = RequestLocation("/located", "/located?located=query", "located.oto.tools:8080", "located.oto.tools", true)
      val located  = request(target).addAttr(otoroshi.plugins.Keys.RequestLocationKey, location)
      (located.thePath, located.relativeUri) mustBe ("/located", "/located?located=query")
      target.reads.get() mustBe 0
    }

    "be resolved from the uri on each call when the request carries none, as before" in {
      (request(new CountingTarget("/a/b?c=d")).thePath, request(new CountingTarget("/a/b?c=d")).relativeUri) mustBe
      ("/a/b", "/a/b?c=d")
      val absolute = request(new CountingTarget("http://absolute.oto.tools:8080/a/b?c=d"))
      (absolute.thePath, absolute.relativeUri) mustBe ("/a/b", "/a/b?c=d")
      // an uri that does not parse: its path fails, its relative uri is the raw one
      an[IllegalUriException] must be thrownBy request(new CountingTarget("/%zz")).thePath
      request(new CountingTarget("/%zz")).relativeUri mustBe "/%zz"
    }
  }
}
