package plugins

import functional.PluginsTestSpec
import otoroshi.models.{EntityLocation, GlobalConfig}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.OverrideHost
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.wasm.proxywasm.{CorazaWafConfig, NgCorazaWAF, NgCorazaWAFConfig}
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.{WSAuthScheme, WSResponse}

import scala.concurrent.Future

class GlobalCorazaWafTests(parent: PluginsTestSpec) {

  import parent._

  val coraza    = CorazaWafConfig(
    location = EntityLocation.default,
    id = IdGenerator.uuid,
    name = "Coraza",
    description = "Coraza",
    tags = Seq.empty,
    metadata = Map.empty,
    inspectInputBody = true,
    inspectOutputBody = true,
    includeOwaspCRS = true,
    isBlockingMode = true,
    directives = Seq(
      "SecRule REQUEST_URI \"@streq /admin\" \"id:101,phase:1,t:lowercase,deny,msg:'ADMIN PATH forbidden'\""
    ),
    poolCapacity = 2
  )
  val wafResult = createOtoroshiWAF(coraza).futureValue

  private def updateGlobalConfig(globalConfig: GlobalConfig) = {
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
                  "incoming_request_validators" -> Json.arr(
                    Json.obj(
                      "config"  -> Json.obj(
                        "ref" -> coraza.id
                      ),
                      "debug"   -> false,
                      "enabled" -> true,
                      "plugin"  -> "cp:otoroshi.wasm.proxywasm.NgIncomingRequestValidatorCorazaWAF"
                    )
                  )
                )
              )
            )
            .toJson
        )
      )
  }

  private def resetGlobalConfig(globalConfig: GlobalConfig): Future[WSResponse] = {
    ws.url(s"http://localhost:$port/api/globalconfig")
      .withHttpHeaders(
        "Host"         -> "otoroshi-api.oto.tools",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .put(
        Json.stringify(globalConfig.toJson)
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
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      )
    )
  )

  updateGlobalConfig(globalConfig)

  val resp = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .stream()
    .futureValue

  resp.status mustBe 200

  val unauthorizedCall = ws
    .url(s"http://127.0.0.1:$port/admin")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .stream()
    .futureValue

  unauthorizedCall.status mustBe 403

  resetGlobalConfig(globalConfig)

  deleteOtoroshiWAF(coraza).futureValue
  deleteOtoroshiRoute(route).futureValue
}
