package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{NgSecurityTxt, NgSecurityTxtConfig, OverrideHost}
import otoroshi.security.IdGenerator
import play.api.http.Status
import play.api.libs.json.JsObject

class SecurityTxtTests(parent: PluginsTestSpec) {
  import parent._

  def test(config: NgSecurityTxtConfig, expected: Seq[String]) = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[NgSecurityTxt],
          config = NgPluginInstanceConfig(config.json.as[JsObject])
        )
      ),
      id = IdGenerator.uuid
    ).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port/.well-known/security.txt")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
    expected.foreach(str => resp.body.contains(str) mustBe true)

    deleteOtoroshiRoute(route).futureValue
  }

  test(
    NgSecurityTxtConfig(
      contact = Seq("mailto:security@example.com")
    ),
    Seq("mailto:security@example.com")
  )

  test(
    NgSecurityTxtConfig(
      contact = Seq("mailto:security@example.com", "https://example.com/security-contact"),
      expires = Some("2026-12-31T23:59:59Z"),
      policy = Some("https://example.com/security-policy")
    ),
    Seq(
      "mailto:security@example.com",
      "https://example.com/security-contact",
      "https://example.com/security-policy"
    )
  )

  test(
    NgSecurityTxtConfig(
      contact = Seq(
        "mailto:security@example.com",
        "https://example.com/security"
      ),
      expires = Some("2026-01-01T00:00:00Z"),
      acknowledgments = Some("https://example.com/hall-of-fame"),
      preferredLanguages = Some("en, fr, es"),
      policy = Some("https://example.com/security-policy"),
      hiring = Some("https://example.com/jobs/security"),
      encryption = Some("https://example.com/pgp-key.txt"),
      csaf = Some("https://example.com/.well-known/csaf/provider-metadata.json")
    ),
    Seq(
      "https://example.com/hall-of-fame",
      "https://example.com/security-policy",
      "https://example.com/jobs/security",
      "https://example.com/pgp-key.txt",
      "https://example.com/.well-known/csaf/provider-metadata.json"
    )
  )
}
