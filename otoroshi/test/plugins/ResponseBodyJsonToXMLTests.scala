package plugins

import akka.util.ByteString
import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{JsonToXmlResponse, JsonTransformConfig, OverrideHost}
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.http.Status
import play.api.libs.json._

import scala.xml.Elem

class ResponseBodyJsonToXMLTests(parent: PluginsTestSpec) {

  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[JsonToXmlResponse],
        config = NgPluginInstanceConfig(
          JsonTransformConfig().json.as[JsObject]
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

  resp.status mustBe Status.OK

  val cleanXml =
    ByteString(s"<request>${resp.body}</request>").utf8String.dropWhile(_.isWhitespace).stripPrefix("\uFEFF")
  val xml      = scala.xml.XML.loadString(cleanXml)

  val item = xml.head.asInstanceOf[Elem]
  item.child.find(_.text.contains("method"))

  deleteOtoroshiRoute(route).futureValue
}
