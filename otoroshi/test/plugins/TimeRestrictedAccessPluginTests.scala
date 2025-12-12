package plugins

import functional.PluginsTestSpec
import org.joda.time.{DateTime, LocalTime}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{
  OverrideHost,
  TimeRestrictedAccessPlugin,
  TimeRestrictedAccessPluginConfig,
  TimeRestrictedAccessPluginConfigRule
}
import play.api.http.Status
import play.api.libs.json._

import scala.concurrent.duration.DurationInt

class TimeRestrictedAccessPluginTests(parent: PluginsTestSpec) {
  import parent._

  val dnow  = DateTime.now()
  val now   = LocalTime.now()
  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[TimeRestrictedAccessPlugin],
        config = NgPluginInstanceConfig(
          TimeRestrictedAccessPluginConfig(
            rules = Seq(
              TimeRestrictedAccessPluginConfigRule(
                timeStart = now.plusSeconds(5),
                timeEnd = now.plusSeconds(10),
                dayStart = dnow.getDayOfWeek,
                dayEnd = dnow.getDayOfWeek
              ),
              TimeRestrictedAccessPluginConfigRule(
                timeStart = now.plusSeconds(15),
                timeEnd = now.plusSeconds(20),
                dayStart = dnow.getDayOfWeek,
                dayEnd = dnow.getDayOfWeek
              )
            )
          ).json.as[JsObject]
        )
      )
    )
  ).futureValue

  def call(): Int = {
    ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue
      .status
  }

  call() mustBe 403

  await(6.seconds)

  call() mustBe Status.OK

  await(5.seconds)

  call() mustBe 403

  await(5.seconds)

  call() mustBe Status.OK

  await(5.seconds)

  call() mustBe 403

  deleteOtoroshiRoute(route).futureValue
}
