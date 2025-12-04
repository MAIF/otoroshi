package plugins

import akka.http.scaladsl.model.headers.{`Set-Cookie`, HttpCookie}
import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{AdditionalCookieOutConfig, MissingCookieOut, OverrideHost}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json._
import play.api.libs.ws.DefaultWSCookie

class MissingCookiesOutTests(parent: PluginsTestSpec) {
  import parent._

  val id    = IdGenerator.uuid
  val route = createRouteWithExternalTarget(
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
            domain = s"$id.oto.tools".some
          ).json.as[JsObject]
        )
      )
    ),
    id = id
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
    rawDomain = "missing.oto.tools".some,
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
