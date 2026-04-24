package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class JsonSchemaRequestValidatorTests(parent: PluginsTestSpec) {

  import parent._

  private val schema =
    """{
      |  "type": "object",
      |  "required": ["name", "age"],
      |  "properties": {
      |    "name": { "type": "string" },
      |    "age":  { "type": "integer", "minimum": 0 }
      |  }
      |}""".stripMargin

  private def routePlugins(failOnValidationError: Boolean) = Seq(
    NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
    NgPluginInstance(
      plugin = NgPluginHelper.pluginId[JsonSchemaRequestValidator],
      config = NgPluginInstanceConfig(
        JsonSchemaValidatorConfig(
          schema = Some(schema),
          failOnValidationError = failOnValidationError
        ).json.as[JsObject]
      )
    )
  )

  def acceptsValidBody() = {
    val route = createRouteWithExternalTarget(routePlugins(failOnValidationError = true)).futureValue
    val call  = ws
      .url(s"http://127.0.0.1:$port/api/users")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain, "Content-Type" -> "application/json")
      .post(Json.stringify(Json.obj("name" -> "Alice", "age" -> 32)))
      .futureValue
    call.status mustBe Status.OK
    deleteOtoroshiRoute(route).futureValue
  }

  def rejectsInvalidBodyWithFailOn() = {
    val route = createRouteWithExternalTarget(routePlugins(failOnValidationError = true)).futureValue
    val call  = ws
      .url(s"http://127.0.0.1:$port/api/users")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain, "Content-Type" -> "application/json")
      .post(Json.stringify(Json.obj("name" -> "Alice"))) // missing required "age"
      .futureValue
    call.status mustBe Status.UNPROCESSABLE_ENTITY
    val body = Json.parse(call.body)
    (body \ "error").asOpt[String] mustBe Some("request body does not match the json schema")
    (body \ "validation_errors").asOpt[Seq[String]].exists(_.nonEmpty) mustBe true
    deleteOtoroshiRoute(route).futureValue
  }

  def passThroughWithFailOff() = {
    val route = createRouteWithExternalTarget(routePlugins(failOnValidationError = false)).futureValue
    val call  = ws
      .url(s"http://127.0.0.1:$port/api/users")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain, "Content-Type" -> "application/json")
      .post(Json.stringify(Json.obj("name" -> "Alice"))) // invalid but tolerated
      .futureValue
    call.status mustBe Status.OK
    deleteOtoroshiRoute(route).futureValue
  }

  def skipsNonJsonContentType() = {
    val route = createRouteWithExternalTarget(routePlugins(failOnValidationError = true)).futureValue
    val call  = ws
      .url(s"http://127.0.0.1:$port/api/users")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain, "Content-Type" -> "text/plain")
      .post("not json at all") // would fail validation but skipped because not json
      .futureValue
    call.status mustBe Status.OK
    deleteOtoroshiRoute(route).futureValue
  }
}
