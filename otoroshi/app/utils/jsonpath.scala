package otoroshi.utils

import com.fasterxml.jackson.databind.JsonNode
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider
import com.jayway.jsonpath.{Configuration, JsonPath}
import net.minidev.json.{JSONArray, JSONObject}
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
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.jackson.JacksonJson

import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}

object JsonPathUtils {

  private val logger = Logger("otoroshi-jsonpath-utils")

  def matchWith(payload: JsValue, what: String): String => Boolean = { (query: String) =>
    {
      getAtPolyJson(payload, query).isDefined
    }
  }

  def getAtJson[T](payload: JsValue, path: String)(implicit r: Reads[T]): Option[T] = {
    getAt[T](Json.stringify(payload), path)(r)
  }

  def getAt[T](payload: String, path: String)(implicit r: Reads[T]): Option[T] = {
    getAtPoly(payload, path).flatMap(_.asOpt[T](r))
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

  private val config: Configuration = {
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

  def getAtPolyF(payload: String, path: String): Either[JsonPathReadError, JsValue] = {
    //val env = OtoroshiEnvHolder.get()
    //env.metrics.withTimer("JsonPathUtils.getAtPolyF") {
    Try {
      val docCtx = JsonPath.parse(payload, config)
      val read   = docCtx.read[JsonNode](path)
      if (read != null) {
        Right(Writes.jsonNodeWrites.writes(read))
      } else {
        Left(JsonPathReadError("null read", path, payload, None))
      }
    } match {
      case Failure(e)                               => Left(JsonPathReadError("error while trying to read", path, payload, e.some))
      case Success(s)                               => s
    }
    //}
  }

  def getAtPoly(payload: String, path: String): Option[JsValue] = {
    getAtPolyF(payload, path) match {
      case Right(value)                                      => value.some
      case Left(JsonPathReadError(message, _, _, Some(err))) =>
        if(logger.isDebugEnabled) logger.debug(s"${message} : '$path' on '$payload'", err)
        None
      case Left(JsonPathReadError(message, _, _, _))         =>
        if(logger.isDebugEnabled) logger.debug(message)
        None
    }
  }
}

case class JsonPathReadError(message: String, path: String, payload: String, err: Option[Throwable])

case class JsonPathValidator(path: String, value: JsValue, error: Option[String] = None) extends JsonValidator {
  def json: JsValue         = JsonPathValidator.format.writes(this)
  override def kind: String = "json-path-validator"
  def validate(ctx: JsValue)(implicit env: Env): Boolean = {
    ctx.atPath(path).asOpt[JsValue] match {
      case None                                                     => false
      case Some(JsNumber(v)) if value.isInstanceOf[JsString]        => v.toString == value.asString
      case Some(JsBoolean(v)) if value.isInstanceOf[JsString]       => v.toString == value.asString
      case Some(JsArray(seq))
          if path.startsWith("[?(") && path.endsWith(")]") && ctx.isInstanceOf[JsObject] && value
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
