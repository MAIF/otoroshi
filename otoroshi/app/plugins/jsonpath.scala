package otoroshi.plugins

import com.fasterxml.jackson.databind.JsonNode
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider
import com.jayway.jsonpath.{Configuration, JsonPath}
import net.minidev.json.{JSONArray, JSONObject}
import play.api.Logger
import play.api.libs.json.{JsArray, JsBoolean, JsNumber, JsObject, JsString, JsValue, Json, Writes}
import otoroshi.utils.syntax.implicits._

import scala.util.{Failure, Success, Try}

object JsonPathUtils {

  private val logger = Logger("otoroshi-plugins-jsonpath-helper")

  def matchWith(payload: JsValue, what: String): String => Boolean = { query: String => {
    Try(JsonPath.parse(Json.stringify(payload)).read[JSONArray](query)) match {
      case Failure(err) =>
        logger.error(s"error while matching query '$query' against $what: $err")
        false
      case Success(res) =>
        res.size() > 0
    }
  }
  }

  def getAtJson[T](payload: JsValue, path: String): Option[T] = getAt[T](Json.stringify(payload), path)

  def getAt[T](payload: String, path: String): Option[T] = {
    Try(JsonPath.parse(payload).read(path)) match {
      case Failure(err) =>
        logger.error(s"error while matching query '$path' against '$payload': $err")
        None
      case Success(res) => Option(res)
    }
  }

  //def getAtJsonWithType(payload: JsValue, path: String, typ: String): Option[JsValue] = getAtWithType(Json.stringify(payload), path, typ)
  //def getAtWithType(payload: String, path: String, typ: String): Option[JsValue] = {
  //  def handle[T](v: Try[T], f: T => JsValue): Option[JsValue] = {
  //    v match {
  //      case Failure(err) =>
  //        logger.error(s"error while matching query '$path' against '$payload': $err")
  //        None
  //      case Success(res) => Option(res).map(v => f(v))
  //    }
  //  }
  //  typ match {
  //    case "string" => handle(Try(JsonPath.parse(payload).read[String](path)), JsString.apply)
  //    case "boolean" => handle(Try(JsonPath.parse(payload).read[Boolean](path)), JsBoolean.apply)
  //    case "int" => handle[Int](Try(JsonPath.parse(payload).read[Int](path)), v => JsNumber(BigDecimal(v)))
  //    case "long" => handle[Long](Try(JsonPath.parse(payload).read[Long](path)), v => JsNumber(BigDecimal(v)))
  //    case "double" => handle[Double](Try(JsonPath.parse(payload).read[Double](path)), v => JsNumber(BigDecimal(v)))
  //    case "object" => handle[JSONObject](Try(JsonPath.parse(payload).read[JSONObject](path)), v => Json.parse(v.toJSONString()))
  //    case "array" => handle[JSONArray](Try(JsonPath.parse(payload).read[JSONArray](path)), v => Json.parse(v.toJSONString()))
  //    case _ => None
  //  }
  //}

  def getAtPolyJsonStr(payload: JsValue, path: String): String = {
    (getAtPoly(Json.stringify(payload), path) match {
      case Some(JsString(value)) => value.some
      case Some(JsBoolean(value)) => value.toString.some
      case Some(JsNumber(value)) => value.toString.some
      case Some(o@JsObject(_)) => o.stringify.some
      case Some(o@JsArray(_)) => o.stringify.some
      case _ => "null".some
    }) getOrElse("null")
  }

  private val config: Configuration = {
    val default = Configuration.defaultConfiguration()
    Configuration.builder()
      .evaluationListener(default.getEvaluationListeners)
      .options(default.getOptions)
      .jsonProvider(new JacksonJsonNodeJsonProvider())
      .mappingProvider(new JacksonMappingProvider())
      .build()
  }

  def getAtPolyJson(payload: JsValue, path: String): Option[JsValue] = getAtPoly(Json.stringify(payload), path)

  def getAtPoly(payload: String, path: String): Option[JsValue] = {
    Try {
      val docCtx = JsonPath.parse(payload, config)
      Writes.jsonNodeWrites.writes(docCtx.read[JsonNode](path))
    } match {
      case Failure(e) => None
      case Success(s) => s.some
    }
  }
}
