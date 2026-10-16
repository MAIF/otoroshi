package otoroshi.api

import otoroshi.env.Env
import play.api.libs.json.{JsArray, JsObject, JsString, JsValue, Json}
import otoroshi.utils.syntax.implicits.*
import otoroshi.utils.yaml.Yaml

import java.nio.charset.StandardCharsets
import scala.collection.concurrent.TrieMap

object OpenApi {

  private val cache = new TrieMap[String, String]()

  // the paths of the complete and of the core documents, computed once like the documents themselves
  private val pathsCache = new TrieMap[Boolean, Seq[String]]()

  /**
   * false when no path of the document can match the group, i.e. its sub document would be empty: the caller can
   * answer with a 404 without computing it.
   */
  def mayKnowExtensionGroup(env: Env, group: String, coreOnly: Boolean = false): Boolean = {
    val paths = pathsCache.getOrElseUpdate(
      coreOnly,
      Json.parse(generate(env, None, coreOnly = coreOnly)).select("paths").asObject.keys.toSeq
    )
    paths.exists(_.startsWith(s"/apis/$group"))
  }

  private val logger = play.api.Logger("otoroshi-openapi")

  private def schemaName(resource: Resource): String = s"${resource.group}.${resource.kind}"

  private def cleanupSchemas(schemas: Map[String, JsValue]): Map[String, JsValue] = {
    var finalSchemas = Map.empty[String, JsValue]
    schemas.foreach {
      // swagger resolves an entity to its own schema and the schemas it references, which its $refs point to at the
      // root of components.schemas. they are moved there, the component keeping only the schema of the entity
      case (key, resolved) if resolved.select("referencedSchemas").isDefined => {
        resolved.select("referencedSchemas").asOpt[JsObject].foreach { referenced =>
          referenced.value.foreach { case (rkey, rvalue) =>
            finalSchemas.get(rkey) match {
              case None                              => finalSchemas = finalSchemas.put(rkey, rvalue)
              // swagger names a schema after the simple name of its class, two classes can share it
              case Some(existing) if existing != rvalue =>
                logger.warn(s"openapi: '$key' references another schema named '$rkey', only the first one is kept")
              case Some(_)                           => ()
            }
          }
        }
        finalSchemas = finalSchemas.put(key, resolved.select("schema").asOpt[JsObject].getOrElse(resolved))
      }
      case (key, schema) => {
        schema.select("definitions").asOpt[JsObject] match {
          case None              => ()
          case Some(definitions) => {
            definitions.value.foreach {
              case (dkey, dvalue) => {
                if (!finalSchemas.contains(dkey)) {
                  finalSchemas = finalSchemas.put(
                    dkey,
                    (dvalue.asObject - "additionalProperties" - "id" - "patternProperties").stringify
                      .replace("#/definitions/", "#/components/schemas/")
                      .parseJson
                  )
                }
              }
            }
          }
        }
        val finalSchema: JsValue =
          (schema.asObject - "definitions" - "additionalProperties" - "id" - "patternProperties").stringify
            .replace("#/definitions/", "#/components/schemas/")
            .parseJson
        finalSchemas = finalSchemas.put(key, finalSchema)
      }
    }
    finalSchemas
  }

