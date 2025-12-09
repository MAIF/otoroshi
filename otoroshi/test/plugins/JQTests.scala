package plugins

import functional.PluginsTestSpec
import org.apache.pekko.util.ByteString
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.*
import otoroshi.next.plugins.api.NgPluginHelper
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.WSBodyWritables.writeableOf_String

class JQTests(parent: PluginsTestSpec) {

  import parent.{*, given}

  val route = createLocalRoute(
    Seq(
      NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[JQ],
        config = NgPluginInstanceConfig(
          JQConfig(
            response = "{username: .user.name}",
            request = "{username: .user.name}"
          ).json.as[JsObject]
        )
      )
    ),
    result = req => {
      req.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map { bodyByteString =>
        val body = bodyByteString.utf8String
        Json.parse(body) mustBe Json.obj("username" -> "Julien")
      }
      Json.obj("user" -> Json.obj("name" -> "Julien"))
    }
  )

  val call = ws
    .url(s"http://127.0.0.1:$port/api/users")
    .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
    .post(Json.stringify(Json.obj("user" -> Json.obj("name" -> "Julien"))))
    .futureValue

  call.status.mustBe(Status.OK)
  Json.parse(call.body).mustBe(Json.obj("username" -> "Julien"))

  deleteOtoroshiRoute(route).futureValue
}
