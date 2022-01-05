package otoroshi.next.models

import otoroshi.models.{EntityLocation, EntityLocationSupport}
import play.api.libs.json.JsValue

case class Backend(id: String, name: String, description: String, tags: Seq[String], metadata: Map[String, String], location: EntityLocation) extends EntityLocationSupport {

  override def internalId: String = id
  override def json: JsValue = ???
  override def theName: String = name
  override def theDescription: String = description
  override def theTags: Seq[String] = tags
  override def theMetadata: Map[String, String] = metadata
}
