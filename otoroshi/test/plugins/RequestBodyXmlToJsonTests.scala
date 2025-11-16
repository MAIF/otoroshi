package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{OverrideHost, XmlToJsonRequest}
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.libs.json.Json

class RequestBodyXmlToJsonTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(NgPluginHelper.pluginId[XmlToJsonRequest])
    )
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withHttpHeaders(
      "Host"         -> route.frontend.domains.head.domain,
      "Content-Type" -> "text/xml"
    )
    .post("""
        |<?xml version="1.0" encoding="UTF-8" ?>
        |<book category="web" cover="paperback">
        |   <title lang="en">Learning XML</title>
        | </book>
        |""".stripMargin)
    .futureValue

  val body = Json.parse(resp.body).selectAsObject("body")

  body.selectAsOptObject("book").isDefined mustBe true
  body.selectAsObject("book").selectAsString("category") mustBe "web"
  body.selectAsObject("book").selectAsString("cover") mustBe "paperback"
  body.selectAsObject("book").selectAsOptObject("title").isDefined mustBe true

  deleteOtoroshiRoute(route).futureValue
}
