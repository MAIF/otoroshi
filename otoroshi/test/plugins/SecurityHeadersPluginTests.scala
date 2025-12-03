package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.CspMode.ENABLED
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins._
import play.api.libs.json._

class SecurityHeadersPluginTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[SecurityHeadersPlugin],
        config = NgPluginInstanceConfig(
          SecurityHeadersPluginConfig(
            frameOptions = FrameOptions.SAMEORIGIN,
            xssProtection = XssProtection.BLOCK,
            contentTypeOptions = true,
            hsts = HstsConf(
              enabled = true,
              includeSubdomains = true,
              maxAge = 1000,
              preload = true,
              onHttp = true
            ),
            csp = CspConf(ENABLED, "default-src none; script-src self; connect-src self; img-src self; style-src self;")
          ).json.as[JsObject]
        )
      )
    )
  )

  def call(): Map[String, String] = {
    ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue
      .headers
      .mapValues(_.last)
  }

  val headers = call()

  headers("X-Frame-Options") mustBe "SAMEORIGIN"
  headers("X-XSS-Protection") mustBe "1; mode=block"
  headers("X-Content-Type-Options") mustBe "nosniff"
  headers("Strict-Transport-Security") mustBe "max-age=1000; includeSubDomains; preload"
  headers(
    "Content-Security-Policy"
  ) mustBe "default-src none; script-src self; connect-src self; img-src self; style-src self;"

  deleteOtoroshiRoute(route).futureValue
}
