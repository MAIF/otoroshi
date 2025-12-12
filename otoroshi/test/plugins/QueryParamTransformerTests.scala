package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{OverrideHost, QueryTransformer, QueryTransformerConfig}
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class QueryParamTransformerTests(parent: PluginsTestSpec) {
  import parent._

  val route = createLocalRoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[QueryTransformer],
        config = NgPluginInstanceConfig(
          QueryTransformerConfig(
            remove = Seq("foo"),
            rename = Map("bar" -> "baz"),
            add = Map("new_query" -> "value")
          ).json.as[JsObject]
        )
      )
    ),
    result = req => {
      Json.obj("query_params" -> req.uri.query().toMap)
    }
  ).futureValue

  val resp = ws
    .url(s"http://127.0.0.1:$port/api/?foo=bar&bar=foo")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  resp.status mustBe Status.OK
  Json.parse(resp.body).selectAsObject("query_params") mustEqual Json.obj(
    "baz"       -> "foo",
    "new_query" -> "value"
  )

  deleteOtoroshiRoute(route).futureValue
}
