package plugins

import akka.http.scaladsl.model.headers.RawHeader
import functional.PluginsTestSpec
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{OverrideHost, OverrideLocationHeader}
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
    )

    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> LOCAL_HOST
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
      domain = "foo.oto.tools",
      responseHeaders = List(RawHeader("Location", s"http://location.oto.tools:$port/api"))
    )

    val finalTargetRoute = createLocalRoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        )
      ),
      result = _ => {
        Json.obj("message" -> "reached the target route")
      },
      domain = "location.oto.tools"
    )

    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> "foo.oto.tools"
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK

    deleteOtoroshiRoute(route).futureValue
    deleteOtoroshiRoute(finalTargetRoute).futureValue
  }
}
