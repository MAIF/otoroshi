package otoroshi.api

import otoroshi.env.Env
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import otoroshi.utils.syntax.implicits._

import java.nio.charset.StandardCharsets
import scala.collection.concurrent.TrieMap

object OpenApi {

  private val cache = new TrieMap[String, String]()

  private def buildCount(resource: Resource): JsObject = {
    Json.obj(
      "get" -> Json.obj(
        "tags" -> Json.arr(resource.singularName, resource.group),
        "summary" -> s"Get number of entity of kind ${resource.kind}",
        "operationId" -> s"${resource.group}.${resource.kind}.count",
        "parameters" -> Json.arr(),
        "security" -> Json.arr(Json.obj(
          "otoroshi_auth" -> Json.arr()
        )),
        "responses" -> Json.obj(
          "401" -> Json.obj(
            "description" -> "You have to provide an Api Key. Api Key can be passed using basic http authentication",
            "content" -> Json.obj(
              "application/json" -> Json.obj(
                "schema" -> Json.obj(
                  "$ref" -> "#/components/schemas/ErrorResponse"
                )
              )
            )
          ),
          "400" -> Json.obj(
            "description" -> "Bad resource format. Take another look to the swagger, or open an issue",
            "content" -> Json.obj(
              "application/json" -> Json.obj(
                "schema" -> Json.obj(
                  "$ref" -> "#/components/schemas/ErrorResponse"
                )
              )
            )
          ),
          "404" -> Json.obj(
            "description" -> "Resource not found or does not exist",
            "content" -> Json.obj(
              "application/json" -> Json.obj(
                "schema" -> Json.obj(
                  "$ref" -> "#/components/schemas/ErrorResponse"
                )
              )
            )
          ),
          "200" -> Json.obj(
            "description" -> "Successful operation",
            "content" -> Json.obj(
              "application/json" -> Json.obj(
                "schema" -> Json.obj(
                  "description" -> "Resources count",
                  "type" -> "object",
                  "properties" -> Json.obj(
                    "count" -> Json.obj(
                      "type" -> "number",
                      "description" -> "the number of resources",
                    )
                  )
                )
              )
            )
          )
        )
      )
    )
  }

