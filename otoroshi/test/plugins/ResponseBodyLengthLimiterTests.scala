package plugins

import akka.http.scaladsl.model.headers.RawHeader
import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterJsValueReader, BetterSyntax}
import play.api.http.Status
import play.api.libs.json._

import java.net.SocketException

class ResponseBodyLengthLimiterTests(parent: PluginsTestSpec) {

  import parent._

  def validCall() {
    val message = Json.obj("message" -> "creation done")
    val route   = createLocalRoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ResponseBodyLengthLimiter],
          config = NgPluginInstanceConfig(
            BodyLengthLimiterConfig(
              maxLength = 200L.some,
              fail = false
            ).json.as[JsObject]
          )
        )
      ),
      responseStatus = Status.OK,
      responseHeaders = List(RawHeader("Content-Length", Json.stringify(message).length.toString)),
      result = _ => {
        message
      }
    )

    val resp = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    resp.status mustBe Status.OK

    deleteOtoroshiRoute(route).futureValue
  }

  def tooBigBody() {
    val message = Json.obj("message" -> "creation done")
    val route   = createLocalRoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ResponseBodyLengthLimiter],
          config = NgPluginInstanceConfig(
            BodyLengthLimiterConfig(
              maxLength = 5L.some,
              fail = true
            ).json.as[JsObject]
          )
        )
      ),
      responseStatus = Status.OK,
      responseHeaders = List(RawHeader("Content-Length", Json.stringify(message).length.toString)),
      result = _ => {
        message
      }
    )

    val resp = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .recover { case e: SocketException =>
        true mustBe true
      }
      .futureValue

    deleteOtoroshiRoute(route).futureValue
  }

  def chunkBody() {
    val message =
      "Hello from backend!, Hello from backend!, Hello from backend!, Hello from backend!, Hello from backend!"
    val route   = createLocalRoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ResponseBodyLengthLimiter],
          config = NgPluginInstanceConfig(
            BodyLengthLimiterConfig(
              maxLength = 5L.some,
              fail = false
            ).json.as[JsObject]
          )
        )
      ),
      responseStatus = Status.OK,
      responseHeaders = List(RawHeader("Content-Length", message.length.toString)),
      jsonAPI = false,
      stringResult = _ => message
    )

    val resp = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    println(resp.body)
    resp.body mustBe "Hello"
    deleteOtoroshiRoute(route).futureValue
  }
}
