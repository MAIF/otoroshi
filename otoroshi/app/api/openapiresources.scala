package otoroshi.api

import play.api.libs.json.*

/**
 * The operations the generic admin api (GenericApiController) serves for an entity, described the way the
 * controller answers them. The schemas they reference live in conf/schemas/openapi/generic.json, the schema of the
 * entity itself is the one of its group file (or the reflected one for the entities of third party extensions).
 */
object OpenApiResourcePaths {

  private def ref(name: String): JsObject = Json.obj("$ref" -> s"#/components/schemas/$name")

  private val security: JsArray = Json.arr(Json.obj("otoroshi_auth" -> Json.arr()))

  private def json(schema: JsValue): JsObject = Json.obj("application/json" -> Json.obj("schema" -> schema))

  private def response(description: String, content: JsObject = Json.obj()): JsObject =
    if (content.keys.isEmpty) Json.obj("description" -> description)
    else Json.obj("description" -> description, "content" -> content)

  private def error(description: String): JsObject = response(description, json(ref("ErrorResponse")))

  // the entities that require a role on top of the location rights (GenericApiController.resourcesRole)
  private val roles: Map[(String, String), String] = Map(
    ("security.otoroshi.io", "admins")            -> "tenant admin",
    ("security.otoroshi.io", "admin-sessions")    -> "tenant admin",
    ("security.otoroshi.io", "auth-module-users") -> "tenant admin",
    ("config.otoroshi.io", "global-configs")      -> "super admin",
    ("catalogs.otoroshi.io", "remote-catalogs")   -> "super admin"
  )

  // the errors every operation can answer, an operation describing some of them more precisely. the admin api is
  // served by a route of otoroshi: a call without credentials, or beyond the quotas, is refused before reaching it
  private def errors(resource: Resource): Seq[(String, JsObject)] = Seq(
    "400" -> error("The credentials are missing"),
    "401" -> error("The credentials are wrong, or they do not give access to this entity"),
    "404" -> error(s"The ${resource.singularName} or the kind of entity does not exist"),
    "429" -> error("The quotas of the credentials, or the number of calls allowed per ip address, are exceeded")
  ) ++ roles.get((resource.group, resource.pluralName)).map { role =>
    "403" -> error(s"The admin behind the credentials is not a $role")
  }

  private def parameter(name: String, in: String, description: String, schema: JsObject, required: Boolean = false): JsObject =
    Json.obj("name" -> name, "in" -> in, "description" -> description, "required" -> required, "schema" -> schema)

  private def idParameter(resource: Resource): JsObject =
    parameter(
      "id",
      "path",
      s"The id of the ${resource.singularName}, the value of its `${resource.access.idFieldName()}` field",
      Json.obj("type" -> "string"),
      required = true
    )

  private val prettyParameter = parameter(
    "pretty",
    "query",
    "Indents the json answered. The default comes from the otoroshi.options.defaultPrettyAdminApi setting",
    Json.obj("type" -> "boolean")
  )

  private val fieldsParameter = parameter(
    "fields",
    "query",
    "Comma separated list of the fields to answer, for instance `id,name,frontend.domains`. Every field is answered when absent",
    Json.obj("type" -> "string")
  )

  private val inMemoryParameter = parameter(
    "in_mem",
    "query",
    "Reads the entities from the in memory state of the node instead of the datastore. Faster, but a change made on another node shows up after the next state synchronization",
    Json.obj("type" -> "boolean")
  )

  private val groupingParameter = parameter(
    "_group",
    "query",
    "How many entities are processed in parallel, from 1 to 9. 1 when absent",
    Json.obj("type" -> "integer", "format" -> "int32", "minimum" -> 1, "maximum" -> 9)
  )

  private val listParameters: Seq[JsObject] = Seq(
    parameter(
      "page",
      "query",
      "The page to answer, starting at 1. The number of pages is answered in the X-Pages header",
      Json.obj("type" -> "integer", "format" -> "int32", "minimum" -> 1)
    ),
    parameter(
      "pageSize",
      "query",
      "The number of entities of a page. Every entity is answered when absent",
      Json.obj("type" -> "integer", "format" -> "int32", "minimum" -> 1)
    ),
    parameter(
      "sorted",
      "query",
      "Comma separated list of `field:descending` pairs to sort the entities on, for instance `name:false,metadata.created_at:true`",
      Json.obj("type" -> "string")
    ),
    parameter(
      "filtered",
      "query",
      "Comma separated list of `field:value` pairs, kept for the backoffice tables. Use the `filter.<field>` parameters to filter the entities",
      Json.obj("type" -> "string")
    ),
    fieldsParameter,
    inMemoryParameter,
    prettyParameter
  )

