package next.models

import otoroshi.models.{EntityLocation, EntityLocationSupport}
import play.api.libs.json.{Format, JsResult, JsValue}

case class Api(
   location: EntityLocation,
   id: String,
   name: String,
   description: String,
   tags: Seq[String],
   metadata: Map[String, String],
   version: String, // or Seq[ApiVersion] ?
 ) extends EntityLocationSupport {
  override def internalId: String = id
  override def json: JsValue = Api.format.writes(this)
  override def theName: String = name
  override def theDescription: String = description
  override def theTags: Seq[String] = tags
  override def theMetadata: Map[String, String] = metadata
}

object Api {
  val format = new Format[Api] {
    override def reads(json: JsValue): JsResult[Api] = ???
    override def writes(o: Api): JsValue = ???
  }
}
