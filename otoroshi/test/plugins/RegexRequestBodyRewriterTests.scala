package plugins

import functional.PluginsTestSpec
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time.{Seconds, Span}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{
  NgTrafficMirroring,
  NgTrafficMirroringConfig,
  OverrideHost,
  RegexBodyRewriterConfig,
  RegexReplacementRule,
  RegexRequestBodyRewriter
}
import otoroshi.plugins.mirror.MirroringPluginConfig
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.http.Status
import play.api.libs.json._

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Promise

class RegexRequestBodyRewriterTests(parent: PluginsTestSpec) {

  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[RegexRequestBodyRewriter],
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
    )
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .post(Json.obj("foo" -> "bar"))
    .futureValue

  Json.parse(resp.body).selectAsObject("body") mustBe Json.obj("bar" -> "bar")

  resp.status mustBe Status.OK
  deleteOtoroshiRoute(route).futureValue
}
