package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{AdditionalCookieOutConfig, MissingCookieIn, OverrideHost}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import play.api.http.Status
import play.api.libs.json._
import play.api.libs.ws.DefaultWSCookie

class MissingCookiesInTests(parent: PluginsTestSpec) {
  import parent._

  val id    = IdGenerator.uuid
  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[MissingCookieIn],
        config = NgPluginInstanceConfig(
          AdditionalCookieOutConfig(
            name = "foo",
            value = "baz",
            domain = s"$id.oto.tools".some
          ).json.as[JsObject]
        )
      )
    ),
    id = id,
    domain = s"$id.oto.tools".some
  )

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
    val cookies = Json
      .parse(resp.body)
      .as[JsValue]
      .select("cookies")
      .as[Map[String, String]]

    cookies.get("foo") mustBe Some("baz")
  }

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withCookies(
        DefaultWSCookie(
          name = "foo",
          value = "bar",
          domain = route.frontend.domains.head.domain.some
        )
      )
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
    val cookies = Json
      .parse(resp.body)
      .as[JsValue]
      .select("cookies")
      .as[Map[String, String]]

    cookies.get("foo") mustBe Some("bar")
  }

  deleteOtoroshiRoute(route).futureValue
}
