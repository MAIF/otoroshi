package otoroshi.openapi

import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import scala.collection.concurrent.TrieMap

class OpenapiToJson(spec: JsValue) {

  val logger = Logger("otoroshi-openapi-to-json")
  val openAPIV3SchemaPath = "openAPIV3Schema/properties/spec/properties"
  val nullType            = "#/components/schemas/Null"
  val otoroshiSchemaType  = "#/components/schemas/otoroshi."

  def run() = {
    val data               = extractSchemasFromOpenapi()
    process(data)
    data
  }

  def extractSchemasFromOpenapi() = {
    val schemas = (spec \ "components" \ "schemas").as[JsObject]
    val data    = new TrieMap[String, JsValue]()
    schemas.fields.foreach(curr => data.put(curr._1, curr._2))
    data
  }

  def process(data: TrieMap[String, JsValue]) = {
    var changed = true
    do {
      changed = replaceOneOf(data)
    } while (changed)
  }

  def reads(path: String): JsPath = {
    if (path.isEmpty)
      JsPath()
    else
      (if (path.startsWith("/")) path.substring(1) else path).split("/").foldLeft(JsPath()) { (acc, num) =>
        acc \ num
      }
  }

  def containsOnlyRef(values: IndexedSeq[JsValue]): Boolean =
    values.forall(p => (p \ "$ref").as[String] != nullType)

  def containsNullAndRef(values: IndexedSeq[JsValue]): Boolean =
    values.exists(p => (p \ "$ref").as[String] == nullType) &&
      values.exists(p => (p \ "$ref").as[String] != nullType)

  def pruneField(data: TrieMap[String, JsValue], key: String, path: String) =
    data.put(key, data(key).transform(reads(path).json.prune).get)

  def updateField(data: TrieMap[String, JsValue], key: String, path: String, additionalObj: JsValue) =
    data.put(
      key,
      data(key).transform(reads(path).json.update(__.read[JsObject].map(o => o ++ additionalObj.as[JsObject]))).get
    )

  def replaceSubOneOf(data: TrieMap[String, JsValue], key: String, path: String): Boolean = {
    val currentObj = data(key).as[JsObject].atPointer(path).asOpt[JsObject] match {
      case Some(p) => p
      case _       => Json.obj()
    }

    ((currentObj \ "type").asOpt[String], (currentObj \ "items").asOpt[JsObject]) match {
      case (Some(t), Some(items)) if t == "array" && (items \ "$ref").asOpt[String].isDefined =>
        pruneField(data, key, path + "/items/$ref")

        replaceRef(data, key, path + "/items", (items \ "$ref").as[String])

        true
      case _                                                                                  =>
        currentObj.fields
          .map {
            case ("oneOf", oneOfArray)                             =>
              val fields = oneOfArray.as[Seq[JsValue]]

              pruneField(data, key, path + "/oneOf")

              fields.find(field => (field \ "type").asOpt[String].isDefined) match {
                case Some(field) =>
                  updateField(data, key, path, field)
                case None        =>
                  // corresponding when the object is composed of a list of references
                  updateField(data, key, path, Json.obj("type" -> "object"))

                  // get a flat object for each #ref and check if object is not only composed of an oneOf of refs
                  val refs = fields
                    .flatMap { rawField =>
                      val field = (rawField \ "$ref").as[String]
                      if (field != nullType) {
                        val obj = getRef(data, field)
                        (obj \ "oneOf").asOpt[Seq[JsValue]] match {
                          case Some(arr) =>
                            arr.map { f =>
                              val r = (f \ "$ref").as[String]
                              if (r != nullType)
                                getRef(data, r)
                              else
                                Json.obj()
                            }
                          case _         => Seq(obj)
                        }
                      } else
                        Seq(Json.obj())
                    }
                    .filter(f => f.fields.nonEmpty)

                  if (refs.length == 1)
                    (refs.head \ "oneOf").asOpt[JsArray] match {
                      case Some(ob) if ob.value.map(_.as[JsObject]).forall(p => (p \ "$ref").isDefined) =>
                        updateField(data, key, path, refs.head)
                      case _                                                                            =>
                        updateField(data, key, path, Json.obj("properties" -> refs.head))
                    }
                  else
                    updateField(
                      data,
                      key,
                      path,
                      Json.obj(
                        "oneOfConstraints" -> refs.map(ref => Json.obj("required" -> ref.keys)),
                        "properties"       -> refs.foldLeft(Json.obj())((acc, curr) => acc ++ curr)
                      )
                    )
              }
              true
            case ("$ref", fields) if fields.isInstanceOf[JsString] =>
              pruneField(data, key, path + "/$ref")
              replaceRef(data, key, path, fields.as[String])
              true

            case (fieldName, fields) if fields.isInstanceOf[JsObject] => replaceSubOneOf(data, key, s"$path/$fieldName")
            case _                                                    => false
          }
          .foldLeft(false)(_ || _)

    }
  }

  def getRef(data: TrieMap[String, JsValue], ref: String): JsObject = {
    if (ref.startsWith(otoroshiSchemaType)) {
      val reference = ref.replace("#/components/schemas/", "")

      try {
        (data(reference) \ "properties").asOpt[JsObject] match {
          case Some(prop) => prop
          case _ => data(reference).as[JsObject]
        }
      } catch {
        case _: Throwable =>
          logger.debug(s"$reference not found")
          Json.obj()
      }
    } else
      Json.obj()
  }

  def replaceRef(data: TrieMap[String, JsValue], key: String, path: String, ref: String) = {
    if (ref.startsWith(otoroshiSchemaType)) {
      val out = getRef(data, ref)

      (out \ "type").asOpt[String] match {
        case Some(t) if t == "string" && (out \ "enum").asOpt[JsArray].isEmpty =>
          updateField(data, key, path, Json.obj("type" -> "string"))
        case _                                                                 =>
          updateField(
            data,
            key,
            path,
            (out \ "oneOf").asOpt[JsArray] match {
              case Some(arr) if arr.value.length > 2 || containsNullAndRef(arr.value) => out
              case Some(arr) if containsOnlyRef(arr.value)                            =>
                Json.obj("type" -> (getRef(data, (arr.value.head \ "$ref").as[String]) \ "type").as[String])
              case None if (out \ "enum").isDefined                                   =>
                out
              case _                                                                  => Json.obj("properties" -> out, "type" -> "object")
            }
          )
      }
    }
  }

  def replaceOneOf(data: TrieMap[String, JsValue]): Boolean =
    JsObject(data).fields
      .map(field => replaceSubOneOf(data, field._1, ""))
      .foldLeft(false)(_ || _)
}
