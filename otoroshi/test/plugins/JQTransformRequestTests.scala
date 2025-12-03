package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class JQTransformRequestTests(parent: PluginsTestSpec) {

  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[JQRequest],
        config = NgPluginInstanceConfig(
          JQRequestConfig(
            filter = "{username: .user.name}"
          ).json.as[JsObject]
        )
      )
    )
  )

  val call = ws
    .url(s"http://127.0.0.1:$port/api/users")
    .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
    .post(Json.stringify(Json.obj("user" -> Json.obj("name" -> "Julien"))))
    .futureValue

  call.status mustBe Status.OK
  Json.parse(Json.parse(call.body).selectAsString("body")) mustBe Json.obj("username" -> "Julien")

  deleteOtoroshiRoute(route).futureValue
}
