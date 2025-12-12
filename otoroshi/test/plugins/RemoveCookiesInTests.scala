package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{OverrideHost, RemoveCookiesIn, RemoveCookiesInConfig}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.DefaultWSCookie

class RemoveCookiesInTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        NgPluginHelper.pluginId[RemoveCookiesIn],
        config = NgPluginInstanceConfig(
          RemoveCookiesInConfig(
            names = Seq("foo")
          ).json.as[JsObject]
        )
      )
    ),
    id = IdGenerator.uuid
  ).futureValue

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withCookies(
      Seq(
        DefaultWSCookie(name = "foo", value = "bar", domain = route.frontend.domains.head.domain.some),
        DefaultWSCookie(
          name = "baz",
          value = "bar",
          domain = route.frontend.domains.head.domain.some
        )
      ): _*
    )
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  val cookies = Json
    .parse(resp.body)
    .as[JsValue]
    .select("cookies")
    .as[Map[String, String]]

  cookies.get("foo") mustBe None
  cookies.get("baz") mustBe Some("bar")

  deleteOtoroshiRoute(route).futureValue
}
