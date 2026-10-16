package openapi

import play.api.libs.json.*

import java.util.Base64
import scala.util.Try

// a place in a json value, written the way jq or jsonpath users read it: $.frontend.domains[0]
object JsonPath {
  private val identifier                       = "^[A-Za-z_$][A-Za-z0-9_$-]*$".r
  def field(path: String, key: String): String =
    if (identifier.matches(key)) s"$path.$key" else s"""$path["${key.replace("\"", "\\\"")}"]"""
  def index(path: String, idx: Int): String    = s"$path[$idx]"
}

// something the openapi document says that the api does not do, or the other way around
case class Violation(path: String, message: String) {
  override def toString: String = s"$path: $message"
}

object Violations {
  // the failure message of a test lists every violation, one per line, so that a run tells everything at once
  def report(title: String, violations: Seq[Violation]): String =
    (s"$title (${violations.size})" +: violations.distinct.map(v => s"  - $v")).mkString("\n")
}

// one operation of the document, with the parameters of its path merged in
case class OpenApiOperation(path: String, method: String, json: JsObject, pathParameters: Seq[JsObject]) {

  def name: String                  = s"${method.toUpperCase} $path"
  def operationId: Option[String]   = (json \ "operationId").asOpt[String]
  def tags: Seq[String]             = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty)
  def parameters: Seq[JsObject]     = {
    val own = (json \ "parameters").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
    // an operation parameter overrides the path parameter with the same name and location
    val key = (p: JsObject) => ((p \ "name").asOpt[String], (p \ "in").asOpt[String])
    own ++ pathParameters.filterNot(p => own.exists(o => key(o) == key(p)))
  }
  def requestBody: Option[JsObject] = (json \ "requestBody").asOpt[JsObject]
  def requestContent: Map[String, JsObject] =
    requestBody.flatMap(b => (b \ "content").asOpt[Map[String, JsObject]]).getOrElse(Map.empty)
  def responses: Map[String, JsObject] = (json \ "responses").asOpt[Map[String, JsObject]].getOrElse(Map.empty)
  def documentedStatuses: Set[String]  = responses.keySet

  // the response documented for a status, the 2XX like ranges and default included
  def response(status: Int): Option[JsObject] =
    responses.get(status.toString).orElse(responses.get(s"${status / 100}XX")).orElse(responses.get("default"))

  def responseContent(status: Int): Map[String, JsObject] =
    response(status).flatMap(r => (r \ "content").asOpt[Map[String, JsObject]]).getOrElse(Map.empty)

  def pathParameterNames: Seq[String] = "\\{([^}]+)\\}".r.findAllMatchIn(path).map(_.group(1)).toSeq
}

// an openapi 3.0 document, the target of its local $ref resolved
class OpenApiDocument(val json: JsObject) {

  val methods: Seq[String] = Seq("get", "put", "post", "delete", "options", "head", "patch", "trace")

  lazy val schemas: JsObject = (json \ "components" \ "schemas").asOpt[JsObject].getOrElse(Json.obj())
  lazy val paths: JsObject   = (json \ "paths").asOpt[JsObject].getOrElse(Json.obj())

  lazy val operations: Seq[OpenApiOperation] = paths.fields.toSeq.sortBy(_._1).flatMap {
    case (path, item: JsObject) =>
      val pathParameters = (item \ "parameters").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
      methods.flatMap { method =>
        (item \ method).asOpt[JsObject].map(op => OpenApiOperation(path, method, op, pathParameters))
      }
    case _                      => Seq.empty
  }

  def operation(method: String, path: String): Option[OpenApiOperation] =
    operations.find(op => op.method == method.toLowerCase && op.path == path)

  // a json pointer as written in a $ref: #/components/schemas/Foo
  def resolve(ref: String): Option[JsValue] = {
    if (!ref.startsWith("#")) None
    else {
      val tokens = ref.drop(1).split("/").toSeq.filter(_.nonEmpty).map(_.replace("~1", "/").replace("~0", "~"))
      tokens.foldLeft(Option(json: JsValue)) {
        case (Some(obj: JsObject), token) => obj.value.get(token)
        case (Some(arr: JsArray), token)  => token.toIntOption.flatMap(idx => arr.value.lift(idx))
        case _                            => None
      }
    }
  }

