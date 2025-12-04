package plugins

import akka.http.scaladsl.model.headers.RawHeader
import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{NgHeaderNamesConfig, OverrideHost, RemoveHeadersOut}
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class RemoveHeadersOutTests(parent: PluginsTestSpec) {
  import parent._

  val route = createLocalRoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[RemoveHeadersOut],
        config = NgPluginInstanceConfig(
          NgHeaderNamesConfig(
            names = Seq("foo")
          ).json.as[JsObject]
        )
      )
    ),
    result = req => {
      Json.obj()
    },
    responseHeaders = List(RawHeader("foo", "bar"), RawHeader("foo2", "baz"))
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  resp.status mustBe Status.OK

  getOutHeader(resp, "foo") mustBe None
  getOutHeader(resp, "foo2") mustBe Some("baz")

  deleteOtoroshiRoute(route).futureValue
}
