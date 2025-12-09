package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{
  OverrideHost,
  RegexBodyRewriterConfig,
  RegexReplacementRule,
  RegexRequestBodyRewriter,
  RegexResponseBodyRewriter
}
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.http.Status
import play.api.libs.json._

class RegexResponseBodyRewriterTests(parent: PluginsTestSpec) {

  import parent._

  val route = createLocalRoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[RegexResponseBodyRewriter],
        config = NgPluginInstanceConfig(
          RegexBodyRewriterConfig(
            contentTypes = Seq("application/json"),
            rules = Seq(
              RegexReplacementRule(
                pattern = "foo",
                replacement = "bar",
                flags = Some("i")
              )
            )
          ).json.as[JsObject]
        )
      )
    ),
    result = _ => Json.obj("foo" -> "bar")
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .post(Json.obj("foo" -> "bar"))
    .futureValue

  Json.parse(resp.body) mustBe Json.obj("bar" -> "bar")

  resp.status mustBe Status.OK
  deleteOtoroshiRoute(route).futureValue
}
