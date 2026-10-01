package plugins

import functional.PluginsTestSpecBase
import play.api.libs.ws.WSBodyReadables.given
import play.api.libs.ws.WSBodyWritables.given
import otoroshi.next.models.{NgDomainAndPath, NgPluginInstance, NgPluginInstanceConfig, NgRoute}
import otoroshi.next.plugins.SwaggerUIConfig.BundledSwaggerUIVersion
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{QueryTransformer, QueryTransformerConfig, SwaggerUIConfig, SwaggerUIPlugin}
import play.api.http.Status
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.WSResponse

import scala.concurrent.duration.DurationInt

class SwaggerUIPluginTests(parent: PluginsTestSpecBase) {
  import parent.*

  private def withRoute[A](config: JsObject, plugins: Seq[NgPluginInstance] = Seq.empty)(f: NgRoute => A): A = {
    val route = createRouteWithExternalTarget(
      plugins :+ NgPluginInstance(
        plugin = NgPluginHelper.pluginId[SwaggerUIPlugin],
        config = NgPluginInstanceConfig(config)
      )
    ).futureValue
    try f(route)
    finally deleteOtoroshiRoute(route).futureValue
  }

  private def call(route: NgRoute, path: String = "/docs"): WSResponse = ws
    .url(s"http://127.0.0.1:$port$path")
    .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
    .get()
    .futureValue

  private def config(fields: (String, Json.JsValueWrapper)*): JsObject =
    SwaggerUIConfig.default.copy(swaggerUrl = "https://example.com/openapi.json").json.as[JsObject] ++ Json.obj(fields*)

  // the swagger ui options embedded in the page, as JSON.parse would read them
  private def options(html: String): JsValue = {
    val start = html.indexOf("""<script type="application/json" id="swagger-ui-options">""")
    start must be >= 0
    val from  = html.indexOf('>', start) + 1
    Json.parse(html.substring(from, html.indexOf("</script>", from)))
  }

  // a query only url, html escaped in the page
  private def bundled(file: String): String = s"?__swagger_ui_asset=$file&amp;__swagger_ui_v=$BundledSwaggerUIVersion"

  def run() = {
    defaults()
    customOptions()
    darkTheme()
    specUrls()
    escaping()
    untrustedValues()
    bundledAssets()
    routeQueryConstraint()
    cdnAssets()
  }

