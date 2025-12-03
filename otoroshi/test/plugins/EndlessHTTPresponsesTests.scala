package plugins

import akka.stream.scaladsl.Sink
import akka.util.ByteString
import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{EndlessHttpResponse, NgEndlessHttpResponseConfig, NgRedirectionSettings, OverrideHost}
import play.api.http.Status
import play.api.libs.json.JsObject

class EndlessHTTPresponsesTests(parent: PluginsTestSpec) {

  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[EndlessHttpResponse],
        config = NgPluginInstanceConfig(
          NgEndlessHttpResponseConfig(
            finger = true,
            isDebug = true
          ).json.as[JsObject]
        )
      )
    )
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .stream()
    .futureValue

  resp.status mustBe 200
  resp.headers("Content-Type").head must include("application/octet-stream")

  val publisher  = resp.bodyAsSource
  val firstChunk = publisher.take(1).runWith(Sink.head).futureValue

  firstChunk mustBe ByteString.fromString(
    "\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95"
  )

  deleteOtoroshiRoute(route).futureValue
}
