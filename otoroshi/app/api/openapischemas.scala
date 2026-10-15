package otoroshi.api

import otoroshi.env.Env
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import java.nio.charset.StandardCharsets
import scala.collection.concurrent.TrieMap

/**
 * The hand written parts of the openapi document of the admin api, in conf/schemas/openapi, listed in its index.json
 * and read once:
 * - `files`: the json schemas of the entities of otoroshi and of the admin extensions shipped with it, the schemas
 *   they reference, the schemas of the generic api (generic.json) and of the other operations (api-<family>.json)
 * - `paths`: the operations of the admin api that are not the generic api of an entity (paths/<family>.json)
 * - `tags`: the tags of these operations, and their description
 * An entity carries its schema through its ResourceVersion.schema, the way a third party extension can carry its
 * own.
 */
object OpenApiSchemas {

  private val logger = play.api.Logger("otoroshi-openapi")
  private val cache  = new TrieMap[String, JsObject]()

  private def read(env: Env, path: String): Option[JsValue] = env.environment.resourceAsStream(path).map { in =>
    try Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))
    finally in.close()
  }

  // the json objects of the files listed under a key of the index, merged. a key defined twice keeps its first
  // definition, an operation defined twice too
  private def merged(env: Env, key: String, depth: Int): JsObject = {
    val files = read(env, "/schemas/openapi/index.json").flatMap(_.select(key).asOpt[Seq[String]]).getOrElse(Seq.empty)
    files.foldLeft(Json.obj()) { (all, file) =>
      read(env, s"/schemas/openapi/$file").flatMap(_.asOpt[JsObject]) match {
        case None          =>
          logger.error(s"openapi: the file '$file' is missing or is not a json object")
          all
        case Some(entries) =>
          entries.fields.foldLeft(all) {
            case (acc, (name, value: JsObject)) if depth == 2 && acc.keys.contains(name) =>
              val existing = acc.select(name).asObject
              value.fields.foldLeft(acc) {
                case (a, (method, _)) if existing.keys.contains(method) =>
                  logger.error(s"openapi: the operation ${method.toUpperCase} $name of '$file' is already defined, the first definition is kept")
                  a
                case (a, (method, op))                              =>
                  a ++ Json.obj(name -> (a.select(name).asObject ++ Json.obj(method -> op)))
              }
            case (acc, (name, _)) if acc.keys.contains(name)                           =>
              logger.error(s"openapi: '$name' of '$file' is already defined, the first definition is kept")
              acc
            case (acc, (name, value))                                                  => acc ++ Json.obj(name -> value)
          }
      }
    }
  }

  def all(env: Env): JsObject = cache.getOrElseUpdate("all", merged(env, "files", 1))

  def paths(env: Env): JsObject = cache.getOrElseUpdate("paths", merged(env, "paths", 2))

  // the tags of the operations of the paths files, and their description
  def tags(env: Env): Seq[(String, String)] = cache
    .getOrElseUpdate("tags", read(env, "/schemas/openapi/index.json").flatMap(_.select("tags").asOpt[JsObject]).getOrElse(Json.obj()))
    .fields
    .collect { case (name, JsString(description)) => name -> description }
    .toSeq

  // the schemas a schema references, itself excluded, through the schemas they reference
  private def referenced(schema: JsValue, all: JsObject): Map[String, JsValue] = {
    def refs(js: JsValue): Set[String] = js match {
      case obj: JsObject =>
        obj.fields.flatMap {
          case ("$ref", JsString(ref)) if ref.startsWith("#/components/schemas/") =>
            Set(ref.stripPrefix("#/components/schemas/"))
          case (_, value)                                                       => refs(value)
        }.toSet
      case arr: JsArray  => arr.value.flatMap(refs).toSet
      case _             => Set.empty
    }
    var found    = Map.empty[String, JsValue]
    var frontier = refs(schema)
    while (frontier.nonEmpty) {
      val next = frontier.flatMap(name => all.value.get(name).map(name -> _)).filterNot(e => found.contains(e._1))
      found = found ++ next
      frontier = next.flatMap(e => refs(e._2)).filterNot(found.contains)
    }
    found
  }

  /**
   * The schema of an entity (named `<group>.<kind>`) with the schemas it references under its components.schemas
   * field, so that its $refs resolve in it: what the _schema endpoint of the generic api answers.
   */
  def bundle(env: Env, name: String): Option[JsObject] = {
    val schemas = all(env)
    schemas.value.get(name).collect { case schema: JsObject =>
      schema ++ Json.obj("components" -> Json.obj("schemas" -> JsObject(referenced(schema, schemas))))
    }
  }

  // the resource with its hand written schema, unless it already carries one
  def carriedBy(env: Env, resource: Resource): Resource = {
    if (resource.version.schema.isDefined) resource
    else
      bundle(env, s"${resource.group}.${resource.kind}") match {
        case None         => resource
        case Some(schema) => resource.copy(version = resource.version.copy(schema = Some(schema)))
      }
  }
}
