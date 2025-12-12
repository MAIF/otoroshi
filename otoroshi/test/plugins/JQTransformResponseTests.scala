package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class JQTransformResponseTests(parent: PluginsTestSpec) {

  import parent._

  val route = createLocalRoute(
    Seq(
      NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[JQResponse],
        config = NgPluginInstanceConfig(
          JQResponseConfig(
            filter = "{username: .user.name}"
          ).json.as[JsObject]
        )
      )
    ),
    result = _ => Json.obj("user" -> Json.obj("name" -> "Julien"))
  ).futureValue

  val call = ws
    .url(s"http://127.0.0.1:$port/api/users")
    .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
    .get()
    .futureValue

  call.status mustBe Status.OK
  Json.parse(call.body) mustBe Json.obj("username" -> "Julien")

  deleteOtoroshiRoute(route).futureValue
}
