package otoroshi.next.utils

import play.api.libs.json.{JsArray, JsNull, JsValue, Json}

object JsonErrors {
  def errToJson(error: Throwable): JsValue = {
    Json.obj(
      "message" -> error.getMessage,
      "cause" -> Option(error.getCause).map(errToJson).getOrElse(JsNull).as[JsValue],
      "stack" -> JsArray(error.getStackTrace.toSeq.map(el => Json.obj(
        "class_loader_name" -> el.getClassLoaderName,
        "module_name" -> el.getModuleName,
        "module_version" -> el.getModuleVersion,
        "declaring_class" -> el.getClassName,
        "method_name" -> el.getMethodName,
        "file_name" -> el.getFileName,
        "line_number" -> el.getLineNumber
      )))
    )
  }
}
