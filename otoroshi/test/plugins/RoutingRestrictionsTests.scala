package plugins

import functional.PluginsTestSpec
import otoroshi.models.{ApiKey, RouteIdentifier}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json.JsObject
import play.api.libs.ws.WSAuthScheme

class RoutingRestrictionsTests(parent: PluginsTestSpec) {

  import parent._

  def testAllowedPath(): Unit = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[RoutingRestrictions],
          config = NgPluginInstanceConfig(
            NgRestrictions(
              allowLast = false,
              allowed = Seq(NgRestrictionPath(path = "/api/users", method = "GET"))
            ).json.as[JsObject]
          )
        )
      )
    )

    val allowedCall = ws
      .url(s"http://127.0.0.1:$port/api/users")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    allowedCall.status mustBe Status.OK

    deleteOtoroshiRoute(route).futureValue
  }

  def testForbiddenPath(): Unit = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[RoutingRestrictions],
          config = NgPluginInstanceConfig(
            NgRestrictions(
              allowLast = true,
              forbidden = Seq(NgRestrictionPath(path = "/api/admin", method = "GET"))
            ).json.as[JsObject]
          )
        )
      )
    )

    val forbiddenCall = ws
      .url(s"http://127.0.0.1:$port/api/admin")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    forbiddenCall.status mustBe Status.FORBIDDEN

    deleteOtoroshiRoute(route).futureValue
  }

  def testNotFoundPath(): Unit = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[RoutingRestrictions],
          config = NgPluginInstanceConfig(
            NgRestrictions(
              allowLast = true,
              notFound = Seq(NgRestrictionPath(path = "/api/hidden", method = "GET"))
            ).json.as[JsObject]
          )
        )
      )
    )

    val notFoundCall = ws
      .url(s"http://127.0.0.1:$port/api/hidden")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    notFoundCall.status mustBe Status.NOT_FOUND
    deleteOtoroshiRoute(route).futureValue
  }

  def testAllowLastTrue(): Unit = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[RoutingRestrictions],
          config = NgPluginInstanceConfig(
            NgRestrictions(
              allowLast = true,
              allowed = Seq(NgRestrictionPath(path = "/api/public", method = "GET"))
            ).json.as[JsObject]
          )
        )
      )
    )

    val unlistedCall = ws
      .url(s"http://127.0.0.1:$port/api/other")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    unlistedCall.status mustBe Status.OK

    deleteOtoroshiRoute(route).futureValue
  }

  def testAllowLastFalse(): Unit = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[RoutingRestrictions],
          config = NgPluginInstanceConfig(
            NgRestrictions(
              allowLast = false,
              allowed = Seq(NgRestrictionPath(path = "/api/public", method = "GET"))
            ).json.as[JsObject]
          )
        )
      )
    )

    val unlistedCall = ws
      .url(s"http://127.0.0.1:$port/api/other")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    unlistedCall.status mustBe Status.NOT_FOUND

    deleteOtoroshiRoute(route).futureValue
  }

  def testForbiddenOverridesAllowed(): Unit = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[RoutingRestrictions],
          config = NgPluginInstanceConfig(
            NgRestrictions(
              allowLast = true,
              allowed = Seq(NgRestrictionPath(path = "/api/.*", method = "GET")),
              forbidden = Seq(NgRestrictionPath(path = "/api/admin", method = "GET"))
            ).json.as[JsObject]
          )
        )
      )
    )

    val forbiddenCall = ws
      .url(s"http://127.0.0.1:$port/api/admin")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    forbiddenCall.status mustBe Status.FORBIDDEN

    val allowedCall = ws
      .url(s"http://127.0.0.1:$port/api/users")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    allowedCall.status mustBe Status.OK

    deleteOtoroshiRoute(route).futureValue
  }

  def testMultiplePaths(): Unit = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[RoutingRestrictions],
          config = NgPluginInstanceConfig(
            NgRestrictions(
              allowLast = false,
              allowed = Seq(
                NgRestrictionPath(path = "/api/users", method = "GET"),
                NgRestrictionPath(path = "/api/products", method = "GET"),
                NgRestrictionPath(path = "/health", method = "GET")
              ),
              forbidden = Seq(NgRestrictionPath(path = "/api/users/admin", method = "GET")),
              notFound = Seq(NgRestrictionPath(path = "/secret", method = "GET"))
            ).json.as[JsObject]
          )
        )
      )
    )

    val usersCall = ws
      .url(s"http://127.0.0.1:$port/api/users")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue
    usersCall.status mustBe Status.OK

    val healthCall = ws
      .url(s"http://127.0.0.1:$port/health")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue
    healthCall.status mustBe Status.OK

    val adminCall = ws
      .url(s"http://127.0.0.1:$port/api/users/admin")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue
    adminCall.status mustBe Status.FORBIDDEN

    val secretCall = ws
      .url(s"http://127.0.0.1:$port/secret")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue
    secretCall.status mustBe Status.NOT_FOUND

    val unlistedCall = ws
      .url(s"http://127.0.0.1:$port/api/other")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue
    unlistedCall.status mustBe Status.NOT_FOUND

    deleteOtoroshiRoute(route).futureValue
  }

  def testEmptyRestrictions(): Unit = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[RoutingRestrictions],
          config = NgPluginInstanceConfig(
            NgRestrictions(
              allowLast = true,
              allowed = Seq.empty,
              forbidden = Seq.empty,
              notFound = Seq.empty
            ).json.as[JsObject]
          )
        )
      )
    )

    val anyCall = ws
      .url(s"http://127.0.0.1:$port/api/anything")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    anyCall.status mustBe Status.OK

    deleteOtoroshiRoute(route).futureValue
  }

  def testRegexPatterns(): Unit = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[RoutingRestrictions],
          config = NgPluginInstanceConfig(
            NgRestrictions(
              allowLast = false,
              allowed = Seq(NgRestrictionPath(path = "/api/v[0-9]+/.*", method = "GET")),
              forbidden = Seq(NgRestrictionPath(path = "/api/v[0-9]+/admin.*", method = "GET"))
            ).json.as[JsObject]
          )
        )
      )
    )

    val v1Call = ws
      .url(s"http://127.0.0.1:$port/api/v1/users")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue
    v1Call.status mustBe Status.OK

    val v2Call = ws
      .url(s"http://127.0.0.1:$port/api/v2/products")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue
    v2Call.status mustBe Status.OK

    val adminCall = ws
      .url(s"http://127.0.0.1:$port/api/v1/admin")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue
    adminCall.status mustBe Status.OK

    val noVersionCall = ws
      .url(s"http://127.0.0.1:$port/api/users")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue
    noVersionCall.status mustBe Status.NOT_FOUND

    deleteOtoroshiRoute(route).futureValue
  }
}