  private def entity(resource: Resource): JsObject = ref(s"${resource.group}.${resource.kind}")

  // what the api answers: the entity and its kind
  private def answered(resource: Resource): JsObject =
    Json.obj("allOf" -> Json.arr(entity(resource), ref("ApiEntityKind")))

  private def entityContent(resource: Resource): JsObject = Json.obj(
    "application/json" -> Json.obj("schema" -> answered(resource)),
    "application/yaml" -> Json.obj("schema" -> answered(resource))
  )

  private def entityBody(resource: Resource, description: String): JsObject = Json.obj(
    "description" -> description,
    "required"    -> true,
    "content"     -> Json.obj(
      "application/json" -> Json.obj("schema" -> entity(resource)),
      "application/yaml" -> Json.obj("schema" -> entity(resource))
    )
  )

  private def ndjsonBody(description: String, line: JsValue): JsObject = Json.obj(
    "description" -> description,
    "required"    -> true,
    "content"     -> Json.obj("application/x-ndjson" -> Json.obj("schema" -> line))
  )

  private def idLine(resource: Resource): JsObject = Json.obj(
    "type"        -> "object",
    "description" -> s"The ${resource.singularName} to process",
    "required"    -> Json.arr(resource.access.idFieldName()),
    "properties"  -> Json.obj(
      resource.access.idFieldName() -> Json.obj("type" -> "string", "description" -> s"The id of the ${resource.singularName}")
    )
  )

  private def bulkResponses(resource: Resource, success: String): Seq[(String, JsObject)] = Seq(
    "200" -> response(
      s"$success. The outcome of each line is answered on its own line, in the order of the lines of the request",
      Json.obj("application/x-ndjson" -> Json.obj("schema" -> ref("BulkOperationOutcome")))
    ),
    "400" -> error("The content type is not application/x-ndjson, or the credentials are missing")
  )

  // the responses of the operation, then the errors every operation can answer that it does not describe itself
  private def operation(
      resource: Resource,
      id: String,
      summary: String,
      description: String,
      parameters: Seq[JsObject],
      responses: Seq[(String, JsObject)],
      body: Option[JsObject] = None
  ): JsObject = {
    val all = responses ++ errors(resource).filterNot(e => responses.exists(_._1 == e._1))
    Json
      .obj(
        "tags"        -> Json.arr(resource.singularName, resource.group),
        "summary"     -> summary,
        "description" -> description,
        "operationId" -> s"${resource.group}.${resource.kind}.$id",
        "parameters"  -> JsArray(parameters),
        "security"    -> security,
        "responses"   -> JsObject(all.sortBy(_._1))
      ) ++ body.map(b => Json.obj("requestBody" -> b)).getOrElse(Json.obj())
  }

