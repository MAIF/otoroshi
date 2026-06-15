package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json._

import java.nio.charset.StandardCharsets

class HttpSignatureSignResponseTests(parent: PluginsTestSpec) {
  import parent._

  private val hmacSecret = "this-is-a-32-byte-test-secret!!"
  private val keyid      = "test-shared-secret"

  def default(): Unit = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[HttpSignatureSignResponse],
          config = NgPluginInstanceConfig(
            HttpSignatureSignResponseConfig(
              key = HttpSigKeyInline(hmacSecret, keyid.some, "hmac-sha256".some).some,
              algorithm = "hmac-sha256",
              keyid = keyid,
              components = Seq("@status", "content-type"),
              addContentDigest = false
            ).json.as[JsObject]
          )
        )
      )
    ).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    resp.status mustBe Status.OK
    getOutHeader(resp, "Signature-Input").isDefined mustBe true
    getOutHeader(resp, "Signature").isDefined mustBe true

    deleteOtoroshiRoute(route).futureValue
  }

  def signedResponseIsVerifiable(): Unit = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[HttpSignatureSignResponse],
          config = NgPluginInstanceConfig(
            HttpSignatureSignResponseConfig(
              key = HttpSigKeyInline(hmacSecret, keyid.some, "hmac-sha256".some).some,
              algorithm = "hmac-sha256",
              keyid = keyid,
              components = Seq("@status", "content-type", "content-digest"),
              addContentDigest = true,
              contentDigestAlgorithm = "sha-256"
            ).json.as[JsObject]
          )
        )
      )
    ).futureValue

    val host = route.frontend.domains.head.domain
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders("Host" -> host)
      .get()
      .futureValue

    resp.status mustBe Status.OK
    val sigInputRaw = getOutHeader(resp, "Signature-Input").get
    val sigRaw      = getOutHeader(resp, "Signature").get
    val digestRaw   = getOutHeader(resp, "Content-Digest").get

    // Content-Digest must cover the actual response body.
    HttpSigContentDigest.verify(digestRaw, resp.body[String].getBytes(StandardCharsets.UTF_8)).isRight mustBe true

    val inputs         = HttpSigStructuredFields.parseSignatureInputDict(sigInputRaw).right.get
    val sigs           = HttpSigStructuredFields.parseSignatureDict(sigRaw).right.get.toMap
    val (label, input) = inputs.head
    input.keyid mustBe Some(keyid)
    input.alg mustBe Some("hmac-sha256")

    // Re-derive the canonical signature base from the response Otoroshi sent us and verify the HMAC.
    val respHeaders = resp.headers.toSeq.flatMap { case (k, vs) => vs.map(v => k -> v) }
    val responseMsg = SimpleSigMessage(
      method = "GET",
      fullUri = s"http://$host/api",
      headers = respHeaders,
      status = Some(resp.status)
    )
    val base        = HttpSigBase.build(responseMsg, input, None).right.get
    val verified    = HttpSigAlgorithms.verify(
      "hmac-sha256",
      base.getBytes(StandardCharsets.UTF_8),
      sigs(label),
      Left(hmacSecret.getBytes(StandardCharsets.UTF_8))
    )
    verified.isRight mustBe true

    deleteOtoroshiRoute(route).futureValue
  }
}
