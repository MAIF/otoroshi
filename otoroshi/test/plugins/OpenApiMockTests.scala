package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgRoute}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{OpenApiMock, OpenApiMockConfig, OpenApiMockResponses, OpenApiMockSpec, OverrideHost}
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import play.api.libs.ws.WSBodyReadables.given
import play.api.libs.ws.WSBodyWritables.given

class OpenApiMockTests(parent: PluginsTestSpec) {
  import parent.*

  private val petstore: String =
    """{
      |  "openapi": "3.0.3",
      |  "info": { "title": "pets", "version": "1.0.0" },
      |  "servers": [ { "url": "https://api.example.com/v1" } ],
      |  "paths": {
      |    "/status": {
      |      "get": { "responses": { "200": { "description": "ok", "content": { "application/json": { "example": { "status": "up" } } } } } }
      |    },
      |    "/types": {
      |      "get": { "responses": { "200": { "description": "ok", "content": { "application/json": { "schema": { "$ref": "#/components/schemas/Types" } } } } } }
      |    },
      |    "/pets": {
      |      "get": { "responses": { "200": { "description": "ok", "content": { "application/json": { "schema": { "type": "array", "items": { "$ref": "#/components/schemas/Pet" } } } } } } },
      |      "post": { "responses": { "201": { "description": "created", "content": { "application/json": { "schema": { "$ref": "#/components/schemas/Pet" } } } } } }
      |    },
      |    "/pets/mine": {
      |      "get": { "responses": { "200": { "description": "ok", "content": { "application/json": { "example": { "mine": true } } } } } }
      |    },
      |    "/pets/{petId}": {
      |      "get": {
      |        "responses": {
      |          "200": { "description": "ok", "content": { "application/json": { "schema": { "$ref": "#/components/schemas/Pet" } } } },
      |          "404": { "description": "not found", "content": { "application/json": { "example": { "error": "not found" } } } }
      |        }
      |      },
      |      "put": { "responses": { "200": { "description": "ok", "content": { "application/json": { "schema": { "$ref": "#/components/schemas/Pet" } } } } } },
      |      "patch": { "responses": { "200": { "description": "ok", "content": { "application/json": { "schema": { "$ref": "#/components/schemas/Pet" } } } } } },
      |      "delete": { "responses": { "204": { "description": "deleted" } } }
      |    }
      |  },
      |  "components": {
      |    "schemas": {
      |      "Pet": {
      |        "type": "object",
      |        "properties": {
      |          "id": { "type": "integer", "format": "int64" },
      |          "name": { "type": "string", "example": "Rex" },
      |          "tag": { "type": "string", "nullable": true }
      |        }
      |      },
      |      "Types": {
      |        "type": "object",
      |        "properties": {
      |          "uuid": { "type": "string", "format": "uuid" },
      |          "date": { "type": "string", "format": "date" },
      |          "dateTime": { "type": "string", "format": "date-time" },
      |          "email": { "type": "string", "format": "email" },
      |          "count": { "type": "integer" },
      |          "ratio": { "type": "number" },
      |          "flag": { "type": "boolean" },
      |          "color": { "type": "string", "enum": [ "red", "blue" ] },
      |          "nullable31": { "type": [ "string", "null" ] },
      |          "tree": { "$ref": "#/components/schemas/Node" },
      |          "merged": { "allOf": [ { "$ref": "#/components/schemas/Base" }, { "type": "object", "properties": { "extra": { "type": "string" } } } ] },
      |          "choice": { "oneOf": [ { "type": "integer" }, { "type": "string" } ] }
      |        }
      |      },
      |      "Node": {
      |        "type": "object",
      |        "properties": {
      |          "name": { "type": "string" },
      |          "children": { "type": "array", "items": { "$ref": "#/components/schemas/Node" } }
      |        }
      |      },
      |      "Base": { "type": "object", "properties": { "base": { "type": "boolean" } } }
      |    }
      |  }
      |}""".stripMargin

