package plugins

import functional.PluginsTestSpec
import io.otoroshi.wasm4s.scaladsl.{WasmSource, WasmSourceKind, WasmVmKillOptions}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterSyntax
import otoroshi.wasm.{WasmAuthorizations, WasmConfig}
import play.api.http.Status
import play.api.libs.json._

class WasmAccessControlTests(parent: PluginsTestSpec) {

  import parent._

  def good() = {
    val id    = IdGenerator.uuid
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[WasmAccessValidator],
          config = NgPluginInstanceConfig(
            WasmConfig(
              source =
                WasmSource(WasmSourceKind.File, "./test/resources/wasm/access_control-1.0.0-dev.wasm", Json.obj()),
              memoryPages = 50,
              functionName = Some("execute"),
              config = Map.empty,
              allowedHosts = Seq.empty,
              allowedPaths = Map.empty,
              wasi = true,
              opa = false,
              httpWasm = false,
              instances = 1
            ).json.as[JsObject]
          )
        )
      ),
      domain = s"$id.oto.tools".some,
      id
    ).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain, "foo" -> "bar")
      .get()
      .futureValue

    resp.status mustBe Status.OK

    deleteOtoroshiRoute(route).futureValue
  }

  def bad() = {
    val id    = IdGenerator.uuid
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[WasmAccessValidator],
          config = NgPluginInstanceConfig(
            WasmConfig(
              source =
                WasmSource(WasmSourceKind.File, "./test/resources/wasm/access_control-1.0.0-dev.wasm", Json.obj()),
              memoryPages = 50,
              functionName = Some("execute"),
              config = Map.empty,
              allowedHosts = Seq.empty,
              allowedPaths = Map.empty,
              wasi = true,
              opa = false,
              httpWasm = false,
              instances = 1
            ).json.as[JsObject]
          )
        )
      ),
      domain = s"$id.oto.tools".some,
      id
    ).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    resp.status mustBe Status.UNAUTHORIZED

    deleteOtoroshiRoute(route).futureValue
  }
}
