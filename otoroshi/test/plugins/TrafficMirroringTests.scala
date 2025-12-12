package plugins

import functional.PluginsTestSpec
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time.{Seconds, Span}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{NgTrafficMirroring, NgTrafficMirroringConfig, OverrideHost}
import otoroshi.plugins.mirror.MirroringPluginConfig
import play.api.http.Status
import play.api.libs.json._

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Promise

class TrafficMirroringTests(parent: PluginsTestSpec) {
  import parent._

  val counter         = new AtomicInteger(0)
  val messagesPromise = Promise[Int]()

  val mirroringRoute = createLocalRoute(
    responseStatus = Status.OK,
    result = _ => {
      counter.incrementAndGet()

      messagesPromise.trySuccess(counter.get)
      Json.obj("foo" -> "bar")
    }
  ).futureValue

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgTrafficMirroring],
        config = NgPluginInstanceConfig(
          NgTrafficMirroringConfig(
            legacy = MirroringPluginConfig(
              Json.obj(
                "to"               -> s"http://127.0.0.1:$port",
                "headers"          -> Map("Host" -> mirroringRoute.frontend.domains.head.domain),
                "enabled"          -> true,
                "capture_response" -> false,
                "generate_events"  -> false
              )
            )
          ).json.as[JsObject]
        )
      )
    )
  ).futureValue

  val resp = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  resp.status mustBe Status.OK

  val res = messagesPromise.future.futureValue(Timeout(Span(20, Seconds)))
  res mustBe 1

  deleteOtoroshiRoute(route).futureValue
}
