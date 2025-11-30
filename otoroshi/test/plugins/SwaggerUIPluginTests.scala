package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.SwaggerUIConfig.DefaultSwaggerUIVersion
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{OverrideHost, SwaggerUIConfig, SwaggerUIPlugin}
import play.api.http.Status
import play.api.libs.json.JsObject

class SwaggerUIPluginTests(parent: PluginsTestSpec) {
  import parent._

  def run() = {
    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[SwaggerUIPlugin],
          config = NgPluginInstanceConfig(
            SwaggerUIConfig(
              swaggerUrl = "https://rickandmorty.zuplo.io/openapi.json",
              title = "My Swagger UI Configuration",
              swaggerUIVersion = DefaultSwaggerUIVersion,
              filter = true,
              showModels = false,
              displayOperationId = false,
              showExtensions = false,
              layout = "BaseLayout",
              sortTags = "alpha",
              sortOps = "alpha",
              theme = "default"
            ).json.as[JsObject]
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
    val html = resp.body

    html.contains("<title>My Swagger UI Configuration</title>") mustBe true
    html.contains("layout: \"BaseLayout\"") mustBe true
    html.contains("url: \"https://rickandmorty.zuplo.io/openapi.json\"") mustBe true

    html.contains("filter: true") mustBe true
    html.contains("defaultModelsExpandDepth: -1") mustBe true
    html.contains("displayOperationId: false") mustBe true
    html.contains("showExtensions: false") mustBe true
    html.contains("operationsSorter: \"alpha\"") mustBe true
    html.contains("tagsSorter: \"alpha\"") mustBe true

    deleteOtoroshiRoute(route).futureValue
  }
}
