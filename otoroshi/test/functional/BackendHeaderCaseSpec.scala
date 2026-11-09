package functional

import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.OverrideHost
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.syntax.implicits.*
import play.api.http.Status

import java.net.{HttpURLConnection, URI}
import scala.jdk.CollectionConverters.*

// header names are case insensitive: the jdk http server behind EchoTarget sends Content-type and Content-length,
// otoroshi used to lose the content type of such a response (#2921). sbt "testOnly functional.BackendHeaderCaseSpec"
class BackendHeaderCaseSpec extends PluginsTestSpecBase {

  "The headers of a backend response" should {

    "be read without case" in {
      val headers = Map("Content-type" -> "application/json", "x-foo" -> "bar")
      headers.getIgnoreCase("Content-Type") mustBe Some("application/json")
      headers.getIgnoreCase("content-type") mustBe Some("application/json")
      headers.getIgnoreCase("X-FOO") mustBe Some("bar")
      headers.getIgnoreCase("Content-Length") mustBe None
      headers.containsIgnoreCase("CONTENT-TYPE") mustBe true
      headers.containsIgnoreCase("Content-Length") mustBe false
      headers.removeIgnoreCase("Content-Type") mustBe Map("x-foo" -> "bar")
      headers.removeAndPutIgnoreCase("content-type" -> "text/plain") mustBe Map(
        "x-foo"        -> "bar",
        "content-type" -> "text/plain"
      )
    }

    "keep the content type a backend sends in another case" in {
      val route = createRouteWithExternalTarget(Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost])))
        .futureValue

      // the raw connection shows the response headers as the jdk server sends them, before any client reads them
      val direct = URI.create(s"http://127.0.0.1:${EchoTarget.port}/").toURL.openConnection().asInstanceOf[HttpURLConnection]
      direct.connect()
      direct.getInputStream.readAllBytes()
      direct.getHeaderFields.keySet().asScala.filter(_ != null) must contain("Content-type")
      direct.disconnect()

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
        .get()
        .futureValue
      resp.status mustBe Status.OK
      // the media type of the backend, application/json being utf-8 by definition the charset may be left out
      resp.header("Content-Type").map(_.split(";").head.trim) mustBe Some("application/json")
      (resp.json \ "path").as[String] mustBe "/api"

      deleteOtoroshiRoute(route).futureValue
    }
  }
}
