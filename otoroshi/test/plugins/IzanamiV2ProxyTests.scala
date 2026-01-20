package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgRoute}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{AdditionalHeadersIn, EchoBackend, IzanamiV2Proxy, IzanamiV2ProxyConfig, NgHeaderValuesConfig, OverrideHost}
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.http.Status
import play.api.libs.json.{JsObject, JsValue}

import scala.concurrent.Future

class IzanamiV2ProxyTests(parent: PluginsTestSpec) {
  import parent._

  private def setup(config: IzanamiV2ProxyConfig): (NgRoute, NgRoute) = {
    val targetRoute = createRouteWithExternalTarget(
      plugins = Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(plugin = NgPluginHelper.pluginId[EchoBackend])
      ),
      domain = Some("izanami.oto.tools")
    ).futureValue

    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[IzanamiV2Proxy],
          config = NgPluginInstanceConfig(
           config.json.as[JsObject]
          )
        )
      )
    ).futureValue

    (route, targetRoute)
  }

  def izanamiCallShouldBeCorrect() = {
    val (route, targetRoute) = setup(
      IzanamiV2ProxyConfig(
        url = s"http://izanami.oto.tools:$port",
        clientId = "client-id",
        clientSecret = "client-secret"
      )
    )

    val resp = ws
      .url(s"http://127.0.0.1:$port/features?features=foo")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK

    val responseBody: JsValue = resp.json
    responseBody.selectAsString("path") mustEqual "/api/v2/features"
    (responseBody \ "query" \ "features").as[String] mustEqual "foo"
    //(responseBody \ "query" \ "context").as[String] mustEqual "prod"
    (responseBody \ "headers" \ "Izanami-Client-Id").as[String] mustEqual "client-id"
    (responseBody \ "headers" \ "Izanami-Client-Secret").as[String] mustEqual "client-secret"

    teardown(Seq(route, targetRoute))
  }

  def contextShouldBeUsed() = {
    val (route, targetRoute) = setup(
      IzanamiV2ProxyConfig(
        url = s"http://izanami.oto.tools:$port",
        clientId = "client-id",
        clientSecret = "client-secret",
        context = Some("prod")
      )
    )

    val resp = ws
      .url(s"http://127.0.0.1:$port/features?features=bar")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK

    val responseBody: JsValue = resp.json
    responseBody.selectAsString("path") mustEqual "/api/v2/features"
    (responseBody \ "query" \ "features").as[String] mustEqual "bar"
    (responseBody \ "query" \ "context").as[String] mustEqual "prod"
    (responseBody \ "headers" \ "Izanami-Client-Id").as[String] mustEqual "client-id"
    (responseBody \ "headers" \ "Izanami-Client-Secret").as[String] mustEqual "client-secret"

    teardown(Seq(route, targetRoute))
  }

  def childContextShouldNotBeOverwritten() = {
    val (route, targetRoute) = setup(
      IzanamiV2ProxyConfig(
        url = s"http://izanami.oto.tools:$port",
        clientId = "client-id",
        clientSecret = "client-secret",
        context = Some("prod")
      )
    )

    val resp = ws
      .url(s"http://127.0.0.1:$port/features?features=baz&context=prod/mobile")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK

    val responseBody: JsValue = resp.json
    responseBody.selectAsString("path") mustEqual "/api/v2/features"
    (responseBody \ "query" \ "features").as[String] mustEqual "baz"
    (responseBody \ "query" \ "context").as[String] mustEqual "prod/mobile"
    (responseBody \ "headers" \ "Izanami-Client-Id").as[String] mustEqual "client-id"
    (responseBody \ "headers" \ "Izanami-Client-Secret").as[String] mustEqual "client-secret"

    teardown(Seq(route, targetRoute))
  }


  def teardown(routes: Seq[NgRoute]) = {
    Future.sequence(routes.map(deleteOtoroshiRoute(_))).futureValue
  }
}