  private def buildById(resource: Resource): JsObject = {
    Json.obj()
      .applyOnIf(resource.access.canRead) { obj =>
        obj ++ Json.obj(
          "get" -> Json.obj(
            "tags" -> Json.arr(resource.singularName, resource.group),
            "summary" -> s"Find resource of kind ${resource.kind} by its id",
            "operationId" -> s"${resource.group}.${resource.kind}.findById",
            "parameters" -> Json.arr(Json.obj(
              "name" -> "id",
              "in" -> "path",
              "schema" -> Json.obj(
                "type" -> "string"
              ),
              "required" -> true,
              "description" -> "The id param of the target entity"
            )),
            "security" -> Json.arr(Json.obj(
              "otoroshi_auth" -> Json.arr()
            )),
            "responses" -> Json.obj(
              "401" -> Json.obj(
                "description" -> "You have to provide an Api Key. Api Key can be passed using basic http authentication",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "400" -> Json.obj(
                "description" -> "Bad resource format. Take another look to the swagger, or open an issue",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "404" -> Json.obj(
                "description" -> "Resource not found or does not exist",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "200" -> Json.obj(
                "description" -> "Successful operation",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "description" -> s"Resource of kind ${resource.kind}",
                      "$ref" -> s"#/components/schemas/${resource.group}.${resource.kind}"
                    )
                  )
                )
              )
            )
          )
        )
      }
      .applyOnIf(resource.access.canCreate) { obj =>
        obj ++ Json.obj()
      }
      .applyOnIf(resource.access.canUpdate) { obj =>
        obj ++ Json.obj(
          "put" -> Json.obj(
            "tags" -> Json.arr(resource.singularName, resource.group),
            "summary" -> s"Updates a specific ${resource.kind} using its id",
            "operationId" -> s"${resource.group}.${resource.kind}.updateById",
            "parameters" -> Json.arr(Json.obj(
              "name" -> "id",
              "in" -> "path",
              "schema" -> Json.obj(
                "type" -> "string"
              ),
              "required" -> true,
              "description" -> "The id param of the target entity"
            )),
            "security" -> Json.arr(Json.obj(
              "otoroshi_auth" -> Json.arr()
            )),
            "responses" -> Json.obj(
              "401" -> Json.obj(
                "description" -> "You have to provide an Api Key. Api Key can be passed using basic http authentication",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "400" -> Json.obj(
                "description" -> "Bad resource format. Take another look to the swagger, or open an issue",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "404" -> Json.obj(
                "description" -> "Resource not found or does not exist",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "200" -> Json.obj(
                "description" -> "Successful operation",
                "content" -> Json.obj(
                  "application/x-ndjson" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> s"#/components/schemas/${resource.group}.${resource.kind}"
                    )
                  )
                )
              )
            ),
            "requestBody" -> Json.obj(
              "description" -> "the request body in nd-json format (1 stringified entity per line)",
              "required" -> true,
              "content" -> Json.obj(
                "application/x-ndjson" -> Json.obj(
                  "schema" -> Json.obj(
                    "$ref" -> s"#/components/schemas/${resource.group}.${resource.kind}"
                  )
                )
              )
            )
          ),
          "patch" -> Json.obj(
            "tags" -> Json.arr(resource.singularName, resource.group),
            "summary" -> s"Updates (using json-patch) a specific ${resource.kind} using its id",
            "operationId" -> s"${resource.group}.${resource.kind}.pathById",
            "parameters" -> Json.arr(Json.obj(
              "name" -> "id",
              "in" -> "path",
              "schema" -> Json.obj(
                "type" -> "string"
              ),
              "required" -> true,
              "description" -> "The id param of the target entity"
            )),
            "security" -> Json.arr(Json.obj(
              "otoroshi_auth" -> Json.arr()
            )),
            "responses" -> Json.obj(
              "401" -> Json.obj(
                "description" -> "You have to provide an Api Key. Api Key can be passed using basic http authentication",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "400" -> Json.obj(
                "description" -> "Bad resource format. Take another look to the swagger, or open an issue",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "404" -> Json.obj(
                "description" -> "Resource not found or does not exist",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "200" -> Json.obj(
                "description" -> "Successful operation",
                "content" -> Json.obj(
                  "application/x-ndjson" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> s"#/components/schemas/${resource.group}.${resource.kind}"
                    )
                  )
                )
              )
            ),
            "requestBody" -> Json.obj(
              "description" -> "the request body in nd-json format (1 stringified entity per line)",
              "required" -> true,
              "content" -> Json.obj(
                "application/x-ndjson" -> Json.obj(
                  "schema" -> Json.obj(
                    "$ref" -> s"#/components/schemas/${resource.group}.${resource.kind}"
                  )
                )
              )
            )
          )
        )
      }
      .applyOnIf(resource.access.canDelete) { obj =>
        obj ++ Json.obj(
          "delete" -> Json.obj(
            "tags" -> Json.arr(resource.singularName, resource.group),
            "summary" -> s"Deletes a specific ${resource.kind} using its id",
            "operationId" -> s"${resource.group}.${resource.kind}.deleteById",
            "parameters" -> Json.arr(Json.obj(
              "name" -> "id",
              "in" -> "path",
              "schema" -> Json.obj(
                "type" -> "string"
              ),
              "required" -> true,
              "description" -> "The id param of the target entity"
            )),
            "security" -> Json.arr(Json.obj(
              "otoroshi_auth" -> Json.arr()
            )),
            "responses" -> Json.obj(
              "401" -> Json.obj(
                "description" -> "You have to provide an Api Key. Api Key can be passed using basic http authentication",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "400" -> Json.obj(
                "description" -> "Bad resource format. Take another look to the swagger, or open an issue",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "404" -> Json.obj(
                "description" -> "Resource not found or does not exist",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "200" -> Json.obj(
                "description" -> "Successful operation",
                "content" -> Json.obj(
                  "application/x-ndjson" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> s"#/components/schemas/${resource.group}.${resource.kind}"
                    )
                  )
                )
              )
            )
          )
        )
      }
  }

  private def buildTemplate(resource: Resource): JsObject = {
    Json.obj(
      "get" -> Json.obj(
        "tags" -> Json.arr(resource.singularName, resource.group),
        "summary" -> s"Return a template of a resource of kind ${resource.kind}",
        "operationId" -> s"${resource.group}.${resource.kind}.template",
        "parameters" -> Json.arr(),
        "security" -> Json.arr(Json.obj(
          "otoroshi_auth" -> Json.arr()
        )),
        "responses" -> Json.obj(
          "401" -> Json.obj(
            "description" -> "You have to provide an Api Key. Api Key can be passed using basic http authentication",
            "content" -> Json.obj(
              "application/json" -> Json.obj(
                "schema" -> Json.obj(
                  "$ref" -> "#/components/schemas/ErrorResponse"
                )
              )
            )
          ),
          "400" -> Json.obj(
            "description" -> "Bad resource format. Take another look to the swagger, or open an issue",
            "content" -> Json.obj(
              "application/json" -> Json.obj(
                "schema" -> Json.obj(
                  "$ref" -> "#/components/schemas/ErrorResponse"
                )
              )
            )
          ),
          "404" -> Json.obj(
            "description" -> "Resource not found or does not exist",
            "content" -> Json.obj(
              "application/json" -> Json.obj(
                "schema" -> Json.obj(
                  "$ref" -> "#/components/schemas/ErrorResponse"
                )
              )
            )
          ),
          "200" -> Json.obj(
            "description" -> "Successful operation",
            "content" -> Json.obj(
              "application/json" -> Json.obj(
                "schema" -> Json.obj(
                  "description" -> s"Resource of kind ${resource.kind}",
                  "$ref" -> s"#/components/schemas/${resource.group}.${resource.kind}"
                )
              )
            )
          )
        )
      )
    )
  }

  private def buildBulk(resource: Resource): JsObject = {
    Json.obj()
      .applyOnIf(resource.access.canRead) { obj =>
        obj ++ Json.obj()
      }
      .applyOnIf(resource.access.canCreate) { obj =>
        obj ++ Json.obj(
          "post" -> Json.obj(
            "tags" -> Json.arr(resource.singularName, resource.group),
            "summary" -> s"Create multiple ${resource.kind} at the same time",
            "operationId" -> s"${resource.group}.${resource.kind}.bulk_create",
            "parameters" -> Json.arr(),
            "security" -> Json.arr(Json.obj(
              "otoroshi_auth" -> Json.arr()
            )),
            "responses" -> Json.obj(
              "401" -> Json.obj(
                "description" -> "You have to provide an Api Key. Api Key can be passed using basic http authentication",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "400" -> Json.obj(
                "description" -> "Bad resource format. Take another look to the swagger, or open an issue",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "404" -> Json.obj(
                "description" -> "Resource not found or does not exist",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "200" -> Json.obj(
                "description" -> "Successful operation",
                "content" -> Json.obj(
                  "application/x-ndjson" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/BulkResponseBody"
                    )
                  )
                )
              )
            ),
            "requestBody" -> Json.obj(
              "description" -> "the request body in nd-json format (1 stringified entity per line)",
              "required" -> false,
              "content" -> Json.obj(
                "application/x-ndjson" -> Json.obj(
                  "schema" -> Json.obj(
                    "type" -> "array",
                    "items" -> Json.obj(
                      "$ref" -> s"#/components/schemas/${resource.group}.${resource.kind}"
                    )
                  )
                )
              )
            )
          )
        )
      }
      .applyOnIf(resource.access.canUpdate) { obj =>
        obj ++ Json.obj(
          "put" -> Json.obj(
            "tags" -> Json.arr(resource.singularName, resource.group),
            "summary" -> s"Update multiple ${resource.kind} at the same time",
            "operationId" -> s"${resource.group}.${resource.kind}.bulk_update",
            "parameters" -> Json.arr(),
            "security" -> Json.arr(Json.obj(
              "otoroshi_auth" -> Json.arr()
            )),
            "responses" -> Json.obj(
              "401" -> Json.obj(
                "description" -> "You have to provide an Api Key. Api Key can be passed using basic http authentication",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "400" -> Json.obj(
                "description" -> "Bad resource format. Take another look to the swagger, or open an issue",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "404" -> Json.obj(
                "description" -> "Resource not found or does not exist",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "200" -> Json.obj(
                "description" -> "Successful operation",
                "content" -> Json.obj(
                  "application/x-ndjson" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/BulkResponseBody"
                    )
                  )
                )
              )
            ),
            "requestBody" -> Json.obj(
              "description" -> "the request body in nd-json format (1 stringified entity per line)",
              "required" -> false,
              "content" -> Json.obj(
                "application/x-ndjson" -> Json.obj(
                  "schema" -> Json.obj(
                    "type" -> "array",
                    "items" -> Json.obj(
                      "$ref" -> s"#/components/schemas/${resource.group}.${resource.kind}"
                    )
                  )
                )
              )
            )
          ),
          "patch" -> Json.obj(
            "tags" -> Json.arr(resource.singularName, resource.group),
            "summary" -> s"Update (using json-patch) multiple ${resource.kind} at the same time",
            "operationId" -> s"${resource.group}.${resource.kind}.bulk_patch",
            "parameters" -> Json.arr(),
            "security" -> Json.arr(Json.obj(
              "otoroshi_auth" -> Json.arr()
            )),
            "responses" -> Json.obj(
              "401" -> Json.obj(
                "description" -> "You have to provide an Api Key. Api Key can be passed using basic http authentication",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "400" -> Json.obj(
                "description" -> "Bad resource format. Take another look to the swagger, or open an issue",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "404" -> Json.obj(
                "description" -> "Resource not found or does not exist",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "200" -> Json.obj(
                "description" -> "Successful operation",
                "content" -> Json.obj(
                  "application/x-ndjson" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/BulkResponseBody"
                    )
                  )
                )
              )
            ),
            "requestBody" -> Json.obj(
              "description" -> "the request body in nd-json format (1 stringified entity per line)",
              "required" -> false,
              "content" -> Json.obj(
                "application/x-ndjson" -> Json.obj(
                  "schema" -> Json.obj(
                    "$ref" -> "#/components/schemas/BulkPatchBody"
                  )
                )
              )
            )
          )
        )
      }
      .applyOnIf(resource.access.canDelete) { obj =>
        obj ++ Json.obj(
          "delete" -> Json.obj(
            "tags" -> Json.arr(resource.singularName, resource.group),
            "summary" -> s"Delete multiple ${resource.kind} at the same time",
            "operationId" -> s"${resource.group}.${resource.kind}.bulk_delete",
            "parameters" -> Json.arr(),
            "security" -> Json.arr(Json.obj(
              "otoroshi_auth" -> Json.arr()
            )),
            "responses" -> Json.obj(
              "401" -> Json.obj(
                "description" -> "You have to provide an Api Key. Api Key can be passed using basic http authentication",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "400" -> Json.obj(
                "description" -> "Bad resource format. Take another look to the swagger, or open an issue",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "404" -> Json.obj(
                "description" -> "Resource not found or does not exist",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "200" -> Json.obj(
                "description" -> "Successful operation",
                "content" -> Json.obj(
                  "application/x-ndjson" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/BulkResponseBody"
                    )
                  )
                )
              )
            )
          )
        )
      }
  }

  private def buildResource(resource: Resource): JsObject = {

    Json.obj()
      .applyOnIf(resource.access.canRead) { obj =>
        obj ++ Json.obj(
          "get" -> Json.obj(
            "tags" -> Json.arr(resource.singularName, resource.group),
            "summary" -> s"Find all possible ${resource.kind} entities",
            "operationId" -> s"${resource.group}.${resource.kind}.findAll",
            "parameters" -> Json.arr(),
            "security" -> Json.arr(Json.obj(
              "otoroshi_auth" -> Json.arr()
            )),
            "responses" -> Json.obj(
              "401" -> Json.obj(
                "description" -> "You have to provide an Api Key. Api Key can be passed using basic http authentication",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "400" -> Json.obj(
                "description" -> "Bad resource format. Take another look to the swagger, or open an issue",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "404" -> Json.obj(
                "description" -> "Resource not found or does not exist",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "200" -> Json.obj(
                "description" -> "Successful operation",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "type" -> "array",
                      "items" -> Json.obj(
                        "$ref" -> s"#/components/schemas/${resource.group}.${resource.kind}"
                      )
                    )
                  )
                )
              )
            )
          )
        )
      }
      .applyOnIf(resource.access.canCreate) { obj =>
        obj ++ Json.obj(
          "post" -> Json.obj(
            "tags" -> Json.arr(resource.singularName, resource.group),
            "summary" -> s"Creates a ${resource.kind}",
            "operationId" -> s"${resource.group}.${resource.kind}.create",
            "parameters" -> Json.arr(),
            "security" -> Json.arr(Json.obj(
              "otoroshi_auth" -> Json.arr()
            )),
            "responses" -> Json.obj(
              "401" -> Json.obj(
                "description" -> "You have to provide an Api Key. Api Key can be passed using basic http authentication",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "400" -> Json.obj(
                "description" -> "Bad resource format. Take another look to the swagger, or open an issue",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "404" -> Json.obj(
                "description" -> "Resource not found or does not exist",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> "#/components/schemas/ErrorResponse"
                    )
                  )
                )
              ),
              "201" -> Json.obj(
                "description" -> "Successful operation",
                "content" -> Json.obj(
                  "application/json" -> Json.obj(
                    "schema" -> Json.obj(
                      "$ref" -> s"#/components/schemas/${resource.group}.${resource.kind}"
                    )
                  )
                )
              )
            ),
            "requestBody" -> Json.obj(
              "description" -> "the request body in nd-json format (1 stringified entity per line)",
              "required" -> true,
              "content" -> Json.obj(
                "application/json" -> Json.obj(
                  "schema" -> Json.obj(
                    "$ref" -> s"#/components/schemas/${resource.group}.${resource.kind}"
                  )
                )
              )
            )
          )
        )
      }
      .applyOnIf(resource.access.canUpdate) { obj =>
        obj ++ Json.obj()
      }
      .applyOnIf(resource.access.canDelete) { obj =>
        obj ++ Json.obj()
      }
  }

  private def buildPaths(resource: Resource): Map[String, JsValue] = {
    Map(
      s"/apis/${resource.group}/${resource.version.name}/${resource.pluralName}/_count" -> buildCount(resource),
      s"/apis/${resource.group}/${resource.version.name}/${resource.pluralName}/_template" -> buildTemplate(resource),
      s"/apis/${resource.group}/${resource.version.name}/${resource.pluralName}/{id}" -> buildById(resource),
      s"/apis/${resource.group}/${resource.version.name}/${resource.pluralName}" -> buildResource(resource),
    ).applyOnIf(resource.access.canBulk) { obj =>
      obj ++ Map(
        s"/apis/${resource.group}/${resource.version.name}/${resource.pluralName}/_bulk" -> buildBulk(resource),
      )
    }
  }

  private def cleanupSchemas(schemas: Map[String, JsValue]): Map[String, JsValue] = {
    var finalSchemas = Map.empty[String, JsValue]
    schemas.foreach {
      case (key, schema) => {
        schema.select("definitions").asOpt[JsObject] match {
          case None => ()
          case Some(definitions) => {
            definitions.value.foreach {
              case (dkey, dvalue) => {
                if (!finalSchemas.contains(dkey)) {
                  finalSchemas = finalSchemas.put(dkey, (dvalue.asObject - "additionalProperties" - "id" - "patternProperties").stringify.replace("#/definitions/", "#/components/schemas/").parseJson)
                }
              }
            }
          }
        }
        val finalSchema: JsValue = (schema.asObject - "definitions" - "additionalProperties" - "id" - "patternProperties").stringify.replace("#/definitions/", "#/components/schemas/").parseJson
        finalSchemas = finalSchemas.put(key, finalSchema)
      }
    }
    finalSchemas
  }

  def generate(env: Env, version: Option[String]): String = {
    // TODO: missing live metrics api
    // TODO: missing analytics api
    if (env.isDev) {
      val additionalPathsFile = env.environment.resourceAsStream("/schemas/additionalPaths.json").get
      val additionalPathsRaw = new String(additionalPathsFile.readAllBytes(), StandardCharsets.UTF_8)
      val additionalPathsJson = Json.parse(additionalPathsRaw).asObject

      val additionalComponentsFile = env.environment.resourceAsStream("/schemas/additionalComponents.json").get
      val additionalComponentsRaw = new String(additionalComponentsFile.readAllBytes(), StandardCharsets.UTF_8)
      val additionalComponentsJson = Json.parse(additionalComponentsRaw).asObject

      cache.getOrElseUpdate("singleton", {
        val resources = env.allResources.resources.filter(_.version.served).filterNot(_.version.deprecated)
        val _schemas: Map[String, JsValue] = resources.map(res => (s"${res.group}.${res.kind}", res.version.finalSchema(res.kind, res.access.clazz))).toMap
        val schemas: Map[String, JsValue] = cleanupSchemas(_schemas)
        val paths: Map[String, JsValue] = resources.flatMap(buildPaths).toMap
        Json.obj(
          "openapi" -> "3.0.3", //"3.1.0"
          "info" -> Json.obj(
            "title" -> "Otoroshi Admin API",
            "description" -> "Admin API of the Otoroshi reverse proxy",
            "version" -> version.getOrElse(env.otoroshiVersion).json,
            "contact" -> Json.obj(
              "name" -> "Otoroshi Team",
              "email" -> "oss@maif.fr"
            ),
            "license" -> Json.obj(
              "name" -> "Apache 2.0",
              "url" -> "http://www.apache.org/licenses/LICENSE-2.0.html"
            )
          ),
          "externalDocs" -> Json.obj(
            "url" -> "https://www.otoroshi.io",
            "description" -> "Otoroshi website"
          ),
          "servers" -> Json.arr(
            Json.obj(
              "url" -> s"${env.exposedRootScheme}://${env.adminApiExposedHost}:${if (env.exposedRootSchemeIsHttps) env.exposedHttpsPortInt else env.exposedHttpPortInt}",
              "description" -> "your local otoroshi server"
            )
          ),
          "tags" -> JsArray(
            resources.map(res => Json.obj("name" -> res.singularName, "description" -> s"all the operations about the ${res.singularName} entity")).distinct ++
            resources.map(res => Json.obj("name" -> res.group, "description" -> s"all the operations in the ${res.group} group")).distinct ++
            Seq(
              "pki",
              "cluster",
              "snowmonkey",
              "import-export",
              "events",
              "tunnels",
            ).map(res => Json.obj("name" -> res, "description" -> s"all the operations in the ${res} api"))
          ),
          "paths" -> (JsObject(paths) ++ additionalPathsJson),
          "components" -> Json.obj(
            "schemas" -> (JsObject(schemas) ++ additionalComponentsJson),
            "securitySchemes" -> Json.obj(
              "otoroshi_auth" -> Json.obj(
                "type" -> "http",
                "scheme" -> "basic",
              )
            )
          ),
        ).prettify
      })
    } else {
      cache.getOrElseUpdate("singleton", {
        val openapi = env.environment.resourceAsStream("/schemas/openapi.json").get
        val openapiRaw = new String(openapi.readAllBytes(), StandardCharsets.UTF_8)
        Json.parse(openapiRaw).asObject.prettify
      })
    }
  }
}
