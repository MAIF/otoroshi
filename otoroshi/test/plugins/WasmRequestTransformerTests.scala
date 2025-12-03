package plugins

import functional.PluginsTestSpec
import io.otoroshi.wasm4s.scaladsl.{WasmSource, WasmSourceKind}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterSyntax
import otoroshi.wasm.WasmConfig
import play.api.http.Status
import play.api.libs.json._

class WasmRequestTransformerTests(parent: PluginsTestSpec) {

  import parent._

  val id    = IdGenerator.uuid
  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[WasmRequestTransformer],
        config = NgPluginInstanceConfig(
          WasmConfig(
            source = WasmSource(WasmSourceKind.File, "./test/resources/wasm/transformer-1.0.0-dev.wasm", Json.obj()),
            config = Map.empty,
            functionName = "transform".some,
            wasi = true,
            allowedHosts = Seq.empty,
            allowedPaths = Map.empty
          ).json.as[JsObject]
        )
      )
    ),
    domain = s"$id.oto.tools".some,
    id
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
    .get()
    .futureValue

  getInHeader(resp, "otoroshi_wasm_plugin_id").contains("OTOROSHI_WASM_REQUEST_TRANSFORMER") mustBe true
  resp.status mustBe Status.OK

  deleteOtoroshiRoute(route).futureValue
}
