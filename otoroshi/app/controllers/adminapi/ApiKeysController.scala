package otoroshi.controllers.adminapi

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.models.ApiKey
import otoroshi.utils.controllers.{
  AdminApiHelper,
  ApiError,
  BulkControllerHelper,
  CrudControllerHelper,
  EntityAndContext,
  JsonApiError,
  NoEntityAndContext,
  OptionalEntityAndContext,
  SeqEntityAndContext
}
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{AbstractController, ControllerComponents, RequestHeader, Results}
import otoroshi.security.IdGenerator
import otoroshi.utils.json.JsonPatchHelpers.patchJson

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class ApiKeysFromServiceController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
    extends AbstractController(cc)
    with AdminApiHelper {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-apikeys-fs-api")

  def apiKeyQuotas(serviceId: String, clientId: String) =
    ApiAction.async { ctx =>
      env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
        case None                                 => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
        case Some(desc) if !ctx.canUserRead(desc) => ctx.fforbidden
        case Some(desc)                           =>
          env.datastores.apiKeyDataStore.findById(clientId).flatMap {
            case None                                                 => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
            case Some(apiKey) if !ctx.canUserRead(apiKey)             => ctx.fforbidden
            case Some(apiKey) if !apiKey.authorizedOnService(desc.id) =>
              NotFound(
                Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for service with id: '$serviceId'")
              ).asFuture
            case Some(apiKey) if apiKey.authorizedOnService(desc.id)  => {
              sendAudit(
                "ACCESS_SERVICE_APIKEY_QUOTAS",
                s"User accessed an apikey quotas from a service descriptor",
                Json.obj("serviceId" -> serviceId, "clientId" -> clientId),
                ctx
              )
              apiKey.remainingQuotas().map(rq => Ok(rq.toJson))
            }
          }
      }
    }

  def resetApiKeyQuotas(serviceId: String, clientId: String) =
    ApiAction.async { ctx =>
      env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
        case None                                  => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
        case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
        case Some(desc)                            =>
          env.datastores.apiKeyDataStore.findById(clientId).flatMap {
            case None                                                                      => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
            case Some(apiKey) if !ctx.canUserWrite(apiKey)                                 => ctx.fforbidden
            case Some(apiKey) if !apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups) =>
              NotFound(
                Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for service with id: '$serviceId'")
              ).asFuture
            case Some(apiKey) if apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups)  => {
              sendAudit(
                "RESET_SERVICE_APIKEY_QUOTAS",
                s"User reset an apikey quotas for a service descriptor",
                Json.obj("serviceId" -> serviceId, "clientId" -> clientId),
                ctx
              )
              env.datastores.apiKeyDataStore.resetQuotas(apiKey).map(rq => Ok(rq.toJson))
            }
          }
      }
    }

  def createApiKey(serviceId: String) =
    ApiAction.async(parse.json) { ctx =>
      val body: JsObject = ((ctx.request.body \ "clientId").asOpt[String] match {
        case None    => ctx.request.body.as[JsObject] ++ Json.obj("clientId" -> IdGenerator.namedToken("apki", 16, env))
        case Some(b) => ctx.request.body.as[JsObject]
      }) ++ ((ctx.request.body \ "clientSecret").asOpt[String] match {
        case None    => Json.obj("clientSecret" -> IdGenerator.namedToken("apks", 64, env))
        case Some(b) => Json.obj()
      })
      env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
        case None                                  => NotFound(Json.obj("error" -> s"Service with id $serviceId not found")).asFuture
        case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
        case Some(desc)                            => {
          val oldGroup   = (body \ "authorizedGroup").asOpt[String].map(g => "group_" + g).toSeq
          val entities   = (Seq("service_" + serviceId) ++ oldGroup).distinct
          val apiKeyJson = ((body \ "authorizedEntities").asOpt[Seq[String]] match {
            case None                                                => body ++ Json.obj("authorizedEntities" -> Json.arr("service_" + serviceId))
            case Some(sid) if !sid.contains(s"service_${serviceId}") =>
              body ++ Json.obj("authorizedEntities" -> (entities ++ sid).distinct)
            case Some(sid) if sid.contains(s"service_${serviceId}")  => body
            case Some(_)                                             => body
          }) - "authorizedGroup"
          ApiKey.fromJsonSafe(apiKeyJson) match {
            case JsError(e)                                        => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
            case JsSuccess(apiKey, _) if !ctx.canUserWrite(apiKey) => ctx.fforbidden
            case JsSuccess(apiKey, _)                              =>
              apiKey.save().map {
                case false => InternalServerError(Json.obj("error" -> "ApiKey not stored ..."))
                case true  => {
                  sendAuditAndAlert(
                    "CREATE_APIKEY",
                    s"User created an ApiKey",
                    "ApiKeyCreatedAlert",
                    Json.obj(
                      "desc"   -> desc.toJson,
                      "apikey" -> apiKey.toJson
                    ),
                    ctx
                  )
                  env.datastores.apiKeyDataStore.addFastLookupByService(serviceId, apiKey).map { _ =>
                    env.datastores.apiKeyDataStore.findAll()
                  }
                  Created(apiKey.toJson)
                }
              }
          }
        }
      }
    }

  def updateApiKey(serviceId: String, clientId: String) =
    ApiAction.async(parse.json) { ctx =>
      env.datastores.serviceDescriptorDataStore
        .findById(serviceId)
        .flatMap {
          case None                                  => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
          case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
          case Some(desc)                            =>
            env.datastores.apiKeyDataStore.findById(clientId).flatMap {
              case None                                                                      => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
              case Some(apiKey) if !ctx.canUserWrite(apiKey)                                 => ctx.fforbidden
              case Some(apiKey) if !apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups) =>
                NotFound(
                  Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for service with id: '$serviceId'")
                ).asFuture
              case Some(apiKey) if apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups)  => {
                ApiKey.fromJsonSafe(ctx.request.body) match {
                  case JsError(e)                                                => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
                  case JsSuccess(newApiKey, _) if newApiKey.clientId != clientId =>
                    BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
                  case JsSuccess(newApiKey, _) if newApiKey.clientId == clientId => {
                    sendAuditAndAlert(
                      "UPDATE_APIKEY",
                      s"User updated an ApiKey",
                      "ApiKeyUpdatedAlert",
                      Json.obj(
                        "desc"   -> desc.toJson,
                        "apikey" -> apiKey.toJson
                      ),
                      ctx
                    )
                    newApiKey.save().map(_ => Ok(newApiKey.toJson))
                  }
                }
              }
            }
        }
    }

  def patchApiKey(serviceId: String, clientId: String) =
    ApiAction.async(parse.json) { ctx =>
      env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
        case None                                  => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
        case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
        case Some(desc)                            =>
          env.datastores.apiKeyDataStore.findById(clientId).flatMap {
            case None                                                                      => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
            case Some(apiKey) if !ctx.canUserWrite(apiKey)                                 => ctx.fforbidden
            case Some(apiKey) if !apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups) =>
              NotFound(
                Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for service with id: '$serviceId'")
              ).asFuture
            case Some(apiKey) if apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups)  => {
              val currentApiKeyJson = apiKey.toJson
              val newApiKeyJson     = patchJson(ctx.request.body, currentApiKeyJson)
              ApiKey.fromJsonSafe(newApiKeyJson) match {
                case JsError(e)                                                => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
                case JsSuccess(newApiKey, _) if newApiKey.clientId != clientId =>
                  BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
                case JsSuccess(newApiKey, _) if newApiKey.clientId == clientId => {
                  sendAuditAndAlert(
                    "UPDATE_APIKEY",
                    s"User updated an ApiKey",
                    "ApiKeyUpdatedAlert",
                    Json.obj(
                      "desc"   -> desc.toJson,
                      "apikey" -> apiKey.toJson
                    ),
                    ctx
                  )
                  newApiKey.save().map(_ => Ok(newApiKey.toJson))
                }
              }
            }
          }
      }
    }

  def deleteApiKey(serviceId: String, clientId: String) =
    ApiAction.async { ctx =>
      env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
        case None                                  => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
        case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
        case Some(desc)                            =>
          env.datastores.apiKeyDataStore.findById(clientId).flatMap {
            case None                                                                      => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
            case Some(apiKey) if !ctx.canUserWrite(apiKey)                                 => ctx.fforbidden
            case Some(apiKey) if !apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups) =>
              NotFound(
                Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for service with id: '$serviceId'")
              ).asFuture
            case Some(apiKey) if apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups)  => {
              sendAuditAndAlert(
                "DELETE_APIKEY",
                s"User deleted an ApiKey",
                "ApiKeyDeletedAlert",
                Json.obj(
                  "desc"   -> desc.toJson,
                  "apikey" -> apiKey.toJson
                ),
                ctx
              )
              env.datastores.apiKeyDataStore.deleteFastLookupByService(serviceId, apiKey)
              apiKey.delete().map(res => Ok(Json.obj("deleted" -> true)))
            }
          }
      }
    }

  def apiKeys(serviceId: String) =
    ApiAction.async { ctx =>
      val paginationPage: Int      = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
      val paginationPageSize: Int  =
        ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
      val paginationPosition       = (paginationPage - 1) * paginationPageSize
      val clientId: Option[String] = ctx.request.queryString.get("clientId").flatMap(_.headOption)
      val name: Option[String]     = ctx.request.queryString.get("name").flatMap(_.headOption)
      val group: Option[String]    = ctx.request.queryString.get("group").flatMap(_.headOption)
      val enabled: Option[String]  = ctx.request.queryString.get("enabled").flatMap(_.headOption)
      val hasFilters               = clientId.orElse(name).orElse(group).orElse(name).orElse(enabled).isDefined
      env.datastores.apiKeyDataStore.findByService(serviceId).fold {
        case Failure(_)       => NotFound(Json.obj("error" -> s"ApiKeys for service with id: '$serviceId' does not exist"))
        case Success(apiKeys) => {
          sendAudit(
            "ACCESS_SERVICE_APIKEYS",
            s"User accessed apikeys from a service descriptor",
            Json.obj("serviceId" -> serviceId),
            ctx
          )
          if (hasFilters) {
            Ok(
              JsArray(
                apiKeys
                  .filter(ctx.canUserRead)
                  .filter {
                    case keys if group.isDefined && keys.authorizedOnGroup(group.get)       => true
                    case keys if clientId.isDefined && keys.clientId == clientId.get        => true
                    case keys if name.isDefined && keys.clientName == name.get              => true
                    case keys if enabled.isDefined && keys.enabled == enabled.get.toBoolean => true
                    case _                                                                  => false
                  }
                  .drop(paginationPosition)
                  .take(paginationPageSize)
                  .map(_.toJson)
              )
            )
          } else {
            Ok(JsArray(apiKeys.filter(ctx.canUserRead).map(_.toJson)))
          }
        }
      }
    }

  def allApiKeys() =
    ApiAction.async { ctx =>
      sendAudit(
        "ACCESS_ALL_APIKEYS",
        s"User accessed all apikeys",
        Json.obj(),
        ctx
      )
      val paginationPage: Int      = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
      val paginationPageSize: Int  =
        ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
      val paginationPosition       = (paginationPage - 1) * paginationPageSize
      val clientId: Option[String] = ctx.request.queryString.get("clientId").flatMap(_.headOption)
      val name: Option[String]     = ctx.request.queryString.get("name").flatMap(_.headOption)
      val group: Option[String]    = ctx.request.queryString.get("group").flatMap(_.headOption)
      val enabled: Option[String]  = ctx.request.queryString.get("enabled").flatMap(_.headOption)
      val hasFilters               = clientId.orElse(name).orElse(group).orElse(name).orElse(enabled).isDefined
      env.datastores.apiKeyDataStore.streamedFindAndMat(_ => true, 50, paginationPage, paginationPageSize).map { keys =>
        if (hasFilters) {
          Ok(
            JsArray(
              keys
                .filter(ctx.canUserRead)
                .filter {
                  case keys if group.isDefined && keys.authorizedOnGroup(group.get)       => true
                  case keys if clientId.isDefined && keys.clientId == clientId.get        => true
                  case keys if name.isDefined && keys.clientName == name.get              => true
                  case keys if enabled.isDefined && keys.enabled == enabled.get.toBoolean => true
                  case _                                                                  => false
                }
                .map(_.toJson)
            )
          )
        } else {
          Ok(JsArray(keys.filter(ctx.canUserRead).map(_.toJson)))
        }
      }
    }

  def apiKey(serviceId: String, clientId: String) =
    ApiAction.async { ctx =>
      env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
        case None                                 => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
        case Some(desc) if !ctx.canUserRead(desc) => ctx.fforbidden
        case Some(desc)                           =>
          env.datastores.apiKeyDataStore.findById(clientId).map {
            case None                                                                      => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found"))
            case Some(apiKey) if !ctx.canUserRead(apiKey)                                  => ctx.forbidden
            case Some(apiKey) if !apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups) =>
              NotFound(
                Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for service with id: '$serviceId'")
              )
            case Some(apiKey) if apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups)  => {
              sendAudit(
                "ACCESS_SERVICE_APIKEY",
                s"User accessed an apikey from a service descriptor",
                Json.obj("serviceId" -> serviceId, "clientId" -> clientId),
                ctx
              )
              Ok(apiKey.toJson)
            }
          }
      }
    }
}

