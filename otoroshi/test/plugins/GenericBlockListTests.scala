package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{NgGenericAllowedList, NgGenericBlockList, NgGenericListConfig, OverrideHost}
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json.JsObject

class GenericBlockListTests(parent: PluginsTestSpec) {

  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgGenericBlockList],
        config = NgPluginInstanceConfig(
          NgGenericListConfig(
            expression = "${req.headers.foo}".some,
            values = Seq("baz")
          ).json.as[JsObject]
        )
      )
    )
  )

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
      "foo"  -> "baz"
    )
    .get()
    .futureValue

  unauthorizedCall.status mustBe Status.FORBIDDEN

  val callMissingHeader = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  callMissingHeader.status mustBe Status.OK

  deleteOtoroshiRoute(route).futureValue
}
