package plugins

import functional.PluginsTestSpec
import otoroshi.models.{EntityLocation, ErrorTemplate}
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{BuildMode, MaintenanceMode, OverrideHost}
import otoroshi.utils.syntax.implicits.{BetterJsValueReader, BetterSyntax}
import play.api.http.Status
import play.api.libs.json.Json

class CustomErrorTemplateTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[BuildMode]
      )
    )
  )

  val maintenanceRoute = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[MaintenanceMode]
      )
    ),
    domain = "maintenance.oto.tools".some,
    id = "maintenance route"
  )

  val error = ErrorTemplate(
    location = EntityLocation.default,
    serviceId = "global",
    name = "global error template",
    description = "global error template description",
    template50x = "",
    templateBuild = "build mode enabled, bye",
    template40x = "",
    templateMaintenance = "maintenance mode enabled, bye",
    genericTemplates = Map.empty,
    messages = Map(
      "errors.service.under.construction" -> "build mode enabled",
      "errors.service.in.maintenance"     -> "maintenance mode enabled"
    ),
    tags = Seq.empty,
    metadata = Map.empty
  )

  createOtoroshiErrorTemplate(error).futureValue

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host"   -> route.frontend.domains.head.domain,
        "Accept" -> "text/html"
      )
      .get()
      .futureValue

    resp.status mustBe Status.SERVICE_UNAVAILABLE
    resp.body mustEqual "build mode enabled, bye"

    val resp2 = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp2.status mustBe Status.SERVICE_UNAVAILABLE

    Json.parse(resp2.body).selectAsString("otoroshi-cause") mustEqual "build mode enabled"
    Json.parse(resp2.body).selectAsString("otoroshi-error") mustEqual "Service under construction"
  }

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host"   -> maintenanceRoute.frontend.domains.head.domain,
        "Accept" -> "text/html"
      )
      .get()
      .futureValue

    resp.status mustBe Status.SERVICE_UNAVAILABLE
    resp.body mustEqual "maintenance mode enabled, bye"

    val resp2 = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> maintenanceRoute.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp2.status mustBe Status.SERVICE_UNAVAILABLE

    Json.parse(resp2.body).selectAsString("otoroshi-cause") mustEqual "maintenance mode enabled"
    Json.parse(resp2.body).selectAsString("otoroshi-error") mustEqual "Service in maintenance mode"
  }

  deleteOtoroshiErrorTemplate(error).futureValue
  deleteOtoroshiRoute(route).futureValue
  deleteOtoroshiRoute(maintenanceRoute).futureValue
}
