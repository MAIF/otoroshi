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

class WasmPreRouteTests(parent: PluginsTestSpec) {

  import parent._

  val id    = IdGenerator.uuid
  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[WasmPreRoute],
        config = NgPluginInstanceConfig(
          WasmConfig(
            source = WasmSource(WasmSourceKind.File, "./test/resources/wasm/pre-route-1.0.0-dev.wasm", Json.obj()),
            config = Map.empty,
            functionName = "validate".some,
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
    .withHttpHeaders("Host" -> route.frontend.domains.head.domain, "foo" -> "bar")
    .get()
    .futureValue

  resp.status mustBe Status.OK

  val unauthorizedCall = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders("Host" -> route.frontend.domains.head.domain, "foo" -> "baz")
    .get()
    .futureValue

  unauthorizedCall.status mustBe Status.UNAUTHORIZED

  deleteOtoroshiRoute(route).futureValue
}
