package plugins

import functional.PluginsTestSpec
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.util.ByteString
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{JsonTransformConfig, OverrideHost, XmlToJsonResponse}
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.libs.json.{JsObject, Json}


class ResponseBodyXmlToJsonTests(parent: PluginsTestSpec) {
  import parent.{*, given}

  val route = createLocalRoute(
    Seq(
      NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        NgPluginHelper.pluginId[XmlToJsonResponse],
        config = NgPluginInstanceConfig(
          JsonTransformConfig().json.as[JsObject]
        )
      )
    ),
    responseHeaders = List(RawHeader("Content-Type", "text/xml; charset=UTF-8")),
    stringResult = _ => {
      ByteString(
        """
        |<?xml version="1.0" encoding="UTF-8" ?>
        |     <book category="web" cover="paperback">
        |         <title lang="en">Learning XML</title>
        |         <author>Erik T. Ray</author>
        |         <year>2003</year>
        |         <price>39.95</price>
        |     </book>
        |""".stripMargin,
        "utf-8"
      ).utf8String
    },
    jsonAPI = false,
    responseContentType = "text/xml"
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  Json.parse(resp.body).selectAsOptObject("book").isDefined mustBe true
  Json.parse(resp.body).selectAsObject("book").selectAsString("category") mustBe "web"
  Json.parse(resp.body).selectAsObject("book").selectAsString("cover") mustBe "paperback"
  Json.parse(resp.body).selectAsObject("book").selectAsOptObject("title").isDefined mustBe true
  Json.parse(resp.body).selectAsObject("book").selectAsString("author") mustBe "Erik T. Ray"

  deleteOtoroshiRoute(route).futureValue
}
