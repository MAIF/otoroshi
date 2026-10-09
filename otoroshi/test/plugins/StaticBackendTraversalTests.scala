package plugins

import functional.PluginsTestSpecBase
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{FileUtils, OverrideHost, StaticBackend, StaticBackendConfig}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.libs.json.*

import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

// the static backend only serves the files under its root path, whatever the path of the request.
// the requests are sent on a raw socket, as an http client would resolve the dot segments itself.
class StaticBackendTraversalTests(parent: PluginsTestSpecBase) {

  import parent.*

  private val tempDir: Path = Files.createTempDirectory("staticBackendTraversal")
  private val root: Path    = Files.createDirectory(tempDir.resolve("root"))
  Files.write(root.resolve("index.html"), "<div>inside the root</div>".getBytes())
  Files.write(tempDir.resolve("secret.txt"), "outside the root".getBytes())
  Files.write(Files.createDirectory(tempDir.resolve("root2")).resolve("secret.txt"), "outside the root".getBytes())

  private val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[StaticBackend],
        config = NgPluginInstanceConfig(StaticBackendConfig(root.toAbsolutePath.toString).json.as[JsObject])
      )
    ),
    id = IdGenerator.uuid,
    domain = "static-traversal.oto.tools".some
  ).futureValue

  private def rawGet(path: String): String = {
    val socket = new Socket("127.0.0.1", port)
    try {
      socket.setSoTimeout(10000)
      socket.getOutputStream.write(
        s"GET $path HTTP/1.1\r\nHost: static-traversal.oto.tools\r\nConnection: close\r\n\r\n"
          .getBytes(StandardCharsets.UTF_8)
      )
      socket.getOutputStream.flush()
      new String(socket.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    } finally {
      socket.close()
    }
  }

  rawGet("/index.html") must include("inside the root")
  rawGet("/") must include("inside the root")

  private val leaks = Seq(
    "/../secret.txt",
    "/./../secret.txt",
    "/%2e%2e/secret.txt",
    "/%2E%2E/secret.txt",
    "/.%2e/secret.txt",
    "/..%2fsecret.txt",
    "/%2e%2e%2fsecret.txt",
    "/..%5csecret.txt",
    "/foo/../../secret.txt",
    "/foo/%2e%2e/%2e%2e/secret.txt",
    "/../root2/secret.txt"
  ).filter(path => rawGet(path).contains("outside the root"))
  leaks mustBe empty

  // the engine resolves the dot segments of the request path before the plugin gets it, so the
  // requests above never reach the guard: it is checked on its own
  private val fileUtils = new FileUtils(env)
  private val rootPath  = root.toAbsolutePath.toString
  fileUtils.isUnderRoot(fileUtils.normalize("/index.html", rootPath), rootPath) mustBe true
  fileUtils.isUnderRoot(fileUtils.normalize("/", rootPath), rootPath) mustBe true
  fileUtils.isUnderRoot(fileUtils.normalize("/../secret.txt", rootPath), rootPath) mustBe false
  fileUtils.isUnderRoot(fileUtils.normalize("/foo/../../secret.txt", rootPath), rootPath) mustBe false
  fileUtils.isUnderRoot(fileUtils.normalize("/../root2/secret.txt", rootPath), rootPath) mustBe false

  deleteOtoroshiRoute(route).futureValue
  Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
}
