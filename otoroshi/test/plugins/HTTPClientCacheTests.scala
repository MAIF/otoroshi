package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import play.api.libs.json._

class HTTPClientCacheTests(parent: PluginsTestSpec) {

  import parent._

  def matchesWildcardMimeType() = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[NgHttpClientCache],
          config = NgPluginInstanceConfig(
            NgHttpClientCacheConfig.default.copy(mimeTypes = Seq("*")).json.as[JsObject]
          )
        )
      ),
      id = IdGenerator.uuid
    ).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    resp.status mustBe 200
    resp.headers.contains("Cache-Control") mustBe true

    deleteOtoroshiRoute(route).futureValue
  }

  def doesNotAddCacheHeadersIfContentTypeDoesNotMatch() = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[NgHttpClientCache],
          config = NgPluginInstanceConfig(
            NgHttpClientCacheConfig.default.copy(mimeTypes = Seq("text/html")).json.as[JsObject]
          )
        )
      ),
      id = IdGenerator.uuid
    ).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    resp.status mustBe 200
    resp.headers.contains("Cache-Control") mustBe false

    deleteOtoroshiRoute(route).futureValue
  }

  def doesNotAddCacheHeadersIfStatusDoesNotMatch() = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[NgHttpClientCache],
          config = NgPluginInstanceConfig(
            NgHttpClientCacheConfig.default.copy(status = Seq(404)).json.as[JsObject]
          )
        )
      ),
      id = IdGenerator.uuid
    ).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    resp.status mustBe 200
    resp.headers.contains("Cache-Control") mustBe false

    deleteOtoroshiRoute(route).futureValue
  }

  def doesNotAddCacheHeadersIfHTTPMethodDoesNotMatch() = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[NgHttpClientCache],
          config = NgPluginInstanceConfig(
            NgHttpClientCacheConfig.default.copy(methods = Seq("POST")).json.as[JsObject]
          )
        )
      ),
      id = IdGenerator.uuid
    ).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    resp.status mustBe 200
    resp.headers.contains("Cache-Control") mustBe false

    deleteOtoroshiRoute(route).futureValue
  }

  def addCacheHeadersWhenMethodStatusAndContentTypeMatch() {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[NgHttpClientCache],
          config = NgPluginInstanceConfig(
            NgHttpClientCacheConfig.default
              .copy(mimeTypes = Seq("*"))
              .json
              .as[JsObject]
          )
        )
      ),
      id = IdGenerator.uuid
    ).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp.status mustBe 200

    resp.headers.contains("Cache-Control") mustBe true
    resp.headers.contains("Date") mustBe true
    resp.headers.contains("Expires") mustBe true
    resp.headers.contains("ETag") mustBe true
    resp.headers.contains("Last-Modified") mustBe true
    resp.headers.contains("Vary") mustBe true
    resp.headers.get("Cache-Control").exists(values => values.exists(v => v.contains("max-age="))) mustBe true

    deleteOtoroshiRoute(route).futureValue
  }
}
