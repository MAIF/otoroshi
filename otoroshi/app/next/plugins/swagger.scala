package otoroshi.next.plugins

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import org.apache.commons.lang.StringEscapeUtils

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class SwaggerUIConfig(
    swaggerUrl: String,
    title: String,
    swaggerUIVersion: String,
    filter: Boolean,
    showModels: Boolean,
    displayOperationId: Boolean,
    showExtensions: Boolean,
    layout: String,
    sortTags: String,
    sortOps: String,
    theme: String
) extends NgPluginConfig {
  def json: JsValue = SwaggerUIConfig.format.writes(this)
}

object SwaggerUIConfig {
  val DefaultSwaggerUIVersion = "5.30.2"

  val default: SwaggerUIConfig = SwaggerUIConfig(
    swaggerUrl = "",
    title = "",
    swaggerUIVersion = DefaultSwaggerUIVersion,
    filter = true,
    showModels = false,
    displayOperationId = false,
    showExtensions = false,
    layout = "BaseLayout",
    sortTags = "alpha",
    sortOps = "alpha",
    theme = "default"
  )

  val format = new Format[SwaggerUIConfig] {
    override def writes(o: SwaggerUIConfig): JsValue             = Json.obj(
      "swagger_url"          -> o.swaggerUrl,
      "title"                -> o.title,
      "swagger_ui_version"   -> o.swaggerUIVersion,
      "filter"               -> o.filter,
      "show_models"          -> o.showModels,
      "display_operation_id" -> o.displayOperationId,
      "show_extensions"      -> o.showExtensions,
      "layout"               -> o.layout,
      "sort_tags"            -> o.sortTags,
      "sort_ops"             -> o.sortOps,
      "theme"                -> o.theme
    )
    override def reads(json: JsValue): JsResult[SwaggerUIConfig] = Try {
      val version =
        (json \ "swagger_ui_version").asOpt[String].filter(_.nonEmpty).getOrElse(DefaultSwaggerUIVersion)
      SwaggerUIConfig(
        swaggerUrl = (json \ "swagger_url").as[String],
        title = (json \ "title").as[String],
        swaggerUIVersion = version,
        filter = (json \ "filter").asOpt[Boolean].getOrElse(true),
        showModels = (json \ "show_models").asOpt[Boolean].getOrElse(false),
        displayOperationId = (json \ "display_operation_id").asOpt[Boolean].getOrElse(false),
        showExtensions = (json \ "show_extensions").asOpt[Boolean].getOrElse(false),
        layout = (json \ "layout").asOpt[String].getOrElse("BaseLayout"),
        sortTags = (json \ "sort_tags").asOpt[String].getOrElse("alpha"),
        sortOps = (json \ "sort_ops").asOpt[String].getOrElse("alpha"),
        theme = (json \ "theme").asOpt[String].getOrElse("default")
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }

  val configFlow: Seq[String] = Seq(
    "swagger_url",
    "title",
    "swagger_ui_version",
    "theme",
    "layout",
    "sort_ops",
    "sort_tags",
    "show_extensions",
    "filter",
    "show_models",
    "display_operation_id"
  )

  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "swagger_url"          -> Json.obj(
        "type"        -> "string",
        "label"       -> "OpenAPI URL",
        "placeholder" -> "https://your-api.example.com/openapi.json",
        "help"        -> "URL pointing to your OpenAPI JSON or YAML file"
      ),
      "title"                -> Json.obj(
        "type"        -> "string",
        "label"       -> "Page Title",
        "placeholder" -> "API Docs",
        "help"        -> "Title displayed in the browser tab"
      ),
      "swagger_ui_version"   -> Json.obj(
        "type"        -> "string",
        "label"       -> "Swagger UI",
        "placeholder" -> DefaultSwaggerUIVersion,
        "help"        -> s"Swagger UI version to load from unpkg.com CDN (default: $DefaultSwaggerUIVersion)"
      ),
      "filter"               -> Json.obj(
        "type"  -> "bool",
        "label" -> "Filter",
        "help"  -> "Show search/filter field"
      ),
      "show_models"          -> Json.obj(
        "type"  -> "bool",
        "label" -> "Models",
        "help"  -> "Show model schemas"
      ),
      "display_operation_id" -> Json.obj(
        "type"  -> "bool",
        "label" -> "Operation ID",
        "help"  -> "Show operation IDs"
      ),
      "show_extensions"      -> Json.obj(
        "type"  -> "bool",
        "label" -> "Extensions",
        "help"  -> "Show vendor extension fields (x-*)"
      ),
      "layout"               -> Json.obj(
        "type"  -> "select",
        "label" -> "Layout",
        "props" -> Json.obj(
          "options" -> Json.arr(
            Json.obj("label" -> "Base Layout", "value"       -> "BaseLayout"),
            Json.obj("label" -> "Standalone Layout", "value" -> "StandaloneLayout")
          )
        )
      ),
      "sort_tags"            -> Json.obj(
        "type"  -> "select",
        "label" -> "Sort Tags",
        "props" -> Json.obj(
          "options" -> Json.arr(
            Json.obj("label" -> "Alphabetically", "value" -> "alpha"),
            Json.obj("label" -> "Unsorted", "value"       -> "none")
          )
        )
      ),
      "sort_ops"             -> Json.obj(
        "type"  -> "select",
        "label" -> "Sort Ops",
        "props" -> Json.obj(
          "options" -> Json.arr(
            Json.obj("label" -> "Alphabetically", "value" -> "alpha"),
            Json.obj("label" -> "By Method", "value"      -> "method"),
            Json.obj("label" -> "Unsorted", "value"       -> "none")
          )
        )
      ),
      "theme"                -> Json.obj(
        "type"  -> "select",
        "label" -> "Theme",
        "help"  -> "Themes from swagger-ui-themes loaded from unpkg.com CDN",
        "props" -> Json.obj(
          "options" -> Json.arr(
            Json.obj("label" -> "Default", "value"      -> "default"),
            Json.obj("label" -> "Feeling Blue", "value" -> "feeling-blue"),
            Json.obj("label" -> "Flattop", "value"      -> "flattop"),
            Json.obj("label" -> "Material", "value"     -> "material"),
            Json.obj("label" -> "Monokai", "value"      -> "monokai"),
            Json.obj("label" -> "Muted", "value"        -> "muted"),
            Json.obj("label" -> "Newspaper", "value"    -> "newspaper"),
            Json.obj("label" -> "Outline", "value"      -> "outline")
          )
        )
      )
    )
  )
}

