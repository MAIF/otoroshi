package tools

import com.typesafe.config.ConfigFactory
import functional.OtoroshiSpec
import otoroshi.api.{Resource, ResourceAccessApi}
import otoroshi.models.EntityLocationSupport
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.*

import java.io.File
import java.nio.file.Files
import scala.util.Try

// writes a first draft of the hand written schema of each entity of otoroshi that has none yet, in
// conf/schemas/openapi/<group>.json, to be completed by hand until openapi.OpenApiConformanceSpec passes. a draft
// takes its properties from the json the format of the entity writes for its template, their type from the schema
// reflected from its class when the names match, and their description from app/openapi/openapi-cfg.json. it
// never changes a schema that already exists
//
//   sbt "testOnly tools.OpenApiSchemaDraftsSpec"
class OpenApiSchemaDraftsSpec extends OtoroshiSpec {

  private val directory = new File("./conf/schemas/openapi")
  private val indexFile = new File(directory, "index.json")

  override def getTestConfiguration(configuration: Configuration): Configuration =
    Configuration(ConfigFactory.parseString("app.env = dev").resolve()).withFallback(configuration)

  private def readJson(file: File): Option[JsObject] =
    if (file.exists()) Try(Json.parse(Files.readString(file.toPath)).as[JsObject]).toOption else None

  private val descriptions: Map[String, String] =
    readJson(new File("./app/openapi/openapi-cfg.json"))
      .flatMap(j => (j \ "descriptions").asOpt[Map[String, String]])
      .getOrElse(Map.empty)
      .filter { case (_, d) => d.trim.nonEmpty && d.trim != "???" }

  // simple class name -> fully qualified names, from the keys of the descriptions
  private val classes: Map[String, Set[String]] = descriptions.keys.toSeq
    .flatMap { key =>
      val parts = key.split("\\.").toSeq
      if (parts.size > 2) Some(parts.init.last -> parts.init.mkString(".")) else None
    }
    .groupBy(_._1)
    .view
    .mapValues(_.map(_._2).toSet)
    .toMap

  private def description(className: Option[String], field: String): Option[String] =
    className.toSeq.flatMap(c => classes.getOrElse(c, Set.empty) + c).flatMap(fq => descriptions.get(s"$fq.$field")).headOption

  // the json schema swagger reflects from the class of an entity: its own schema and the ones it references
  private def reflected(resource: Resource): (JsObject, Map[String, JsObject]) = {
    val raw = Try(resource.version.finalSchema(resource.kind, resource.access.clazz)(using otoroshiComponents.env)).getOrElse(Json.obj())
    def clean(js: JsValue): JsValue = Json.parse(Json.stringify(js).replace("#/definitions/", "#/components/schemas/"))
    val referenced = (raw \ "referencedSchemas").asOpt[JsObject].getOrElse(Json.obj()).fields.collect { case (k, v: JsObject) =>
      k -> clean(v).as[JsObject]
    }.toMap
    val own        = (raw \ "schema").asOpt[JsObject].getOrElse(raw).asInstanceOf[JsObject]
    (clean(own).as[JsObject], referenced)
  }

  private def snakeToCamel(s: String): String = "_([a-z])".r.replaceAllIn(s, m => m.group(1).toUpperCase)

  // the reflected property a json key stands for
  private def matching(key: String, properties: JsObject): Option[(String, JsValue)] = {
    val candidates = Seq(key, snakeToCamel(key), if (key == "_loc") "location" else key)
    candidates.flatMap(c => properties.value.get(c).map(c -> _)).headOption.orElse {
      properties.fields.find { case (p, _) => p.camelToSnake == key }
    }
  }

  class Drafter(referenced: Map[String, JsObject], known: Set[String]) {
    var components: Map[String, JsObject] = Map.empty

    private def refName(s: JsValue): Option[String] =
      (s \ "$ref").asOpt[String].filter(_.startsWith("#/components/schemas/")).map(_.stripPrefix("#/components/schemas/"))

    private def resolve(s: JsValue): (Option[String], Option[JsObject]) = refName(s) match {
      case Some(name) => (Some(name), referenced.get(name))
      case None       => (None, s.asOpt[JsObject])
    }

    private def scalar(value: JsValue, reflectedSchema: Option[JsObject]): JsObject = {
      val format = reflectedSchema.flatMap(r => (r \ "format").asOpt[String])
      value match {
        case JsString(_)  => Json.obj("type" -> "string") ++ format.filter(Set("date-time", "date", "byte").contains).map(f => Json.obj("format" -> f)).getOrElse(Json.obj())
        case n: JsNumber  =>
          if (n.value.isWhole && !reflectedSchema.exists(r => (r \ "type").asOpt[String].contains("number")))
            Json.obj("type" -> "integer", "format" -> format.filter(Set("int32", "int64").contains).getOrElse(if (n.value.isValidInt) "int32" else "int64"))
          else Json.obj("type" -> "number", "format" -> "double")
        case JsBoolean(_) => Json.obj("type" -> "boolean")
        case _            => Json.obj()
      }
    }