  def paths(resource: Resource): Map[String, JsObject] = {
    val base   = s"/apis/${resource.group}/${resource.version.name}/${resource.pluralName}"
    val a      = resource.access
    val kind   = resource.kind
    val single = resource.singularName
    val plural = resource.pluralName
    val idField = a.idFieldName()

    def ops(entries: (Boolean, String, JsObject)*): JsObject =
      JsObject(entries.collect { case (true, method, op) => method -> op })

    val collection = ops(
      (
        a.canRead,
        "get",
        operation(
          resource,
          "findAll",
          s"List the $plural",
          s"Answers every $single the credentials can read. The `filter.<field>=<value>` query parameters keep the $plural whose field (a dotted path, or a json path starting with `$$`) holds the value, or an array holding it.",
          listParameters,
          Seq(
            "200" -> (response(
              s"The $plural the credentials can read, with their kind, filtered, sorted, paginated and projected as asked",
              Json.obj(
                "application/json"     -> Json.obj("schema" -> Json.obj("type" -> "array", "items" -> answered(resource))),
                "application/x-ndjson" -> Json.obj("schema" -> answered(resource)),
                "application/yaml"     -> Json.obj("schema" -> Json.obj("type" -> "array", "items" -> answered(resource)))
              )
            ) ++ Json.obj(
              "headers" -> Json.obj(
                "X-Pages" -> Json.obj(
                  "description" -> "The number of pages, -1 when the entities are not paginated",
                  "schema"      -> Json.obj("type" -> "integer", "format" -> "int32")
                )
              )
            ))
          )
        )
      ),
      (
        a.canCreate,
        "post",
        operation(
          resource,
          "create",
          s"Create a $single",
          s"Creates a $single. Its id is generated when the body holds none. A yaml body can be a kubernetes resource, its `spec` being the $single.",
          Seq(prettyParameter),
          Seq(
            "201" -> response(s"The $single created", entityContent(resource)),
            "400" -> error(s"The body is not a $single, the $single is not valid, or the credentials are missing")
          ) :+ ("401" -> error(s"The credentials are wrong, they do not give access to this $single, or a $single with this id already exists")),
          Some(entityBody(resource, s"The $single to create"))
        )
      ),
      (
        a.canDelete,
        "delete",
        operation(
          resource,
          "deleteAll",
          s"Delete every $single",
          s"Deletes every $single the credentials can write.",
          Seq.empty,
          Seq("204" -> response(s"The $plural are deleted"))
        )
      )
    )

    val byId = ops(
      (
        a.canRead,
        "get",
        operation(
          resource,
          "findById",
          s"Read a $single",
          s"Answers the $single with this id.",
          Seq(idParameter(resource), fieldsParameter, inMemoryParameter, prettyParameter),
          Seq("200" -> response(s"The $single, with its kind", entityContent(resource)))
        )
      ),
      (
        a.canCreate,
        "post",
        operation(
          resource,
          "upsertById",
          s"Create or replace a $single",
          s"Creates the $single with this id, or replaces it when it exists. The id of the path wins over the one of the body. The `Otoroshi-Entity-Updated` header of an update tells whether the $single was already the same.",
          Seq(idParameter(resource), prettyParameter),
          Seq(
            "200" -> (response(s"The $single replaced", entityContent(resource)) ++ Json.obj(
              "headers" -> Json.obj(
                "Otoroshi-Entity-Updated" -> Json.obj(
                  "description" -> s"`true` when the $single sent is the one that was already stored",
                  "schema"      -> Json.obj("type" -> "string", "enum" -> Json.arr("true", "false"))
                )
              )
            )),
            "201" -> response(s"The $single created", entityContent(resource)),
            "400" -> response(
              s"The body is not a $single, the $single is not valid, or the credentials are missing",
              json(ref("BadEntityResponse"))
            )
          ),
          Some(entityBody(resource, s"The $single to store"))
        )
      ),
      (
        a.canUpdate,
        "put",
        operation(
          resource,
          "updateById",
          s"Replace a $single",
          s"Replaces the $single with this id. The id of the path wins over the one of the body.",
          Seq(idParameter(resource), prettyParameter),
          Seq(
            "200" -> response(s"The $single replaced", entityContent(resource)),
            "400" -> response(
              s"The body is not a $single, the $single is not valid, or the credentials are missing",
              json(ref("BadEntityResponse"))
            )
          ) :+ ("401" -> error(s"The credentials are wrong, they do not give access to this $single, or no $single has this id")),
          Some(entityBody(resource, s"The $single that replaces the stored one"))
        )
      ),
      (
        a.canUpdate,
        "patch",
        operation(
          resource,
          "patchById",
          s"Patch a $single",
          s"Changes some fields of the $single with this id. An `application/json` body is a json patch (RFC 6902). An `application/json+oto-patch` body is a list of fields (dotted paths) and of their new values, as is a form body.",
          Seq(idParameter(resource), prettyParameter),
          Seq(
            "200" -> response(s"The $single patched", entityContent(resource)),
            "400" -> error(s"The body is not a patch, the $single patched is not valid, or the credentials are missing")
          ),
          Some(
            Json.obj(
              "description" -> s"The changes to apply to the $single",
              "required"    -> true,
              "content"     -> Json.obj(
                "application/json"                  -> Json.obj("schema" -> ref("JsonPatch")),
                "application/json+oto-patch"        -> Json.obj("schema" -> ref("FieldsPatch")),
                "application/x-www-form-urlencoded" -> Json.obj("schema" -> ref("FormPatch"))
              )
            )
          )
        )
      ),
      (
        a.canDelete,
        "delete",
        operation(
          resource,
          "deleteById",
          s"Delete a $single",
          s"Deletes the $single with this id, and answers it.",
          Seq(idParameter(resource), prettyParameter),
          Seq("200" -> response(s"The $single deleted", entityContent(resource)))
        )
      )
    )

    val templateParameters = if (Set("AuthModule", "DataExporter").contains(kind)) {
      Seq(
        parameter(
          "type",
          "query",
          s"The type of the $single to build a template of",
          Json.obj("type" -> "string")
        )
      )
    } else Seq.empty

    val count = ops(
      (
        a.canRead,
        "get",
        operation(
          resource,
          "count",
          s"Count the $plural",
          s"Answers the number of $plural the credentials can read.",
          Seq(inMemoryParameter, prettyParameter),
          Seq("200" -> response(s"The number of $plural", json(ref("EntityCount"))))
        )
      )
    )

    // the entities without a template answer a 404 to every call of _template: the operation is left out
    val hasTemplate = scala.util.Try(a.template(resource.version.name, Map.empty, None)).toOption.exists(_ != Json.obj())
    val template = ops(
      (
        a.canRead && hasTemplate,
        "get",
        operation(
          resource,
          "template",
          s"Build a new $single",
          s"Answers a $single holding the default value of each field, and a new id, ready to be changed and created.",
          templateParameters,
          Seq(
            "200" -> response(s"A new $single", json(entity(resource))),
            "404" -> error(s"The kind of entity does not exist, or has no template")
          )
        )
      )
    )

    val schema = ops(
      (
        a.canRead,
        "get",
        operation(
          resource,
          "schema",
          s"Describe the $plural",
          s"Answers the json schema of a $single. The schemas it references are under its `components.schemas` field.",
          Seq.empty,
          Seq("200" -> response(s"The json schema of a $single", json(ref("JsonSchema"))))
        )
      )
    )

    val bulk = if (!a.canBulk) Json.obj()
    else
      ops(
        (
          a.canCreate,
          "post",
          operation(
            resource,
            "bulk_create",
            s"Create several $plural",
            s"Creates the $plural of the body, one per line.",
            Seq(groupingParameter),
            bulkResponses(resource, s"The $plural are processed"),
            Some(ndjsonBody(s"The $plural to create, one json $single per line", entity(resource)))
          )
        ),
        (
          a.canUpdate,
          "put",
          operation(
            resource,
            "bulk_update",
            s"Replace several $plural",
            s"Replaces the $plural of the body, one per line, each one by its `$idField`.",
            Seq(groupingParameter),
            bulkResponses(resource, s"The $plural are processed"),
            Some(ndjsonBody(s"The $plural that replace the stored ones, one json $single per line", entity(resource)))
          )
        ),
        (
          a.canUpdate,
          "patch",
          operation(
            resource,
            "bulk_patch",
            s"Patch several $plural",
            s"Applies a json patch (RFC 6902) to each $single of the body, one per line.",
            Seq(groupingParameter),
            bulkResponses(resource, s"The $plural are processed"),
            Some(
              ndjsonBody(
                s"The $plural to patch and their patch, one per line",
                idLine(resource).deepMerge(
                  Json.obj(
                    "required"   -> Json.arr(idField, "patch"),
                    "properties" -> Json.obj("patch" -> ref("JsonPatch"))
                  )
                )
              )
            )
          )
        ),
        (
          a.canDelete,
          "delete",
          operation(
            resource,
            "bulk_delete",
            s"Delete several $plural",
            s"Deletes the $plural of the body, one per line.",
            Seq(groupingParameter),
            bulkResponses(resource, s"The $plural are processed"),
            Some(ndjsonBody(s"The $plural to delete, one per line", idLine(resource)))
          )
        )
      )

    Map(
      base                -> collection,
      s"$base/{id}"       -> byId,
      s"$base/_count"     -> count,
      s"$base/_template"  -> template,
      s"$base/_schema"    -> schema,
      s"$base/_bulk"      -> bulk
    ).filter(_._2.keys.nonEmpty)
  }
}
