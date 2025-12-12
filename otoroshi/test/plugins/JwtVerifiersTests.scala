package plugins

import functional.PluginsTestSpec
import otoroshi.models._
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{
  JwtVerification,
  JwtVerificationOnly,
  NgJwtVerificationConfig,
  NgJwtVerificationOnlyConfig,
  OverrideHost
}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json.JsObject

class JwtVerifiersTests(parent: PluginsTestSpec) {
  import parent._

  val verifier = GlobalJwtVerifier(
    id = IdGenerator.uuid,
    name = "verifier",
    desc = "verifier",
    strict = true,
    source = InHeader(name = "foo"),
    algoSettings = HSAlgoSettings(256, "secret"),
    strategy = PassThrough(
      verificationSettings = VerificationSettings(Map("iss" -> "foo"))
    )
  )

  val verifier2 = GlobalJwtVerifier(
    id = IdGenerator.uuid,
    name = "verifier2",
    desc = "verifier2",
    strict = true,
    source = InHeader(name = "foo"),
    algoSettings = HSAlgoSettings(512, "secret"),
    strategy = PassThrough(
      verificationSettings = VerificationSettings(Map("iss" -> "foo"))
    )
  )
  createOtoroshiVerifier(verifier).futureValue
  createOtoroshiVerifier(verifier2).futureValue

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        NgPluginHelper.pluginId[JwtVerification],
        config = NgPluginInstanceConfig(
          NgJwtVerificationConfig(
            verifiers = Seq(verifier2.id, verifier.id)
          ).json.as[JsObject]
        )
      )
    )
  ).futureValue

  val token256    =
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJmb28iLCJpYXQiOjE3NjAxMDM1MzN9.TAj08m-Ax3dUFrZ2NU3oG3tPdIFOGvJdpO3Yhas63rw"
  val token512    =
    "eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJmb28iLCJpYXQiOjE3NjAxMDQ1MDN9.EWLHg8HQimFAhKnaUZ1C_1vYEjSbFuLgErRzHQ2tMTeHFoWwIws52GmhXoCBGx37viQcGqRLRtBv2me8oRd6BA"
  val wrongSecret =
    "eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJmb28iLCJpYXQiOjE3NjAxMDM5NDl9.M0Wc2Vt4-W7bSGzsplXQVu4oqWvqzxQbP5PJIyUVWrMQ6ba4KERzI4MzlONZFx7y95Z49_dISF6xQQr9hpdAGw"

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain,
        "foo"  -> token256
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
  }

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain,
        "foo"  -> token512
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
  }

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain,
        "foo"  -> wrongSecret
      )
      .get()
      .futureValue

    resp.status mustBe Status.BAD_REQUEST
  }

  deleteOtoroshiVerifier(verifier).futureValue
  deleteOtoroshiVerifier(verifier2).futureValue
  deleteOtoroshiRoute(route).futureValue
}
