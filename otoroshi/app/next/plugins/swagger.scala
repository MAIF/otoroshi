package otoroshi.next.plugins

import org.apache.pekko.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api.*
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import org.apache.commons.text.StringEscapeUtils
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.util.ByteString

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

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

  // version of the swagger-ui-dist files shipped in public/swagger-ui, served by otoroshi itself
  val BundledSwaggerUIVersion = "5.33.1"
  val DefaultSwaggerUIVersion = BundledSwaggerUIVersion
  val DefaultTitle            = "API Docs"
  val SwaggerUIThemesVersion  = "3.0.1"

  val Layouts: Seq[String]      = Seq("BaseLayout", "StandaloneLayout")
  val TagsSorts: Seq[String]    = Seq("alpha", "none")
  val OpsSorts: Seq[String]     = Seq("alpha", "method", "none")
  val Themes: Seq[String]       =
    Seq("default", "dark", "feeling-blue", "flattop", "material", "monokai", "muted", "newspaper", "outline")

  // only a plain version may be appended to the cdn url: anything else could point the page to another package
  private val VersionPattern = """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$""".r

  def isValidVersion(version: String): Boolean = VersionPattern.matches(version)

  val default: SwaggerUIConfig = SwaggerUIConfig(
    swaggerUrl = "",
    title = DefaultTitle,
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
    override def writes(o: SwaggerUIConfig): JsValue = Json.obj(
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

    // unknown or invalid values fall back to the defaults, so the generated page only ever contains known values
    override def reads(json: JsValue): JsResult[SwaggerUIConfig] = {
      def str(key: String): Option[String]                       = (json \ key).asOpt[String].map(_.trim).filter(_.nonEmpty)
      def oneOf(key: String, allowed: Seq[String], fallback: String) = str(key).filter(allowed.contains).getOrElse(fallback)
      JsSuccess(
        SwaggerUIConfig(
          swaggerUrl = str("swagger_url").getOrElse(""),
          title = str("title").getOrElse(default.title),
          swaggerUIVersion = str("swagger_ui_version").filter(isValidVersion).getOrElse(default.swaggerUIVersion),
          filter = (json \ "filter").asOpt[Boolean].getOrElse(default.filter),
          showModels = (json \ "show_models").asOpt[Boolean].getOrElse(default.showModels),
          displayOperationId = (json \ "display_operation_id").asOpt[Boolean].getOrElse(default.displayOperationId),
          showExtensions = (json \ "show_extensions").asOpt[Boolean].getOrElse(default.showExtensions),
          layout = oneOf("layout", Layouts, default.layout),
          sortTags = oneOf("sort_tags", TagsSorts, default.sortTags),
          sortOps = oneOf("sort_ops", OpsSorts, default.sortOps),
          theme = oneOf("theme", Themes, default.theme)
        )
      )
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

  private def options(values: (String, String)*): JsObject = Json.obj(
    "options" -> JsArray(values.map { case (label, value) => Json.obj("label" -> label, "value" -> value) })
  )

  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "swagger_url"          -> Json.obj(
        "type"        -> "string",
        "label"       -> "OpenAPI URL",
        "placeholder" -> "/openapi.json",
        "help"        -> "URL of your OpenAPI JSON or YAML file: an HTTP(S) URL, or a reference resolved by the browser against the page URL (/openapi.json starts at the root of the domain). The browser fetches it, so a spec on another domain must allow CORS"
      ),
      "title"                -> Json.obj(
        "type"        -> "string",
        "label"       -> "Page Title",
        "placeholder" -> DefaultTitle,
        "help"        -> s"Title displayed in the browser tab (default: $DefaultTitle)"
      ),
      "swagger_ui_version"   -> Json.obj(
        "type"        -> "string",
        "label"       -> "Swagger UI",
        "placeholder" -> DefaultSwaggerUIVersion,
        "help"        -> s"Swagger UI version. $BundledSwaggerUIVersion is served by Otoroshi itself, any other version (x.y.z) is loaded from the unpkg.com CDN. An invalid value falls back to $BundledSwaggerUIVersion"
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
        "props" -> options("Base Layout" -> "BaseLayout", "Standalone Layout" -> "StandaloneLayout")
      ),
      "sort_tags"            -> Json.obj(
        "type"  -> "select",
        "label" -> "Sort Tags",
        "props" -> options("Alphabetically" -> "alpha", "Unsorted" -> "none")
      ),
      "sort_ops"             -> Json.obj(
        "type"  -> "select",
        "label" -> "Sort Ops",
        "props" -> options("Alphabetically" -> "alpha", "By Method" -> "method", "Unsorted" -> "none")
      ),
      "theme"                -> Json.obj(
        "type"  -> "select",
        "label" -> "Theme",
        "help"  -> "Colors and style of the page: Default is the standard look of Swagger UI, Dark its dark mode, the other themes restyle it",
        "props" -> options(
          "Default"      -> "default",
          "Dark"         -> "dark",
          "Feeling Blue" -> "feeling-blue",
          "Flattop"      -> "flattop",
          "Material"     -> "material",
          "Monokai"      -> "monokai",
          "Muted"        -> "muted",
          "Newspaper"    -> "newspaper",
          "Outline"      -> "outline"
        )
      )
    )
  )
}

