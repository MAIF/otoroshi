package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class SwaggerPluginConfigsSpec extends PluginConfigFormatSpec {

  "SwaggerUIConfig" should {

    val format  = SwaggerUIConfig.format
    val default = SwaggerUIConfig.default

    val full = SwaggerUIConfig(
      swaggerUrl = "https://api.oto.tools/openapi.json",
      title = "Pets API",
      swaggerUIVersion = "4.15.5",
      filter = false,
      showModels = true,
      displayOperationId = true,
      showExtensions = true,
      layout = "StandaloneLayout",
      sortTags = "none",
      sortOps = "method",
      theme = "monokai"
    )

    // the select options of a field of the config schema
    def schemaOptions(key: String): Seq[String] =
      (SwaggerUIConfig.configSchema.get \ key \ "props" \ "options").as[Seq[JsObject]].map(o => (o \ "value").as[String])

    "serve the bundled swagger ui with no spec url, a filter and alphabetical sorts by default" in {
      default mustBe SwaggerUIConfig(
        swaggerUrl = "",
        title = "API Docs",
        swaggerUIVersion = SwaggerUIConfig.BundledSwaggerUIVersion,
        filter = true,
        showModels = false,
        displayOperationId = false,
        showExtensions = false,
        layout = "BaseLayout",
        sortTags = "alpha",
        sortOps = "alpha",
        theme = "default"
      )
    }

    "write the default config" in {
      format.writes(default) mustBe Json.obj(
        "swagger_url"          -> "",
        "title"                -> "API Docs",
        "swagger_ui_version"   -> SwaggerUIConfig.BundledSwaggerUIVersion,
        "filter"               -> true,
        "show_models"          -> false,
        "display_operation_id" -> false,
        "show_extensions"      -> false,
        "layout"               -> "BaseLayout",
        "sort_tags"            -> "alpha",
        "sort_ops"             -> "alpha",
        "theme"                -> "default"
      )
    }

    "write every field under its snake case key" in {
      format.writes(full) mustBe Json.obj(
        "swagger_url"          -> "https://api.oto.tools/openapi.json",
        "title"                -> "Pets API",
        "swagger_ui_version"   -> "4.15.5",
        "filter"               -> false,
        "show_models"          -> true,
        "display_operation_id" -> true,
        "show_extensions"      -> true,
        "layout"               -> "StandaloneLayout",
        "sort_tags"            -> "none",
        "sort_ops"             -> "method",
        "theme"                -> "monokai"
      )
    }

    "round trip the default config" in {
      roundTrip(format, default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a relative spec url" in {
      roundTrip(format, default.copy(swaggerUrl = "/openapi.json"))
    }

    "round trip every layout" in {
      roundTrip(format, default.copy(layout = "BaseLayout"))
      roundTrip(format, default.copy(layout = "StandaloneLayout"))
    }

    "round trip every tags sort" in {
      roundTrip(format, default.copy(sortTags = "alpha"))
      roundTrip(format, default.copy(sortTags = "none"))
    }

    "round trip every operations sort" in {
      roundTrip(format, default.copy(sortOps = "alpha"))
      roundTrip(format, default.copy(sortOps = "method"))
      roundTrip(format, default.copy(sortOps = "none"))
    }

    "round trip every theme" in {
      SwaggerUIConfig.Themes.foreach(theme => roundTrip(format, default.copy(theme = theme)).theme mustBe theme)
    }

    "round trip a version loaded from the cdn" in {
      roundTrip(format, default.copy(swaggerUIVersion = "3.52.5"))
    }

    "round trip a pre-release version" in {
      roundTrip(format, default.copy(swaggerUIVersion = "5.0.0-alpha.1"))
    }

    "round trip a title holding html" in {
      roundTrip(format, default.copy(title = "<script>alert('x')</script> & co"))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe default
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe default
    }

    "read a json string as the default config, not as the spec url" in {
      readOk(format, JsString("/openapi.json")) mustBe default
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("/openapi.json")) mustBe default
    }

    "read a spec url alone" in {
      readOk(format, Json.obj("swagger_url" -> "/openapi.yaml")) mustBe default.copy(swaggerUrl = "/openapi.yaml")
    }

    "read a theme alone" in {
      readOk(format, Json.obj("theme" -> "dark")) mustBe default.copy(theme = "dark")
    }

    "read show_models alone" in {
      readOk(format, Json.obj("show_models" -> true)) mustBe default.copy(showModels = true)
    }

    "trim the spec url and the title" in {
      val config = readOk(format, Json.obj("swagger_url" -> "  /openapi.json \n", "title" -> "\t Pets API  "))
      config.swaggerUrl mustBe "/openapi.json"
      config.title mustBe "Pets API"
    }

    "read a blank title as the default title" in {
      readOk(format, Json.obj("title" -> "   ")).title mustBe SwaggerUIConfig.DefaultTitle
    }

    "read a blank spec url as no spec url" in {
      readOk(format, Json.obj("swagger_url" -> "   ")).swaggerUrl mustBe ""
    }

    "trim the version before validating it" in {
      readOk(format, Json.obj("swagger_ui_version" -> " 4.15.5 ")).swaggerUIVersion mustBe "4.15.5"
    }

    "fall back to the bundled version when the version is not a plain version" in {
      readOk(format, Json.obj("swagger_ui_version" -> "latest")).swaggerUIVersion mustBe "5.33.1"
      readOk(format, Json.obj("swagger_ui_version" -> "5.x")).swaggerUIVersion mustBe "5.33.1"
      readOk(format, Json.obj("swagger_ui_version" -> "v5.0.0")).swaggerUIVersion mustBe "5.33.1"
    }

    "fall back to the bundled version when the version could point to another package" in {
      readOk(format, Json.obj("swagger_ui_version" -> "5.0.0/../../evil-package")).swaggerUIVersion mustBe "5.33.1"
      readOk(format, Json.obj("swagger_ui_version" -> "5.0.0?x=y")).swaggerUIVersion mustBe "5.33.1"
    }

    "fall back to the base layout for an unknown layout" in {
      readOk(format, Json.obj("layout" -> "FancyLayout")).layout mustBe "BaseLayout"
    }

    "match the layout with its exact case" in {
      readOk(format, Json.obj("layout" -> "standalonelayout")).layout mustBe "BaseLayout"
    }

    "fall back to the alphabetical tags sort for a sort that only applies to operations" in {
      readOk(format, Json.obj("sort_tags" -> "method")).sortTags mustBe "alpha"
    }

    "fall back to the alphabetical operations sort for an unknown sort" in {
      readOk(format, Json.obj("sort_ops" -> "random")).sortOps mustBe "alpha"
    }

    "fall back to the default theme for an unknown theme" in {
      readOk(format, Json.obj("theme" -> "solarized")).theme mustBe "default"
    }

    "match the theme with its exact case" in {
      readOk(format, Json.obj("theme" -> "Dark")).theme mustBe "default"
    }

    "trim the select values before matching them" in {
      val config = readOk(format, Json.obj("theme" -> " dark ", "layout" -> " StandaloneLayout", "sort_ops" -> "none "))
      config.theme mustBe "dark"
      config.layout mustBe "StandaloneLayout"
      config.sortOps mustBe "none"
    }

    "ignore a filter written as a string" in {
      readOk(format, Json.obj("filter" -> "false")).filter mustBe true
    }

    "ignore a show_extensions written as a number" in {
      readOk(format, Json.obj("show_extensions" -> 1)).showExtensions mustBe false
    }

    "ignore a spec url that is not a string" in {
      readOk(format, Json.obj("swagger_url" -> Json.obj("url" -> "/openapi.json"))).swaggerUrl mustBe ""
    }

    "ignore a version written as a number" in {
      readOk(format, Json.obj("swagger_ui_version" -> 5)).swaggerUIVersion mustBe "5.33.1"
    }

    "not read the camel case keys" in {
      readOk(
        format,
        Json.obj(
          "swaggerUrl"         -> "/openapi.json",
          "swaggerUIVersion"   -> "4.15.5",
          "showModels"         -> true,
          "displayOperationId" -> true,
          "sortOps"            -> "none"
        )
      ) mustBe default
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("swagger_url" -> "/openapi.json", "foo" -> "bar")) mustBe default.copy(
        swaggerUrl = "/openapi.json"
      )
    }

    "accept plain and pre-release semantic versions" in {
      SwaggerUIConfig.isValidVersion("5.33.1") mustBe true
      SwaggerUIConfig.isValidVersion("0.0.0") mustBe true
      SwaggerUIConfig.isValidVersion("10.20.30") mustBe true
      SwaggerUIConfig.isValidVersion("5.0.0-rc.1") mustBe true
      SwaggerUIConfig.isValidVersion("5.0.0-beta-2") mustBe true
    }

    "reject versions that are not plain semantic versions" in {
      SwaggerUIConfig.isValidVersion("") mustBe false
      SwaggerUIConfig.isValidVersion("5") mustBe false
      SwaggerUIConfig.isValidVersion("5.1") mustBe false
      SwaggerUIConfig.isValidVersion("05.1.0") mustBe false
      SwaggerUIConfig.isValidVersion("5.1.0+build") mustBe false
      SwaggerUIConfig.isValidVersion("5.1.0-") mustBe false
      SwaggerUIConfig.isValidVersion("5.1.0/x") mustBe false
      SwaggerUIConfig.isValidVersion(" 5.1.0") mustBe false
    }

    "consider the bundled and default versions valid" in {
      SwaggerUIConfig.isValidVersion(SwaggerUIConfig.BundledSwaggerUIVersion) mustBe true
      SwaggerUIConfig.DefaultSwaggerUIVersion mustBe SwaggerUIConfig.BundledSwaggerUIVersion
    }

    "only default to allowed select values" in {
      SwaggerUIConfig.Layouts must contain(default.layout)
      SwaggerUIConfig.TagsSorts must contain(default.sortTags)
      SwaggerUIConfig.OpsSorts must contain(default.sortOps)
      SwaggerUIConfig.Themes must contain(default.theme)
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "list every written key in its config flow and schema" in {
      SwaggerUIConfig.configFlow.toSet mustBe writtenKeys(format, default)
      SwaggerUIConfig.configSchema.get.keys mustBe SwaggerUIConfig.configFlow.toSet
    }

    "offer in its schema exactly the select values the format accepts" in {
      schemaOptions("layout") mustBe SwaggerUIConfig.Layouts
      schemaOptions("sort_tags") mustBe SwaggerUIConfig.TagsSorts
      schemaOptions("sort_ops") mustBe SwaggerUIConfig.OpsSorts
      schemaOptions("theme") mustBe SwaggerUIConfig.Themes
    }

    "be the default config of SwaggerUIPlugin" in {
      defaultConfigReadsBack(new SwaggerUIPlugin(), format) mustBe default
    }

    "be the config flow and schema of SwaggerUIPlugin" in {
      val plugin = new SwaggerUIPlugin()
      plugin.configFlow mustBe SwaggerUIConfig.configFlow
      plugin.configSchema mustBe SwaggerUIConfig.configSchema
    }
  }
}
