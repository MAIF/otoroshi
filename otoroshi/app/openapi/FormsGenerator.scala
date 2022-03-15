package otoroshi.openapi

import play.api.libs.json._

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class FormsGenerator(spec: JsValue) {

  val openAPIV3SchemaPath = "openAPIV3Schema/properties/spec/properties"
  val nullType            = "#/components/schemas/Null"
  val otoroshiSchemaType  = "#/components/schemas/otoroshi."
  val whiteList           = Seq("otoroshi.next.plugins")
  val openapiTypesToFormTypes = Map(
    "integer" -> "number",
    "boolean" -> "bool"
  )

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
          informations = informations ++ Json.obj("format" -> "form")
        else if(`type` == "array") {
          isArray = true
          val rawType = (prop._2 \ "items" \ "type").asOpt[String].getOrElse("unknown type")
          informations = informations ++ Json.obj(
            "array" -> true,
            "type" -> JsString(openapiTypesToFormTypes.getOrElse(rawType, rawType))
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
            informations
          )
      )
    }
  }

  def convertSchemasToForms(data: Seq[(String, JsValue)]) = {
    data.foldLeft(Json.arr()) {
      case (forms, schema) =>
        val outSchema = (schema._2 \ "properties").asOpt[JsObject]
          .map(openapiPropertiesToForms)
          .getOrElse(Json.obj())
          .as[JsValue]

        forms :+ Json.obj(
        schema._1 -> Json.obj(
          "schema" -> outSchema,
          "flow" -> outSchema.as[JsObject].keys,
          "openapiSchema" -> schema._2
        )
      )
    }
  }

  def run() = {
    val data   = JsObject(
      new OpenapiToJson(spec).run()
        .filter(w => whiteList.exists(p => w._1.startsWith(p)))
    )
    val models = convertSchemasToForms(data.fields)

    val schemasAsString = Json.prettyPrint(models)

    val file            = new File(s"../test-resources/schema.json")
    Files.write(file.toPath, schemasAsString.getBytes(StandardCharsets.UTF_8))
  }

}
