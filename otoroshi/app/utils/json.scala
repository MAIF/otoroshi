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
    patch.apply(document) match {
      case JsSuccess(value, _) => value
      case JsError(err)        => JsNull
    }
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

  def genericInsertAtPath(acc: JsValue, path: Seq[String], value: JsValue): JsValue = {
    acc match {
      case acc @ JsObject(_)         => {
        if (path.length == 1) {
          acc.deepMerge(Json.obj(path.head -> value))
        } else {
          acc.deepMerge(
            Json.obj(
              path.head -> genericInsertAtPath((acc \ path.head).asOpt[JsValue].getOrElse(Json.obj()), path.tail, value)
            )
          )
        }
      }
      case acc @ JsArray(underlying) => {
        if (path.length == 1) {
          val idx = path.head.toInt
          if (underlying.isDefinedAt(idx)) {
            JsArray(underlying.updated(idx, value))
          } else {
            JsArray(underlying :+ value)
          }
        } else {
          val idx = path.head.toInt
          if (underlying.isDefinedAt(idx)) {
            val oldValue = underlying.apply(idx)
            JsArray(underlying.updated(idx, genericInsertAtPath(oldValue, path.tail, value)))
          } else {
            JsArray(
              underlying :+ genericInsertAtPath(
                (acc \ path.head).asOpt[JsValue].getOrElse(Json.obj()),
                path.tail,
                value
              )
            )
          }
        }
      }
      case _                         => acc
    }
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
