package otoroshi.plugins.apikeys

import akka.http.scaladsl.util.FastFuture
import env.Env
import models.{ApiKey, RemainingQuotas}
import org.apache.commons.codec.binary.Base64
import org.joda.time.DateTime
import otoroshi.script.{AccessContext, AccessValidator, PreRouting, PreRoutingContext}
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import security.IdGenerator

import scala.concurrent.{ExecutionContext, Future}

class HasAllowedApiKeyValidator extends AccessValidator {

  override def name: String = "Allowed apikeys only"

  override def configRoot: Option[String] = Some("HasAllowedApiKeyValidator")

  override def configFlow: Seq[String] = Seq("clientIds", "tags", "metadata")

  override def configSchema: Option[JsObject] =
    Some(
      Json.parse("""{
      |  "clientIds": {
      |    "type": "array",
      |    "props": { "label": "Allowed apikeys", "valuesFrom": "/bo/api/proxy/api/apikeys", "transformerMapping": { "label":"clientName", "value":"clientId" } }
      |  },
      |  "tags": {
      |    "type": "array",
      |    "props": { "label": "Allowed tags" }
      |  },
      |  "metadata": {
      |    "type": "object",
      |    "props": { "label": "Allowed metadata" }
      |  }
      |}""".stripMargin).as[JsObject]
    )

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        configRoot.get -> Json.obj(
          "clientIds" -> Json.arr(),
          "tags"      -> Json.arr(),
          "metadata"  -> Json.obj(),
        )
      )
    )

  override def description: Option[String] = Some("""Validation based on apikeys
      |
      |```json
      |{
      |  "HasAllowedApiKeyValidator": {
      |    "clientIds": [],  // list of allowed client ids,
      |    "tags": [],       // list of allowed tags
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
          .orElse((context.config \ "HasAllowedApiKeyValidator").asOpt[JsValue])
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

  override def description: Option[String] =
    Some(
      """This plugin only let pass apikeys containing the id of the service on their tags. It is quite useful to create apikeys that
      |can access a `pack` of services. Apikeys should have tags named like
      |
      |```
      |"allowed-on-${service.id}"
      |```
      |
    """.stripMargin
    )

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

class CertificateAsApikey extends PreRouting {

  override def name: String = "Client certificate as apikey"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "readOnly" ->false,
        "allowClientIdOnly" -> false,
        "throttlingQuota" -> 100,
        "dailyQuota" -> RemainingQuotas.MaxValue,
        "monthlyQuota" -> RemainingQuotas.MaxValue,
        "constrainedServicesOnly" -> false,
        "tags" -> Json.arr(),
        "metadata" -> Json.obj(),
      )
    )

  override def description: Option[String] =
    Some(
      s"""This plugin uses client certificate as an apikey. The apikey will be stored for classic apikey usage
        |
        |```json
        |${Json.prettyPrint(Json.obj("CertificateAsApikey" -> defaultConfig.get))}
        |```
      """.stripMargin
    )

  override def preRoute(context: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    context.request.clientCertificateChain.flatMap(_.headOption) match {
      case None => FastFuture.successful(())
      case Some(cert) => {
        val conf = context.configFor("CertificateAsApikey")
        val serialNumber = cert.getSerialNumber.toString
        val subjectDN = cert.getSubjectDN.getName
        val clientId = Base64.encodeBase64String((subjectDN + "-" + serialNumber).getBytes)
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case Some(apikey) => FastFuture.successful(apikey)
          case None => {
            val apikey = ApiKey(
              clientId = clientId,
              clientSecret = IdGenerator.token(128),
              clientName = s"$subjectDN ($serialNumber)",
              authorizedGroup = context.descriptor.groupId,
              validUntil = Some(new DateTime(cert.getNotAfter)),
              readOnly = (conf \ "readOnly").asOpt[Boolean].getOrElse(false),
              allowClientIdOnly = (conf \ "allowClientIdOnly").asOpt[Boolean].getOrElse(false),
              throttlingQuota = (conf \ "throttlingQuota").asOpt[Long].getOrElse(100),
              dailyQuota = (conf \ "dailyQuota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
              monthlyQuota = (conf \ "monthlyQuota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
              constrainedServicesOnly = (conf \ "constrainedServicesOnly").asOpt[Boolean].getOrElse(false),
              tags = (conf \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty),
              metadata = (conf \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty)
            )
            apikey.save().map(_ => apikey)
          }
        }.map { apikey =>
          context.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apikey)
          ()
        }
      }
    }
  }
}

