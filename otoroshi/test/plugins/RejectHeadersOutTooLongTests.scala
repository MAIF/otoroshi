package plugins

import akka.http.scaladsl.model.headers.RawHeader
import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{OverrideHost, RejectHeaderConfig, RejectHeaderOutTooLong}
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class RejectHeadersOutTooLongTests(parent: PluginsTestSpec) {
  import parent._

  val route = createLocalRoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[RejectHeaderOutTooLong],
        config = NgPluginInstanceConfig(
          RejectHeaderConfig(
            value = 15
          ).json.as[JsObject]
        )
      )
    ),
    responseStatus = Status.OK,
    result = _ => Json.obj(),
    responseHeaders = List(RawHeader("foo", "bar"), RawHeader("baz", "very very very long header value"))
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> LOCAL_HOST
    )
    .get()
    .futureValue

  resp.status mustBe Status.OK
  getOutHeader(resp, "foo") mustBe Some("bar")
  getOutHeader(resp, "baz") mustBe None

  deleteOtoroshiRoute(route).futureValue
}
