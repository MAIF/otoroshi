package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{NgErrorRewriter, NgErrorRewriterConfig, OverrideHost, ResponseStatusRange}
import play.api.http.Status
import play.api.libs.json.JsObject
import play.api.libs.ws.DefaultBodyReadables.readableAsString

class ErrorResponseRewriteTests(parent: PluginsTestSpec) {
  import parent.{*, given}

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgErrorRewriter],
        config = NgPluginInstanceConfig(
          NgErrorRewriterConfig(
            ranges = Seq(ResponseStatusRange(200, 299)),
            templates = Map(
              "default"          -> "custom response",
              "application/json" -> "custom json response"
            ),
            log = false,
            `export` = false
          ).json.as[JsObject]
        )
      )
    )
  ).futureValue

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
    resp.body[String] mustEqual "custom response"
    resp.header("x-otoroshi-error-id").exists(_.nonEmpty) mustBe true
    resp.header("x-otoroshi-req-id").exists(_.nonEmpty) mustBe true
  }

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host"   -> route.frontend.domains.head.domain,
        "Accept" -> "application/json"
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
    resp.body.mustEqual("custom json response")
  }

  // no configured template matches the client Accept -> fallback on the otoroshi error template (negotiated json)
  // and hardening headers are applied on the rewritten response
  val routeFallback = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgErrorRewriter],
        config = NgPluginInstanceConfig(
          NgErrorRewriterConfig(
            ranges = Seq(ResponseStatusRange(200, 299)),
            templates = Map.empty,
            log = false,
            `export` = false,
            useOtoroshiErrorTemplate = true,
            additionalHeaders = Map("x-hardening" -> "on")
          ).json.as[JsObject]
        )
      )
    )
  ).futureValue

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host"   -> routeFallback.frontend.domains.head.domain,
        "Accept" -> "application/json"
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
    resp.header("x-hardening") mustEqual Some("on")
    resp.header("x-otoroshi-error-id").exists(_.nonEmpty) mustBe true
    (resp.json \ "otoroshi-error-id").asOpt[String].isDefined mustBe true
  }

  deleteOtoroshiRoute(routeFallback).futureValue

  // hierarchical keys: "<status>-<content-type>" beats "<content-type>", and "<content-type>" is negotiated
  val routeKeys = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgErrorRewriter],
        config = NgPluginInstanceConfig(
          NgErrorRewriterConfig(
            ranges = Seq(ResponseStatusRange(200, 299)),
            templates = Map(
              "200-application/json" -> "json-200",
              "application/json"     -> "json-generic",
              "text/html"            -> "html-generic",
              "default"              -> "the-default"
            ),
            log = false,
            `export` = false
          ).json.as[JsObject]
        )
      )
    )
  ).futureValue

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host"   -> routeKeys.frontend.domains.head.domain,
        "Accept" -> "application/json"
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
    resp.body[String] mustEqual "json-200"
  }

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host"   -> routeKeys.frontend.domains.head.domain,
        "Accept" -> "text/html"
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
    resp.body[String] mustEqual "html-generic"
  }

  deleteOtoroshiRoute(routeKeys).futureValue

  deleteOtoroshiRoute(route).futureValue
}
