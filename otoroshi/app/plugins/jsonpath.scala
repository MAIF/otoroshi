package otoroshi.plugins

import com.jayway.jsonpath.JsonPath
import net.minidev.json.JSONArray
import play.api.Logger
import play.api.libs.json.{JsValue, Json}

import scala.util.{Failure, Success, Try}

object JsonPathUtils {

  private val logger = Logger("otoroshi-plugins-jsonpath-helper")

  def matchWith(payload: JsValue, what: String): String => Boolean = { query: String =>
    {
      Try(JsonPath.parse(Json.stringify(payload)).read[JSONArray](query)) match {
        case Failure(err) =>
          logger.error(s"error while matching query '$query' against $what: $err")
          false
        case Success(res) =>
          res.size() > 0
      }
    }
  }
}
