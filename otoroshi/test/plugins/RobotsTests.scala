package plugins

import functional.PluginsTestSpec
import otoroshi.models.ApiKey
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins._
import otoroshi.security.IdGenerator
import play.api.http.Status
import play.api.libs.json.JsObject

class RobotsTests(parent: PluginsTestSpec) {

  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[Robots],
        config = NgPluginInstanceConfig(
          RobotConfig(
            robotEnabled = true,
            robotTxtContent = "User-agent: *\nDisallow: /admin",
            metaEnabled = true,
            metaContent = "noindex, nofollow",
            headerEnabled = true,
            headerContent = "noindex, nofollow"
          ).json
            .as[JsObject]
        )
      )
    )
  )

  val authorizedCall = ws
    .url(s"http://127.0.0.1:$port/robots.txt")
    .withHttpHeaders(
      "Host" -> PLUGINS_HOST
    )
    .get()
    .futureValue

  authorizedCall.status mustBe Status.OK
  authorizedCall.body must include("User-agent: *")
  authorizedCall.body must include("Disallow: /admin")

  val htmlResp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> PLUGINS_HOST
    )
    .get()
    .futureValue

  htmlResp.status mustBe Status.OK
  htmlResp.header("X-Robots-Tag") mustBe Some("noindex, nofollow")

  deleteOtoroshiRoute(route).futureValue
}
