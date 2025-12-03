package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{NgRedirectionSettings, OverrideHost, Redirection}
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class RedirectionTests(parent: PluginsTestSpec) {

  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[Redirection],
        config = NgPluginInstanceConfig(
          NgRedirectionSettings(
            code = 301,
            to = "http://request.otoroshi.io/foo"
          ).json.as[JsObject]
        )
      )
    )
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withFollowRedirects(false)
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  resp.status mustBe Status.MOVED_PERMANENTLY
  resp.headers("Location").head mustBe "http://request.otoroshi.io/foo"

  deleteOtoroshiRoute(route).futureValue
}
