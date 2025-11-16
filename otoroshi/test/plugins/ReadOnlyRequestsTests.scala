package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{OverrideHost, ReadOnlyCalls}
import play.api.http.Status

class ReadOnlyRequestsTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(NgPluginHelper.pluginId[ReadOnlyCalls])
    )
  )

  def req() = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )

  req()
    .get()
    .futureValue
    .status mustBe Status.OK

  req()
    .head()
    .futureValue
    .status mustBe Status.OK

  req()
    .options()
    .futureValue
    .status mustBe Status.NO_CONTENT

  req()
    .post("")
    .futureValue
    .status mustBe Status.METHOD_NOT_ALLOWED

  req()
    .patch("")
    .futureValue
    .status mustBe Status.METHOD_NOT_ALLOWED

  deleteOtoroshiRoute(route).futureValue
}
