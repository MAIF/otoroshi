package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{NgGenericAllowedList, NgGenericListConfig, OverrideHost}
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json.JsObject

class GenericAllowedListTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgGenericAllowedList],
        config = NgPluginInstanceConfig(
          NgGenericListConfig(
            expression = "${req.headers.foo}".some,
            values = Seq("bar", "baz")
          ).json.as[JsObject]
        )
      )
    )
  ).futureValue

  val authorizedCall = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain,
      "foo"  -> "bar"
    )
    .get()
    .futureValue

  authorizedCall.status mustBe Status.OK

  val unauthorizedCall = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain,
      "foo"  -> "bbar"
    )
    .get()
    .futureValue

  unauthorizedCall.status mustBe Status.FORBIDDEN

  val unauthorizedCallMissingHeader = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  unauthorizedCallMissingHeader.status mustBe Status.FORBIDDEN

  deleteOtoroshiRoute(route).futureValue
}
