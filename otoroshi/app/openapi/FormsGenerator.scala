package otoroshi.openapi

import play.api.libs.json._

import scala.collection.concurrent.TrieMap

class FormsGenerator(spec: TrieMap[String, JsValue]){
  val openapiTypesToFormTypes = Map(
    "integer" -> "number",
    "boolean" -> "bool"
  )

  def run(): Map[String, Form] = {
    convertSchemasToForms(JsObject(spec).fields)
  }

  def openapiPropertiesToForms(properties: JsObject): JsValue = {
    properties.fields.foldLeft(Json.obj()) {
      case (props, prop) =>
        val `type`: String    = (prop._2 \ "type").asOpt[String].getOrElse("unknown type")
        val label: String     = prop._1
        var isArray: Boolean  = false
        var informations      = Json.obj(
          "label" -> label,
          "type" -> JsString(openapiTypesToFormTypes.getOrElse(`type`, `type`))
        )

        if (`type` == "object" && (prop._2 \ "properties").asOpt[JsObject].nonEmpty)
          informations = informations ++ Json.obj("format" -> "form", "collapsable" -> true, "collasped" -> true)
        else if(`type` == "array") {
          isArray = true
          val rawType = (prop._2 \ "items" \ "type").asOpt[String].getOrElse("unknown type")
          val outType = JsString(openapiTypesToFormTypes.getOrElse(rawType, rawType))
          informations = informations ++ Json.obj(
            "array" -> true,
            "type" -> outType,
            "format" -> (
              if(outType == JsString("object")) JsString("form")
              else if(outType == JsString("string")) JsString("singleLineCode")
              else JsNull)
          )
        }

        props ++ Json.obj(
          label ->  (if (isArray)
            (prop._2 \ "items" \ "properties").asOpt[JsObject] match {
              case Some(subProperties)  =>
                val schema = openapiPropertiesToForms(subProperties)
                informations ++ Json.obj("schema" -> schema, "flow" -> schema.as[JsObject].keys)
              case None                 => informations
            }
          else if (`type` == "object")
            (prop._2 \ "properties").asOpt[JsObject] match {
              case Some(subProperties)  =>
                val schema = openapiPropertiesToForms(subProperties)
                informations ++ Json.obj("schema" -> schema, "flow" -> schema.as[JsObject].keys)
              case None                 => informations
            }
          else
            if ((informations \ "type").as[String] == "string") {
              if (label == "body")
                informations ++ Json.obj("format" -> "code")
              else
                informations ++ Json.obj("format" -> "singleLineCode")
            }
            else if((informations \ "type").as[String] == "number") {
              informations ++ Json.obj(
                // "value" -> "undefined",
                "constraints" -> Json.arr(Json.obj(
                  "type" -> "nullable"
                ))
              )
            }
            else
              informations
            )
      )
    }
  }

  def convertSchemasToForms(data: Seq[(String, JsValue)]): Map[String, Form] = {
    data.map(schema => {
      val outSchema = (schema._2 \ "properties").asOpt[JsObject]
          .map(openapiPropertiesToForms)
          .getOrElse(Json.obj())
          .as[JsValue]

      schema._1 -> Form(schema = outSchema.as[JsObject], flow = outSchema.as[JsObject].keys.toSet[String])
    }).toMap
  }

}
