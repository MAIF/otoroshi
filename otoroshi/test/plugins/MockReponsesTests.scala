package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{MockResponse, MockResponses, MockResponsesConfig, OverrideHost}
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import play.api.http.Status
import play.api.libs.json._

class MockReponsesTests(parent: PluginsTestSpec) {
  import parent._

  val route = createLocalRoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[MockResponses],
        config = NgPluginInstanceConfig(
          MockResponsesConfig(
            responses = Seq(
              MockResponse(
                headers = Map.empty,
                body = Json.obj("foo" -> "bar").stringify
              ),
              MockResponse(
                path = "/users/:id",
                method = "POST",
                status = 201,
                headers = Map.empty,
                body = Json.obj("message" -> "done").stringify
              )
            )
          ).json.as[JsObject]
        )
      )
    ),
    rawDomain = "mock.oto.tools".some,
    frontendPath = "/"
  )

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
    Json.parse(resp.body) mustBe Json.obj("foo" -> "bar")
  }

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/users/foo")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .post("")
      .futureValue

    resp.status mustBe Status.CREATED
    Json.parse(resp.body) mustBe Json.obj("message" -> "done")
  }

  deleteOtoroshiRoute(route).futureValue
}