class SwaggerUIPlugin extends NgBackendCall {

  override def useDelegates: Boolean                       = false
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Swagger UI"
  override def description: Option[String]                 =
    "Serves a Swagger UI page from a configurable OpenAPI specification URL".some
  override def defaultConfigObject: Option[NgPluginConfig] = SwaggerUIConfig.default.some
  override def noJsForm: Boolean                           = true

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Documentation"))
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)

  override def configFlow: Seq[String]        = SwaggerUIConfig.configFlow
  override def configSchema: Option[JsObject] = SwaggerUIConfig.configSchema

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {

    ctx.cachedConfig(internalName)(SwaggerUIConfig.format) match {
      case Some(config) if config.swaggerUrl.nonEmpty && config.title.nonEmpty =>
        if (!isValidUrl(config.swaggerUrl)) {
          inMemoryBodyResponse(
            400,
            Map("Content-Type" -> "application/json"),
            Json
              .obj(
                "error"   -> "invalid_url",
                "message" -> s"Invalid swagger URL: ${config.swaggerUrl}, only HTTP and HTTPS protocols are allowed"
              )
              .stringify
              .byteString
          ).future
        } else {
          val htmlContent = generateSwaggerHTML(config)
          inMemoryBodyResponse(
            200,
            Map(
              "Content-Type"  -> "text/html; charset=utf-8",
              "Cache-Control" -> "no-cache, no-store, must-revalidate"
            ),
            htmlContent.byteString
          ).future
        }
      case _                                                                   =>
        inMemoryBodyResponse(
          400,
          Map("Content-Type" -> "application/json"),
          Json
            .obj(
              "error"   -> "invalid_configuration",
              "message" -> "Plugin configuration is incomplete, both 'swagger_url' and 'title' are required"
            )
            .stringify
            .byteString
        ).future
    }
  }

  private def isValidUrl(url: String): Boolean = {
    Try {
      val u = new java.net.URL(url)
      u.getProtocol == "http" || u.getProtocol == "https"
    }.getOrElse(false)
  }

  private def generateSwaggerHTML(config: SwaggerUIConfig): String = {
    val modelsDepth = if (config.showModels) 1 else -1

    val operationsSorter = config.sortOps match {
      case "alpha"  => """"alpha""""
      case "method" => """"method""""
      case _        => "undefined"
    }

    val tagsSorter = config.sortTags match {
      case "alpha" => """"alpha""""
      case _       => "undefined"
    }

    // Escape values to prevent XSS - use escapeHtml for values injected in HTML context
    val safeTitle      = StringEscapeUtils.escapeHtml(config.title)
    val safeSwaggerUrl = StringEscapeUtils.escapeHtml(config.swaggerUrl)
    val safeVersion    = StringEscapeUtils.escapeHtml(config.swaggerUIVersion)
    val safeLayout     = StringEscapeUtils.escapeHtml(config.layout)
    val safeTheme      = StringEscapeUtils.escapeHtml(config.theme)

    val themeLink = if (config.theme.nonEmpty && config.theme != "default") {
      s"""    <link rel="stylesheet" type="text/css" href="https://unpkg.com/swagger-ui-themes/themes/3.x/theme-$safeTheme.css">"""
    } else {
      ""
    }

    s"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>$safeTitle</title>
    <link rel="stylesheet" type="text/css" href="https://unpkg.com/swagger-ui-dist@$safeVersion/swagger-ui.css">
$themeLink
    <style>
        html {
            box-sizing: border-box;
            overflow: -moz-scrollbars-vertical;
            overflow-y: scroll;
        }
        *, *:before, *:after {
            box-sizing: inherit;
        }
        body {
            margin: 0;
            padding: 0;
        }
    </style>
</head>
<body>
    <div id="swagger-ui"></div>
    <script src="https://unpkg.com/swagger-ui-dist@$safeVersion/swagger-ui-bundle.js"></script>
    <script src="https://unpkg.com/swagger-ui-dist@$safeVersion/swagger-ui-standalone-preset.js"></script>
    <script>
        window.onload = function() {
            window.ui = SwaggerUIBundle({
                url: "$safeSwaggerUrl",
                dom_id: '#swagger-ui',
                deepLinking: true,
                filter: ${config.filter},
                defaultModelsExpandDepth: $modelsDepth,
                displayOperationId: ${config.displayOperationId},
                showExtensions: ${config.showExtensions},
                operationsSorter: $operationsSorter,
                tagsSorter: $tagsSorter,
                presets: [
                    SwaggerUIBundle.presets.apis,
                    SwaggerUIStandalonePreset
                ],
                plugins: [
                    SwaggerUIBundle.plugins.DownloadUrl
                ],
                layout: "$safeLayout"
            });
        };
    </script>
</body>
</html>"""
  }
}
