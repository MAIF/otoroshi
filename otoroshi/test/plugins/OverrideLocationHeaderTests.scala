package plugins

import akka.http.scaladsl.model.headers.RawHeader
import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{OverrideHost, OverrideLocationHeader, OverrideLocationHeaderConfig}
import otoroshi.utils.syntax.implicits.{BetterJsReadable, BetterSyntax}
import play.api.http.Status
import play.api.libs.json.Json

class OverrideLocationHeaderTests(parent: PluginsTestSpec) {
  import parent._

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
      rawDomain = "foo.oto.tools/api".some,
      backendHostname = "backend.oto.tools".some,
      responseHeaders = List(RawHeader("Location", s"http://backend.oto.tools:$port/foo"))
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
      rawDomain = "foo.oto.tools/foo".some
    ).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withFollowRedirects(true)
      .withHttpHeaders(
        "Host" -> "foo.oto.tools"
      )
      .get()
      .futureValue

    val resp2 = ws
      .url(s"http://127.0.0.1:$port/api")
      .withFollowRedirects(false)
      .withHttpHeaders(
        "Host" -> "foo.oto.tools"
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
    resp.body mustBe Json.stringify(Json.obj("message" -> "reached the target route"))
    getOutHeader(resp2, "Location") mustBe Some(s"http://foo.oto.tools:$port/foo")

    deleteOtoroshiRoute(route).futureValue
    deleteOtoroshiRoute(finalTargetRoute).futureValue
  }

  def redirectToDomainAndPathWithMatchingHostnames() = {
    val route = createLocalRoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideLocationHeader],
          config = NgPluginInstanceConfig(OverrideLocationHeaderConfig(
            matchingHostnames = Seq("backend.oto.tools")
          ).json.asObject)
        )
      ),
      responseStatus = Status.FOUND,
      result = _ => {
        Json.obj("message" -> "creation done")
      },
      rawDomain = "foo.oto.tools/api".some,
      responseHeaders = List(RawHeader("Location", s"http://backend.oto.tools:$port/foo"))
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
      rawDomain = "foo.oto.tools/foo".some
    ).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withFollowRedirects(true)
      .withHttpHeaders(
        "Host" -> "foo.oto.tools"
      )
      .get()
      .futureValue

    val resp2 = ws
      .url(s"http://127.0.0.1:$port/api")
      .withFollowRedirects(false)
      .withHttpHeaders(
        "Host" -> "foo.oto.tools"
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
    resp.body mustBe Json.stringify(Json.obj("message" -> "reached the target route"))
    getOutHeader(resp2, "Location") mustBe Some(s"http://foo.oto.tools:$port/foo")

    deleteOtoroshiRoute(route).futureValue
    deleteOtoroshiRoute(finalTargetRoute).futureValue
  }
}
