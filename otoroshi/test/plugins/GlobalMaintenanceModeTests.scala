package plugins

import functional.PluginsTestSpec
import otoroshi.models.GlobalConfig
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.OverrideHost
import otoroshi.next.plugins.api.NgPluginHelper
import play.api.http.Status
import play.api.libs.json.Json
import play.api.libs.ws.{WSAuthScheme, WSResponse}

import scala.concurrent.Future

class GlobalMaintenanceModeTests(parent: PluginsTestSpec) {

  import parent._

  private def enableGlobalMaintenance(): Future[WSResponse] = {
    ws.url(s"http://localhost:$port/api/globalconfig")
      .withHttpHeaders(
        "Host"         -> "otoroshi-api.oto.tools",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .put(
        Json.stringify(
          globalConfig
            .copy(
              plugins = globalConfig.plugins.copy(
                enabled = true,
                config = Json.obj(
                  "ng" -> Json.arr(
                    Json.obj(
                      "enabled" -> true,
                      "plugin"  -> "cp:otoroshi.next.plugins.GlobalMaintenanceMode"
                    )
                  )
                )
              )
            )
            .toJson
        )
      )
  }

  val globalConfig = ws
    .url(s"http://localhost:$port/api/globalconfig")
    .withHttpHeaders(
      "Host"         -> "otoroshi-api.oto.tools",
      "Content-Type" -> "application/json"
    )
    .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
    .get()
    .map(resp => GlobalConfig._fmt.reads(resp.json).get)
    .futureValue

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost])
    )
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .stream()
    .futureValue

  resp.status mustBe Status.OK

  enableGlobalMaintenance().futureValue

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .stream()
      .futureValue

    resp.status mustBe Status.SERVICE_UNAVAILABLE
  }

  deleteOtoroshiRoute(route).futureValue
}
