package plugins

import functional.PluginsTestSpec
import otoroshi.models.{GlobalJwtVerifier, HSAlgoSettings, InHeader, PassThrough, VerificationSettings}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{
  JwtVerificationOnly,
  NgJwtVerificationOnlyConfig,
  NgSecurityTxt,
  NgSecurityTxtConfig,
  OverrideHost
}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json.JsObject

class JwtVerificationOnlyTests(parent: PluginsTestSpec) {
  import parent._

  def withToken() = {
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
    createOtoroshiVerifier(verifier).futureValue

    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          NgPluginHelper.pluginId[JwtVerificationOnly],
          config = NgPluginInstanceConfig(
            NgJwtVerificationOnlyConfig(
              verifier = verifier.id.some,
              failIfAbsent = true
            ).json.as[JsObject]
          )
        )
      )
    )

    {
      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain,
          "foo"  -> "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJmb28iLCJpYXQiOjE3NjAxMDM1MzN9.TAj08m-Ax3dUFrZ2NU3oG3tPdIFOGvJdpO3Yhas63rw"
        )
        .get()
        .futureValue

      resp.status mustBe Status.OK
    }

    deleteOtoroshiVerifier(verifier).futureValue
    deleteOtoroshiRoute(route).futureValue
  }

  def withoutToken() = {
    val verifier = GlobalJwtVerifier(
      id = IdGenerator.uuid,
      name = "verifier",
      desc = "verifier",
      strict = true,
      source = InHeader(name = "foo"),
      algoSettings = HSAlgoSettings(512, "secret"),
      strategy = PassThrough(
        verificationSettings = VerificationSettings(Map("iss" -> "foo"))
      )
    )
    createOtoroshiVerifier(verifier).futureValue

    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          NgPluginHelper.pluginId[JwtVerificationOnly],
          config = NgPluginInstanceConfig(
            NgJwtVerificationOnlyConfig(
              verifier = verifier.id.some,
              failIfAbsent = true
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
      .get()
      .futureValue

    resp.status mustBe Status.BAD_REQUEST

    deleteOtoroshiVerifier(verifier).futureValue
    deleteOtoroshiRoute(route).futureValue
  }

  def withoutVerifier() = {
    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          NgPluginHelper.pluginId[JwtVerificationOnly],
          config = NgPluginInstanceConfig(
            NgJwtVerificationOnlyConfig(
              verifier = None,
              failIfAbsent = true
            ).json.as[JsObject]
          )
        )
      )
    )

    {
      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain
        )
        .get()
        .futureValue

      resp.status mustBe Status.BAD_REQUEST
    }

    deleteOtoroshiRoute(route).futureValue
  }
}
