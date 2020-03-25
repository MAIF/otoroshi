package utils

import play.api.libs.json._
import org.joda.time.DateTime

object JsonImplicits {
  implicit val jodaDateTimeWrites: Writes[DateTime] = play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites
  implicit val jodaDateTimeReads: Reads[DateTime]   = play.api.libs.json.JodaReads.DefaultJodaDateTimeReads
}


object JsonPatchHelpers {

  // import gnieh.diffson.playJson._

  import play.api.libs.json._
  import diffson.playJson._
  import diffson.playJson.DiffsonProtocol._
  import diffson.jsonpatch._

  def patchJson(patchOps: JsValue, document: JsValue): JsValue = {
    val patch = diffson.playJson.DiffsonProtocol.JsonPatchFormat.reads(patchOps).get
    patch.apply(document).get
  }
}