  /**
   * With `coreOnly`, the document only describes otoroshi and the admin extensions shipped with it (workflows, remote
   * catalogs, coraza waf, ...): the resources of the third party extensions are left out.
   */
  def generate(env: Env, version: Option[String], extensionGroup: Option[String] = None, coreOnly: Boolean = false): String = {
    // the core document and the complete one are cached separately
    val cacheSuffix = if (coreOnly) ":core" else ""

    val finalDoc = cache.getOrElseUpdate(
      s"singleton$cacheSuffix", {
        // the deprecated entities are still served: they are described, their operations flagged deprecated
        val resources                      = (if (coreOnly) env.allResources.coreResources else env.allResources.resources)
          .filter(_.version.served)
        val handWritten                    = OpenApiSchemas.all(env)
        // an entity carries its schema and the schemas it references (OpenApiSchemas.bundle), the ones of third
        // party extensions that carry none are described by reflection on their class
        val carried: Map[String, JsValue]  = resources.flatMap { res =>
          res.version.schema.collect { case bundle: JsObject =>
            (bundle.select("components").select("schemas").asOpt[JsObject].map(_.fields).getOrElse(Seq.empty) :+
            (schemaName(res) -> (bundle - "components"))).toSeq
          }.getOrElse(Seq.empty)
        }.toMap
        val _schemas: Map[String, JsValue] = resources
          .filter(_.version.schema.isEmpty)
          .map(res => (schemaName(res), res.version.finalSchema(res.kind, res.access.clazz)(using env)))
          .toMap
        val schemas: Map[String, JsValue]  = cleanupSchemas(_schemas) ++ carried
        val paths: Map[String, JsValue]    = resources.flatMap(OpenApiResourcePaths.paths).toMap
        Json
          .obj(
            "openapi"      -> "3.0.3", //"3.1.0"
            "info"         -> Json.obj(
              "title"       -> "Otoroshi Admin API",
              "description" -> "Admin API of the Otoroshi reverse proxy",
              "version"     -> env.otoroshiVersion.json,
              "contact"     -> Json.obj(
                "name"  -> "Otoroshi Team",
                "email" -> "oss@maif.fr"
              ),
              "license"     -> Json.obj(
                "name" -> "Apache 2.0",
                "url"  -> "http://www.apache.org/licenses/LICENSE-2.0.html"
              )
            ),
            "externalDocs" -> Json.obj(
              "url"         -> "https://www.otoroshi.io",
              "description" -> "Otoroshi website"
            ),
            "servers"      -> Json.arr(
              Json.obj(
                "url"         -> s"${env.exposedRootScheme}://${env.adminApiExposedHost}:${if (env.exposedRootSchemeIsHttps) env.exposedHttpsPortInt
                else env.exposedHttpPortInt}",
                "description" -> "your local otoroshi server"
              )
            ),
            "tags"         -> JsArray(
              resources
                .map(res =>
                  Json.obj(
                    "name"        -> res.singularName,
                    "description" -> s"Create, read, update and delete the ${res.pluralName} (${res.groupKind})"
                  )
                )
                .distinct ++
              resources
                .map(res =>
                  Json.obj("name" -> res.group, "description" -> s"The entities of the ${res.group} group")
                )
                .distinct ++
              OpenApiSchemas.tags(env).map { case (name, description) =>
                Json.obj("name" -> name, "description" -> description)
              }
            ),
            "paths"        -> (JsObject(paths) ++ OpenApiSchemas.paths(env)),
            "components"   -> Json.obj(
              "schemas"         -> (JsObject(schemas) ++ handWritten),
              "securitySchemes" -> Json.obj(
                "otoroshi_auth" -> Json.obj(
                  "type"   -> "http",
                  "scheme" -> "basic"
                )
              )
            )
          )
          .prettify
      }
    )

    // the group comes from the query string of a public endpoint: only the groups of the served resources are cached,
    // caching any value let anybody grow the cache without bound
    val knownGroups: Set[String] =
      (if (coreOnly) env.allResources.coreResources else env.allResources.resources).map(_.group).toSet
    def cachedGroupDoc(group: String, compute: => String): String = {
      if (knownGroups.contains(group)) cache.getOrElseUpdate(s"$group$cacheSuffix", compute) else compute
    }

    val doc = extensionGroup match {
      case None        => finalDoc
      case Some(group) => {
        cachedGroupDoc(
          group, {

            env.logger.info(s"Compute sub openapi.json document for group: '${group}'")

            def findNeededComponents(js: JsValue): Set[String] = {
              js.stringify.split("#/components/schemas/").map(str => str.split(""""""").head).toSet
            }

            def buildFilteredComponentsSchema(initialObj: JsValue, componentsSchema: JsObject): JsObject = {
              var previousNeededComponents: Set[String] = Set.empty
              var previousComponents: JsValue           = Json.obj("paths" -> initialObj, "schemas" -> Json.obj())
              var run                                   = true
              while (run) {
                val neededComponents = findNeededComponents(previousComponents)
                if (neededComponents.size == previousNeededComponents.size) {
                  run = false
                } else {
                  previousComponents = Json.obj(
                    "paths"   -> initialObj,
                    "schemas" -> JsObject(componentsSchema.value.filter {
                      case (key, _) if neededComponents.contains(key) => true
                      case _                                          => false
                    })
                  )
                  previousNeededComponents = neededComponents
                }
              }
              previousComponents.select("schemas").asObject
            }

            val json  = Json.parse(finalDoc).asObject
            val paths = json.select("paths").asObject

            val tags             = json.select("tags").asArray
            val components       = json.select("components").asObject
            val componentsSchema = json.select("components").select("schemas").asObject
            val filteredPaths    = JsObject(paths.value.filter(_._1.startsWith(s"/apis/${group}")))
            val needTags         = filteredPaths.values.flatMap { path =>
              path.asObject.values.flatMap { endpoint =>
                endpoint.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]).toSeq
              }
            }.toSet
            val filteredTags     = JsArray(
              tags.value.filter(t => needTags.contains(t.select("name").asString))
            ) //t.select("name").asString.startsWith(group)))
            val filteredComponentsSchema = buildFilteredComponentsSchema(filteredPaths, componentsSchema)
            val customComponents         = components ++ Json.obj("schemas" -> filteredComponentsSchema)
            val customDoc                =
              json ++ Json.obj("tags" -> filteredTags, "paths" -> filteredPaths, "components" -> customComponents)
            customDoc.prettify
          }
        )
      }
    }

    // the cached documents describe the running version. a requested version is only written in the returned document,
    // keeping it out of the cache keys so that the cache cannot grow with every version callers ask for
    version match {
      case Some(v) if v != env.otoroshiVersion =>
        val json = Json.parse(doc).asObject
        (json ++ Json.obj("info" -> (json.select("info").asObject ++ Json.obj("version" -> v)))).prettify
      case _                                   => doc
    }
  }
}
