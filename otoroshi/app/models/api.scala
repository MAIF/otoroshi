package otoroshi.models

import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

trait Entity { self =>
  def internalId: String
  def json: JsValue
  def theId: String    = internalId
  def theJson: JsValue = json
  def theName: String
  def theDescription: String
  def theTags: Seq[String]
  def theMetadata: Map[String, String]
  def fillSecrets[A](reads: Format[A], env: Env): A = {
    val jsonstr = json.stringify
    if (jsonstr.contains("${vault://")) {
      reads.reads(env.vaults.fillSecrets(jsonstr).parseJson).get
    } else {
      this.asInstanceOf[A]
    }
  }
}
