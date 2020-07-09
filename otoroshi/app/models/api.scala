package otoroshi.models

import play.api.libs.json.JsValue

trait Entity {
  def internalId: String
  def json: JsValue
}