  def schemaRef(name: String): JsObject = Json.obj("$ref" -> s"#/components/schemas/$name")

  // follows the $ref chain of a schema until a schema that is not a reference
  def deref(schema: JsValue, seen: Set[String] = Set.empty): Option[JsObject] = schema match {
    case obj: JsObject =>
      (obj \ "$ref").asOpt[String] match {
        case Some(ref) if seen.contains(ref) => None
        case Some(ref)                       => resolve(ref).flatMap(target => deref(target, seen + ref))
        case None                            => Some(obj)
      }
    case _             => None
  }

  // every $ref of a json value, with the place it was found at
  def refs(js: JsValue, path: String = "$"): Seq[(String, String)] = js match {
    case obj: JsObject =>
      obj.fields.toSeq.flatMap {
        case ("$ref", JsString(ref)) => Seq((path, ref))
        case (key, value)            => refs(value, JsonPath.field(path, key))
      }
    case arr: JsArray  => arr.value.zipWithIndex.toSeq.flatMap { case (value, idx) => refs(value, JsonPath.index(path, idx)) }
    case _             => Seq.empty
  }
}

// validates a json value against an openapi 3.0 schema object. it is stricter than openapi on one point: a
// property that an object schema with properties does not declare is a violation, unless additionalProperties
// says otherwise, because the document has to describe everything the api writes
class SchemaValidator(doc: OpenApiDocument) {

  def validate(value: JsValue, schema: JsValue, path: String = "$"): Seq[Violation] =
    check(value, schema, path, Set.empty, lenient = false)

  private def typeOf(value: JsValue): String = value match {
    case JsNull       => "null"
    case _: JsString  => "string"
    case n: JsNumber  => if (n.value.isWhole) "integer" else "number"
    case _: JsBoolean => "boolean"
    case _: JsArray   => "array"
    case _: JsObject  => "object"
  }

  private def show(value: JsValue): String = {
    val str = Json.stringify(value)
    if (str.length > 80) str.take(77) + "..." else str
  }

  private def matchesType(value: JsValue, tpe: String): Boolean = (tpe, value) match {
    case ("string", _: JsString)   => true
    case ("number", _: JsNumber)   => true
    case ("integer", n: JsNumber)  => n.value.isWhole
    case ("boolean", _: JsBoolean) => true
    case ("array", _: JsArray)     => true
    case ("object", _: JsObject)   => true
    case _                         => false
  }

  private def checkFormat(value: JsValue, format: String, path: String): Seq[Violation] = (format, value) match {
    case ("int32", n: JsNumber) if !n.value.isValidInt                                          =>
      Seq(Violation(path, s"${n.value} does not fit the int32 format"))
    case ("int64", n: JsNumber) if !n.value.isValidLong                                         =>
      Seq(Violation(path, s"${n.value} does not fit the int64 format"))
    case ("date-time", JsString(s)) if Try(java.time.OffsetDateTime.parse(s)).isFailure         =>
      Seq(Violation(path, s"'$s' is not a date-time (RFC 3339)"))
    case ("date", JsString(s)) if Try(java.time.LocalDate.parse(s)).isFailure                   =>
      Seq(Violation(path, s"'$s' is not a date (RFC 3339 full-date)"))
    case ("byte", JsString(s)) if Try(Base64.getDecoder.decode(s)).isFailure                    =>
      Seq(Violation(path, s"'${s.take(40)}' is not base64 encoded"))
    case ("uuid", JsString(s)) if Try(java.util.UUID.fromString(s)).isFailure                   =>
      Seq(Violation(path, s"'$s' is not an uuid"))
    case _                                                                                      => Seq.empty
  }

  // lenient: the undocumented properties are checked by the caller, as for the parts of an allOf that only describe
  // a share of the properties
  private def check(value: JsValue, schema: JsValue, path: String, seenRefs: Set[String], lenient: Boolean): Seq[Violation] =
    schema match {
      case JsBoolean(true)  => Seq.empty
      case JsBoolean(false) => Seq(Violation(path, "no value is allowed here"))
      case obj: JsObject    =>
        (obj \ "$ref").asOpt[String] match {
          // a $ref met again at the same place of the value would loop forever
          case Some(ref) if seenRefs.contains(ref) => Seq.empty
          case Some(ref)                           =>
            doc.resolve(ref) match {
              case None         => Seq(Violation(path, s"the schema $ref does not exist"))
              case Some(target) => check(value, target, path, seenRefs + ref, lenient)
            }
          case None                                => checkSchema(value, obj, path, seenRefs, lenient)
        }
      case other            => Seq(Violation(path, s"invalid schema ${show(other)}"))
    }

