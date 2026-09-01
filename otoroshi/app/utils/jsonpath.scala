package otoroshi.utils

import com.fasterxml.jackson.databind.JsonNode
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider
import com.jayway.jsonpath.{Configuration, DocumentContext, JsonPath}
import otoroshi.api.OtoroshiEnvHolder
import otoroshi.env.Env
import play.api.Logger
import play.api.libs.json.{
  Format,
  JsArray,
  JsBoolean,
  JsError,
  JsNull,
  JsNumber,
  JsObject,
  JsResult,
  JsString,
  JsSuccess,
  JsValue,
  Json,
  Reads,
  Writes
}
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.jackson.JacksonJson

import scala.annotation.tailrec
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}

object JsonPathUtils {

  private val logger = Logger("otoroshi-jsonpath-utils")

  def matchWith(payload: JsValue): String => Boolean = { (query: String) =>
    {
      getAtPolyJson(payload, query).isDefined
    }
  }

  def getAtJson[T](payload: JsValue, path: String)(using r: Reads[T]): Option[T] = {
    getAt[T](Json.stringify(payload), path)(using r)
  }

  def getAt[T](payload: String, path: String)(using r: Reads[T]): Option[T] = {
    getAtPoly(payload, path).flatMap(_.asOpt[T](using r))
  }

  def getAtPolyJsonStr(payload: JsValue, path: String): String = {
    (getAtPoly(Json.stringify(payload), path) match {
      case Some(JsString(value))  => value.some
      case Some(JsBoolean(value)) => value.toString.some
      case Some(JsNumber(value))  => value.toString.some
      case Some(o @ JsObject(_))  => o.stringify.some
      case Some(o @ JsArray(_))   => o.stringify.some
      case _                      => "null".some
    }).getOrElse("null")
  }

  private[utils] val config: Configuration = {
    val default = Configuration.defaultConfiguration()
    Configuration
      .builder()
      .evaluationListener(default.getEvaluationListeners)
      .options(default.getOptions)
      .jsonProvider(new JacksonJsonNodeJsonProvider())
      .mappingProvider(new JacksonMappingProvider())
      .build()
  }

  def getAtPolyJson(payload: JsValue, path: String): Option[JsValue] = {
    getAtPoly(Json.stringify(payload), path)
    // val env = OtoroshiEnvHolder.get()
    // env.metrics.withTimer("JsonPathUtils.getAtPolyJson") {
    //   Try {
    //     val docCtx = JsonPath.parse(Reads.JsonNodeReads.reads(payload).get, config)
    //     Writes.jsonNodeWrites.writes(docCtx.read[JsonNode](path))
    //   } match {
    //     case Failure(e) =>
    //       logger.error(s"error while trying to read '$path' on '$payload'", e)
    //       None
    //     case Success(s) => s.some
    //   }
    // }
  }

  private lazy val jsonPathNullReadIsJsNull = OtoroshiEnvHolder.get().jsonPathNullReadIsJsNull

  def getAtPolyF(payload: String, path: String): Either[JsonPathReadError, JsValue] = {
    //val env = OtoroshiEnvHolder.get()
    //env.metrics.withTimer("JsonPathUtils.getAtPolyF") {
    Try {
      val docCtx = JsonPath.parse(payload, config)
      val read   = docCtx.read[JsonNode](path)
      if (read != null) {
        Right(Writes.jsonNodeWrites.writes(read))
      } else {
        if (jsonPathNullReadIsJsNull) {
          Right(JsNull)
        } else {
          Left(JsonPathReadError("null read", path, payload, None))
        }
      }
    } match {
      case Failure(e) => Left(JsonPathReadError("error while trying to read", path, payload, e.some))
      case Success(s) => s
    }
    //}
  }

  // parses a payload once so that several validators can read their own path off it, instead of
  // each of them serialising and re-parsing the whole payload. see JsonPathDocument.
  def document(payload: JsValue): JsonPathDocument = new JsonPathDocument(payload)

  private[utils] def nullRead: Option[JsValue] = if (jsonPathNullReadIsJsNull) JsNull.some else None

