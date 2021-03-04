package otoroshi.models

import java.util.Base64

import otoroshi.env.Env
import play.api.Logger
import play.api.libs.json.{JsResult, JsValue, Json}
import otoroshi.storage.BasicStore

import scala.concurrent.ExecutionContext

case class ErrorTemplate(serviceId: String,
                         template40x: String,
                         template50x: String,
                         templateBuild: String,
                         templateMaintenance: String,
                         messages: Map[String, String] = Map.empty[String, String]) {
  def renderHtml(status: Int, causeId: String, otoroshiMessage: String, errorId: String): String = {
    val template = (status, causeId) match {
      case (_, "errors.service.in.maintenance")     => templateMaintenance
      case (_, "errors.service.under.construction") => templateBuild
      case (s, _) if s > 399 && s < 500             => template40x
      case (s, _) if s > 499 && s < 600             => template50x
      case _                                        => template50x
    }
    val messageKey = s"message-$status"
    val message    = messages.getOrElse(messageKey, otoroshiMessage)
    val cause      = messages.getOrElse(causeId, causeId)
    template
      .replace("${message}", message)
      .replace("${cause}", cause)
      .replace("${otoroshiMessage}", otoroshiMessage)
      .replace("${errorId}", errorId)
      .replace("${status}", status.toString)
  }
  def renderJson(status: Int, causeId: String, otoroshiMessage: String, errorId: String): JsValue = {
    val messageKey = s"message-$status"
    val message    = messages.getOrElse(messageKey, otoroshiMessage)
    val cause      = messages.getOrElse(causeId, causeId)
    Json.obj(
      "otoroshi-error-id"  -> errorId,
      "otoroshi-error"     -> message,
      "otoroshi-cause"     -> cause,
      "otoroshi-raw-error" -> otoroshiMessage
    )
  }
  def toJson: JsValue                                 = ErrorTemplate.format.writes(this)
  def save()(implicit ec: ExecutionContext, env: Env) = env.datastores.errorTemplateDataStore.set(this)
}

object ErrorTemplate {
  lazy val logger                           = Logger("otoroshi-error-template")
  val format                                = Json.format[ErrorTemplate]
  val base64decoder                         = Base64.getUrlDecoder
  def toJson(value: ErrorTemplate): JsValue = format.writes(value)
  def fromJsons(value: JsValue): ErrorTemplate =
    try {
      format.reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }
  def fromJsonSafe(value: JsValue): JsResult[ErrorTemplate] = format.reads(value)
}

trait ErrorTemplateDataStore extends BasicStore[ErrorTemplate] {}
