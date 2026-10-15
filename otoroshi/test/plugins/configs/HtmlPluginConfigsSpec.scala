package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

/**
 * NgHtmlPatcherConfig has no format: the plugin reads its config inline, out of the request context,
 * so only the written json and the default config of the plugin are pinned here.
 */
class HtmlPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgHtmlPatcherConfig" should {

    val full = NgHtmlPatcherConfig(
      appendHead = Seq("<script src=\"/end-of-head.js\"></script>"),
      appendBody = Seq("<div id=\"footer\">${req.host}</div>"),
      prependHead = Seq("<meta name=\"x\" content=\"y\">"),
      prependBody = Seq("<div id=\"banner\">été</div>")
    )

    "inject nothing by default" in {
      NgHtmlPatcherConfig() mustBe NgHtmlPatcherConfig(Seq.empty, Seq.empty, Seq.empty, Seq.empty)
    }

    "write the default config as four empty arrays with snake case keys" in {
      NgHtmlPatcherConfig().json mustBe Json.obj(
        "append_head"  -> Json.arr(),
        "append_body"  -> Json.arr(),
        "prepend_head" -> Json.arr(),
        "prepend_body" -> Json.arr()
      )
    }

    "write each list of snippets under its own key" in {
      full.json mustBe Json.obj(
        "append_head"  -> Json.arr("<script src=\"/end-of-head.js\"></script>"),
        "append_body"  -> Json.arr("<div id=\"footer\">${req.host}</div>"),
        "prepend_head" -> Json.arr("<meta name=\"x\" content=\"y\">"),
        "prepend_body" -> Json.arr("<div id=\"banner\">été</div>")
      )
    }

    "write the same keys whatever the content" in {
      NgHtmlPatcherConfig().json.as[JsObject].keys mustBe full.json.as[JsObject].keys
    }

    "write the snippets in their order, duplicates included" in {
      (NgHtmlPatcherConfig(appendBody = Seq("<b>", "<a>", "<b>")).json \ "append_body").as[Seq[String]] mustBe Seq(
        "<b>",
        "<a>",
        "<b>"
      )
    }

    "write empty snippets as empty strings" in {
      (NgHtmlPatcherConfig(prependHead = Seq("")).json \ "prepend_head").as[JsArray] mustBe Json.arr("")
    }

    "not write camel case keys" in {
      full.json.as[JsObject].keys must contain noneOf ("appendHead", "appendBody", "prependHead", "prependBody")
    }

    "be the default config object of NgHtmlPatcher" in {
      new NgHtmlPatcher().defaultConfigObject mustBe Some(NgHtmlPatcherConfig())
    }

    "be the default config json of NgHtmlPatcher" in {
      new NgHtmlPatcher().defaultConfig mustBe Some(NgHtmlPatcherConfig().json.as[JsObject])
    }
  }
}
