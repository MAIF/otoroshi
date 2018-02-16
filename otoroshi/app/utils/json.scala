package utils

import play.api.libs.json._
import org.joda.time.DateTime

object JsonImplicits {
  implicit val jodaDateTimeWrites: Writes[DateTime] = play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites
  implicit val jodaDateTimeReads: Reads[DateTime] = play.api.libs.json.JodaReads.DefaultJodaDateTimeReads
}