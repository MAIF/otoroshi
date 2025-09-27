package otoroshi.utils.config

import play.api.libs.json.{JsObject, JsValue, Json}

object ConfigUtils {

  def mergeOpt(value1: JsValue, value2: Option[JsValue]): JsValue = {
    merge(value1, value2.getOrElse(Json.obj()))
  }

  def merge(value1: JsValue, value2: JsValue): JsValue = {
    val a = value1.asOpt[JsObject].getOrElse(Json.obj())
    val b = value2.asOpt[JsObject].getOrElse(Json.obj())
    a.deepMerge(b)
  }
}