  private def checkSchema(value: JsValue, schema: JsObject, path: String, seenRefs: Set[String], lenient: Boolean): Seq[Violation] = {
    val nullable = (schema \ "nullable").asOpt[Boolean].contains(true)
    val tpe      = (schema \ "type").asOpt[String]
    if (value == JsNull) {
      val constrained = tpe.isDefined || schema.keys.exists(k =>
        Set("properties", "items", "enum", "oneOf", "anyOf", "allOf", "additionalProperties").contains(k)
      )
      if (nullable || !constrained) Seq.empty
      else Seq(Violation(path, s"null is not allowed, the schema is not nullable${tpe.map(t => s" ($t)").getOrElse("")}"))
    } else {
      // the composition keywords apply on top of the other keywords of the same schema
      val composition = checkComposition(value, schema, path, seenRefs, lenient)
      val own         = tpe match {
        case Some(t) if !matchesType(value, t) =>
          Seq(Violation(path, s"expected ${t}, got ${typeOf(value)} ${show(value)}"))
        case _                                 =>
          val enumViolations = (schema \ "enum").asOpt[JsArray] match {
            case Some(values) if !values.value.contains(value) =>
              Seq(Violation(path, s"${show(value)} is not one of ${show(values)}"))
            case _                                             => Seq.empty
          }
          val formatViolations = (schema \ "format").asOpt[String].toSeq.flatMap(f => checkFormat(value, f, path))
          enumViolations ++ formatViolations ++ checkConstraints(value, schema, path) ++ (value match {
            case obj: JsObject => checkObject(obj, schema, path, lenient)
            case arr: JsArray  => checkArray(arr, schema, path, seenRefs)
            case _             => Seq.empty
          })
      }
      composition ++ own
    }
  }

  // the properties a schema declares for a value: its own, the ones of its allOf parts, and the ones of the oneOf or
  // anyOf alternatives the value matches (what json schema calls the evaluated properties). None when the schema
  // accepts any property
  private def declared(value: JsValue, schema: JsValue, seenRefs: Set[String]): Option[Set[String]] =
    doc.deref(schema) match {
      case None    => Some(Set.empty)
      case Some(s) =>
        if ((s \ "additionalProperties").isDefined) None
        else {
          val own          = (s \ "properties").asOpt[JsObject].map(_.keys.toSet).getOrElse(Set.empty)
          val parts        = (s \ "allOf").asOpt[Seq[JsValue]].getOrElse(Seq.empty).map(p => declared(value, p, seenRefs))
          val alternatives = Seq("oneOf", "anyOf")
            .flatMap(k => (s \ k).asOpt[Seq[JsValue]].toSeq.flatten)
            .filter(alt => check(value, alt, "$", seenRefs, lenient = true).isEmpty)
            .map(alt => declared(value, alt, seenRefs))
          (parts ++ alternatives).foldLeft(Option(own)) {
            case (Some(acc), Some(more)) => Some(acc ++ more)
            case _                       => None
          }
        }
    }

