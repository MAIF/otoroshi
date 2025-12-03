package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgRoute}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{NgCustomQuotas, NgCustomQuotasConfig, OverrideHost}
import otoroshi.security.IdGenerator
import play.api.http.Status
import play.api.libs.json.JsObject

class CustomQuotasTests(parent: PluginsTestSpec) {
  import parent._

  def global() = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[NgCustomQuotas],
          config = NgPluginInstanceConfig(
            NgCustomQuotasConfig(
              dailyQuota = 2,
              monthlyQuota = 2,
              perRoute = false,
              global = true,
              group = None,
              expression = "${req.headers.foo}"
            ).json.as[JsObject]
          )
        )
      )
    )

    val secondRoute = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[NgCustomQuotas],
          config = NgPluginInstanceConfig(
            NgCustomQuotasConfig(
              dailyQuota = 2,
              monthlyQuota = 2,
              perRoute = false,
              global = true,
              group = None,
              expression = "${req.headers.foo}"
            ).json.as[JsObject]
          )
        )
      ),
      id = IdGenerator.uuid
    )

    def call(route: NgRoute) = {
      ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain,
          "foo"  -> "bar"
        )
        .get()
        .futureValue
    }

    {
      val resp = call(route)
      resp.status mustBe Status.OK
    }

    {
      val resp = call(secondRoute)
      resp.status mustBe Status.OK
    }

    {
      val resp = call(route)
      resp.status mustBe Status.FORBIDDEN
    }

    deleteOtoroshiRoute(secondRoute).futureValue
    deleteOtoroshiRoute(route).futureValue
  }

  def perRoute() = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[NgCustomQuotas],
          config = NgPluginInstanceConfig(
            NgCustomQuotasConfig(
              dailyQuota = 1,
              monthlyQuota = 1,
              perRoute = true,
              global = false,
              group = None,
              expression = "${req.headers.foo}"
            ).json.as[JsObject]
          )
        )
      )
    )

    def call(value: String) = {
      ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain,
          "foo"  -> value
        )
        .get()
        .futureValue
    }

    {
      val resp = call("bar")
      resp.status mustBe Status.OK
    }

    {
      val resp = call("bar")
      resp.status mustBe Status.FORBIDDEN
    }

    {
      val resp = call("baz")
      resp.status mustBe Status.OK
    }

    deleteOtoroshiRoute(route).futureValue
  }
}
