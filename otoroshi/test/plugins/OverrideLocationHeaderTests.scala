package plugins

import functional.PluginsTestSpec
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{OverrideHost, OverrideLocationHeader}
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json.Json

class OverrideLocationHeaderTests(parent: PluginsTestSpec) {
  import parent.{*, given}

  def redirectToRelativePath() = {
    val route = createLocalRoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideLocationHeader]
        )
      ),
      responseStatus = Status.CREATED,
      result = _ => {
        Json.obj("message" -> "creation done")
      },
      responseHeaders = List(RawHeader("Location", "/foo"))
    ).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp.status mustBe Status.CREATED
    getOutHeader(resp, "Location") mustBe Some("/foo")

    deleteOtoroshiRoute(route).futureValue
  }

  def redirectToDomainAndPath() = {
    val route = createLocalRoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideLocationHeader]
        )
      ),
      responseStatus = Status.FOUND,
      result = _ => {
        Json.obj("message" -> "creation done")
      },
      rawDomain = "foo.oto.tools".some,
      responseHeaders = List(RawHeader("Location", s"http://location.oto.tools:$port/api"))
    ).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> "foo.oto.tools"
      )
      .withFollowRedirects(false)
      .get()
      .futureValue

    resp.status mustBe Status.FOUND
    getOutHeader(resp, "Location") mustBe Some(s"http://foo.oto.tools:$port/api")

    deleteOtoroshiRoute(route).futureValue
  }

  def redirectToDomainAndPathFollowRedirect() = {
    val route = createLocalRoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideLocationHeader]
        )
      ),
      responseStatus = Status.FOUND,
      result = _ => {
        Json.obj("message" -> "creation done")
      },
        rawDomain = "foo.oto.tools".some,
      responseHeaders = List(RawHeader("Location", s"http://127.0.0.1:$port/api"))
    ).futureValue

    val finalTargetRoute = createLocalRoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        )
      ),
      result = _ => {
        Json.obj("message" -> "reached the target route")
      },
      rawDomain = "location.oto.tools".some
    ).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> "foo.oto.tools"
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
    (resp.json \ "message").as[String] mustBe "reached the target route"

    deleteOtoroshiRoute(route).futureValue
    deleteOtoroshiRoute(finalTargetRoute).futureValue
  }
}