    // a schema of the value the format writes, typed by the reflected schema of the same place when there is one
    def draft(value: JsValue, reflectedSchema: Option[JsValue], className: Option[String]): JsObject = {
      val (name, resolved) = reflectedSchema.map(resolve).getOrElse((None, None))
      value match {
        case obj: JsObject =>
          val reflectedProperties = resolved.flatMap(r => (r \ "properties").asOpt[JsObject]).getOrElse(Json.obj())
          val properties          = obj.fields.map { case (key, v) =>
            if (key == "_loc") key -> Json.obj("$ref" -> "#/components/schemas/EntityLocation")
            else {
              val m     = matching(key, reflectedProperties)
              val child = draft(v, m.map(_._2), None)
              val desc  = m.flatMap(x => description(name.orElse(className), x._1))
              key -> (child ++ desc.map(d => Json.obj("description" -> d)).getOrElse(Json.obj()))
            }
          }
          val schema = Json.obj("type" -> "object", "properties" -> JsObject(properties))
          name.filter(n => obj.keys.nonEmpty && !n.startsWith("Seq") && !n.startsWith("Map")) match {
            // a named type of the class becomes a schema of its own, shared by every place it is used
            case Some(n) =>
              if (!known.contains(n) && !components.contains(n)) components = components + (n -> schema)
              Json.obj("$ref" -> s"#/components/schemas/$n")
            case None    => schema
          }
        case arr: JsArray  =>
          val items = resolved.flatMap(r => (r \ "items").toOption)
          arr.value.headOption match {
            case Some(first) => Json.obj("type" -> "array", "items" -> draft(first, items, None))
            case None        => Json.obj("type" -> "array", "items" -> items.map(i => draft(JsNull, Some(i), None)).getOrElse(Json.obj()))
          }
        case JsNull        =>
          resolved match {
            case Some(r) if (r \ "type").asOpt[String].contains("object") && (r \ "properties").isDefined =>
              Json.obj("type" -> "object", "nullable" -> true, "properties" -> (r \ "properties").as[JsObject])
            case Some(r)                                                                                 =>
              (r - "additionalProperties" - "description") ++ Json.obj("nullable" -> true)
            case None                                                                                    => Json.obj("nullable" -> true)
          }
        case other         => scalar(other, resolved)
      }
    }
  }

  private def roundTrip(resource: Resource, json: JsValue): JsValue = {
    val access = resource.access.asInstanceOf[ResourceAccessApi[EntityLocationSupport]]
    Try(access.format.reads(json).map(access.format.writes).asOpt).toOption.flatten.getOrElse(json)
  }

  s"The openapi schema drafts" should {
    "warm up" in {
      startOtoroshi()
    }
    "be written for the entities that have none" in {
      val env       = otoroshiComponents.env
      val index     = readJson(indexFile).flatMap(j => (j \ "files").asOpt[Seq[String]]).getOrElse(Seq.empty)
      val existing  = index.flatMap(f => readJson(new File(directory, f)).toSeq).flatMap(_.keys).toSet
      var known     = existing
      val resources = env.allResources.coreResources.filter(_.version.served).filterNot(_.version.deprecated)
      val byGroup   = resources.groupBy(_.group).toSeq.sortBy(_._1)
      var files     = index
      byGroup.foreach { case (group, groupResources) =>
        val fileName = s"$group.json"
        val file     = new File(directory, fileName)
        var drafted  = readJson(file).getOrElse(Json.obj())
        groupResources.sortBy(_.kind).foreach { resource =>
          val schemaName = s"${resource.group}.${resource.kind}"
          if (!known.contains(schemaName)) {
            val (own, referenced) = reflected(resource)
            val template          = Try(resource.access.template(resource.version.name, Map.empty, None)).getOrElse(Json.obj())
            val written           = roundTrip(resource, template)
            val drafter           = new Drafter(referenced, known)
            val className         = Some(resource.access.clazz.getSimpleName)
            val schema            = drafter.draft(written, Some(own), className)
            val entity            = Json.obj(
              "description" -> description(className, "").orElse(descriptions.get(s"entity_description.${resource.access.clazz.getName}")).getOrElse(s"A ${resource.singularName}")
            ) ++ schema ++ Json.obj("example" -> written)
            val nested            = drafter.components
            drafted = drafted ++ Json.obj(schemaName -> entity) ++ JsObject(nested.toSeq.filterNot(n => known.contains(n._1)))
            known = known + schemaName ++ nested.keys
          }
        }
        if (drafted.keys.nonEmpty) {
          Files.writeString(file.toPath, Json.prettyPrint(drafted) + "\n")
          if (!files.contains(fileName)) files = files :+ fileName
        }
      }
      Files.writeString(indexFile.toPath, Json.prettyPrint(Json.obj("files" -> files)) + "\n")
    }
    "shutdown" in {
      stopAll()
    }
  }
}
