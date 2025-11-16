package plugins

import akka.http.scaladsl.model.headers.RawHeader
import akka.util.ByteString
import functional.PluginsTestSpec
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{ImageReplacer, OverrideHost}
import play.api.http.Status

class ImageReplacerTests(parent: PluginsTestSpec) {
  import parent._

  val route = createLocalRoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[ImageReplacer]
      )
    ),
    responseHeaders = List(RawHeader("Content-Type", "image/jpeg")),
    stringResult = _ => {
      ByteString("""""".stripMargin, "utf-8").utf8String
    },
    jsonAPI = false,
    responseContentType = "image/jpeg"
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  getOutHeader(resp, "Content-Type").get mustBe "image/png"
  resp.status mustBe Status.OK

  deleteOtoroshiRoute(route).futureValue
}
