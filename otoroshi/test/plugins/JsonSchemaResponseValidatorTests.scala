package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class JsonSchemaResponseValidatorTests(parent: PluginsTestSpec) {

  import parent._

  private val schema =
    """{
      |  "type": "object",
      |  "required": ["id", "email"],
      |  "properties": {
      |    "id":    { "type": "string" },
      |    "email": { "type": "string", "format": "email" }
      |  }
      |}""".stripMargin

  private def routePlugins(failOnValidationError: Boolean) = Seq(
    NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
    NgPluginInstance(
      plugin = NgPluginHelper.pluginId[JsonSchemaResponseValidator],
      config = NgPluginInstanceConfig(
        JsonSchemaValidatorConfig(
          schema = Some(schema),
          failOnValidationError = failOnValidationError
        ).json.as[JsObject]
      )
    )
  )

  def acceptsValidResponse() = {
    val route = createLocalRoute(
      plugins = routePlugins(failOnValidationError = true),
      result = _ => Json.obj("id" -> "u-1", "email" -> "alice@example.com")
    ).futureValue
    val call  = ws
      .url(s"http://127.0.0.1:$port/api/users")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue
    call.status mustBe Status.OK
    Json.parse(call.body) mustBe Json.obj("id" -> "u-1", "email" -> "alice@example.com")
    deleteOtoroshiRoute(route).futureValue
  }

  def rejectsInvalidResponseWithFailOn() = {
    val route = createLocalRoute(
      plugins = routePlugins(failOnValidationError = true),
      result = _ => Json.obj("id" -> "u-1") // missing required "email"
    ).futureValue
    val call  = ws
      .url(s"http://127.0.0.1:$port/api/users")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue
    call.status mustBe Status.BAD_GATEWAY
    val body = Json.parse(call.body)
    (body \ "error").asOpt[String] mustBe Some("response body does not match the json schema")
    (body \ "validation_errors").asOpt[Seq[String]].exists(_.nonEmpty) mustBe true
    deleteOtoroshiRoute(route).futureValue
  }

  def passThroughWithFailOff() = {
    val route = createLocalRoute(
      plugins = routePlugins(failOnValidationError = false),
      result = _ => Json.obj("id" -> "u-1") // invalid but tolerated
    ).futureValue
    val call  = ws
      .url(s"http://127.0.0.1:$port/api/users")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .get()
      .futureValue
    call.status mustBe Status.OK
    Json.parse(call.body) mustBe Json.obj("id" -> "u-1")
    deleteOtoroshiRoute(route).futureValue
  }
}