  private def checkComposition(value: JsValue, schema: JsObject, path: String, seenRefs: Set[String], lenient: Boolean): Seq[Violation] = {
    val parts = (schema \ "allOf").asOpt[Seq[JsValue]].getOrElse(Seq.empty)
    // every part describes a share of the properties, a property is undocumented when no part declares it
    val allOf = parts.flatMap(s => check(value, s, path, seenRefs, lenient = true)) ++ (value match {
      case obj: JsObject if parts.nonEmpty && !lenient =>
        declared(value, schema, seenRefs) match {
          case None                         => Seq.empty
          case Some(keys) if keys.isEmpty   => Seq.empty
          case Some(keys)                   =>
            obj.fields.toSeq.collect {
              case (key, v) if !keys.contains(key) =>
                Violation(JsonPath.field(path, key), s"written but not documented (${typeOf(v)} ${show(v)})")
            }
        }
      case _                                           => Seq.empty
    })
    // the alternatives of a part of an allOf leave the undocumented properties to the allOf
    def alternatives(key: String): Seq[Seq[Violation]] =
      (schema \ key).asOpt[Seq[JsValue]].getOrElse(Seq.empty).map(s => check(value, s, path, seenRefs, lenient = lenient))
    val anyOf = alternatives("anyOf") match {
      case Seq()                            => Seq.empty
      case results if results.exists(_.isEmpty) => Seq.empty
      case results                          =>
        Violation(path, s"matches none of the ${results.size} anyOf alternatives, the closest one says:") +:
        results.minBy(_.size)
    }
    val oneOf = alternatives("oneOf") match {
      case Seq()   => Seq.empty
      case results =>
        results.count(_.isEmpty) match {
          case 1 => Seq.empty
          case 0 =>
            Violation(path, s"matches none of the ${results.size} oneOf alternatives, the closest one says:") +:
            results.minBy(_.size)
          case n => Seq(Violation(path, s"matches $n of the oneOf alternatives instead of exactly one"))
        }
    }
    allOf ++ anyOf ++ oneOf
  }

  private def checkConstraints(value: JsValue, schema: JsObject, path: String): Seq[Violation] = {
    def num(key: String): Option[BigDecimal] = (schema \ key).asOpt[BigDecimal]
    def int(key: String): Option[Int]        = (schema \ key).asOpt[Int]
    value match {
      case JsString(s)  =>
        int("minLength").filter(s.length < _).map(m => Violation(path, s"shorter than $m characters")).toSeq ++
        int("maxLength").filter(s.length > _).map(m => Violation(path, s"longer than $m characters")).toSeq ++
        (schema \ "pattern")
          .asOpt[String]
          .filterNot(p => Try(p.r.findFirstIn(s).isDefined).getOrElse(true))
          .map(p => Violation(path, s"'$s' does not match the pattern $p"))
          .toSeq
      case JsNumber(n)  =>
        val exclusiveMin = (schema \ "exclusiveMinimum").asOpt[Boolean].contains(true)
        val exclusiveMax = (schema \ "exclusiveMaximum").asOpt[Boolean].contains(true)
        num("minimum").filter(m => if (exclusiveMin) n <= m else n < m).map(m => Violation(path, s"$n is below the minimum $m")).toSeq ++
        num("maximum").filter(m => if (exclusiveMax) n >= m else n > m).map(m => Violation(path, s"$n is above the maximum $m")).toSeq ++
        num("multipleOf").filter(m => m != 0 && (n % m) != 0).map(m => Violation(path, s"$n is not a multiple of $m")).toSeq
      case arr: JsArray =>
        int("minItems").filter(arr.value.size < _).map(m => Violation(path, s"less than $m items")).toSeq ++
        int("maxItems").filter(arr.value.size > _).map(m => Violation(path, s"more than $m items")).toSeq ++
        (if ((schema \ "uniqueItems").asOpt[Boolean].contains(true) && arr.value.distinct.size != arr.value.size)
           Seq(Violation(path, "the items are not unique"))
         else Seq.empty)
      case obj: JsObject =>
        int("minProperties").filter(obj.keys.size < _).map(m => Violation(path, s"less than $m properties")).toSeq ++
        int("maxProperties").filter(obj.keys.size > _).map(m => Violation(path, s"more than $m properties")).toSeq
      case _            => Seq.empty
    }
  }