object SwaggerUIPlugin {

  val BundledAssetParam   = "__swagger_ui_asset"
  val BundledVersionParam = "__swagger_ui_v"
  val BundledAssets     = Set("swagger-ui.css", "swagger-ui-bundle.js", "swagger-ui-standalone-preset.js")

  private val bundledAssets = new TrieMap[String, ByteString]()

  // read once from the files otoroshi ships in public/swagger-ui, nothing outside the known files
  def bundledAsset(file: String)(using env: Env): Option[ByteString] = {
    if (!BundledAssets.contains(file)) None
    else bundledAssets.get(file).orElse {
      env.environment.resourceAsStream(s"public/swagger-ui/$file").map { stream =>
        val content =
          try ByteString(stream.readAllBytes())
          finally stream.close()
        bundledAssets.putIfAbsent(file, content)
        content
      }
    }
  }
}

class SwaggerUIPlugin extends NgBackendCall {

  import SwaggerUIConfig.*
  import SwaggerUIPlugin.*

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
  )(using
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    ctx.cachedConfig(internalName)(SwaggerUIConfig.format) match {
      case Some(config) if isValidSpecUrl(config.swaggerUrl) =>
        // the request of the browser, not the one transformers may have prepared for a backend
        ctx.rawRequest.getQueryString(BundledAssetParam) match {
          case Some(file) if config.swaggerUIVersion == BundledSwaggerUIVersion =>
            serveBundledAsset(ctx, file)
          case _                                                               =>
            inMemoryBodyResponse(
              200,
              Map(
                "Content-Type"  -> "text/html; charset=utf-8",
                "Cache-Control" -> "no-cache, no-store, must-revalidate"
              ),
              generateSwaggerHTML(config, pageQuery(ctx)).byteString
            ).future
        }
      case _                                                 =>
        // a misconfigured route is not the caller's fault, and the configured url is not echoed back
        inMemoryBodyResponse(
          500,
          Map("Content-Type" -> "application/json"),
          Json
            .obj(
              "error"   -> "invalid_configuration",
              "message" -> "'swagger_url' must be an HTTP(S) URL or a relative path"
            )
            .stringify
            .byteString
        ).future
    }
  }

  // an absolute http(s) url, or a relative reference resolved by the browser against the page url
  private[plugins] def isValidSpecUrl(url: String): Boolean = {
    url.nonEmpty && Try(new java.net.URI(url)).toOption.exists { uri =>
      Option(uri.getScheme).map(_.toLowerCase) match {
        case None                         => true
        // URI finds no host in internationalized names or names with underscores, URL's host parser does
        case Some("http") | Some("https") => Try(uri.toURL.getHost).toOption.exists(host => host != null && host.nonEmpty)
        case Some(_)                      => false
      }
    }
  }

  // the bundled swagger ui files are served by the plugin itself, on the path of the page with a query parameter, so
  // they are reachable wherever the page is: on any listener, on a route matching its path exactly, behind a proxy
  // that strips a path prefix
  private def serveBundledAsset(ctx: NgbBackendCallContext, file: String)(using
      env: Env
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    def error(status: Int, error: String, headers: Map[String, String] = Map.empty) = inMemoryBodyResponse(
      status,
      Map("Content-Type" -> "application/json") ++ headers,
      Json.obj("error" -> error).stringify.byteString
    ).future
    if (!Set("GET", "HEAD").contains(ctx.rawRequest.method.toUpperCase)) {
      error(405, "method_not_allowed", Map("Allow" -> "GET, HEAD"))
    } else {
      SwaggerUIPlugin.bundledAsset(file) match {
        case Some(content) =>
          val contentType =
            if (file.endsWith(".css")) "text/css; charset=utf-8" else "application/javascript; charset=utf-8"
          // the urls of the page carry the bundled version: only those may be cached for good
          val cacheControl =
            if (ctx.rawRequest.getQueryString(BundledVersionParam).contains(BundledSwaggerUIVersion))
              "public, max-age=31536000, immutable"
            else "no-cache"
          inMemoryBodyResponse(200, Map("Content-Type" -> contentType, "Cache-Control" -> cacheControl), content).future
        case None          => error(404, "not_found")
      }
    }
  }

  // the query of the page, that the route may require, without the parameters of the plugin
  private def pageQuery(ctx: NgbBackendCallContext): Seq[(String, String)] = {
    ctx.rawRequest.queryString.toSeq
      .filterNot { case (key, _) => key == BundledAssetParam || key == BundledVersionParam }
      .flatMap { case (key, values) => values.map(key -> _) }
  }

  // a query only url resolves against the url of the page, path included
  private def assetUrl(config: SwaggerUIConfig, pageQuery: Seq[(String, String)], file: String): String = {
    if (config.swaggerUIVersion == BundledSwaggerUIVersion) {
      val query = pageQuery ++ Seq(BundledAssetParam -> file, BundledVersionParam -> BundledSwaggerUIVersion)
      s"?${Uri.Query(query*).toString}"
    } else {
      s"https://unpkg.com/swagger-ui-dist@${config.swaggerUIVersion}/$file"
    }
  }

  // the options are written as json in a non executable script tag. json alone does not escape '<', so a value
  // containing '</script>' would close the tag: '<', '>' and '&' are written as unicode escapes, which JSON.parse reads back
  private def swaggerUIOptions(config: SwaggerUIConfig): String = {
    val operationsSorter = config.sortOps match {
      case "none" => Json.obj()
      case sort   => Json.obj("operationsSorter" -> sort)
    }
    val tagsSorter       = config.sortTags match {
      case "none" => Json.obj()
      case sort   => Json.obj("tagsSorter" -> sort)
    }
    (Json.obj(
      "url"                      -> config.swaggerUrl,
      "deepLinking"              -> true,
      "filter"                   -> config.filter,
      "defaultModelsExpandDepth" -> (if (config.showModels) 1 else -1),
      "displayOperationId"       -> config.displayOperationId,
      "showExtensions"           -> config.showExtensions,
      "layout"                   -> config.layout,
      "validatorUrl"             -> JsNull,
      "queryConfigEnabled"       -> false
    ) ++ operationsSorter ++ tagsSorter).stringify
      .replace("<", "\\u003c")
      .replace(">", "\\u003e")
      .replace("&", "\\u0026")
  }

  private def generateSwaggerHTML(config: SwaggerUIConfig, pageQuery: Seq[(String, String)]): String = {
    val safeTitle                    = StringEscapeUtils.escapeHtml4(config.title)
    def asset(file: String): String = StringEscapeUtils.escapeHtml4(assetUrl(config, pageQuery, file))

    // the dark theme is the dark mode of swagger ui, set on the whole document through a class on the html element
    val htmlClass            = if (config.theme == "dark") """ class="dark-mode"""" else ""
    // the standalone layout has its own dark mode toggle, that would override any theme but the default one
    val darkModeTogglePlugin = if (config.theme != "default") {
      """
            options.plugins.push(function() {
                return { components: { DarkModeToggle: function() { return null; } } };
            });"""
    } else {
      ""
    }

    // the other themes come from swagger-ui-themes
    val themeLink = if (config.theme != "default" && config.theme != "dark") {
      s"""    <link rel="stylesheet" type="text/css" href="https://unpkg.com/swagger-ui-themes@$SwaggerUIThemesVersion/themes/3.x/theme-${config.theme}.css">"""
    } else {
      ""
    }

    s"""<!DOCTYPE html>
<html lang="en"$htmlClass>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>$safeTitle</title>
    <link rel="stylesheet" type="text/css" href="${asset("swagger-ui.css")}">
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
    <script type="application/json" id="swagger-ui-options">${swaggerUIOptions(config)}</script>
    <script src="${asset("swagger-ui-bundle.js")}"></script>
    <script src="${asset("swagger-ui-standalone-preset.js")}"></script>
    <script>
        window.onload = function() {
            var options = JSON.parse(document.getElementById('swagger-ui-options').textContent);
            options.dom_id = '#swagger-ui';
            options.presets = [
                SwaggerUIBundle.presets.apis,
                SwaggerUIStandalonePreset
            ];
            options.plugins = [
                SwaggerUIBundle.plugins.DownloadUrl
            ];$darkModeTogglePlugin
            window.ui = SwaggerUIBundle(options);
        };
    </script>
</body>
</html>"""
  }
}
