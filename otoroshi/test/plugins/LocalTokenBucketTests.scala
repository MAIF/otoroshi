package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{LocalTokenBucket, LocalTokensBucketStrategyConfig, OverrideHost}
import play.api.http.Status
import play.api.libs.json._

import scala.concurrent.Future

class LocalTokenBucketTests(parent: PluginsTestSpec) {
  import parent._

  def run() = Future {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[LocalTokenBucket],
          config = NgPluginInstanceConfig(
            LocalTokensBucketStrategyConfig(
              bucketKey = "${req.headers.foo}",
              capacity = 2,
              refillRequestIntervalMs = 2000,
              refillRequestedTokens = 2
            ).json().as[JsObject]
          )
        )
      )
    ).futureValue

    def call(fooHeader: String) = {
      ws
        .url(s"http://127.0.0.1:$port/")
        .withHttpHeaders(
          "Host" -> route.frontend.domains.head.domain,
          "foo"  -> fooHeader
        )
        .get()
    }

    val resp = call("foo").futureValue
    resp.status mustBe Status.OK

    {
      val resp = call("foo").futureValue
      resp.status mustBe Status.OK
    }
    {
      val resp = call("foo").futureValue
      resp.status mustBe Status.TOO_MANY_REQUESTS
    }
    {
      val resp = call("bar").futureValue
      resp.status mustBe Status.OK
    }

    Thread.sleep(6000)

    {
      val resp = call("foo").futureValue
      resp.status mustBe Status.OK
    }

    deleteOtoroshiRoute(route).futureValue
  }
}
