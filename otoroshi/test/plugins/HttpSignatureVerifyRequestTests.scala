package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json._

import java.nio.charset.StandardCharsets
import java.util.Base64

class HttpSignatureVerifyRequestTests(parent: PluginsTestSpec) {
  import parent._

  // B.2.5 fixture from RFC 9421 — HMAC-SHA256 with a known shared secret.
  private val hmacSecretB64 =
    "uzvJfB4u3N0Jy4T7NZ75MDVcr8zSTInedJtkgcu46YW4XByzNJjxBdtjUkdJPBtbmHhIDi6pcl8jsasjlTMtDQ=="
  private val hmacSecret    = Base64.getDecoder.decode(hmacSecretB64)
  private val keyid         = "test-shared-secret"

  private def signRequest(
      method: String,
      url: String,
      hostHeader: String,
      components: List[HttpSigStructuredFields.ComponentId] = List(
        HttpSigStructuredFields.ComponentId("@method", Nil),
        HttpSigStructuredFields.ComponentId("@target-uri", Nil)
      ),
      extraHeaders: Seq[(String, String)] = Seq.empty,
      includeAlg: Boolean = true,
      kid: String = keyid
  ): (String, String) = {
    val now    = System.currentTimeMillis() / 1000L
    val params = collection.mutable.ListBuffer.empty[(String, HttpSigStructuredFields.Param)]
    params += ("created"             -> HttpSigStructuredFields.ParamInt(now))
    params += ("keyid"               -> HttpSigStructuredFields.ParamString(kid))
    if (includeAlg) params += ("alg" -> HttpSigStructuredFields.ParamString("hmac-sha256"))
    val sigInput       = HttpSigStructuredFields.SignatureInputValue(components, params.toList)
    val msg            = SimpleSigMessage(
      method = method,
      fullUri = url,
      headers = Seq("Host" -> hostHeader) ++ extraHeaders,
      status = None
    )
    val base           = HttpSigBase.build(msg, sigInput, None).right.get
    val sigBytes       =
      HttpSigAlgorithms.sign("hmac-sha256", base.getBytes(StandardCharsets.UTF_8), Left(hmacSecret)).right.get
    val sigInputHeader = HttpSigStructuredFields.serializeSignatureInputDict(List("sig1" -> sigInput))
    val sigHeader      = HttpSigStructuredFields.serializeSignatureDict(List("sig1" -> sigBytes))
    (sigInputHeader, sigHeader)
  }

  def default(): Unit = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[HttpSignatureVerifyRequest],
          config = NgPluginInstanceConfig(
            HttpSignatureVerifyRequestConfig(
              keys = List(HttpSigKeyInline(s"base64:$hmacSecretB64", keyid.some, "hmac-sha256".some)),
              mandatory = true
            ).json.as[JsObject]
          )
        )
      )
    ).futureValue

    val host                        = route.frontend.domains.head.domain
    val url                         = s"http://$host/api"
    val (sigInputHeader, sigHeader) = signRequest("GET", url, host)

    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host"            -> host,
        "Signature-Input" -> sigInputHeader,
        "Signature"       -> sigHeader
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK

    deleteOtoroshiRoute(route).futureValue
  }

  def mandatoryRejectsUnsigned(): Unit = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[HttpSignatureVerifyRequest],
          config = NgPluginInstanceConfig(
            HttpSignatureVerifyRequestConfig(
              keys = List(HttpSigKeyInline(s"base64:$hmacSecretB64", keyid.some, "hmac-sha256".some)),
              mandatory = true
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

    resp.status mustBe Status.UNAUTHORIZED

    deleteOtoroshiRoute(route).futureValue
  }

  def optionalAllowsUnsigned(): Unit = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[HttpSignatureVerifyRequest],
          config = NgPluginInstanceConfig(
            HttpSignatureVerifyRequestConfig(
              keys = List(HttpSigKeyInline(s"base64:$hmacSecretB64", keyid.some, "hmac-sha256".some)),
              mandatory = false
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

    deleteOtoroshiRoute(route).futureValue
  }

  def rejectsTamperedSignature(): Unit = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[HttpSignatureVerifyRequest],
          config = NgPluginInstanceConfig(
            HttpSignatureVerifyRequestConfig(
              keys = List(HttpSigKeyInline(s"base64:$hmacSecretB64", keyid.some, "hmac-sha256".some)),
              mandatory = true
            ).json.as[JsObject]
          )
        )
      )
    ).futureValue

    val host                        = route.frontend.domains.head.domain
    val url                         = s"http://$host/api"
    val (sigInputHeader, sigHeader) = signRequest("GET", url, host)
    // Flip one byte inside the base64 payload so the dict is still well-formed but the HMAC will not match.
    val openIdx                     = sigHeader.indexOf(":") + 1
    val first                       = sigHeader.charAt(openIdx)
    val flipped                     = if (first == 'A') 'B' else 'A'
    val tampered                    = sigHeader.substring(0, openIdx) + flipped + sigHeader.substring(openIdx + 1)

    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host"            -> host,
        "Signature-Input" -> sigInputHeader,
        "Signature"       -> tampered
      )
      .get()
      .futureValue

    resp.status mustBe Status.UNAUTHORIZED

    deleteOtoroshiRoute(route).futureValue
  }

  def rejectsUnknownKeyid(): Unit = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[HttpSignatureVerifyRequest],
          config = NgPluginInstanceConfig(
            HttpSignatureVerifyRequestConfig(
              keys = List(HttpSigKeyInline(s"base64:$hmacSecretB64", keyid.some, "hmac-sha256".some)),
              mandatory = true
            ).json.as[JsObject]
          )
        )
      )
    ).futureValue

    val host                        = route.frontend.domains.head.domain
    val url                         = s"http://$host/api"
    val (sigInputHeader, sigHeader) = signRequest("GET", url, host, kid = "unknown-key")

    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host"            -> host,
        "Signature-Input" -> sigInputHeader,
        "Signature"       -> sigHeader
      )
      .get()
      .futureValue

    resp.status mustBe Status.UNAUTHORIZED

    deleteOtoroshiRoute(route).futureValue
  }
}
