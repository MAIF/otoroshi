package plugins

import akka.http.scaladsl.model.headers.{HttpCookie, `Set-Cookie`}
import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{AdditionalCookieOutConfig, MissingCookieOut, OverrideHost}
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json._
import play.api.libs.ws.DefaultWSCookie

class MissingCookiesOutTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[MissingCookieOut],
        config = NgPluginInstanceConfig(
          AdditionalCookieOutConfig(
            name = "foo",
            value = "baz",
            domain = PLUGINS_HOST.some
          ).json.as[JsObject]
        )
      )
    )
  )

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> PLUGINS_HOST
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
    resp.cookies.find(_.name == "foo").get.value mustBe "baz"
  }

  val localRoute = createLocalRoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[MissingCookieOut],
        config = NgPluginInstanceConfig(
          AdditionalCookieOutConfig(
            name = "foo",
            value = "baz",
            domain = "missing.oto.tools".some
          ).json.as[JsObject]
        )
      )
    ),
    domain = "missing.oto.tools",
    responseHeaders = List(
      `Set-Cookie`(cookie =
        HttpCookie(
          name = "foo",
          value = "bar",
          domain = "missing.oto.tools".some
        )
      )
    )
  )

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withCookies(
        DefaultWSCookie(
          name = "foo",
          value = "bar",
          domain = "missing.oto.tools".some
        )
      )
      .withHttpHeaders(
        "Host" -> "missing.oto.tools"
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
    resp.cookies.find(_.name == "foo").get.value mustBe "bar"

    deleteOtoroshiRoute(localRoute).futureValue
    deleteOtoroshiRoute(route).futureValue
  }
}
