package otoroshi.plugins.apikeys

import akka.http.scaladsl.util.FastFuture
import env.Env
import otoroshi.script.{AccessContext, AccessValidator}
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

class HasAllowedApiKeyValidator extends AccessValidator {

  override def name: String = "Allowed apikeys only"

  override def defaultConfig: Option[JsObject] = Some(Json.obj(
    "HasAllowedApiKeyValidator" -> Json.obj(
      "clientIds" -> Json.arr(),
      "tags" -> Json.arr(),
      "metadata" -> Json.obj(),
    )
  ))

  override def description: Option[String] = Some(
    """Validation based on apikeys
      |
      |```json
      |{
      |  "HasAllowedApiKeyValidator": {
      |    "clientIds": [],  // list of allowed client ids,
      |    "tags": [],       // list of allowed tafs
      |    "metadata": {}    // allowed metadata
      |  }
      |}
      |```
    """.stripMargin)

  def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    context.apikey match {
      case Some(apiKey) => {
        val config = (context.config \ "HasAllowedApiKeyValidator")
          .asOpt[JsValue]
          .orElse((context.config \ "GlobalHasAllowedApiKeyValidator").asOpt[JsValue])
          .getOrElse(context.config)
        val allowedClientIds =
          (config \ "clientIds").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val allowedTags      = (config \ "tags").asOpt[JsArray].map(_.value.map(_.as[String])).getOrElse(Seq.empty[String])
        val allowedMetadatas = (config \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty[String, String])
        if (allowedClientIds.contains(apiKey.clientId) || allowedTags.exists(tag => apiKey.tags.contains(tag)) || allowedMetadatas
          .exists(meta => apiKey.metadata.get(meta._1).contains(meta._2))) {
          FastFuture.successful(true)
        } else {
          FastFuture.successful(false)
        }
      }
      case _ =>
        FastFuture.successful(false)
    }
  }
}

class ApiKeyAllowedOnThisServiceValidator extends AccessValidator {

  override def name: String = "Allowed apikeys for this service only (service packs)"

  override def description: Option[String] = Some(
    """This plugin only let pass apikeys containing the id of the service on their tags. It is quite useful to create apikeys that
      |can access a `pack` of services. Apikeys should have tags named like
      |
      |```
      |"allowed-on-${service.id}"
      |```
      |
    """.stripMargin)

  def canAccess(ctx: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    ctx.apikey match {
      case Some(apiKey) => {
        val serviceIds = apiKey.tags.map(tag => tag.replace("allowed-on-", ""))
        FastFuture.successful(serviceIds.exists(id => id == ctx.descriptor.id))
      }
      case _ => FastFuture.successful(false)
    }
  }
}