  def getAtPoly(payload: String, path: String): Option[JsValue] = {
    getAtPolyF(payload, path) match {
      case Right(value)                                      => value.some
      case Left(JsonPathReadError(message, _, _, Some(err))) =>
        if (logger.isDebugEnabled) logger.debug(s"${message} : '$path' on '$payload'", err)
        None
      case Left(JsonPathReadError(message, _, _, _))         =>
        if (logger.isDebugEnabled) logger.debug(message)
        None
    }
  }
}

case class JsonPathReadError(message: String, path: String, payload: String, err: Option[Throwable])

// Opt in fast reader for the case where the same payload is read by a whole list of json paths.
// Deliberately kept apart from JsonPathUtils.getAtPoly*, which the rest of otoroshi relies on and
// whose exact behaviour must not move. Three things are done differently here:
//
//   - a plain dotted path ($.a.b, $.attrs['x.y'].z) is walked straight on the JsValue, so the common
//     case never touches jackson nor jayway at all;
//   - the jayway document is built lazily, and only when a path that actually needs jayway shows up;
//   - it is built from a JsonNode rather than from a string, which skips a full text serialisation
//     and the matching text parse;
//   - compiled paths are cached, so a path is analysed once and not on every read.
object FastJsonPath {

  // a path made only of plain segments, which is what the overwhelming majority of predicates are
  private val simplePath = """^\$(?:\.[A-Za-z_][A-Za-z0-9_\-]*|\['[^'\[\]]+'\]|\["[^"\[\]]+"\])+$""".r
  private val segment    = """\.([A-Za-z_][A-Za-z0-9_\-]*)|\['([^'\[\]]+)'\]|\["([^"\[\]]+)"\]""".r

  private val segmentsCache: Cache[String, Option[List[String]]] =
    Scaffeine().maximumSize(2000).build[String, Option[List[String]]]()

  private val compiledCache: Cache[String, Option[JsonPath]] =
    Scaffeine().maximumSize(2000).build[String, Option[JsonPath]]()

  // Some(segments) when the path can be walked directly, None when jayway is needed
  def segmentsOf(path: String): Option[List[String]] = segmentsCache.get(
    path,
    p =>
      if (simplePath.matches(p)) {
        segment
          .findAllMatchIn(p)
          .map(m => if (m.group(1) != null) m.group(1) else if (m.group(2) != null) m.group(2) else m.group(3))
          .toList
          .some
      } else {
        None
      }
  )

  def compiledOf(path: String): Option[JsonPath] =
    compiledCache.get(path, p => Try(JsonPath.compile(p)).toOption)

  @tailrec
  def walk(current: JsValue, segments: List[String]): Option[JsValue] = segments match {
    case Nil          => current.some
    case head :: tail =>
      current match {
        case obj: JsObject =>
          obj.value.get(head) match {
            case None        => None
            case Some(value) => walk(value, tail)
          }
        case _             => None
      }
  }
}

// A payload read several times, by several json paths. `isObject` is carried along because
// JsonPathValidator needs to know the shape of the payload it was built from.
final class JsonPathDocument(payload: JsValue) {

  val isObject: Boolean = payload.isInstanceOf[JsObject]

  // only paid for when a path that jayway has to handle actually shows up
  private lazy val document: DocumentContext = Reads.JsonNodeReads.reads(payload) match {
    case JsSuccess(node, _) => JsonPath.parse(node, JsonPathUtils.config)
    case _                  => JsonPath.parse(Json.stringify(payload), JsonPathUtils.config)
  }

  def read(path: String): Option[JsValue] = FastJsonPath.segmentsOf(path) match {
    case Some(segments) => FastJsonPath.walk(payload, segments)
    case None           => readWithJsonPath(path)
  }

  private def readWithJsonPath(path: String): Option[JsValue] = FastJsonPath.compiledOf(path) match {
    case None           => None
    case Some(compiled) =>
      // the untyped read on purpose, exactly like getAtPolyF does. asking jayway for a JsonNode
      // instead would engage its mapping provider, and a path whose result is not a node, such as
      // `length()`, would start resolving here while it does not on the regular road.
      Try(document.read[JsonNode](compiled)) match {
        case Failure(e)                  =>
          if (JsonPathDocument.logger.isDebugEnabled) {
            JsonPathDocument.logger.debug(s"error while trying to read '$path'", e)
          }
          None
        case Success(node) if node != null => Writes.jsonNodeWrites.writes(node).some
        case Success(_)                    => JsonPathUtils.nullRead
      }
  }
}

