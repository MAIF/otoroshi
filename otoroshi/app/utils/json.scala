package otoroshi.utils.json

import org.joda.time.DateTime
import play.api.libs.json._

object JsonImplicits {
  implicit val jodaDateTimeWrites: Writes[DateTime] = play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites
  implicit val jodaDateTimeReads: Reads[DateTime]   = play.api.libs.json.JodaReads.DefaultJodaDateTimeReads
}

object JsonPatchHelpers {

  // import gnieh.diffson.playJson._

  import diffson.playJson.DiffsonProtocol._
  import play.api.libs.json._

  def patchJson(patchOps: JsValue, document: JsValue): JsValue = {
    val patch = diffson.playJson.DiffsonProtocol.JsonPatchFormat.reads(patchOps).get
    patch.apply(document).get
  }
}

trait Jsonable {
  def json: JsValue
}

object JsonOperationsHelper {

  def getValueAtPath(input: String, obj: JsValue) = {
      var acc = obj
      var out = JsString("").as[JsValue]

      input
        .split("\\.")
        .foreach(path => {
          if (path.forall(Character.isDigit)) {
            acc.asOpt[JsArray] match {
              case Some(value) =>
                acc = value.value(path.toInt)
                out = acc
              case None        => acc = Json.obj()
            }
          } else {
            acc \ path match {
              case JsDefined(a @ JsObject(_)) =>
                acc = a
                out = a
              case JsDefined(a @ JsArray(_))  =>
                acc = a
                out = a
              case JsDefined(value)           =>
                out = value
              case _: JsUndefined             =>
                acc = Json.obj()
                out = Json.obj()
            }
          }
        })

      (input, out)
    }
  
  def insertAtPath(acc: JsObject, path: Seq[String], value: JsValue): JsObject = {
    if (path.length == 1) {
      acc.deepMerge(Json.obj(path.head -> value))
    } else {
      acc.deepMerge(
        Json.obj(
          path.head -> insertAtPath((acc \ path.head).asOpt[JsObject].getOrElse(Json.obj()), path.tail, value)
        )
      )
    }
  }
  def filterJson(obj: JsValue, fields: Seq[String]) = {
    val out = fields.map(input => getValueAtPath(input, obj))

    out.foldLeft(Json.obj()) { case (acc, curr) =>
      insertAtPath(acc, curr._1.split("\\."), curr._2)
    }
  }

}