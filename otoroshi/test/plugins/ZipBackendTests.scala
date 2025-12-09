package plugins

import functional.PluginsTestSpec
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.util.ByteString
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.*
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class ZipBackendTests(parent: PluginsTestSpec) {

  import parent.{*, given}

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[ZipFileBackend],
        config = NgPluginInstanceConfig(
          ZipFileBackendConfig(
            url = "file://./test/resources/test.zip",
            headers = Map.empty,
            dir = "./example",
            prefix = None,
            ttl = 60000
          ).json.as[JsObject]
        )
      )
    )
  )

  val resp = ws
    .url(s"http://127.0.0.1:$port/example/index.json")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .stream()
    .futureValue

  resp.status mustBe Status.OK
  Json.parse(resp.body) mustBe Json.obj("foo" -> "bar")

  val notFoundFile = ws
    .url(s"http://127.0.0.1:$port/")
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .stream()
    .futureValue

  notFoundFile.status mustBe Status.NOT_FOUND

  deleteOtoroshiRoute(route).futureValue
}
