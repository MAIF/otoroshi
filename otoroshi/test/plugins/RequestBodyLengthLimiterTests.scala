package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.{BetterJsValueReader, BetterSyntax}
import play.api.http.Status
import play.api.libs.json._

import java.net.SocketException

class RequestBodyLengthLimiterTests(parent: PluginsTestSpec) {

  import parent._

  def validCall() {
    val id    = IdGenerator.uuid
    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[RequestBodyLengthLimiter],
          config = NgPluginInstanceConfig(
            BodyLengthLimiterConfig(
              maxLength = 200L.some,
              fail = false
            ).json.as[JsObject]
          )
        )
      ),
      domain = s"$id.oto.tools".some,
      id
    )

    val resp = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .post("Hello from client!")
      .futureValue

    resp.status mustBe Status.OK

    deleteOtoroshiRoute(route).futureValue
  }

  def tooBigBody() {
    val id    = IdGenerator.uuid
    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[RequestBodyLengthLimiter],
          config = NgPluginInstanceConfig(
            BodyLengthLimiterConfig(
              maxLength = 5L.some,
              fail = true
            ).json.as[JsObject]
          )
        )
      ),
      domain = s"$id.oto.tools".some,
      id
    )

    ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .post("Hello from client!")
      .recover { case e: SocketException =>
        true mustBe true
      }
      .futureValue

    deleteOtoroshiRoute(route).futureValue
  }

  def chunkBody() {
    val id    = IdGenerator.uuid
    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[RequestBodyLengthLimiter],
          config = NgPluginInstanceConfig(
            BodyLengthLimiterConfig(
              maxLength = 5L.some,
              fail = false
            ).json.as[JsObject]
          )
        )
      ),
      domain = s"$id.oto.tools".some,
      id
    )

    val resp = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .post("Hello from client!")
      .futureValue

    Json.parse(resp.body).selectAsString("body") mustBe "Hello"
    deleteOtoroshiRoute(route).futureValue
  }
}