class ApiKeysFromGroupController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
    extends AbstractController(cc)
    with AdminApiHelper {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-apikeys-fg-api")

  def apiKeyFromGroupQuotas(groupId: String, clientId: String) =
    ApiAction.async { ctx =>
      env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
        case None                                   => NotFound(Json.obj("error" -> s"Group with id: '$groupId' not found")).asFuture
        case Some(group) if !ctx.canUserRead(group) => ctx.fforbidden
        case Some(group)                            =>
          env.datastores.apiKeyDataStore.findById(clientId).flatMap {
            case None                                                => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
            case Some(apiKey) if !ctx.canUserRead(apiKey)            => ctx.fforbidden
            case Some(apiKey) if !apiKey.authorizedOnGroup(group.id) =>
              NotFound(
                Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for group with id: '$groupId'")
              ).asFuture
            case Some(apiKey) if apiKey.authorizedOnGroup(group.id)  => {
              sendAudit(
                "ACCESS_SERVICE_APIKEY_QUOTAS",
                s"User accessed an apikey quotas from a service descriptor",
                Json.obj("groupId" -> groupId, "clientId" -> clientId),
                ctx
              )
              apiKey.remainingQuotas().map(rq => Ok(rq.toJson))
            }
          }
      }
    }

  def resetApiKeyFromGroupQuotas(groupId: String, clientId: String) =
    ApiAction.async { ctx =>
      env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
        case None                                    => NotFound(Json.obj("error" -> s"Group with id: '$groupId' not found")).asFuture
        case Some(group) if !ctx.canUserWrite(group) => ctx.fforbidden
        case Some(group)                             =>
          env.datastores.apiKeyDataStore.findById(clientId).flatMap {
            case None                                                => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
            case Some(apiKey) if !ctx.canUserWrite(apiKey)           => ctx.fforbidden
            case Some(apiKey) if !apiKey.authorizedOnGroup(group.id) =>
              NotFound(
                Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for group with id: '$groupId'")
              ).asFuture
            case Some(apiKey) if apiKey.authorizedOnGroup(group.id)  => {
              sendAudit(
                "RESET_SERVICE_APIKEY_QUOTAS",
                s"User accessed an apikey quotas from a service descriptor",
                Json.obj("groupId" -> groupId, "clientId" -> clientId),
                ctx
              )
              env.datastores.apiKeyDataStore.resetQuotas(apiKey).map(rq => Ok(rq.toJson))
            }
          }
      }
    }

  def apiKeysFromGroup(groupId: String) =
    ApiAction.async { ctx =>
      val paginationPage: Int      = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
      val paginationPageSize: Int  =
        ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
      val paginationPosition       = (paginationPage - 1) * paginationPageSize
      val clientId: Option[String] = ctx.request.queryString.get("clientId").flatMap(_.headOption)
      val name: Option[String]     = ctx.request.queryString.get("name").flatMap(_.headOption)
      val group: Option[String]    = ctx.request.queryString.get("group").flatMap(_.headOption)
      val enabled: Option[String]  = ctx.request.queryString.get("enabled").flatMap(_.headOption)
      val hasFilters               = clientId.orElse(name).orElse(group).orElse(name).orElse(enabled).isDefined
      env.datastores.apiKeyDataStore.findByGroup(groupId).fold {
        case Failure(_)       => NotFound(Json.obj("error" -> s"ApiKeys for group with id: '$groupId' does not exist"))
        case Success(apiKeys) => {
          sendAudit(
            "ACCESS_SERVICE_APIKEYS",
            s"User accessed apikeys from a group",
            Json.obj("groupId" -> groupId),
            ctx
          )
          if (hasFilters) {
            Ok(
              JsArray(
                apiKeys
                  .filter(ctx.canUserRead)
                  .filter {
                    case keys if group.isDefined && keys.authorizedOnGroup(group.get)       => true
                    case keys if clientId.isDefined && keys.clientId == clientId.get        => true
                    case keys if name.isDefined && keys.clientName == name.get              => true
                    case keys if enabled.isDefined && keys.enabled == enabled.get.toBoolean => true
                    case _                                                                  => false
                  }
                  .drop(paginationPosition)
                  .take(paginationPageSize)
                  .map(_.toJson)
              )
            )
          } else {
            Ok(JsArray(apiKeys.filter(ctx.canUserRead).map(_.toJson)))
          }
        }
      }
    }

  def apiKeyFromGroup(groupId: String, clientId: String) =
    ApiAction.async { ctx =>
      env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
        case None                                   => NotFound(Json.obj("error" -> s"Group with id: '$groupId' not found")).asFuture
        case Some(group) if !ctx.canUserRead(group) => ctx.fforbidden
        case Some(group)                            =>
          env.datastores.apiKeyDataStore.findById(clientId).map {
            case None                                                => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found"))
            case Some(apiKey) if !ctx.canUserRead(apiKey)            => ctx.forbidden
            case Some(apiKey) if !apiKey.authorizedOnGroup(group.id) =>
              NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for group with id: '$groupId'"))
            case Some(apiKey) if apiKey.authorizedOnGroup(group.id)  => {
              sendAudit(
                "ACCESS_SERVICE_APIKEY",
                s"User accessed an apikey from a service descriptor",
                Json.obj("groupId" -> groupId, "clientId" -> clientId),
                ctx
              )
              Ok(apiKey.toJson)
            }
          }
      }
    }

  def createApiKeyFromGroup(groupId: String) =
    ApiAction.async(parse.json) { ctx =>
      val body: JsObject = ((ctx.request.body \ "clientId").asOpt[String] match {
        case None    => ctx.request.body.as[JsObject] ++ Json.obj("clientId" -> IdGenerator.namedToken("apki", 16, env))
        case Some(b) => ctx.request.body.as[JsObject]
      }) ++ ((ctx.request.body \ "clientSecret").asOpt[String] match {
        case None    => Json.obj("clientSecret" -> IdGenerator.namedToken("apks", 64, env))
        case Some(b) => Json.obj()
      })
      env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
        case None                                    => NotFound(Json.obj("error" -> s"Service group not found")).asFuture
        case Some(group) if !ctx.canUserWrite(group) => ctx.fforbidden
        case Some(group)                             => {
          val oldGroup   = (body \ "authorizedGroup").asOpt[String].map(g => "group_" + g).toSeq
          val entities   = (Seq("group_" + group.id) ++ oldGroup).distinct
          val apiKeyJson = ((body \ "authorizedEntities").asOpt[Seq[String]] match {
            case None                                                     => body ++ Json.obj("authorizedEntities" -> Json.arr("group_" + group.id))
            case Some(groupId) if !groupId.contains(s"group_${group.id}") =>
              body ++ Json.obj("authorizedEntities" -> (entities ++ groupId).distinct)
            case Some(groupId) if groupId.contains(s"group_${group.id}")  => body
          }) - "authorizedGroup"
          ApiKey.fromJsonSafe(apiKeyJson) match {
            case JsError(e)                                        => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
            case JsSuccess(apiKey, _) if !ctx.canUserWrite(apiKey) => ctx.fforbidden
            case JsSuccess(apiKey, _)                              =>
              apiKey.save().map {
                case false => InternalServerError(Json.obj("error" -> "ApiKey not stored ..."))
                case true  => {
                  sendAuditAndAlert(
                    "CREATE_APIKEY",
                    s"User created an ApiKey",
                    "ApiKeyCreatedAlert",
                    Json.obj(
                      "group"  -> group.toJson,
                      "apikey" -> apiKey.toJson
                    ),
                    ctx
                  )
                  env.datastores.apiKeyDataStore.addFastLookupByGroup(groupId, apiKey).map { _ =>
                    env.datastores.apiKeyDataStore.findAll()
                  }
                  Created(apiKey.toJson)
                }
              }
          }
        }
      }
    }

  def updateApiKeyFromGroup(groupId: String, clientId: String) =
    ApiAction.async(parse.json) { ctx =>
      env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
        case None                                    => NotFound(Json.obj("error" -> s"Service Group with id: '$groupId' not found")).asFuture
        case Some(group) if !ctx.canUserWrite(group) => ctx.fforbidden
        case Some(group)                             =>
          env.datastores.apiKeyDataStore.findById(clientId).flatMap {
            case None                                                => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
            case Some(apiKey) if !ctx.canUserWrite(apiKey)           => ctx.fforbidden
            case Some(apiKey) if !apiKey.authorizedOnGroup(group.id) =>
              NotFound(
                Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for group with id: '$groupId'")
              ).asFuture
            case Some(apiKey) if apiKey.authorizedOnGroup(group.id)  => {
              ApiKey.fromJsonSafe(ctx.request.body) match {
                case JsError(e)                                                => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
                case JsSuccess(newApiKey, _) if newApiKey.clientId != clientId =>
                  BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
                case JsSuccess(newApiKey, _) if newApiKey.clientId == clientId => {
                  sendAuditAndAlert(
                    "UPDATE_APIKEY",
                    s"User updated an ApiKey",
                    "ApiKeyUpdatedAlert",
                    Json.obj(
                      "group"  -> group.toJson,
                      "apikey" -> apiKey.toJson
                    ),
                    ctx
                  )
                  newApiKey.save().map(_ => Ok(newApiKey.toJson))
                }
              }
            }
          }
      }
    }

  def patchApiKeyFromGroup(groupId: String, clientId: String) =
    ApiAction.async(parse.json) { ctx =>
      env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
        case None                                    => NotFound(Json.obj("error" -> s"Service Group with id: '$groupId' not found")).asFuture
        case Some(group) if !ctx.canUserWrite(group) => ctx.fforbidden
        case Some(group)                             =>
          env.datastores.apiKeyDataStore.findById(clientId).flatMap {
            case None                                                => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
            case Some(apiKey) if !ctx.canUserWrite(apiKey)           => ctx.fforbidden
            case Some(apiKey) if !apiKey.authorizedOnGroup(group.id) =>
              NotFound(
                Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for group with id: '$groupId'")
              ).asFuture
            case Some(apiKey) if apiKey.authorizedOnGroup(group.id)  => {
              val currentApiKeyJson = apiKey.toJson
              val newApiKeyJson     = patchJson(ctx.request.body, currentApiKeyJson)
              ApiKey.fromJsonSafe(newApiKeyJson) match {
                case JsError(e)                                                => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
                case JsSuccess(newApiKey, _) if newApiKey.clientId != clientId =>
                  BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
                case JsSuccess(newApiKey, _) if newApiKey.clientId == clientId => {
                  sendAuditAndAlert(
                    "UPDATE_APIKEY",
                    s"User updated an ApiKey",
                    "ApiKeyUpdatedAlert",
                    Json.obj(
                      "group"  -> group.toJson,
                      "apikey" -> apiKey.toJson
                    ),
                    ctx
                  )
                  newApiKey.save().map(_ => Ok(newApiKey.toJson))
                }
              }
            }
          }
      }
    }

  def deleteApiKeyFromGroup(groupId: String, clientId: String) =
    ApiAction.async { ctx =>
      env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
        case None                                    => NotFound(Json.obj("error" -> s"Group with id: '$groupId' not found")).asFuture
        case Some(group) if !ctx.canUserWrite(group) => ctx.fforbidden
        case Some(group)                             =>
          env.datastores.apiKeyDataStore.findById(clientId).flatMap {
            case None                                                => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
            case Some(apiKey) if !ctx.canUserWrite(apiKey)           => ctx.fforbidden
            case Some(apiKey) if !apiKey.authorizedOnGroup(group.id) =>
              NotFound(
                Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for group with id: '$groupId'")
              ).asFuture
            case Some(apiKey) if apiKey.authorizedOnGroup(group.id)  => {
              sendAuditAndAlert(
                "DELETE_APIKEY",
                s"User deleted an ApiKey",
                "ApiKeyDeletedAlert",
                Json.obj(
                  "group"  -> group.toJson,
                  "apikey" -> apiKey.toJson
                ),
                ctx
              )
              env.datastores.apiKeyDataStore.deleteFastLookupByGroup(groupId, apiKey)
              apiKey.delete().map(res => Ok(Json.obj("deleted" -> true)))
            }
          }
      }
    }
}