  private val yamlSpec: String =
    """openapi: 3.0.0
      |info:
      |  title: yaml
      |  version: "1"
      |paths:
      |  /hello:
      |    get:
      |      responses:
      |        "200":
      |          description: ok
      |          content:
      |            application/json:
      |              example:
      |                hello: world
      |""".stripMargin

  private def mockRoute(config: OpenApiMockConfig, backend: JsValue = Json.obj()): NgRoute = {
    createLocalRoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OpenApiMockResponses],
          config = NgPluginInstanceConfig(config.json.as[JsObject])
        )
      ),
      result = _ => backend,
      frontendPath = "/"
    ).futureValue
  }

  private def call(
      route: NgRoute,
      method: String,
      path: String,
      body: Option[JsValue] = None,
      headers: Seq[(String, String)] = Seq.empty
  ): (Int, String) = {
    val request = ws
      .url(s"http://127.0.0.1:$port$path")
      .withHttpHeaders(Seq("Host" -> route.frontend.domains.head.domain, "Content-Type" -> "application/json") ++ headers*)
      .withMethod(method)

    val response = body match {
      case Some(json) => request.withBody(json.stringify).execute().futureValue
      case None       => request.execute().futureValue
    }

    (response.status, response.body[String])
  }

  private def callJson(
      route: NgRoute,
      method: String,
      path: String,
      body: Option[JsValue] = None,
      headers: Seq[(String, String)] = Seq.empty
  ): (Int, JsValue) = {
    val (status, raw) = call(route, method, path, body, headers)

    (status, Json.parse(raw))
  }

  def responses(): Unit = {
    val route = mockRoute(OpenApiMockConfig(content = petstore.some))

    callJson(route, "GET", "/status") mustBe 200 -> Json.obj("status" -> "up")
    callJson(route, "GET", "/v1/status") mustBe 200 -> Json.obj("status" -> "up")
    callJson(route, "GET", "/pets/mine") mustBe 200 -> Json.obj("mine" -> true)
    callJson(route, "GET", "/pets/1", headers = Seq("Prefer" -> "code=404")) mustBe 404 -> Json.obj("error" -> "not found")
    call(route, "DELETE", "/pets/1") mustBe 204 -> ""
    call(route, "GET", "/unknown")._1 mustBe 404

    callJson(route, "GET", "/types") mustBe (
      200 -> Json.obj(
        "uuid"       -> "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        "date"       -> "2024-01-01",
        "dateTime"   -> "2024-01-01T00:00:00Z",
        "email"      -> "user@example.com",
        "count"      -> 0,
        "ratio"      -> 0,
        "flag"       -> true,
        "color"      -> "red",
        "nullable31" -> "string",
        "tree"       -> Json.obj("name" -> "string", "children" -> Json.arr()),
        "merged"     -> Json.obj("base" -> true, "extra" -> "string"),
        "choice"     -> 0
      )
    )

    deleteOtoroshiRoute(route).futureValue
  }

  def stateful(): Unit = {
    val route = mockRoute(OpenApiMockConfig(content = petstore.some))

    callJson(route, "GET", "/pets") mustBe 200 -> Json.arr(Json.obj("id" -> 0, "name" -> "Rex", "tag" -> "string"))

    callJson(route, "POST", "/pets", Json.obj("name" -> "Medor").some) mustBe (
      201 -> Json.obj("id" -> 1, "name" -> "Medor", "tag" -> "string")
    )
    callJson(route, "POST", "/pets", Json.obj("id" -> 10, "name" -> "Felix").some) mustBe (
      201 -> Json.obj("id" -> 10, "name" -> "Felix", "tag" -> "string")
    )
    callJson(route, "GET", "/pets/1") mustBe 200 -> Json.obj("id" -> 1, "name" -> "Medor", "tag" -> "string")
    callJson(route, "GET", "/pets") mustBe (
      200 -> Json.arr(
        Json.obj("id" -> 1, "name"  -> "Medor", "tag" -> "string"),
        Json.obj("id" -> 10, "name" -> "Felix", "tag" -> "string")
      )
    )

    callJson(route, "PATCH", "/pets/1", Json.obj("tag" -> "dog").some) mustBe (
      200 -> Json.obj("id" -> 1, "name" -> "Medor", "tag" -> "dog")
    )
    callJson(route, "PUT", "/pets/10", Json.obj("name" -> "Tom").some) mustBe (
      200 -> Json.obj("id" -> 10, "name" -> "Tom", "tag" -> "string")
    )

    call(route, "DELETE", "/pets/1") mustBe 204 -> ""
    callJson(route, "GET", "/pets") mustBe 200 -> Json.arr(Json.obj("id" -> 10, "name" -> "Tom", "tag" -> "string"))
    callJson(route, "GET", "/pets/42") mustBe 200 -> Json.obj("id" -> 42, "name" -> "Rex", "tag" -> "string")

    deleteOtoroshiRoute(route).futureValue
  }

  def stateless(): Unit = {
    val route = mockRoute(OpenApiMockConfig(content = petstore.some, stateful = false))

    callJson(route, "POST", "/pets", Json.obj("name" -> "Medor").some) mustBe (
      201 -> Json.obj("id" -> 0, "name" -> "Rex", "tag" -> "string")
    )
    callJson(route, "GET", "/pets") mustBe 200 -> Json.arr(Json.obj("id" -> 0, "name" -> "Rex", "tag" -> "string"))

    deleteOtoroshiRoute(route).futureValue
  }

  def sources(): Unit = {
    val specRoute = createLocalRoute(result = _ => Json.parse(petstore), frontendPath = "/").futureValue
    val specUrl   = s"http://127.0.0.1:${specRoute.backend.targets.head.port}/openapi.json"
    val fromUrl   = mockRoute(OpenApiMockConfig(url = specUrl.some))
    val fromYaml  = mockRoute(OpenApiMockConfig(content = yamlSpec.some))
    val invalid   = mockRoute(OpenApiMockConfig(content = "this is not a spec".some))

    callJson(fromUrl, "GET", "/status") mustBe 200 -> Json.obj("status" -> "up")
    callJson(fromYaml, "GET", "/hello") mustBe 200 -> Json.obj("hello" -> "world")
    call(invalid, "GET", "/hello")._1 mustBe 502

    deleteOtoroshiRoute(fromUrl).futureValue
    deleteOtoroshiRoute(fromYaml).futureValue
    deleteOtoroshiRoute(invalid).futureValue
    deleteOtoroshiRoute(specRoute).futureValue
  }

  def seed(): Unit = {
    val seed  = Json.obj("/pets" -> Json.arr(Json.obj("id" -> 1, "name" -> "Seeded"), Json.obj("name" -> "NoId")))
    val route = mockRoute(OpenApiMockConfig(content = petstore.some, seed = seed))

    callJson(route, "GET", "/pets/1") mustBe 200 -> Json.obj("id" -> 1, "name" -> "Seeded")
    callJson(route, "GET", "/pets") mustBe 200 -> Json.arr(
      Json.obj("id" -> 1, "name" -> "Seeded"),
      Json.obj("id" -> 2, "name" -> "NoId")
    )

    callJson(route, "POST", "/pets", Json.obj("name" -> "New").some) mustBe (
      201 -> Json.obj("id" -> 3, "name" -> "New", "tag" -> "string")
    )
    call(route, "DELETE", "/pets/1") mustBe 204 -> ""
    callJson(route, "GET", "/pets/1") mustBe 200 -> Json.obj("id" -> 1, "name" -> "Rex", "tag" -> "string")
    callJson(route, "GET", "/_mock/state") mustBe 200 -> Json.obj(
      "/pets" -> Json.arr(Json.obj("id" -> 2, "name" -> "NoId"), Json.obj("id" -> 3, "name" -> "New", "tag" -> "string"))
    )

    callJson(route, "DELETE", "/_mock/state") mustBe 200 -> Json.obj(
      "/pets" -> Json.arr(Json.obj("id" -> 1, "name" -> "Seeded"), Json.obj("id" -> 2, "name" -> "NoId"))
    )

    val pushed = Json.obj("/pets" -> Json.arr(Json.obj("id" -> 7, "name" -> "Pushed")))

    callJson(route, "PUT", "/_mock/state", pushed.some) mustBe 200 -> pushed
    callJson(route, "GET", "/pets/7") mustBe 200 -> Json.obj("id" -> 7, "name" -> "Pushed")
    callJson(route, "GET", "/pets/1") mustBe 200 -> Json.obj("id" -> 1, "name" -> "Rex", "tag" -> "string")
    call(route, "PUT", "/_mock/state", Json.obj("/unknown" -> Json.arr()).some)._1 mustBe 400
    call(route, "PUT", "/_mock/state", Json.obj("/pets" -> Json.arr(1)).some)._1 mustBe 400
    callJson(route, "GET", "/pets/7") mustBe 200 -> Json.obj("id" -> 7, "name" -> "Pushed")

    deleteOtoroshiRoute(route).futureValue
  }

  def readOnly(): Unit = {
    val seed  = Json.obj("/pets" -> Json.arr(Json.obj("id" -> 1, "name" -> "Seeded")))
    val route = mockRoute(OpenApiMockConfig(content = petstore.some, seed = seed, readOnly = true))

    callJson(route, "POST", "/pets", Json.obj("name" -> "New").some) mustBe (
      201 -> Json.obj("id" -> 2, "name" -> "New", "tag" -> "string")
    )
    call(route, "DELETE", "/pets/1") mustBe 204 -> ""
    callJson(route, "GET", "/pets") mustBe 200 -> Json.arr(Json.obj("id" -> 1, "name" -> "Seeded"))
    callJson(route, "GET", "/pets/1") mustBe 200 -> Json.obj("id" -> 1, "name" -> "Seeded")

    deleteOtoroshiRoute(route).futureValue
  }

  def invalidSeed(): Unit = {
    val route = mockRoute(OpenApiMockConfig(content = petstore.some, seed = Json.obj("/nope" -> Json.arr())))

    call(route, "GET", "/status")._1 mustBe 502

    deleteOtoroshiRoute(route).futureValue
  }

  def resetOnChange(): Unit = {
    val spec    = OpenApiMockSpec.parse(petstore).toOption.get
    val id      = s"reset-on-change-${System.nanoTime()}"
    val initial = OpenApiMockConfig(content = petstore.some)
    val item    = Json.obj("id" -> 1, "name" -> "Written")

    OpenApiMock.store(id, initial, spec).put("/pets", "1", item)
    OpenApiMock.store(id, initial.copy(readOnly = true), spec).get("/pets", "1") mustBe item.some

    val newSeed = initial.copy(seed = Json.obj("/pets" -> Json.arr(Json.obj("id" -> 2))))

    OpenApiMock.store(id, newSeed, spec).get("/pets", "1") mustBe None

    val keepData = newSeed.copy(resetOnChange = false)

    OpenApiMock.store(id, keepData, spec).put("/pets", "1", item)
    OpenApiMock.store(id, keepData.copy(seed = Json.obj()), spec).get("/pets", "1") mustBe item.some
    OpenApiMock.store(id, keepData.copy(seed = Json.obj(), maxSizeMb = 10), spec).get("/pets", "1") mustBe item.some
  }

  def passThrough(): Unit = {
    val route = mockRoute(
      OpenApiMockConfig(content = petstore.some, passThrough = true),
      backend = Json.obj("from" -> "backend")
    )

    callJson(route, "GET", "/status") mustBe 200 -> Json.obj("status" -> "up")
    callJson(route, "GET", "/unknown") mustBe 200 -> Json.obj("from" -> "backend")

    deleteOtoroshiRoute(route).futureValue
  }
}