object JsonPathDocument {
  private val logger = Logger("otoroshi-jsonpath-document")
}

case class JsonPathValidator(path: String, value: JsValue, error: Option[String] = None) extends JsonValidator {
  def json: JsValue         = JsonPathValidator.format.writes(this)
  override def kind: String = "json-path-validator"
  def validate(ctx: JsValue)(using env: Env): Boolean =
    check(ctx.atPath(path).asOpt[JsValue], ctx.isInstanceOf[JsObject])

  // reads the path off a payload that was parsed once. a list of validators sharing the same payload
  // then pays a single serialisation and a single json parse instead of one per validator.
  def validate(doc: JsonPathDocument)(using env: Env): Boolean =
    check(doc.read(path), doc.isObject)

  private def check(read: Option[JsValue], payloadIsObject: Boolean): Boolean = {
    val maybeExpr = value.asOptString.getOrElse("")
    read match {
      case None if maybeExpr == "NotDefined()"                      => true
      case None                                                     => false
      case Some(_) if maybeExpr == "IsDefined()"                    => true
      case Some(JsNumber(v)) if value.isInstanceOf[JsString]        => v.toString == value.asString
      case Some(JsBoolean(v)) if value.isInstanceOf[JsString]       => v.toString == value.asString
      case Some(JsArray(seq))
          if path.startsWith("[?(") && path.endsWith(")]") && payloadIsObject && value
            .isInstanceOf[JsBoolean] =>
        seq.nonEmpty
      case Some(arr @ JsArray(seq)) if value.isInstanceOf[JsString] => {
        val expected = value.asString
        if (expected.trim.startsWith("Size(") && expected.trim.endsWith(")")) {
          seq.size == expected.substring(5).init.toInt
        } else if (expected.trim.startsWith("SizeNot(") && expected.trim.endsWith(")")) {
          seq.size != expected.substring(8).init.toInt
        } else if (expected.trim.startsWith("SizeLt(") && expected.trim.endsWith(")")) {
          seq.size < expected.substring(7).init.toInt
        } else if (expected.trim.startsWith("SizeGt(") && expected.trim.endsWith(")")) {
          seq.size > expected.substring(7).init.toInt
        } else if (expected.trim.startsWith("SizeLte(") && expected.trim.endsWith(")")) {
          seq.size <= expected.substring(8).init.toInt
        } else if (expected.trim.startsWith("SizeGte(") && expected.trim.endsWith(")")) {
          seq.size >= expected.substring(8).init.toInt
        } else if (expected.trim.startsWith("Contains(") && expected.trim.endsWith(")")) {
          seq.contains(JsString(expected.substring(9).init))
        } else if (expected.trim.startsWith("ContainsNot(") && expected.trim.endsWith(")")) {
          !seq.contains(JsString(expected.substring(12).init))
        } else if (expected.trim.startsWith("Contains(Regex(") && expected.trim.endsWith("))")) {
          val regex = expected.substring(15).init.init
          val r     = RegexPool.regex(regex)
          seq.exists {
            case JsString(str) => r.matches(str)
            case _             => false
          }
        } else if (expected.trim.startsWith("Contains(Wildcard(") && expected.trim.endsWith("))")) {
          val regex = expected.substring(18).init.init
          val r     = RegexPool.apply(regex)
          seq.exists {
            case JsString(str) => r.matches(str)
            case _             => false
          }
        } else if (expected.trim.startsWith("ContainsNot(Regex(") && expected.trim.endsWith("))")) {
          val regex = expected.substring(18).init.init
          val r     = RegexPool.regex(regex)
          !seq.exists {
            case JsString(str) => r.matches(str)
            case _             => false
          }
        } else if (expected.trim.startsWith("ContainsNot(Wildcard(") && expected.trim.endsWith("))")) {
          val regex = expected.substring(21).init.init
          val r     = RegexPool.apply(regex)
          !seq.exists {
            case JsString(str) => r.matches(str)
            case _             => false
          }
        } ///////
        else if (expected.trim.startsWith("JsonContains(") && expected.trim.endsWith(")")) {
          seq.exists(_.stringify.contains(expected.substring(13).init))
        } else if (expected.trim.startsWith("JsonContainsNot(") && expected.trim.endsWith(")")) {
          !seq.exists(_.stringify.contains(expected.substring(16).init))
        } else if (expected.trim.startsWith("JsonContains(Regex(") && expected.trim.endsWith("))")) {
          val regex = expected.substring(19).init.init
          val r     = RegexPool.regex(regex)
          seq.exists(s => r.matches(s.stringify))
        } else if (expected.trim.startsWith("JsonContains(Wildcard(") && expected.trim.endsWith("))")) {
          val regex = expected.substring(23).init.init
          val r     = RegexPool.apply(regex)
          seq.exists(s => r.matches(s.stringify))
        } else if (expected.trim.startsWith("JsonContainsNot(Regex(") && expected.trim.endsWith("))")) {
          val regex = expected.substring(23).init.init
          val r     = RegexPool.regex(regex)
          !seq.exists(s => r.matches(s.stringify))
        } else if (expected.trim.startsWith("JsonContainsNot(Wildcard(") && expected.trim.endsWith("))")) {
          val regex = expected.substring(25).init.init
          val r     = RegexPool.apply(regex)
          !seq.exists(s => r.matches(s.stringify))
        } /////////
        else if (expected.trim.startsWith("StartsWith(") && expected.trim.endsWith(")")) {
          val v = expected.substring(11).init
          seq.forall {
            case JsString(str) => str.startsWith(v)
            case _             => false
          }
        } else if (expected.trim.startsWith("DontStartsWith(") && expected.trim.endsWith(")")) {
          val v = expected.substring(15).init
          seq.forall {
            case JsString(str) => !str.startsWith(v)
            case _             => false
          }
        } else {
          arr.stringify == expected
        }
      }
      case Some(JsArray(seq)) if !value.isInstanceOf[JsArray]       => seq.contains(value)
      case Some(JsString(v)) if value.isInstanceOf[JsString]        => {
        val expected = value.asString
        if (expected.trim.startsWith("Regex(") && expected.trim.endsWith(")")) {
          val regex = expected.substring(6).init
          RegexPool.regex(regex).matches(v)
        } else if (expected.trim.startsWith("Wildcard(") && expected.trim.endsWith(")")) {
          val regex = expected.substring(9).init
          RegexPool.apply(regex).matches(v)
        } else if (expected.trim.startsWith("RegexNot(") && expected.trim.endsWith(")")) {
          val regex = expected.substring(9).init
          !RegexPool.regex(regex).matches(v)
        } else if (expected.trim.startsWith("WildcardNot(") && expected.trim.endsWith(")")) {
          val regex = expected.substring(12).init
          !RegexPool.apply(regex).matches(v)
        } else if (expected.trim.startsWith("Contains(") && expected.trim.endsWith(")")) {
          val contained = expected.substring(9).init
          v.contains(contained)
        } else if (expected.trim.startsWith("ContainsNot(") && expected.trim.endsWith(")")) {
          val contained = expected.substring(12).init
          !v.contains(contained)
        } else if (expected.trim.startsWith("Not(") && expected.trim.endsWith(")")) {
          val contained = expected.substring(4).init
          v != contained
        } else if (expected.trim.startsWith("ContainedIn(") && expected.trim.endsWith(")")) {
          val contained = expected.substring(12).init
          contained.split(",").map(_.trim()).contains(v)
        } else if (expected.trim.startsWith("NotContainedIn(") && expected.trim.endsWith(")")) {
          val contained = expected.substring(15).init
          val values    = contained.split(",").map(_.trim())
          !values.contains(v)
        } else {
          v == expected
        }
      }
      case Some(v)                                                  => v == value
    }
  }
}

object JsonPathValidator {
  val format = new Format[JsonPathValidator] {
    override def writes(o: JsonPathValidator): JsValue             = Json.obj(
      "kind"  -> "json-path-validator",
      "path"  -> o.path,
      "value" -> o.value,
      "error" -> o.error.map(JsString.apply).orJsNull
    )
    override def reads(json: JsValue): JsResult[JsonPathValidator] = Try {
      JsonPathValidator(
        path = json.select("path").as[String],
        value = json.select("value").asValue,
        error = json.select("error").asOpt[String].filter(_.trim.nonEmpty)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value)     => JsSuccess(value)
    }
  }
}