class ApiKeysController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
    extends AbstractController(cc)
    with BulkControllerHelper[ApiKey, JsValue]
    with CrudControllerHelper[ApiKey, JsValue]
    with AdminApiHelper {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-apikeys-api")

  override def buildError(status: Int, message: String): ApiError[JsValue] =
    JsonApiError(status, play.api.libs.json.JsString(message))

  override def readEntity(json: JsValue): Either[String, ApiKey] =
    ApiKey._fmt.reads(json).asEither match {
      case Left(e)  => Left(e.toString())
      case Right(r) => Right(r)
    }

  override def writeEntity(entity: ApiKey): JsValue = ApiKey._fmt.writes(entity)

  override def findByIdOps(
      id: String
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], OptionalEntityAndContext[ApiKey]]] = {
    env.datastores.apiKeyDataStore.findById(id).map { opt =>
      Right(
        OptionalEntityAndContext(
          entity = opt,
          action = "ACCESS_APIKEY",
          message = "User accessed a apikey",
          metadata = Json.obj("ApiKeyId" -> id),
          alert = "ApiKeyAccessed"
        )
      )
    }
  }

  override def findAllOps(
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[ApiKey]]] = {
    env.datastores.apiKeyDataStore.findAll().map { seq =>
      Right(
        SeqEntityAndContext(
          entity = seq,
          action = "ACCESS_ALL_APIKEYS",
          message = "User accessed all apikeys",
          metadata = Json.obj(),
          alert = "ApiKeysAccessed"
        )
      )
    }
  }

  override def createEntityOps(
      entity: ApiKey
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[ApiKey]]] = {
    env.datastores.apiKeyDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "CREATE_APIKEY",
            message = "User created a apikey",
            metadata = entity.toJson.as[JsObject],
            alert = "ApiKeyCreatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "apikey not stored ...")
          )
        )
      }
    }
  }

  override def updateEntityOps(
      entity: ApiKey
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[ApiKey]]] = {
    env.datastores.apiKeyDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "UPDATE_APIKEY",
            message = "User updated a apikey",
            metadata = entity.toJson.as[JsObject],
            alert = "ApiKeyUpdatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "apikey not stored ...")
          )
        )
      }
    }
  }

  override def deleteEntityOps(
      id: String
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[ApiKey]]] = {
    env.datastores.apiKeyDataStore.delete(id).map {
      case true  => {
        Right(
          NoEntityAndContext(
            action = "DELETE_APIKEY",
            message = "User deleted a apikey",
            metadata = Json.obj("ApiKeyId" -> id),
            alert = "ApiKeyDeletedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "apikey not deleted ...")
          )
        )
      }
    }
  }

  def apiKeyQuotas(clientId: String) =
    ApiAction.async { ctx =>
      env.datastores.apiKeyDataStore.findById(clientId).flatMap {
        case None                                     => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
        case Some(apiKey) if !ctx.canUserRead(apiKey) => ctx.fforbidden
        case Some(apiKey)                             => {
          sendAudit(
            "ACCESS_SERVICE_APIKEY_QUOTAS",
            s"User accessed an apikey quotas from a service descriptor",
            Json.obj("clientId" -> clientId),
            ctx
          )
          apiKey.remainingQuotas().map(rq => Ok(rq.toJson))
        }
      }
    }

  def resetApiKeyQuotas(clientId: String) =
    ApiAction.async { ctx =>
      env.datastores.apiKeyDataStore.findById(clientId).flatMap {
        case None                                      => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
        case Some(apiKey) if !ctx.canUserWrite(apiKey) => ctx.fforbidden
        case Some(apiKey)                              => {
          sendAudit(
            "RESET_SERVICE_APIKEY_QUOTAS",
            s"User reset an apikey quotas for a service descriptor",
            Json.obj("clientId" -> clientId),
            ctx
          )
          env.datastores.apiKeyDataStore.resetQuotas(apiKey).map(rq => Ok(rq.toJson))
        }
      }
    }

  override def extractId(entity: ApiKey): String = entity.clientId
}
