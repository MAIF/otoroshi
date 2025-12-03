package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{NgExternalValidator, NgExternalValidatorConfig, OverrideHost}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.duration.DurationInt

class ExternalRequestValidatorTests(parent: PluginsTestSpec) {

  import parent._

  def rejectRequest() = {
    val invalidRoute = createLocalRoute(
      Seq.empty,
      result = _ => {
        Json.obj("pass" -> false)
      },
      domain = "invalid.oto.tools",
      frontendPath = "/check"
    )

    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[NgExternalValidator],
          config = NgPluginInstanceConfig(
            NgExternalValidatorConfig(
              cacheExpression = None,
              ttl = 60.seconds,
              timeout = 30.seconds,
              url = Some(s"http://${invalidRoute.frontend.domains.head.domain}.oto.tools:$port"),
              headers = Map.empty,
              errorMessage = "you shall not pass",
              errorStatus = 401
            ).json.as[JsObject]
          )
        )
      ),
      domain = "route.oto.tools".some,
      id = IdGenerator.uuid
    )

    val resp = ws
      .url(s"http://127.0.0.1:$port/check")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp.status mustBe Status.UNAUTHORIZED
    resp.body.contains("you shall not pass") mustBe true

    deleteOtoroshiRoute(invalidRoute).futureValue
    deleteOtoroshiRoute(route).futureValue
  }

  def valid() = {
    val validatorRoute = createLocalRoute(
      Seq.empty,
      result = _ => Json.obj("pass" -> true),
      domain = "validator.oto.tools",
      frontendPath = "/check"
    )

    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[NgExternalValidator],
          config = NgPluginInstanceConfig(
            NgExternalValidatorConfig(
              cacheExpression = None,
              ttl = 60.seconds,
              timeout = 30.seconds,
              url = Some(s"http://validator.oto.tools:$port"),
              headers = Map.empty,
              errorMessage = "forbidden",
              errorStatus = 403
            ).json.as[JsObject]
          )
        )
      )
    )

    val resp = ws
      .url(s"http://127.0.0.1:$port/check")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK

    deleteOtoroshiRoute(validatorRoute).futureValue
    deleteOtoroshiRoute(route).futureValue
  }
}
