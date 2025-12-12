package plugins

import akka.http.scaladsl.model.headers.RawHeader
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import functional.PluginsTestSpec
import org.apache.commons.codec.binary.{Base64 => ApacheBase64}
import otoroshi.models.{HSAlgoSettings, SecComVersionV2}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.syntax.implicits.{BetterJsValueReader, BetterSyntax}
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.DurationInt

class OtoroshiChallengeTests(parent: PluginsTestSpec) {

  import parent._

  val route = createLocalRoute(
    Seq(
      NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OtoroshiChallenge],
        config = NgPluginInstanceConfig(
          NgOtoroshiChallengeConfig(
            secComVersion = SecComVersionV2,
            secComTtl = 60000.seconds,
            requestHeaderName = "foo".some,
            responseHeaderName = "bar".some,
            algoOtoToBackend = HSAlgoSettings(256, "secret", false),
            algoBackendToOto = HSAlgoSettings(256, "verysecret", false),
            stateRespLeeway = 5000
          ).json.as[JsObject]
        )
      )
    ),
    rawResult = Some(req => {
      val tokenBody           = req.headers.find(_.name() == "foo").get.value().split("\\.")(1)
      val requestTokenPayload = Json
        .parse(ApacheBase64.decodeBase64(tokenBody))
        .as[JsObject]
      val rawPayload          = requestTokenPayload
        .deepMerge(Json.obj("aud" -> "Otoroshi", "state-resp" -> requestTokenPayload.selectAsString("state")))
      val headerJson          = Json.obj("alg" -> "HS256", "typ" -> "JWT")
      val header              =
        ApacheBase64.encodeBase64URLSafeString(Json.stringify(headerJson).getBytes(StandardCharsets.UTF_8))
      val payload             =
        ApacheBase64.encodeBase64URLSafeString(Json.stringify(rawPayload).getBytes(StandardCharsets.UTF_8))
      val content             = String.format("%s.%s", header, payload)
      val signatureBytes      =
        Algorithm
          .HMAC256("verysecret")
          .sign(
            header.getBytes(StandardCharsets.UTF_8),
            payload.getBytes(StandardCharsets.UTF_8)
          )
      val signature           = ApacheBase64.encodeBase64URLSafeString(signatureBytes)

      val signedToken = s"$content.$signature"
      (200, "", List(RawHeader("bar", signedToken)))
    })
  ).futureValue

  val resp = ws
    .url(s"http://127.0.0.1:$port/api/users")
    .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
    .get()
    .futureValue

  resp.status mustBe Status.OK

  deleteOtoroshiRoute(route).futureValue
}
