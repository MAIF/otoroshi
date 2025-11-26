package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{
  AdditionalCookieIn,
  AdditionalCookieInConfig,
  CookiesValidation,
  CookiesValidationConfig,
  OverrideHost
}
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import play.api.http.Status
import play.api.libs.json._
import play.api.libs.ws.DefaultWSCookie

class CookiesValidationTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[CookiesValidation],
        config = NgPluginInstanceConfig(
          CookiesValidationConfig(
            cookies = Map("foo" -> "bar")
          ).json.as[JsObject]
        )
      )
    )
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .withCookies(
      Seq(
        DefaultWSCookie(
          name = "foo",
          value = "bar",
          domain = route.frontend.domains.head.domain.some,
          httpOnly = true
        )
      ): _*
    )
    .get()
    .futureValue

  resp.status mustBe Status.OK

  val invalidCall = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  invalidCall.status mustBe Status.BAD_REQUEST

  deleteOtoroshiRoute(route).futureValue
}
