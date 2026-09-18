package plugins

import functional.PluginsTestSpecBase
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{
  BrotliResponseCompressor,
  GzipResponseCompressor,
  NgBrotliConfig,
  NgGzipConfig,
  OverrideHost
}
import play.api.libs.json.*

import java.net.{HttpURLConnection, URI}

// A compressor that stops working still returns a perfectly valid response, so only the
// encoding of the bytes on the wire catches it. Brotli is exercised alongside as a control:
// same route shape, same content — a failure on gzip alone points at the gzip plugin.
//
// The calls deliberately bypass the WS client: it decompresses gzip transparently and drops
// `Content-Encoding`, which makes a working compressor look broken.
class GzipResponseCompressorTests(parent: PluginsTestSpecBase) {
  import parent.*

  private def compressorRoute(plugin: NgPluginInstance) =
    createRouteWithExternalTarget(
      Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]), plugin)
    ).futureValue

  /** Raw call, no transparent decoding: returns the encoding header and the first bytes.
    *
    * The route domain goes in the URL rather than in a `Host` header: HttpURLConnection
    * ignores restricted headers, and the request would then match no route at all.
    */
  private def rawCall(host: String, accept: String): (Option[String], Array[Byte]) = {
    val conn = URI.create(s"http://$host:$port/api").toURL.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestProperty("Accept-Encoding", accept)
    conn.connect()
    val bytes = conn.getInputStream.readNBytes(64)
    val enc   = Option(conn.getHeaderField("Content-Encoding"))
    conn.disconnect()
    (enc, bytes)
  }

  def gzipCompressesTheResponse(): Unit = {
    val route         = compressorRoute(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[GzipResponseCompressor],
        config = NgPluginInstanceConfig(NgGzipConfig().json.as[JsObject])
      )
    )
    val (enc, bytes)  = rawCall(route.frontend.domains.head.domain, "gzip")
    enc mustBe Some("gzip")
    // gzip magic number, so the body really is compressed and not merely labelled as such
    (bytes(0) & 0xff, bytes(1) & 0xff) mustBe (0x1f, 0x8b)
    deleteOtoroshiRoute(route).futureValue
  }

  def brotliCompressesTheResponse(): Unit = {
    val route     = compressorRoute(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[BrotliResponseCompressor],
        config = NgPluginInstanceConfig(NgBrotliConfig().json.as[JsObject])
      )
    )
    val (enc, _)  = rawCall(route.frontend.domains.head.domain, "br")
    enc mustBe Some("br")
    deleteOtoroshiRoute(route).futureValue
  }
}
