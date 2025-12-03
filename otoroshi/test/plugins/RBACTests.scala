package plugins

import functional.PluginsTestSpec
import otoroshi.models.{ApiKey, ApiKeyRouteMatcher, RouteIdentifier}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json.JsObject
import play.api.libs.ws.WSAuthScheme

class RBACTests(parent: PluginsTestSpec) {

  import parent._

  def allow() = {
    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ApikeyAuthModule]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[RBAC],
          config = NgPluginInstanceConfig(
            RBACConfig(
              roles = "roles",
              rolePrefix = "tenantA".some,
              allow = Seq("write")
            ).json.as[JsObject]
          )
        )
      )
    )

    val goodApikey = ApiKey(
      clientName = "foo",
      clientId = "foo",
      clientSecret = "bar",
      authorizedEntities = Seq(RouteIdentifier(route.id)),
      tags = Seq("tenantA:role:write")
    )

    createOtoroshiApiKey(goodApikey).futureValue

    val authorizedCall = ws
      .url(s"http://127.0.0.1:$port/api")
      .withAuth(goodApikey.clientId, goodApikey.clientSecret, WSAuthScheme.BASIC)
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    authorizedCall.status mustBe Status.OK

    deletePluginsRouteApiKeys(route.id)
    deleteOtoroshiApiKey(goodApikey)
    deleteOtoroshiRoute(route).futureValue
  }

  def testUnauthorizedRole(): Unit = {
    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(plugin = NgPluginHelper.pluginId[ApikeyAuthModule]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[RBAC],
          config = NgPluginInstanceConfig(
            RBACConfig(
              roles = "roles",
              rolePrefix = "tenantA".some,
              allow = Seq("write")
            ).json.as[JsObject]
          )
        )
      )
    )

    val unauthorizedApikey = ApiKey(
      clientName = "unauthorized",
      clientId = "unauth",
      clientSecret = "unauth",
      authorizedEntities = Seq(RouteIdentifier(route.id)),
      tags = Seq("tenantA:role:read")
    )

    createOtoroshiApiKey(unauthorizedApikey).futureValue

    val unauthorizedCall = ws
      .url(s"http://127.0.0.1:$port/api")
      .withAuth(unauthorizedApikey.clientId, unauthorizedApikey.clientSecret, WSAuthScheme.BASIC)
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    unauthorizedCall.status mustBe Status.FORBIDDEN

    deletePluginsRouteApiKeys(route.id)
    deleteOtoroshiApiKey(unauthorizedApikey)
    deleteOtoroshiRoute(route).futureValue
  }

  def testNoRoles(): Unit = {
    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(plugin = NgPluginHelper.pluginId[ApikeyAuthModule]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[RBAC],
          config = NgPluginInstanceConfig(
            RBACConfig(
              roles = "roles",
              rolePrefix = "tenantA".some,
              allow = Seq("write")
            ).json.as[JsObject]
          )
        )
      )
    )

    val noRolesApikey = ApiKey(
      clientName = "noroles",
      clientId = "noroles",
      clientSecret = "noroles",
      authorizedEntities = Seq(RouteIdentifier(route.id)),
      tags = Seq.empty
    )

    createOtoroshiApiKey(noRolesApikey).futureValue

    val noRolesCall = ws
      .url(s"http://127.0.0.1:$port/api")
      .withAuth(noRolesApikey.clientId, noRolesApikey.clientSecret, WSAuthScheme.BASIC)
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    noRolesCall.status mustBe Status.FORBIDDEN

    deletePluginsRouteApiKeys(route.id)
    deleteOtoroshiApiKey(noRolesApikey)
    deleteOtoroshiRoute(route).futureValue
  }

  def testWrongRolePrefix(): Unit = {
    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(plugin = NgPluginHelper.pluginId[ApikeyAuthModule]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[RBAC],
          config = NgPluginInstanceConfig(
            RBACConfig(
              roles = "roles",
              rolePrefix = "tenantA".some,
              allow = Seq("write")
            ).json.as[JsObject]
          )
        )
      )
    )

    val wrongPrefixApikey = ApiKey(
      clientName = "wrongprefix",
      clientId = "wrongprefix",
      clientSecret = "wrongprefix",
      authorizedEntities = Seq(RouteIdentifier(route.id)),
      tags = Seq("tenantB:role:write")
    )

    createOtoroshiApiKey(wrongPrefixApikey).futureValue

    val wrongPrefixCall = ws
      .url(s"http://127.0.0.1:$port/api")
      .withAuth(wrongPrefixApikey.clientId, wrongPrefixApikey.clientSecret, WSAuthScheme.BASIC)
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    wrongPrefixCall.status mustBe Status.FORBIDDEN

    deletePluginsRouteApiKeys(route.id)
    deleteOtoroshiApiKey(wrongPrefixApikey)
    deleteOtoroshiRoute(route).futureValue
  }

  def testDenyRules(): Unit = {
    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(plugin = NgPluginHelper.pluginId[ApikeyAuthModule]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[RBAC],
          config = NgPluginInstanceConfig(
            RBACConfig(
              roles = "roles",
              rolePrefix = "tenantA".some,
              allow = Seq("write", "admin"),
              deny = Seq("blocked")
            ).json.as[JsObject]
          )
        )
      )
    )

    val blockedApikey = ApiKey(
      clientName = "blocked",
      clientId = "blocked",
      clientSecret = "blocked",
      authorizedEntities = Seq(RouteIdentifier(route.id)),
      tags = Seq("tenantA:role:write", "tenantA:role:blocked")
    )

    createOtoroshiApiKey(blockedApikey).futureValue

    val blockedCall = ws
      .url(s"http://127.0.0.1:$port/api")
      .withAuth(blockedApikey.clientId, blockedApikey.clientSecret, WSAuthScheme.BASIC)
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    blockedCall.status mustBe Status.FORBIDDEN

    deletePluginsRouteApiKeys(route.id)
    deleteOtoroshiApiKey(blockedApikey)
    deleteOtoroshiRoute(route).futureValue
  }

  def testAllowAll(): Unit = {
    val route = createRequestOtoroshiIORoute(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(plugin = NgPluginHelper.pluginId[ApikeyAuthModule]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[RBAC],
          config = NgPluginInstanceConfig(
            RBACConfig(
              roles = "roles",
              rolePrefix = "tenantA".some,
              allow = Seq("write", "admin"),
              allowAll = true
            ).json.as[JsObject]
          )
        )
      )
    )

    val partialRolesApikey = ApiKey(
      clientName = "partial",
      clientId = "partial",
      clientSecret = "partial",
      authorizedEntities = Seq(RouteIdentifier(route.id)),
      tags = Seq("tenantA:role:write")
    )

    createOtoroshiApiKey(partialRolesApikey).futureValue

    val partialRolesCall = ws
      .url(s"http://127.0.0.1:$port/api")
      .withAuth(partialRolesApikey.clientId, partialRolesApikey.clientSecret, WSAuthScheme.BASIC)
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    partialRolesCall.status mustBe Status.FORBIDDEN

    val allRolesApikey = ApiKey(
      clientName = "allroles",
      clientId = "allroles",
      clientSecret = "allroles",
      authorizedEntities = Seq(RouteIdentifier(route.id)),
      tags = Seq("tenantA:role:write", "tenantA:role:admin")
    )

    createOtoroshiApiKey(allRolesApikey).futureValue

    val allRolesCall = ws
      .url(s"http://127.0.0.1:$port/api")
      .withAuth(allRolesApikey.clientId, allRolesApikey.clientSecret, WSAuthScheme.BASIC)
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue

    allRolesCall.status mustBe Status.OK

    deletePluginsRouteApiKeys(route.id)
    deleteOtoroshiApiKey(allRolesApikey)
    deleteOtoroshiApiKey(partialRolesApikey)
    deleteOtoroshiRoute(route).futureValue
  }
}
