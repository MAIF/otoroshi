package plugins

import functional.PluginsTestSpec
import org.apache.commons.codec.binary.{Base64 => ApacheBase64}
import otoroshi.models.{DefaultToken, GlobalJwtVerifier, HSAlgoSettings, InHeader}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{JwtSigner, NgJwtSignerConfig, OverrideHost}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.{BetterJsValueReader, BetterSyntax}
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class JwtSignerTests(parent: PluginsTestSpec) {
  import parent._

  def default() = {
    val verifier = GlobalJwtVerifier(
      id = "verifier",
      name = "verifier",
      desc = "verifier",
      strict = true,
      source = InHeader(name = "X-JWT-Token"),
      algoSettings = HSAlgoSettings(512, "secret"),
      strategy = DefaultToken(
        strict = true,
        token = Json.obj("iss" -> "foo")
      )
    )
    createOtoroshiVerifier(verifier).futureValue

    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          NgPluginHelper.pluginId[JwtSigner],
          config = NgPluginInstanceConfig(
            NgJwtSignerConfig(
              verifier = verifier.id.some,
              replaceIfPresent = true,
              failIfPresent = false
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

    val tokenBody = getInHeader(resp, "x-jwt-token").get.split("\\.")(1)
    Json.parse(ApacheBase64.decodeBase64(tokenBody)).as[JsObject].selectAsString("iss") mustBe "foo"

    deleteOtoroshiVerifier(verifier).futureValue
    deleteOtoroshiRoute(route).futureValue
  }

  def shouldNotReplaceTheIncomingToken() = {
    val verifier = GlobalJwtVerifier(
      id = IdGenerator.uuid,
      name = "verifier",
      desc = "verifier",
      strict = true,
      source = InHeader(name = "X-JWT-Token"),
      algoSettings = HSAlgoSettings(512, "secret"),
      strategy = DefaultToken(
        strict = true,
        token = Json.obj("iss" -> "bar")
      )
    )
    createOtoroshiVerifier(verifier).futureValue

    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          NgPluginHelper.pluginId[JwtSigner],
          config = NgPluginInstanceConfig(
            NgJwtSignerConfig(
              verifier = verifier.id.some,
              replaceIfPresent = false,
              failIfPresent = false
            ).json.as[JsObject]
          )
        )
      )
    )

    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host"        -> route.frontend.domains.head.domain,
        "x-jwt-token" -> "eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJmb28iLCJpYXQiOjE3NjAxMDY2OTZ9.bI7ghu2LG9k0s4QXPBlunwFk8TlHeUVyDF6Kv4Xfa8KF-3WXqORlJdW5o8NcY1tcs9UXvUw4TeRrS_QoZhvooQ"
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK

    val tokenBody = getInHeader(resp, "x-jwt-token").get.split("\\.")(1)
    Json.parse(ApacheBase64.decodeBase64(tokenBody)).as[JsObject].selectAsString("iss") mustBe "foo"

    deleteOtoroshiVerifier(verifier).futureValue
    deleteOtoroshiRoute(route).futureValue
  }
}
