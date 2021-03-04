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
