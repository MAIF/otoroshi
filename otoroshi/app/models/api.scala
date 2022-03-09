package otoroshi.models

import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

trait Entity { self =>
  def internalId: String
  def json: JsValue
  def theId: String    = internalId
  def theJson: JsValue = json
  def theName: String
  def theDescription: String
  def theTags: Seq[String]
  def theMetadata: Map[String, String]
  def fillSecrets[A](reads: Format[A])(implicit env: Env, ec: ExecutionContext): Future[A] = {
    val jsonstr = json.stringify
    if (env.vaults.enabled && jsonstr.contains("${vault://")) {
      env.vaults.fillSecretsAsync(jsonstr).map { filled =>
        reads.reads(filled.parseJson).get
      }
    } else {
      this.asInstanceOf[A].vfuture
    }
  }
}