  private def checkObject(obj: JsObject, schema: JsObject, path: String, lenient: Boolean): Seq[Violation] = {
    val properties = (schema \ "properties").asOpt[JsObject].getOrElse(Json.obj())
    val required   = (schema \ "required").asOpt[Seq[String]].getOrElse(Seq.empty)
    val missing    = required.filterNot(obj.keys.contains).map(k => Violation(JsonPath.field(path, k), "required but missing"))
    val fields     = obj.fields.toSeq.flatMap { case (key, value) =>
      val fieldPath = JsonPath.field(path, key)
      properties.value.get(key) match {
        case Some(propertySchema) => check(value, propertySchema, fieldPath, Set.empty, lenient = false)
        case None                 =>
          (schema \ "additionalProperties").toOption match {
            case Some(JsBoolean(true))   => Seq.empty
            case Some(JsBoolean(false))  => Seq(Violation(fieldPath, "not allowed by additionalProperties: false"))
            case Some(other: JsObject)   => check(value, other, fieldPath, Set.empty, lenient = false)
            // composition schemas describe their properties in their alternatives
            case _ if !lenient && properties.keys.nonEmpty && !schema.keys.exists(Set("allOf", "anyOf", "oneOf").contains) =>
              Seq(Violation(fieldPath, s"written but not documented (${typeOf(value)} ${show(value)})"))
            case _                       => Seq.empty
          }
      }
    }
    missing ++ fields
  }

  private def checkArray(arr: JsArray, schema: JsObject, path: String, seenRefs: Set[String]): Seq[Violation] =
    (schema \ "items").toOption match {
      case Some(items) =>
        arr.value.zipWithIndex.toSeq.flatMap { case (v, idx) => check(v, items, JsonPath.index(path, idx), Set.empty, lenient = false) }
      case None        => Seq.empty
    }
}

// a json value built from a schema the way a client reading the document would: every documented property gets a
// value of its documented type, the examples and the enums of the document being used first. the values are chosen
// to differ from the usual defaults (true, 42, a non empty string) so that a field the api ignores shows up
case class SampleLeaf(path: String, value: JsValue)

// the read only properties are written by the api, a client does not send them and they are left out of a sample.
// the write only ones are sent but not answered, they are left out of the values expected back
class SampleBuilder(doc: OpenApiDocument, maxDepth: Int = 7) {

  private def flagged(schema: JsValue, flag: String): Boolean = doc.deref(schema).exists(s => (s \ flag).asOpt[Boolean].contains(true)) ||
    (schema \ flag).asOpt[Boolean].contains(true)
  def readOnly(schema: JsValue): Boolean  = flagged(schema, "readOnly")
  def writeOnly(schema: JsValue): Boolean = flagged(schema, "writeOnly")


  def sample(schema: JsValue): Option[JsValue] = build(schema, "value", 0, Set.empty)

  private def sanitize(name: String): String = name.replaceAll("[^A-Za-z0-9]+", "-").stripPrefix("-").stripSuffix("-")

  private def build(schema: JsValue, name: String, depth: Int, refs: Set[String]): Option[JsValue] = schema match {
    case obj: JsObject if depth <= maxDepth =>
      (obj \ "$ref").asOpt[String] match {
        // a recursive schema stops at its second occurrence on a branch
        case Some(ref) if refs.contains(ref) => None
        case Some(ref)                       => doc.resolve(ref).flatMap(target => build(target, name, depth, refs + ref))
        case None                            => buildSchema(obj, name, depth, refs)
      }
    case _                                  => None
  }

