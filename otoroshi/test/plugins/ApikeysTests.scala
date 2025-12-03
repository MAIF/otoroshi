package plugins

import functional.PluginsTestSpec
import otoroshi.models.{ApiKey, RouteIdentifier}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{
  AllowHttpMethods,
  ApikeyCalls,
  NgAllowedMethodsConfig,
  NgApikeyCallsConfig,
  NgApikeyExtractorCustomHeaders,
  NgApikeyExtractors,
  NgApikeyMatcher,
  OverrideHost
}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class ApikeysTests(parent: PluginsTestSpec) {
  import parent._

  def default() = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ApikeyCalls],
          config = NgPluginInstanceConfig(
            NgApikeyCallsConfig(
            ).json.as[JsObject]
          )
        )
      )
    )

    createPluginsRouteApiKeys(route.id)

    val unknownCaller = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    unknownCaller.status mustBe 400

    val authorizedCall = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host"                   -> route.frontend.domains.head.domain,
        "Otoroshi-Client-Id"     -> getValidApiKeyForPluginsRoute(route.id).clientId,
        "Otoroshi-Client-Secret" -> getValidApiKeyForPluginsRoute(route.id).clientSecret
      )
      .get()
      .futureValue

    authorizedCall.status mustBe Status.OK

    deletePluginsRouteApiKeys(route.id)
    deleteOtoroshiRoute(route).futureValue
  }

  def passApikeyToBackend() = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ApikeyCalls],
          config = NgPluginInstanceConfig(
            NgApikeyCallsConfig(
              wipeBackendRequest = false
            ).json.as[JsObject]
          )
        )
      )
    )

    val apikey = ApiKey(
      clientId = "apikey-test",
      clientSecret = "1234",
      clientName = "apikey-test",
      authorizedEntities = Seq(RouteIdentifier(route.id))
    )
    createOtoroshiApiKey(apikey).futureValue

    val authorizedCall = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host"                   -> route.frontend.domains.head.domain,
        "Otoroshi-Client-Id"     -> apikey.clientId,
        "Otoroshi-Client-Secret" -> apikey.clientSecret
      )
      .get()
      .futureValue

    authorizedCall.status mustBe Status.OK
    getInHeader(authorizedCall, "otoroshi-client-id").isDefined mustBe true
    getInHeader(authorizedCall, "otoroshi-client-secret").isDefined mustBe true

    deleteOtoroshiApiKey(apikey).futureValue
    deleteOtoroshiRoute(route).futureValue
  }

  def passApikeyToBackendWithCustomHeaders() = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ApikeyCalls],
          config = NgPluginInstanceConfig(
            NgApikeyCallsConfig(
              wipeBackendRequest = false,
              extractors = NgApikeyExtractors(
                customHeaders = NgApikeyExtractorCustomHeaders(
                  enabled = true,
                  clientIdHeaderName = Some("id"),
                  clientSecretHeaderName = Some("secret")
                )
              )
            ).json.as[JsObject]
          )
        )
      )
    )

    val apikey = ApiKey(
      clientId = "apikey-test",
      clientSecret = "1234",
      clientName = "apikey-test",
      authorizedEntities = Seq(RouteIdentifier(route.id))
    )
    createOtoroshiApiKey(apikey).futureValue

    {
      val call = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"                   -> route.frontend.domains.head.domain,
          "Otoroshi-Client-Id"     -> apikey.clientId,
          "Otoroshi-Client-Secret" -> apikey.clientSecret
        )
        .get()
        .futureValue

      call.status mustBe Status.BAD_REQUEST
    }
    val authorizedCall = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host"   -> route.frontend.domains.head.domain,
        "id"     -> apikey.clientId,
        "secret" -> apikey.clientSecret
      )
      .get()
      .futureValue

    authorizedCall.status mustBe Status.OK
    getInHeader(authorizedCall, "id").isDefined mustBe true
    getInHeader(authorizedCall, "secret").isDefined mustBe true

    deleteOtoroshiApiKey(apikey).futureValue
    deleteOtoroshiRoute(route).futureValue
  }

  def notMandatory() = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ApikeyCalls],
          config = NgPluginInstanceConfig(
            NgApikeyCallsConfig(
              mandatory = false
            ).json.as[JsObject]
          )
        )
      )
    )

    val apikey = ApiKey(
      clientId = "apikey-test",
      clientSecret = "1234",
      clientName = "apikey-test",
      authorizedEntities = Seq(RouteIdentifier(route.id))
    )
    createOtoroshiApiKey(apikey).futureValue

    {
      val call = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"                   -> route.frontend.domains.head.domain,
          "Otoroshi-Client-Id"     -> apikey.clientId,
          "Otoroshi-Client-Secret" -> apikey.clientSecret
        )
        .get()
        .futureValue

      call.status mustBe Status.OK
    }

    val authorizedCall = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    authorizedCall.status mustBe Status.OK

    deleteOtoroshiApiKey(apikey).futureValue
    deleteOtoroshiRoute(route).futureValue
  }

  private def testRoutingMatch(
      id: String,
      matcher: NgApikeyMatcher,
      goodKey: ApiKey,
      badKey: ApiKey,
      expectedStatusOnBad: Int = Status.NOT_FOUND,
      expectedStatusOnGood: Int = Status.OK
  ): Unit = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ApikeyCalls],
          config = NgPluginInstanceConfig(
            NgApikeyCallsConfig(routing = matcher.copy(enabled = true)).json
              .as[JsObject]
          )
        )
      ),
      domain = s"routing-$id.oto.tools".some,
      id
    )

    createOtoroshiApiKey(goodKey).futureValue
    createOtoroshiApiKey(badKey).futureValue

    def call(key: ApiKey) =
      ws.url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"                   -> route.frontend.domains.head.domain,
          "Otoroshi-Client-Id"     -> key.clientId,
          "Otoroshi-Client-Secret" -> key.clientSecret
        )
        .get()
        .futureValue

    call(badKey).status mustBe expectedStatusOnBad
    call(goodKey).status mustBe expectedStatusOnGood

    deleteOtoroshiApiKey(badKey).futureValue
    deleteOtoroshiApiKey(goodKey).futureValue
    deleteOtoroshiRoute(route).futureValue
  }

  def matchOneTagIn() = {
    val id = IdGenerator.uuid
    testRoutingMatch(
      id,
      matcher = NgApikeyMatcher(oneTagIn = Seq("foo")),
      goodKey =
        ApiKey("good", "secret", "good", tags = Seq("foo", "bar"), authorizedEntities = Seq(RouteIdentifier(id))),
      badKey = ApiKey("bad", "secret", "bad", tags = Seq("bar"), authorizedEntities = Seq(RouteIdentifier(id)))
    )
  }

  def matchAllTagsIn() = {
    val id = IdGenerator.uuid
    testRoutingMatch(
      id,
      matcher = NgApikeyMatcher(allTagsIn = Seq("a", "b")),
      goodKey = ApiKey(
        IdGenerator.uuid,
        "secret",
        "good",
        tags = Seq("a", "b", "c"),
        authorizedEntities = Seq(RouteIdentifier(id))
      ),
      badKey = ApiKey(IdGenerator.uuid, "secret", "bad", tags = Seq("a"), authorizedEntities = Seq(RouteIdentifier(id)))
    )
  }

  def matchOneMetaIn() = {
    val id = IdGenerator.uuid
    testRoutingMatch(
      id,
      matcher = NgApikeyMatcher(oneMetaIn = Map("team" -> "ops")),
      goodKey = ApiKey(
        IdGenerator.uuid,
        "secret",
        "good",
        tags = Seq.empty,
        metadata = Map("team" -> "ops"),
        authorizedEntities = Seq(RouteIdentifier(id))
      ),
      badKey = ApiKey(
        IdGenerator.uuid,
        "secret",
        "bad",
        metadata = Map("team" -> "dev"),
        authorizedEntities = Seq(RouteIdentifier(id))
      )
    )
  }

  def matchAllMetaIn() = {
    val id = IdGenerator.uuid
    testRoutingMatch(
      id,
      matcher = NgApikeyMatcher(allMetaIn = Map("env" -> "prod", "team" -> "ops")),
      goodKey = ApiKey(
        IdGenerator.uuid,
        "secret",
        "good",
        metadata = Map("env" -> "prod", "team" -> "ops", "extra" -> "x"),
        authorizedEntities = Seq(RouteIdentifier(id))
      ),
      badKey = ApiKey(
        IdGenerator.uuid,
        "secret",
        "bad",
        metadata = Map("env" -> "prod"),
        authorizedEntities = Seq(RouteIdentifier(id))
      )
    )
  }

  def respectNoneMetaIn() = {
    val id = IdGenerator.uuid
    testRoutingMatch(
      id,
      matcher = NgApikeyMatcher(noneMetaIn = Map("role" -> "admin")),
      goodKey = ApiKey(
        IdGenerator.uuid,
        "secret",
        "good",
        metadata = Map("role" -> "user"),
        authorizedEntities = Seq(RouteIdentifier(id))
      ),
      badKey = ApiKey(
        IdGenerator.uuid,
        "secret",
        "bad",
        metadata = Map("role" -> "admin"),
        authorizedEntities = Seq(RouteIdentifier(id))
      )
    )
  }

  def matchOneMetaKeyIn() = {
    val id = IdGenerator.uuid
    testRoutingMatch(
      id,
      matcher = NgApikeyMatcher(oneMetaKeyIn = Seq("team")),
      goodKey = ApiKey(
        IdGenerator.uuid,
        "secret",
        "good",
        metadata = Map("team" -> "ops"),
        authorizedEntities = Seq(RouteIdentifier(id))
      ),
      badKey = ApiKey(
        IdGenerator.uuid,
        "secret",
        "bad",
        metadata = Map("env" -> "dev"),
        authorizedEntities = Seq(RouteIdentifier(id))
      )
    )
  }

  def matchAllMetaKeysIn() = {
    val id = IdGenerator.uuid
    testRoutingMatch(
      id,
      matcher = NgApikeyMatcher(allMetaKeysIn = Seq("team", "env")),
      goodKey = ApiKey(
        IdGenerator.uuid,
        "secret",
        "good",
        metadata = Map("team" -> "ops", "env" -> "prod"),
        authorizedEntities = Seq(RouteIdentifier(id))
      ),
      badKey = ApiKey(
        IdGenerator.uuid,
        "secret",
        "bad",
        metadata = Map("team" -> "ops"),
        authorizedEntities = Seq(RouteIdentifier(id))
      )
    )
  }

  def respectNoneMetaKeysIn() = {
    val id = IdGenerator.uuid
    testRoutingMatch(
      id,
      matcher = NgApikeyMatcher(noneMetaKeysIn = Seq("secret")),
      goodKey = ApiKey(
        IdGenerator.uuid,
        "secret",
        "good",
        metadata = Map("team" -> "ops"),
        authorizedEntities = Seq(RouteIdentifier(id))
      ),
      badKey = ApiKey(
        IdGenerator.uuid,
        "secret",
        "bad",
        metadata = Map("secret" -> "xxx"),
        authorizedEntities = Seq(RouteIdentifier(id))
      )
    )
  }

}
