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

class ApiKeysFromRouteController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
    extends AbstractController(cc)
    with AdminApiHelper {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-apikeys-fs-api")

  def apiKeyQuotas(routeId: String, clientId: String) =
    ApiAction.async { ctx =>
      env.datastores.routeDataStore.findById(routeId).flatMap {
        case None                                 => NotFound(Json.obj("error" -> s"Service with id: '$routeId' not found")).asFuture
        case Some(desc) if !ctx.canUserRead(desc) => ctx.fforbidden
        case Some(desc)                           =>
          env.datastores.apiKeyDataStore.findById(clientId).flatMap {
            case None                                                 => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
            case Some(apiKey) if !ctx.canUserRead(apiKey)             => ctx.fforbidden
            case Some(apiKey) if !apiKey.authorizedOnService(desc.id) =>
              NotFound(
                Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for service with id: '$routeId'")
              ).asFuture
            case Some(apiKey) if apiKey.authorizedOnService(desc.id)  => {
              sendAudit(
                "ACCESS_SERVICE_APIKEY_QUOTAS",
                s"User accessed an apikey quotas from a service descriptor",
                Json.obj("routeId" -> routeId, "clientId" -> clientId),
                ctx
              )
              apiKey.remainingQuotas().map(rq => Ok(rq.toJson))
            }
          }
      }
    }

  def resetApiKeyQuotas(routeId: String, clientId: String) =
    ApiAction.async { ctx =>
      env.datastores.routeDataStore.findById(routeId).flatMap {
        case None                                  => NotFound(Json.obj("error" -> s"Service with id: '$routeId' not found")).asFuture
        case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
        case Some(desc)                            =>
          env.datastores.apiKeyDataStore.findById(clientId).flatMap {
            case None                                                                      => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
            case Some(apiKey) if !ctx.canUserWrite(apiKey)                                 => ctx.fforbidden
            case Some(apiKey) if !apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups) =>
              NotFound(
                Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for service with id: '$routeId'")
              ).asFuture
            case Some(apiKey) if apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups)  => {
              sendAudit(
                "RESET_SERVICE_APIKEY_QUOTAS",
                s"User reset an apikey quotas for a service descriptor",
                Json.obj("routeId" -> routeId, "clientId" -> clientId),
                ctx
              )
              env.datastores.apiKeyDataStore.resetQuotas(apiKey).map(rq => Ok(rq.toJson))
            }
          }
      }
    }

  def createApiKey(routeId: String) =
    ApiAction.async(parse.json) { ctx =>
      val body: JsObject = ((ctx.request.body \ "clientId").asOpt[String] match {
        case None    => ctx.request.body.as[JsObject] ++ Json.obj("clientId" -> IdGenerator.namedToken("apki", 16, env))
        case Some(b) => ctx.request.body.as[JsObject]
      }) ++ ((ctx.request.body \ "clientSecret").asOpt[String] match {
        case None    => Json.obj("clientSecret" -> IdGenerator.namedToken("apks", 64, env))
        case Some(b) => Json.obj()
      })
      env.datastores.routeDataStore.findById(routeId).flatMap {
        case None                                  => NotFound(Json.obj("error" -> s"Service with id $routeId not found")).asFuture
        case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
        case Some(desc)                            => {
          val oldGroup   = (body \ "authorizedGroup").asOpt[String].map(g => "group_" + g).toSeq
          val entities   = (Seq("service_" + routeId) ++ oldGroup).distinct
          val apiKeyJson = ((body \ "authorizedEntities").asOpt[Seq[String]] match {
            case None                                              => body ++ Json.obj("authorizedEntities" -> Json.arr("service_" + routeId))
            case Some(sid) if !sid.contains(s"service_${routeId}") =>
              body ++ Json.obj("authorizedEntities" -> (entities ++ sid).distinct)
            case Some(sid) if sid.contains(s"service_${routeId}")  => body
            case Some(_)                                           => body
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
                      "desc"   -> desc.json,
                      "apikey" -> apiKey.toJson
                    ),
                    ctx
                  )
                  env.datastores.apiKeyDataStore.addFastLookupByService(routeId, apiKey).map { _ =>
                    env.datastores.apiKeyDataStore.findAll()
                  }
                  Created(apiKey.toJson)
                }
              }
          }
        }
      }
    }

  def updateApiKey(routeId: String, clientId: String) =
    ApiAction.async(parse.json) { ctx =>
      env.datastores.routeDataStore
        .findById(routeId)
        .flatMap {
          case None                                  => NotFound(Json.obj("error" -> s"Service with id: '$routeId' not found")).asFuture
          case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
          case Some(desc)                            =>
            env.datastores.apiKeyDataStore.findById(clientId).flatMap {
              case None                                                                      => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
              case Some(apiKey) if !ctx.canUserWrite(apiKey)                                 => ctx.fforbidden
              case Some(apiKey) if !apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups) =>
                NotFound(
                  Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for service with id: '$routeId'")
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
                        "desc"   -> desc.json,
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

  def patchApiKey(routeId: String, clientId: String) =
    ApiAction.async(parse.json) { ctx =>
      env.datastores.routeDataStore.findById(routeId).flatMap {
        case None                                  => NotFound(Json.obj("error" -> s"Service with id: '$routeId' not found")).asFuture
        case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
        case Some(desc)                            =>
          env.datastores.apiKeyDataStore.findById(clientId).flatMap {
            case None                                                                      => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
            case Some(apiKey) if !ctx.canUserWrite(apiKey)                                 => ctx.fforbidden
            case Some(apiKey) if !apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups) =>
              NotFound(
                Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for service with id: '$routeId'")
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
                      "desc"   -> desc.json,
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

  def deleteApiKey(routeId: String, clientId: String) =
    ApiAction.async { ctx =>
      env.datastores.routeDataStore.findById(routeId).flatMap {
        case None                                  => NotFound(Json.obj("error" -> s"Service with id: '$routeId' not found")).asFuture
        case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
        case Some(desc)                            =>
          env.datastores.apiKeyDataStore.findById(clientId).flatMap {
            case None                                                                      => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
            case Some(apiKey) if !ctx.canUserWrite(apiKey)                                 => ctx.fforbidden
            case Some(apiKey) if !apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups) =>
              NotFound(
                Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for service with id: '$routeId'")
              ).asFuture
            case Some(apiKey) if apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups)  => {
              sendAuditAndAlert(
                "DELETE_APIKEY",
                s"User deleted an ApiKey",
                "ApiKeyDeletedAlert",
                Json.obj(
                  "desc"   -> desc.json,
                  "apikey" -> apiKey.toJson
                ),
                ctx
              )
              env.datastores.apiKeyDataStore.deleteFastLookupByService(routeId, apiKey)
              apiKey.delete().map(res => Ok(Json.obj("deleted" -> true)))
            }
          }
      }
    }

  def apiKeys(routeId: String) =
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
      env.datastores.apiKeyDataStore.findByService(routeId).fold {
        case Failure(_)       => NotFound(Json.obj("error" -> s"ApiKeys for service with id: '$routeId' does not exist"))
        case Success(apiKeys) => {
          sendAudit(
            "ACCESS_SERVICE_APIKEYS",
            s"User accessed apikeys from a service descriptor",
            Json.obj("routeId" -> routeId),
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

  def apiKey(routeId: String, clientId: String) =
    ApiAction.async { ctx =>
      env.datastores.routeDataStore.findById(routeId).flatMap {
        case None                                 => NotFound(Json.obj("error" -> s"Service with id: '$routeId' not found")).asFuture
        case Some(desc) if !ctx.canUserRead(desc) => ctx.fforbidden
        case Some(desc)                           =>
          env.datastores.apiKeyDataStore.findById(clientId).map {
            case None                                                                      => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found"))
            case Some(apiKey) if !ctx.canUserRead(apiKey)                                  => ctx.forbidden
            case Some(apiKey) if !apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups) =>
              NotFound(
                Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for service with id: '$routeId'")
              )
            case Some(apiKey) if apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups)  => {
              sendAudit(
                "ACCESS_SERVICE_APIKEY",
                s"User accessed an apikey from a service descriptor",
                Json.obj("routeId" -> routeId, "clientId" -> clientId),
                ctx
              )
              Ok(apiKey.toJson)
            }
          }
      }
    }
}
