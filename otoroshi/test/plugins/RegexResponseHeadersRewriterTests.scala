package plugins

import akka.http.scaladsl.model.headers.RawHeader
import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import play.api.http.Status
import play.api.libs.json._

class RegexResponseHeadersRewriterTests(parent: PluginsTestSpec) {

  import parent._

  val route = createLocalRoute(
    Seq(
      NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[RegexResponseHeadersRewriter],
        config = NgPluginInstanceConfig(
          RegexHeadersRewriterConfig(
            rules = Seq(
              RegexHeaderReplacementRule(
                name = "foo",
                pattern = "localhost(:\\\\d+)?",
                replacement = "example"
              )
            )
          ).json
            .as[JsObject]
        )
      )
    ),
    responseHeaders = List(RawHeader("foo", "localhost/bar"))
  ).futureValue

  val resp = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
    .get()
    .futureValue

  resp.status mustBe Status.OK
  getOutHeader(resp, "foo") mustBe Some("example/bar")

  deleteOtoroshiRoute(route).futureValue
}
