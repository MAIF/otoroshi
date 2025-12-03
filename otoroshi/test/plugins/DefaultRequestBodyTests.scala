package plugins

import akka.util.ByteString
import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{NgDefaultRequestBody, NgDefaultRequestBodyConfig, OverrideHost}
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterJsValueReader}
import play.api.http.Status
import play.api.libs.json._

class DefaultRequestBodyTests(parent: PluginsTestSpec) {
  import parent._

  val localRoute = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgDefaultRequestBody],
        config = NgPluginInstanceConfig(
          NgDefaultRequestBodyConfig(
            body = ByteString(Json.obj("foo" -> "bar").stringify),
            contentType = "application/json",
            contentEncoding = None
          ).json.as[JsObject]
        )
      )
    )
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> localRoute.frontend.domains.head.domain
    )
    .get()
    .futureValue

  resp.status mustBe Status.OK
  Json.parse(resp.body).selectAsObject("body") mustEqual Json.obj("foo" -> "bar")

  val resp2 = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> localRoute.frontend.domains.head.domain
    )
    .post(Json.obj("body_from_client" -> true))
    .futureValue

  resp2.status mustBe Status.OK
  Json.parse(resp2.body).selectAsObject("body") mustEqual Json.obj("body_from_client" -> true)

  deleteOtoroshiRoute(localRoute).futureValue
}