  def defaults() = withRoute(config()) { route =>
    val resp = call(route)
    resp.status mustBe Status.OK
    resp.header("Content-Type").get must startWith("text/html")
    val html = resp.body[String]
    html must include(s"<title>${SwaggerUIConfig.DefaultTitle}</title>")
    html must include(s"""href="${bundled("swagger-ui.css")}"""")
    html must include(s"""src="${bundled("swagger-ui-bundle.js")}"""")
    html must include(s"""src="${bundled("swagger-ui-standalone-preset.js")}"""")
    html must not include "unpkg.com"
    html must include("""<html lang="en">""")
    html must not include "DarkModeToggle"
    html must not include "prefers-color-scheme"
    val opts = options(html)
    (opts \ "url").as[String] mustBe "https://example.com/openapi.json"
    (opts \ "layout").as[String] mustBe "BaseLayout"
    (opts \ "filter").as[Boolean] mustBe true
    (opts \ "defaultModelsExpandDepth").as[Int] mustBe -1
    (opts \ "displayOperationId").as[Boolean] mustBe false
    (opts \ "showExtensions").as[Boolean] mustBe false
    (opts \ "operationsSorter").as[String] mustBe "alpha"
    (opts \ "tagsSorter").as[String] mustBe "alpha"
    (opts \ "validatorUrl").toOption mustBe Some(play.api.libs.json.JsNull)
    (opts \ "queryConfigEnabled").as[Boolean] mustBe false
  }

  def customOptions() = withRoute(
    config(
      "title"                -> "My API",
      "swagger_ui_version"   -> "5.30.2",
      "theme"                -> "monokai",
      "layout"               -> "StandaloneLayout",
      "sort_ops"             -> "none",
      "sort_tags"            -> "none",
      "show_models"          -> true,
      "display_operation_id" -> true
    )
  ) { route =>
    val html = call(route).body[String]
    html must include("""<html lang="en">""")
    html must include("DarkModeToggle")
    html must include("<title>My API</title>")
    html must include("""href="https://unpkg.com/swagger-ui-dist@5.30.2/swagger-ui.css"""")
    html must include("""src="https://unpkg.com/swagger-ui-dist@5.30.2/swagger-ui-bundle.js"""")
    html must include(
      s"""href="https://unpkg.com/swagger-ui-themes@${SwaggerUIConfig.SwaggerUIThemesVersion}/themes/3.x/theme-monokai.css""""
    )
    val opts = options(html)
    (opts \ "layout").as[String] mustBe "StandaloneLayout"
    (opts \ "defaultModelsExpandDepth").as[Int] mustBe 1
    (opts \ "displayOperationId").as[Boolean] mustBe true
    (opts \ "operationsSorter").toOption mustBe None
    (opts \ "tagsSorter").toOption mustBe None
  }

  // the dark theme is the dark mode of swagger ui: with the bundled version, nothing is loaded from a cdn
  def darkTheme() = withRoute(config("theme" -> "dark", "layout" -> "StandaloneLayout")) { route =>
    val html = call(route).body[String]
    html must include("""<html lang="en" class="dark-mode">""")
    html must include("DarkModeToggle")
    html must not include "unpkg.com"
  }

  def specUrls() = {
    Seq(
      "/openapi.json",
      "./specs/openapi.yaml",
      "//example.com/openapi.json",
      "HTTPS://example.com/openapi.json",
      "http://api_service/openapi.json",
      "https://éxample.com/openapi.json",
      "https://example.com/openapi.json?v=2&format=json"
    ).foreach { url =>
      withRoute(config("swagger_url" -> url)) { route =>
        val resp = call(route)
        resp.status mustBe Status.OK
        (options(resp.body[String]) \ "url").as[String] mustBe url
      }
    }
    Seq(
      "",
      "javascript:alert(1)",
      "JavaScript:alert(1)",
      "data:text/html,hi",
      "ftp://example.com/openapi.json",
      "http:/openapi.json",
      "http:openapi.json",
      "\\\\example.com\\openapi.json",
      "/openapi.json\nfoo"
    ).foreach {
      url =>
        withRoute(config("swagger_url" -> url)) { route =>
          val resp = call(route)
          resp.status mustBe Status.INTERNAL_SERVER_ERROR
          (resp.json \ "error").as[String] mustBe "invalid_configuration"
          if (url.nonEmpty) resp.body[String] must not include url
        }
    }
  }

  // a spec url can not hold '<' or '"' (it must be a valid URI), the title can
  def escaping() = withRoute(
    config(
      "title"       -> """</title><script>alert("t")</script>""",
      "swagger_url" -> "/openapi.json?a=1&b=2"
    )
  ) { route =>
    val html = call(route).body[String]
    html must not include "<script>alert"
    html must include("<title>&lt;/title&gt;&lt;script&gt;alert(&quot;t&quot;)&lt;/script&gt;</title>")
    html must include("""/openapi.json?a=1\u0026b=2""")
    (options(html) \ "url").as[String] mustBe "/openapi.json?a=1&b=2"
  }

  def untrustedValues() = withRoute(
    config(
      "swagger_ui_version" -> "5.30.2/../../evil@1.0.0",
      "theme"              -> "../../evil",
      "layout"             -> "\"Evil",
      "sort_ops"           -> "evil",
      "sort_tags"          -> "evil"
    )
  ) { route =>
    val html = call(route).body[String]
    html must not include "evil"
    html must include(s"""src="${bundled("swagger-ui-bundle.js")}"""")
    val opts = options(html)
    (opts \ "layout").as[String] mustBe "BaseLayout"
    (opts \ "operationsSorter").as[String] mustBe "alpha"
    (opts \ "tagsSorter").as[String] mustBe "alpha"
  }

  // the plugin serves the bundled swagger ui on the path of the page, whatever the listener
  def bundledAssets() = withRoute(config()) { route =>
    val v = s"&__swagger_ui_v=$BundledSwaggerUIVersion"
    Seq(
      "/docs?__swagger_ui_asset=swagger-ui.css"                       -> ("text/css", ".swagger-ui"),
      "/prefix/docs/?__swagger_ui_asset=swagger-ui-bundle.js"         -> ("application/javascript", s"\"$BundledSwaggerUIVersion\""),
      "/?__swagger_ui_asset=swagger-ui-standalone-preset.js"          -> ("application/javascript", "StandaloneLayout")
    ).foreach { case (url, (contentType, content)) =>
      val resp = call(route, url + v)
      resp.status mustBe Status.OK
      resp.header("Content-Type").get must startWith(contentType)
      resp.header("Cache-Control").get must include("immutable")
      resp.body[String] must include(content)
    }
    // only the urls of the page are cached for good
    call(route, "/docs?__swagger_ui_asset=swagger-ui.css&__swagger_ui_v=1.0.0").header("Cache-Control").get mustBe "no-cache"
    // the query of the page is kept, it may be required by the route
    call(route, "/docs?tenant=a%26b&__swagger_ui_v=old").body[String] must include(
      s"""src="?tenant=a%26b&amp;__swagger_ui_asset=swagger-ui-bundle.js&amp;__swagger_ui_v=$BundledSwaggerUIVersion""""
    )
    // nothing but the known files
    Seq("index.html", "../conf/application.conf", "swagger-initializer.js").foreach { file =>
      call(route, s"/docs?__swagger_ui_asset=$file").status mustBe Status.NOT_FOUND
    }
    val request = ws
      .url(s"http://127.0.0.1:$port/docs?__swagger_ui_asset=swagger-ui.css")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
    val head    = request.head().futureValue
    head.status mustBe Status.OK
    head.body[String] mustBe empty
    val post    = request.post("").futureValue
    post.status mustBe Status.METHOD_NOT_ALLOWED
    post.header("Allow") mustBe Some("GET, HEAD")
  }

  // a route matching /docs exactly and requiring a query parameter still serves the bundled files: their urls keep
  // the path and the query of the page, the one of the browser, as what a transformer prepares for a backend never
  // reaches the page
  def routeQueryConstraint() = withRoute(
    config(),
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[QueryTransformer],
        config = NgPluginInstanceConfig(
          QueryTransformerConfig(remove = Seq("tenant"), add = Map("backend_token" -> "server-only")).json.as[JsObject]
        )
      )
    )
  ) { created =>
    val route = created.copy(frontend =
      created.frontend.copy(
        domains = Seq(NgDomainAndPath(s"${created.frontend.domains.head.domain}/docs")),
        exact = true,
        query = Map("tenant" -> "a")
      )
    )
    updateOtoroshiRoute(route).futureValue
    await(2.seconds)
    val html  = call(route, "/docs?tenant=a").body[String]
    html must include(
      s"""src="?tenant=a&amp;__swagger_ui_asset=swagger-ui-bundle.js&amp;__swagger_ui_v=$BundledSwaggerUIVersion""""
    )
    html must not include "server-only"
    Seq(
      "swagger-ui.css"                  -> "text/css",
      "swagger-ui-bundle.js"            -> "application/javascript",
      "swagger-ui-standalone-preset.js" -> "application/javascript"
    ).foreach { case (file, contentType) =>
      val asset = call(route, s"/docs?tenant=a&__swagger_ui_asset=$file&__swagger_ui_v=$BundledSwaggerUIVersion")
      asset.status mustBe Status.OK
      asset.header("Content-Type").get must startWith(contentType)
    }
    call(route, "/docs?__swagger_ui_asset=swagger-ui-bundle.js").status must not be Status.OK
  }

  // with a cdn version, nothing is served from otoroshi
  def cdnAssets() = withRoute(config("swagger_ui_version" -> "5.30.2")) { route =>
    val resp = call(route, "/docs?__swagger_ui_asset=swagger-ui-bundle.js")
    resp.status mustBe Status.OK
    resp.header("Content-Type").get must startWith("text/html")
  }
}
