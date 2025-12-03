package plugins

import functional.PluginsTestSpec
import otoroshi.models._
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{JwtVerification, NgJwtVerificationConfig, OtoroshiHealthEndpoint, OverrideHost}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class OtoroshiHealthEndpointTests(parent: PluginsTestSpec) {
  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(NgPluginHelper.pluginId[OtoroshiHealthEndpoint], include = Seq("/health"))
    )
  )

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
    Json.parse(resp.body).selectAsString("method") mustEqual "GET"
  }

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/health")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
    Json.parse(resp.body).selectAsString("otoroshi") mustEqual "healthy"
    Json.parse(resp.body).selectAsString("datastore") mustEqual "healthy"

    val keys = Json.parse(resp.body).as[JsObject].keys

    keys.contains("proxy")
    keys.contains("storage")
    keys.contains("eventstore")
    keys.contains("certificates")
    keys.contains("scripts")
    keys.contains("cluster")
  }

  deleteOtoroshiRoute(route).futureValue
}
