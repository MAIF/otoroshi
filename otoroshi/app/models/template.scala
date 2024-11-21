package otoroshi.models

import otoroshi.env.Env
import otoroshi.storage.BasicStore
import otoroshi.utils.RegexPool
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import java.util.Base64
import scala.concurrent.ExecutionContext
import scala.util.Try

case class ErrorTemplate(
    location: EntityLocation,
    serviceId: String,
    name: String,
    description: String,
    metadata: Map[String, String],
    tags: Seq[String],
    template40x: String,
    template50x: String,
    templateBuild: String,
    templateMaintenance: String,
    genericTemplates: Map[String, String],
    messages: Map[String, String] = Map.empty[String, String]
) extends EntityLocationSupport {
  def renderHtml(status: Int, causeId: String, otoroshiMessage: String, errorId: String): String = {
    val template   = genericTemplates
      .get(causeId)
      .orElse(
        genericTemplates.keys.find(k => RegexPool.apply(k).matches(causeId)).flatMap(k => genericTemplates.get(k))
      ) match {
      case Some(tmpl) => tmpl
      case None       =>
        (status, causeId) match {
          case (_, "errors.service.in.maintenance")     => templateMaintenance
          case (_, "errors.service.under.construction") => templateBuild
          case (s, _) if s > 399 && s < 500             => template40x
          case (s, _) if s > 499 && s < 600             => template50x
          case _                                        => template50x
        }
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

  override def json: JsValue                    = ErrorTemplate.format.writes(this)
  override def internalId: String               = serviceId
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
}

object ErrorTemplate {
  lazy val logger                                           = Logger("otoroshi-error-template")
  val format                                                = new Format[ErrorTemplate] {
    override def writes(o: ErrorTemplate): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "serviceId"           -> o.serviceId,
      "name"                -> o.name,
      "description"         -> o.description,
      "metadata"            -> o.metadata,
      "tags"                -> o.tags,
      "template40x"         -> o.template40x,
      "template50x"         -> o.template50x,
      "templateBuild"       -> o.templateBuild,
      "templateMaintenance" -> o.templateMaintenance,
      "genericTemplates"    -> o.genericTemplates,
      "messages"            -> o.messages
    )
    override def reads(json: JsValue): JsResult[ErrorTemplate] = Try {
      val serviceId = json.select("serviceId").asString
      JsSuccess(
        ErrorTemplate(
          location = otoroshi.models.EntityLocation.readFromKey(json),
          serviceId = serviceId,
          name = json.select("name").asOpt[String].getOrElse(serviceId),
          description = json.select("description").asOpt[String].getOrElse(serviceId),
          metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
          template40x = json.select("template40x").asString,
          template50x = json.select("template50x").asString,
          templateBuild = json.select("templateBuild").asString,
          templateMaintenance = json.select("templateMaintenance").asString,
          genericTemplates = json.select("genericTemplates").asOpt[Map[String, String]].getOrElse(Map.empty),
          messages = json.select("messages").asOpt[Map[String, String]].getOrElse(Map.empty)
        )
      )
    } recover { case e =>
      JsError(e.getMessage)
    } get
  }
  val fmt                                                   = format
  val base64decoder                                         = Base64.getUrlDecoder
  def toJson(value: ErrorTemplate): JsValue                 = format.writes(value)
  def fromJsons(value: JsValue): ErrorTemplate              =
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