  private def buildSchema(schema: JsObject, name: String, depth: Int, refs: Set[String]): Option[JsValue] = {
    lazy val alternatives = (schema \ "oneOf").asOpt[Seq[JsValue]].orElse((schema \ "anyOf").asOpt[Seq[JsValue]])
    (schema \ "example").toOption
      .orElse((schema \ "enum").asOpt[JsArray].flatMap(_.value.headOption))
      .orElse {
        (schema \ "allOf").asOpt[Seq[JsValue]] match {
          // the parts of an object are merged, a single part wrapped to add readOnly or nullable is that part
          case Some(parts) =>
            val built = parts.flatMap(p => build(p, name, depth + 1, refs))
            if (built.nonEmpty && built.forall(_.isInstanceOf[JsObject])) Some(built.collect { case o: JsObject => o }.foldLeft(Json.obj())(_ ++ _))
            else built.headOption
          case None        => None
        }
      }
      .orElse(alternatives.flatMap(_.headOption).flatMap(alt => build(alt, name, depth + 1, refs)))
      .orElse {
        val tpe = (schema \ "type").asOpt[String].orElse {
          if ((schema \ "properties").isDefined || (schema \ "additionalProperties").isDefined) Some("object")
          else if ((schema \ "items").isDefined) Some("array")
          else None
        }
        tpe match {
          case Some("string")  =>
            Some((schema \ "format").asOpt[String] match {
              case Some("date-time") => JsString("2026-03-14T15:09:26.535Z")
              case Some("date")      => JsString("2026-03-14")
              case Some("byte")      => JsString(Base64.getEncoder.encodeToString(s"sample-${sanitize(name)}".getBytes))
              case Some("uuid")      => JsString("0f7b9c3e-2a4d-4e8f-9b1c-5d6e7f8a9b0c")
              case _                 => JsString(s"sample-${sanitize(name)}")
            })
          case Some("integer") => Some(JsNumber(42))
          case Some("number")  => Some(JsNumber(BigDecimal("4.2")))
          case Some("boolean") => Some(JsBoolean(true))
          case Some("array")   =>
            Some(JsArray((schema \ "items").toOption.flatMap(items => build(items, s"$name-item", depth + 1, refs)).toSeq))
          case Some("object")  =>
            val properties = (schema \ "properties").asOpt[JsObject].getOrElse(Json.obj())
            val fields     = properties.fields.toSeq.filterNot { case (_, s) => readOnly(s) }.flatMap { case (key, s) =>
              build(s, key, depth + 1, refs).map(v => key -> v)
            }
            val additional = (schema \ "additionalProperties").toOption match {
              case Some(s: JsObject) if properties.keys.isEmpty =>
                build(s, s"$name-value", depth + 1, refs).map(v => s"sample-${sanitize(name)}-key" -> v).toSeq
              case _                                            => Seq.empty
            }
            Some(JsObject(fields ++ additional))
          case _               => None
        }
      }
  }

  // the scalar values of a sample that a client expects to read back, with their place. the free form parts of a
  // schema (no type, an object without properties) say nothing about what comes back, they are left out
  def leaves(value: JsValue, schema: JsValue, path: String = "$", refs: Set[String] = Set.empty): Seq[SampleLeaf] =
    doc.deref(schema) match {
      case None    => Seq.empty
      case Some(s) =>
        val alternatives = Seq("oneOf", "anyOf").flatMap(k => (s \ k).asOpt[Seq[JsValue]].toSeq.flatten)
        val allOf        = (s \ "allOf").asOpt[Seq[JsValue]].getOrElse(Seq.empty)
        if (allOf.nonEmpty) allOf.flatMap(part => leaves(value, part, path, refs)).distinct
        else if (alternatives.nonEmpty) alternatives.headOption.toSeq.flatMap(alt => leaves(value, alt, path, refs))
        else
          value match {
            case obj: JsObject =>
              val properties = (s \ "properties").asOpt[JsObject].getOrElse(Json.obj())
              val additional = (s \ "additionalProperties").asOpt[JsObject]
              obj.fields.toSeq.flatMap { case (key, v) =>
                properties.value.get(key).orElse(additional) match {
                  case Some(ps) if readOnly(ps) || writeOnly(ps) => Seq.empty
                  case Some(ps)                                  => leaves(v, ps, JsonPath.field(path, key), refs)
                  case None                                      => Seq.empty
                }
              }
            case arr: JsArray  =>
              (s \ "items").toOption.toSeq.flatMap { items =>
                arr.value.zipWithIndex.toSeq.flatMap { case (v, idx) => leaves(v, items, JsonPath.index(path, idx), refs) }
              }
            case JsNull        => Seq.empty
            case scalar        => if ((s \ "type").isDefined || (s \ "enum").isDefined) Seq(SampleLeaf(path, scalar)) else Seq.empty
          }
    }
}

// reads a value at a JsonPath built by JsonPath.field and JsonPath.index
object JsonPathReader {
  private val token = """\.([A-Za-z_$][A-Za-z0-9_$-]*)|\["((?:[^"\\]|\\.)*)"\]|\[(\d+)\]""".r

  def read(value: JsValue, path: String): Option[JsValue] = {
    token.findAllMatchIn(path.stripPrefix("$")).foldLeft(Option(value)) { (current, m) =>
      current.flatMap { js =>
        if (m.group(1) != null) (js \ m.group(1)).toOption
        else if (m.group(2) != null) (js \ m.group(2).replace("\\\"", "\"")).toOption
        else (js \ m.group(3).toInt).toOption
      }
    }
  }
}
