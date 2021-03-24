package otoroshi.models

import play.api.libs.json.JsValue

trait Entity {
  def internalId: String
  def json: JsValue
  def theId: String = internalId
  def theJson: JsValue = json
  def theName: String
  def theDescription: String
  def theTags: Seq[String]
  def theMetadata: Map[String, String]
}
