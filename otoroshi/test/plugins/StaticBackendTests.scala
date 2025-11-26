package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{OverrideHost, StaticBackend, StaticBackendConfig}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.libs.json._

import java.nio.file.{Files, Path}

class StaticBackendTests(parent: PluginsTestSpec) {

  import parent._

  val tempRoot: Path = Files.createTempDirectory("testRoot")

  val file = tempRoot.resolve("index.html")
  Files.write(file, "<div>Hello from file system</div>".getBytes())

  Files.exists(file) mustBe true
  Files.exists(tempRoot) mustBe true

  new String(Files.readAllBytes(file)).contains("Hello from file system") mustBe true

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[StaticBackend],
        config = NgPluginInstanceConfig(
          StaticBackendConfig(tempRoot.toAbsolutePath.toString).json
            .as[JsObject]
        )
      )
    ),
    id = IdGenerator.uuid,
    domain = "s3backend.oto.tools".some
  )

  val resp2 = ws
    .url(s"http://127.0.0.1:$port/index.html")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  resp2.status mustBe 200
  resp2.body contains "Hello from file system" mustBe true

  Files
    .walk(tempRoot)
    .sorted(java.util.Comparator.reverseOrder())
    .forEach(Files.delete)

  deleteOtoroshiRoute(route).futureValue
}
