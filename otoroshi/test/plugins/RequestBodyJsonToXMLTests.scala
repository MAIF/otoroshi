package plugins

import akka.util.ByteString
import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{AdditionalCookieInConfig, JsonToXmlRequest, JsonTransformConfig, OverrideHost}
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterJsValueReader}
import otoroshi.utils.xml.Xml
import play.api.http.Status
import play.api.libs.json._

import scala.xml.Elem

class RequestBodyJsonToXMLTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[JsonToXmlRequest],
        config = NgPluginInstanceConfig(
          JsonTransformConfig().json.as[JsObject]
        )
      )
    )
  ).futureValue

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .post(
      Json.obj(
        "person" -> Json.obj(
          "@id"   -> "123",
          "@name" -> "John Doe",
          "@age"  -> 30,
          "$"     -> "Employee"
        )
      )
    )
    .futureValue

  resp.status mustBe Status.OK

  val rawXml   = ByteString(Json.parse(resp.body).selectAsString("body"))
  val cleanXml = rawXml.utf8String.dropWhile(_.isWhitespace).stripPrefix("\uFEFF")
  val xml      = scala.xml.XML.loadString(cleanXml)

  val personElem = xml.head.asInstanceOf[Elem]
  personElem.attribute("id").get.text mustBe "123"
  personElem.attribute("name").get.text mustBe "John Doe"
  personElem.attribute("age").get.text mustBe "30"

  deleteOtoroshiRoute(route).futureValue
}
