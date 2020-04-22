package controllers.adminapi

import actions.ApiAction
import env.Env
import events._
import models.ApiKey
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{AbstractController, ControllerComponents}
import security.IdGenerator
import utils.JsonPatchHelpers.patchJson

import otoroshi.utils.syntax.implicits._

import scala.util.{Failure, Success}

class ApiKeysController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
  extends AbstractController(cc) {

  implicit lazy val ec = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-apikeys-api")

  def createApiKey(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body: JsObject = ((ctx.request.body \ "clientId").asOpt[String] match {
      case None    => ctx.request.body.as[JsObject] ++ Json.obj("clientId" -> IdGenerator.token(16))
      case Some(b) => ctx.request.body.as[JsObject]
    }) ++ ((ctx.request.body \ "clientSecret").asOpt[String] match {
      case None    => Json.obj("clientSecret" -> IdGenerator.token(64))
      case Some(b) => Json.obj()
    })
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id $serviceId not found")).asFuture
      case Some(desc) =>
        desc.group.flatMap {
          case None => NotFound(Json.obj("error" -> s"Service group not found")).asFuture
          case Some(group) => {
            val apiKeyJson = (body \ "authorizedGroup").asOpt[String] match {
              case None                                 => body ++ Json.obj("authorizedGroup" -> group.id)
              case Some(groupId) if groupId != group.id => body ++ Json.obj("authorizedGroup" -> group.id)
              case Some(groupId) if groupId == group.id => body
            }
            ApiKey.fromJsonSafe(apiKeyJson) match {
              case JsError(e) => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(apiKey, _) =>
                apiKey.save().map {
                  case false => InternalServerError(Json.obj("error" -> "ApiKey not stored ..."))
                  case true => {
                    val event: AdminApiEvent = AdminApiEvent(
                      env.snowflakeGenerator.nextIdStr(),
                      env.env,
                      Some(ctx.apiKey),
                      ctx.user,
                      "CREATE_APIKEY",
                      s"User created an ApiKey",
                      ctx.from,
                      ctx.ua,
                      Json.obj(
                        "desc"   -> desc.toJson,
                        "apikey" -> apiKey.toJson
                      )
                    )
                    Audit.send(event)
                    Alerts.send(
                      ApiKeyCreatedAlert(env.snowflakeGenerator.nextIdStr(),
                        env.env,
                        ctx.user.getOrElse(ctx.apiKey.toJson),
                        event,
                        ctx.from,
                        ctx.ua)
                    )
                    env.datastores.apiKeyDataStore.addFastLookupByService(serviceId, apiKey).map { _ =>
                      env.datastores.apiKeyDataStore.findAll()
                    }
                    Ok(apiKey.toJson)
                  }
                }
            }
          }
        }
    }
  }

  def createApiKeyFromGroup(groupId: String) = ApiAction.async(parse.json) { ctx =>
    val body: JsObject = ((ctx.request.body \ "clientId").asOpt[String] match {
      case None    => ctx.request.body.as[JsObject] ++ Json.obj("clientId" -> IdGenerator.token(16))
      case Some(b) => ctx.request.body.as[JsObject]
    }) ++ ((ctx.request.body \ "clientSecret").asOpt[String] match {
      case None    => Json.obj("clientSecret" -> IdGenerator.token(64))
      case Some(b) => Json.obj()
    })
    env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service group not found")).asFuture
      case Some(group) => {
        val apiKeyJson = (body \ "authorizedGroup").asOpt[String] match {
          case None                         => body ++ Json.obj("authorizedGroup" -> group.id)
          case Some(gid) if gid != group.id => body ++ Json.obj("authorizedGroup" -> group.id)
          case Some(gid) if gid == group.id => body
        }
        ApiKey.fromJsonSafe(apiKeyJson) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
          case JsSuccess(apiKey, _) =>
            apiKey.save().map {
              case false => InternalServerError(Json.obj("error" -> "ApiKey not stored ..."))
              case true => {
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "CREATE_APIKEY",
                  s"User created an ApiKey",
                  ctx.from,
                  ctx.ua,
                  Json.obj(
                    "group"  -> group.toJson,
                    "apikey" -> apiKey.toJson
                  )
                )
                Audit.send(event)
                Alerts.send(
                  ApiKeyCreatedAlert(env.snowflakeGenerator.nextIdStr(),
                    env.env,
                    ctx.user.getOrElse(ctx.apiKey.toJson),
                    event,
                    ctx.from,
                    ctx.ua)
                )
                env.datastores.apiKeyDataStore.addFastLookupByGroup(groupId, apiKey).map { _ =>
                  env.datastores.apiKeyDataStore.findAll()
                }
                Ok(apiKey.toJson)
              }
            }
        }
      }
    }
  }

  def updateApiKey(serviceId: String, clientId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId => {
            ApiKey.fromJsonSafe(ctx.request.body) match {
              case JsError(e) => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId != clientId =>
                BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId == clientId => {
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "UPDATE_APIKEY",
                  s"User updated an ApiKey",
                  ctx.from,
                  ctx.ua,
                  Json.obj(
                    "desc"   -> desc.toJson,
                    "apikey" -> apiKey.toJson
                  )
                )
                Audit.send(event)
                Alerts.send(
                  ApiKeyUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                    env.env,
                    ctx.user.getOrElse(ctx.apiKey.toJson),
                    event,
                    ctx.from,
                    ctx.ua)
                )
                newApiKey.save().map(_ => Ok(newApiKey.toJson))
              }
            }
          }
        }
    }
  }

  def patchApiKey(serviceId: String, clientId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId => {
            val currentApiKeyJson = apiKey.toJson
            val newApiKeyJson     = patchJson(ctx.request.body, currentApiKeyJson)
            ApiKey.fromJsonSafe(newApiKeyJson) match {
              case JsError(e) => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId != clientId =>
                BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId == clientId => {
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "UPDATE_APIKEY",
                  s"User updated an ApiKey",
                  ctx.from,
                  ctx.ua,
                  Json.obj(
                    "desc"   -> desc.toJson,
                    "apikey" -> apiKey.toJson
                  )
                )
                Audit.send(event)
                Alerts.send(
                  ApiKeyUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                    env.env,
                    ctx.user.getOrElse(ctx.apiKey.toJson),
                    event,
                    ctx.from,
                    ctx.ua)
                )
                newApiKey.save().map(_ => Ok(newApiKey.toJson))
              }
            }
          }
        }
    }
  }

  def updateApiKeyFromGroup(groupId: String, clientId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service Group with id: '$groupId' not found")).asFuture
      case Some(group) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != group.id =>
            NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for group with id: '$groupId'")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == group.id => {
            ApiKey.fromJsonSafe(ctx.request.body) match {
              case JsError(e) => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId != clientId =>
                BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId == clientId => {
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "UPDATE_APIKEY",
                  s"User updated an ApiKey",
                  ctx.from,
                  ctx.ua,
                  Json.obj(
                    "group"  -> group.toJson,
                    "apikey" -> apiKey.toJson
                  )
                )
                Audit.send(event)
                Alerts.send(
                  ApiKeyUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                    env.env,
                    ctx.user.getOrElse(ctx.apiKey.toJson),
                    event,
                    ctx.from,
                    ctx.ua)
                )
                newApiKey.save().map(_ => Ok(newApiKey.toJson))
              }
            }
          }
        }
    }
  }

  def patchApiKeyFromGroup(groupId: String, clientId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service Group with id: '$groupId' not found")).asFuture
      case Some(group) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != group.id =>
            NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for group with id: '$groupId'")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == group.id => {
            val currentApiKeyJson = apiKey.toJson
            val newApiKeyJson     = patchJson(ctx.request.body, currentApiKeyJson)
            ApiKey.fromJsonSafe(newApiKeyJson) match {
              case JsError(e) => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId != clientId =>
                BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId == clientId => {
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "UPDATE_APIKEY",
                  s"User updated an ApiKey",
                  ctx.from,
                  ctx.ua,
                  Json.obj(
                    "group"  -> group.toJson,
                    "apikey" -> apiKey.toJson
                  )
                )
                Audit.send(event)
                Alerts.send(
                  ApiKeyUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                    env.env,
                    ctx.user.getOrElse(ctx.apiKey.toJson),
                    event,
                    ctx.from,
                    ctx.ua)
                )
                newApiKey.save().map(_ => Ok(newApiKey.toJson))
              }
            }
          }
        }
    }
  }

  def deleteApiKeyFromGroup(groupId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Group with id: '$groupId' not found")).asFuture
      case Some(group) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != group.id =>
            NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for group with id: '$groupId'")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == group.id => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "DELETE_APIKEY",
              s"User deleted an ApiKey",
              ctx.from,
              ctx.ua,
              Json.obj(
                "group"  -> group.toJson,
                "apikey" -> apiKey.toJson
              )
            )
            Audit.send(event)
            Alerts.send(
              ApiKeyDeletedAlert(env.snowflakeGenerator.nextIdStr(),
                env.env,
                ctx.user.getOrElse(ctx.apiKey.toJson),
                event,
                ctx.from,
                ctx.ua)
            )
            env.datastores.apiKeyDataStore.deleteFastLookupByGroup(groupId, apiKey)
            apiKey.delete().map(res => Ok(Json.obj("deleted" -> true)))
          }
        }
    }
  }

  def deleteApiKey(serviceId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "DELETE_APIKEY",
              s"User deleted an ApiKey",
              ctx.from,
              ctx.ua,
              Json.obj(
                "desc"   -> desc.toJson,
                "apikey" -> apiKey.toJson
              )
            )
            Audit.send(event)
            Alerts.send(
              ApiKeyDeletedAlert(env.snowflakeGenerator.nextIdStr(),
                env.env,
                ctx.user.getOrElse(ctx.apiKey.toJson),
                event,
                ctx.from,
                ctx.ua)
            )
            env.datastores.apiKeyDataStore.deleteFastLookupByService(serviceId, apiKey)
            apiKey.delete().map(res => Ok(Json.obj("deleted" -> true)))
          }
        }
    }
  }

  def apiKeys(serviceId: String) = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition       = (paginationPage - 1) * paginationPageSize
    val clientId: Option[String] = ctx.request.queryString.get("clientId").flatMap(_.headOption)
    val name: Option[String]     = ctx.request.queryString.get("name").flatMap(_.headOption)
    val group: Option[String]    = ctx.request.queryString.get("group").flatMap(_.headOption)
    val enabled: Option[String]  = ctx.request.queryString.get("enabled").flatMap(_.headOption)
    val hasFilters               = clientId.orElse(name).orElse(group).orElse(name).orElse(enabled).isDefined
    env.datastores.apiKeyDataStore.findByService(serviceId).fold {
      case Failure(_) => NotFound(Json.obj("error" -> s"ApiKeys for service with id: '$serviceId' does not exist"))
      case Success(apiKeys) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE_APIKEYS",
            s"User accessed apikeys from a service descriptor",
            ctx.from,
            ctx.ua,
            Json.obj("serviceId" -> serviceId)
          )
        )
        if (hasFilters) {
          Ok(
            JsArray(
              apiKeys
                .filter {
                  case keys if group.isDefined && keys.authorizedGroup == group.get       => true
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
          Ok(JsArray(apiKeys.map(_.toJson)))
        }
      }
    }
  }

  def apiKeysFromGroup(groupId: String) = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition       = (paginationPage - 1) * paginationPageSize
    val clientId: Option[String] = ctx.request.queryString.get("clientId").flatMap(_.headOption)
    val name: Option[String]     = ctx.request.queryString.get("name").flatMap(_.headOption)
    val group: Option[String]    = ctx.request.queryString.get("group").flatMap(_.headOption)
    val enabled: Option[String]  = ctx.request.queryString.get("enabled").flatMap(_.headOption)
    val hasFilters               = clientId.orElse(name).orElse(group).orElse(name).orElse(enabled).isDefined
    env.datastores.apiKeyDataStore.findByGroup(groupId).fold {
      case Failure(_) => NotFound(Json.obj("error" -> s"ApiKeys for group with id: '$groupId' does not exist"))
      case Success(apiKeys) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE_APIKEYS",
            s"User accessed apikeys from a group",
            ctx.from,
            ctx.ua,
            Json.obj("groupId" -> groupId)
          )
        )
        if (hasFilters) {
          Ok(
            JsArray(
              apiKeys
                .filter {
                  case keys if group.isDefined && keys.authorizedGroup == group.get       => true
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
          Ok(JsArray(apiKeys.map(_.toJson)))
        }
      }
    }
  }

  def allApiKeys() = ApiAction.async { ctx =>
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_ALL_APIKEYS",
        s"User accessed all apikeys",
        ctx.from,
        ctx.ua
      )
    )
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
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
              .filter {
                case keys if group.isDefined && keys.authorizedGroup == group.get       => true
                case keys if clientId.isDefined && keys.clientId == clientId.get        => true
                case keys if name.isDefined && keys.clientName == name.get              => true
                case keys if enabled.isDefined && keys.enabled == enabled.get.toBoolean => true
                case _                                                                  => false
              }
              .map(_.toJson)
          )
        )
      } else {
        Ok(JsArray(keys.map(_.toJson)))
      }
    }
  }

  def apiKey(serviceId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).map {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found"))
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            )
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId => {
            Audit.send(
              AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "ACCESS_SERVICE_APIKEY",
                s"User accessed an apikey from a service descriptor",
                ctx.from,
                ctx.ua,
                Json.obj("serviceId" -> serviceId, "clientId" -> clientId)
              )
            )
            Ok(apiKey.toJson)
          }
        }
    }
  }

  def apiKeyFromGroup(groupId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Group with id: '$groupId' not found")).asFuture
      case Some(group) =>
        env.datastores.apiKeyDataStore.findById(clientId).map {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found"))
          case Some(apiKey) if apiKey.authorizedGroup != group.id =>
            NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for group with id: '$groupId'"))
          case Some(apiKey) if apiKey.authorizedGroup == group.id => {
            Audit.send(
              AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "ACCESS_SERVICE_APIKEY",
                s"User accessed an apikey from a service descriptor",
                ctx.from,
                ctx.ua,
                Json.obj("groupId" -> groupId, "clientId" -> clientId)
              )
            )
            Ok(apiKey.toJson)
          }
        }
    }
  }

  def apiKeyGroup(serviceId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId =>
            apiKey.group.map {
              case None => NotFound(Json.obj("error" -> s"ServiceGroup for ApiKey '$clientId' not found"))
              case Some(group) => {
                Audit.send(
                  AdminApiEvent(
                    env.snowflakeGenerator.nextIdStr(),
                    env.env,
                    Some(ctx.apiKey),
                    ctx.user,
                    "ACCESS_SERVICE_APIKEY_GROUP",
                    s"User accessed an apikey servicegroup from a service descriptor",
                    ctx.from,
                    ctx.ua,
                    Json.obj("serviceId" -> serviceId, "clientId" -> clientId)
                  )
                )
                Ok(group.toJson)
              }
            }
        }
    }
  }

  // fixme : use a body to update
  def updateApiKeyGroup(serviceId: String, clientId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId =>
            apiKey.group.flatMap {
              case None => NotFound(Json.obj("error" -> s"ServiceGroup for ApiKey '$clientId' not found")).asFuture
              case Some(group) => {
                val newApiKey = apiKey.copy(authorizedGroup = group.id)
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "UPDATE_APIKEY",
                  s"User updated an ApiKey",
                  ctx.from,
                  ctx.ua,
                  Json.obj(
                    "desc"   -> desc.toJson,
                    "apikey" -> apiKey.toJson
                  )
                )
                Audit.send(event)
                Alerts.send(
                  ApiKeyUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                    env.env,
                    ctx.user.getOrElse(ctx.apiKey.toJson),
                    event,
                    ctx.from,
                    ctx.ua)
                )
                newApiKey.save().map(_ => Ok(newApiKey.toJson))
              }
            }
        }
    }
  }

  def apiKeyQuotas(serviceId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId => {
            Audit.send(
              AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "ACCESS_SERVICE_APIKEY_QUOTAS",
                s"User accessed an apikey quotas from a service descriptor",
                ctx.from,
                ctx.ua,
                Json.obj("serviceId" -> serviceId, "clientId" -> clientId)
              )
            )
            apiKey.remainingQuotas().map(rq => Ok(rq.toJson))
          }
        }
    }
  }

  def resetApiKeyQuotas(serviceId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId => {
            Audit.send(
              AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "RESET_SERVICE_APIKEY_QUOTAS",
                s"User reset an apikey quotas for a service descriptor",
                ctx.from,
                ctx.ua,
                Json.obj("serviceId" -> serviceId, "clientId" -> clientId)
              )
            )
            env.datastores.apiKeyDataStore.resetQuotas(apiKey).map(rq => Ok(rq.toJson))
          }
        }
    }
  }

  def apiKeyFromGroupQuotas(groupId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Group with id: '$groupId' not found")).asFuture
      case Some(group) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != group.id =>
            NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for group with id: '$groupId'")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == group.id => {
            Audit.send(
              AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "ACCESS_SERVICE_APIKEY_QUOTAS",
                s"User accessed an apikey quotas from a service descriptor",
                ctx.from,
                ctx.ua,
                Json.obj("groupId" -> groupId, "clientId" -> clientId)
              )
            )
            apiKey.remainingQuotas().map(rq => Ok(rq.toJson))
          }
        }
    }
  }

  def resetApiKeyFromGroupQuotas(groupId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Group with id: '$groupId' not found")).asFuture
      case Some(group) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != group.id =>
            NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for group with id: '$groupId'")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == group.id => {
            Audit.send(
              AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "RESET_SERVICE_APIKEY_QUOTAS",
                s"User accessed an apikey quotas from a service descriptor",
                ctx.from,
                ctx.ua,
                Json.obj("groupId" -> groupId, "clientId" -> clientId)
              )
            )
            env.datastores.apiKeyDataStore.resetQuotas(apiKey).map(rq => Ok(rq.toJson))
          }
        }
    }
  }
}