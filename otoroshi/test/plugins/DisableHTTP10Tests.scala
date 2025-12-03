package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{DisableHttp10, OverrideHost}

class DisableHTTP10Tests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[DisableHttp10]
      )
    )
  )

  import java.io._
  import java.net.Socket

  def makeHttp10Request(
      host: String,
      port: Int,
      path: String,
      method: String = "GET",
      headers: Map[String, String] = Map()
  ): String = {
    val socket = new Socket(host, port)
    try {
      val out = new PrintWriter(socket.getOutputStream, true)
      val in  = new BufferedReader(new InputStreamReader(socket.getInputStream))

      out.println(s"$method $path HTTP/1.0")

      headers.foreach { case (key, value) =>
        out.println(s"$key: $value")
      }

      out.println("Connection: close")
      out.println()
      out.flush()

      val response = new StringBuilder
      var line     = in.readLine()
      while (line != null) {
        response.append(line).append("\n")
        line = in.readLine()
      }

      response.toString
    } finally {
      socket.close()
    }
  }

  val resp = makeHttp10Request(
    host = "127.0.0.1",
    port = port,
    path = "/api",
    headers = Map("Host" -> route.frontend.domains.head.domain)
  )

  resp.contains("HTTP/1.0 503 Service Unavailable") mustBe true
  deleteOtoroshiRoute(route).futureValue
}
