package otoroshi.controllers.adminapi

import next.models.Api
import otoroshi.actions.{ApiAction, ApiActionContext}
import otoroshi.env.Env
import otoroshi.models.ApiKey
import otoroshi.next.models.NgRoute
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
import play.api.mvc.{AbstractController, AnyContent, ControllerComponents, RequestHeader, Result, Results}
import otoroshi.security.IdGenerator
import otoroshi.storage.BasicStore
import otoroshi.utils.json.JsonPatchHelpers.patchJson

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class ApiKeysFromRouteController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
    extends AbstractController(cc)
    with AdminApiHelper {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-apikeys-fs-api")

  private def canReadOrWriteRoute[T](
      ctx: ApiActionContext[T],
      entityId: String,
      readOrWrite: (ApiActionContext[T], NgRoute) => Boolean
  ): Future[Either[Result, Entity]] = {
    env.datastores.routeDataStore.findById(entityId).flatMap {
      case None                                      => NotFound(Json.obj("error" -> s"Entity with id '$entityId' not found")).leftf
      case Some(entity) if !readOrWrite(ctx, entity) => ctx.fforbidden.map(_.left)
      case Some(entity)                              => Entity(id = entityId, entity.groups, toJson = () => entity.json).rightf
    }
  }

  private def canReadOrWriteApi[T](
      ctx: ApiActionContext[T],
      entityId: String,
      readOrWrite: (ApiActionContext[T], Api) => Boolean
  ): Future[Either[Result, Entity]] = {
    env.datastores.apiDataStore.findById(entityId).flatMap {
      case None                                      => NotFound(Json.obj("error" -> s"Entity with id '$entityId' not found")).leftf
      case Some(entity) if !readOrWrite(ctx, entity) => ctx.fforbidden.map(_.left)
      case Some(entity)                              => Entity(id = entityId, entity.groups, toJson = () => entity.json).rightf
    }
  }

  def apiKeyQuotasOfRoute(routeId: String, clientId: String) = {
    ApiAction.async { ctx =>
      apiKeyQuotas(ctx, routeId, clientId, isRoute = true)
    }
  }

  def apiKeyQuotasOfApi(apiId: String, clientId: String) = {
    ApiAction.async { ctx =>
      apiKeyQuotas(ctx, apiId, clientId, isRoute = false)
    }
  }

  private def apiKeyQuotas(
      ctx: ApiActionContext[AnyContent],
      id: String,
      clientId: String,
      isRoute: Boolean
  ) = {
    (if (isRoute)
       canReadOrWriteRoute[AnyContent](ctx, id, (ctx, entity) => ctx.canUserRead(entity))
     else canReadOrWriteApi[AnyContent](ctx, id, (ctx, entity) => ctx.canUserRead(entity))).flatMap {
      case Left(err) => err.vfuture
      case Right(_)  =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None                                            => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
          case Some(apiKey) if !ctx.canUserRead(apiKey)        => ctx.fforbidden
          case Some(apiKey) if !apiKey.authorizedOnService(id) =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for service with id: '$id'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedOnService(id)  => {
            sendAudit(
              "ACCESS_SERVICE_APIKEY_QUOTAS",
              s"User accessed an apikey quotas",
              getAuditData(clientId.some, isRoute, id),
              ctx
            )
            apiKey.remainingQuotas().map(rq => Ok(rq.toJson))
          }
        }
    }
  }

  private def getAuditData(clientId: Option[String], isRoute: Boolean, id: String) = {
    Json
      .obj()
      .applyOn { payload =>
        if (isRoute)
          payload.deepMerge(Json.obj("routeId" -> id))
        else
          payload.deepMerge(Json.obj("apiId" -> id))
      }
      .applyOnIf(clientId.isDefined) { payload => payload.deepMerge(Json.obj("clientId" -> clientId)) }
  }

  case class Entity(id: String, groups: Seq[String], toJson: () => JsValue)

  def resetApiKeyQuotasOfRoute(routeId: String, clientId: String) = {
    ApiAction.async { ctx =>
      resetApiKeyQuotas(ctx, routeId, clientId, isRoute = true)
    }
  }

  def resetApiKeyQuotasOfApi(apiId: String, clientId: String) = {
    ApiAction.async { ctx =>
      resetApiKeyQuotas(ctx, apiId, clientId, isRoute = false)
    }
  }

  private def resetApiKeyQuotas(
      ctx: ApiActionContext[AnyContent],
      id: String,
      clientId: String,
      isRoute: Boolean
  ) =
    (if (isRoute)
       canReadOrWriteRoute[AnyContent](ctx, id, (ctx, entity) => ctx.canUserWrite(entity))
     else canReadOrWriteApi[AnyContent](ctx, id, (ctx, entity) => ctx.canUserWrite(entity))).flatMap {
      case Left(err)     => err.vfuture
      case Right(entity) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None                                                                   => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
          case Some(apiKey) if !ctx.canUserWrite(apiKey)                              => ctx.fforbidden
          case Some(apiKey) if !apiKey.authorizedOnServiceOrGroups(id, entity.groups) =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for service with id: '$entity.id'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedOnServiceOrGroups(id, entity.groups)  => {
            sendAudit(
              "RESET_SERVICE_APIKEY_QUOTAS",
              s"User reset an apikey quotas for a service descriptor",
              getAuditData(clientId.some, isRoute, id),
              ctx
            )
            env.datastores.apiKeyDataStore.resetQuotas(apiKey).map(rq => Ok(rq.toJson))
          }
        }
    }

  def createApiKeyOfRoute(routeId: String) = {
    ApiAction.async(parse.json) { ctx =>
      createApiKey(ctx, routeId, isRoute = true)
    }
  }

  def createApiKeyOfApi(apiId: String) = {
    ApiAction.async(parse.json) { ctx =>
      createApiKey(ctx, apiId, isRoute = false)
    }
  }

  private def createApiKey(ctx: ApiActionContext[JsValue], id: String, isRoute: Boolean) =
    (if (isRoute)
       canReadOrWriteRoute[JsValue](ctx, id, (ctx, entity) => ctx.canUserWrite(entity))
     else canReadOrWriteApi[JsValue](ctx, id, (ctx, entity) => ctx.canUserWrite(entity))).flatMap {
      case Left(err)   => err.vfuture
      case Right(desc) =>
        val prefix = if (isRoute) "route_" else "api_"

        val body: JsObject = ((ctx.request.body \ "clientId").asOpt[String] match {
          case None    => ctx.request.body.as[JsObject] ++ Json.obj("clientId" -> IdGenerator.lowerCaseToken(16))
          case Some(b) => ctx.request.body.as[JsObject]
        }) ++ ((ctx.request.body \ "clientSecret").asOpt[String] match {
          case None    => Json.obj("clientSecret" -> IdGenerator.lowerCaseToken(64))
          case Some(b) => Json.obj()
        })
        val oldGroup       = (body \ "authorizedGroup").asOpt[String].map(g => "group_" + g).toSeq
        val entities       = (Seq(prefix + id) ++ oldGroup).distinct
        val apiKeyJson     = ((body \ "authorizedEntities").asOpt[Seq[String]] match {
          case None                                      => body ++ Json.obj("authorizedEntities" -> Json.arr(prefix + id))
          case Some(sid) if !sid.contains(s"$prefix$id") =>
            body ++ Json.obj("authorizedEntities" -> (entities ++ sid).distinct)
          case Some(sid) if sid.contains(s"$prefix$id")  => body
          case Some(_)                                   => body
        }) - "authorizedGroup"
        ApiKey.fromJsonSafe(apiKeyJson) match {
          case JsError(e)                                        => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
          case JsSuccess(apiKey, _) if !ctx.canUserWrite(apiKey) => ctx.fforbidden
          case JsSuccess(apiKey, _)                              =>
            env.datastores.apiKeyDataStore.findById(apiKey.clientId).flatMap {
              case Some(_) => BadRequest(Json.obj("error" -> "resource already exists")).asFuture
              case None    => {
                ctx.validateEntity(body, "apikey") match {
                  case Left(errs) =>
                    BadRequest(Json.obj("error" -> "Bad ApiKey format", "details" -> errs)).asFuture
                  case Right(_)   => {
                    apiKey.save().map {
                      case false => InternalServerError(Json.obj("error" -> "ApiKey not stored ..."))
                      case true  => {
                        sendAuditAndAlert(
                          "CREATE_APIKEY",
                          s"User created an ApiKey",
                          "ApiKeyCreatedAlert",
                          Json.obj(
                            "desc"   -> desc.toJson(),
                            "apikey" -> apiKey.toJson
                          ),
                          ctx
                        )
                        env.datastores.apiKeyDataStore.addFastLookupByService(id, apiKey).map { _ =>
                          env.datastores.apiKeyDataStore.findAll()
                        }
                        Created(apiKey.toJson)
                      }
                    }
                  }
                }
              }
            }
        }
    }

  def updateApiKeyOfRoute(routeId: String, clientId: String) = {
    ApiAction.async(parse.json) { ctx =>
      updateApiKey(ctx, routeId, clientId, isRoute = true)
    }
  }

  def updateApiKeyOfApi(apiId: String, clientId: String) = {
    ApiAction.async(parse.json) { ctx =>
      updateApiKey(ctx, apiId, clientId, isRoute = false)
    }
  }

  private def updateApiKey(ctx: ApiActionContext[JsValue], id: String, clientId: String, isRoute: Boolean) =
    (if (isRoute)
       canReadOrWriteRoute[JsValue](ctx, id, (ctx, entity) => ctx.canUserWrite(entity))
     else canReadOrWriteApi[JsValue](ctx, id, (ctx, entity) => ctx.canUserWrite(entity))).flatMap {
      case Left(err)   => err.vfuture
      case Right(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None                                                                      => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
          case Some(apiKey) if !ctx.canUserWrite(apiKey)                                 => ctx.fforbidden
          case Some(apiKey) if !apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups) =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for service with id: '$id'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups)  => {
            val body = ctx.request.body
            env.datastores.apiKeyDataStore.findById(apiKey.clientId).flatMap {
              case None                                => BadRequest(Json.obj("error" -> "resource does no exists")).asFuture
              case Some(old) if !ctx.canUserWrite(old) =>
                BadRequest(Json.obj("error" -> "you cannot access this resource")).asFuture
              case Some(old)                           => {
                ctx.validateEntity(body, "apikey") match {
                  case Left(errs) =>
                    BadRequest(Json.obj("error" -> "Bad ApiKey format", "details" -> errs)).asFuture
                  case Right(_)   => {
                    ApiKey.fromJsonSafe(body) match {
                      case JsError(e)                                                => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
                      case JsSuccess(newApiKey, _) if newApiKey.clientId != clientId =>
                        BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
                      case JsSuccess(newApiKey, _) if newApiKey.clientId == clientId => {
                        sendAuditAndAlert(
                          "UPDATE_APIKEY",
                          s"User updated an ApiKey",
                          "ApiKeyUpdatedAlert",
                          Json.obj(
                            "desc"   -> desc.toJson(),
                            "apikey" -> newApiKey.toJson,
                            "previous_apikey" -> apiKey.toJson
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
          }
        }
    }

  def patchApiKeyOfRoute(routeId: String, clientId: String) = {
    ApiAction.async(parse.json) { ctx =>
      patchApiKey(ctx, routeId, clientId, isRoute = true)
    }
  }

  def patchApiKeyOfApi(apiId: String, clientId: String) = {
    ApiAction.async(parse.json) { ctx =>
      patchApiKey(ctx, apiId, clientId, isRoute = false)
    }
  }

  private def patchApiKey(ctx: ApiActionContext[JsValue], id: String, clientId: String, isRoute: Boolean) =
    (if (isRoute)
       canReadOrWriteRoute[JsValue](ctx, id, (ctx, entity) => ctx.canUserWrite(entity))
     else canReadOrWriteApi[JsValue](ctx, id, (ctx, entity) => ctx.canUserWrite(entity))).flatMap {
      case Left(err)   => err.vfuture
      case Right(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None                                                                      => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
          case Some(apiKey) if !ctx.canUserWrite(apiKey)                                 => ctx.fforbidden
          case Some(apiKey) if !apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups) =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for service with id: '$id'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups)  => {
            val currentApiKeyJson = apiKey.toJson
            val newApiKeyJson     = patchJson(ctx.request.body, currentApiKeyJson)
            ctx.validateEntity(newApiKeyJson, "apikey") match {
              case Left(errs) => BadRequest(Json.obj("error" -> "Bad ApiKey format", "details" -> errs)).asFuture
              case Right(_)   => {
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
                        "desc"   -> desc.toJson(),
                        "apikey" -> newApiKey.toJson,
                        "previous_apikey" -> apiKey.toJson,
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
    }

  def deleteApiKeyOfRoute(routeId: String, clientId: String) = {
    ApiAction.async(parse.json) { ctx =>
      deleteApiKey(ctx, routeId, clientId, isRoute = true)
    }
  }

  def deleteApiKeyOfApi(apiId: String, clientId: String) = {
    ApiAction.async(parse.json) { ctx =>
      deleteApiKey(ctx, apiId, clientId, isRoute = false)
    }
  }

  private def deleteApiKey(ctx: ApiActionContext[JsValue], id: String, clientId: String, isRoute: Boolean) =
    (if (isRoute)
       canReadOrWriteRoute[JsValue](ctx, id, (ctx, entity) => ctx.canUserWrite(entity))
     else canReadOrWriteApi[JsValue](ctx, id, (ctx, entity) => ctx.canUserWrite(entity))).flatMap {
      case Left(err)   => err.vfuture
      case Right(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None                                                                      => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found")).asFuture
          case Some(apiKey) if !ctx.canUserWrite(apiKey)                                 => ctx.fforbidden
          case Some(apiKey) if !apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups) =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for service with id: '$id'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups)  => {
            sendAuditAndAlert(
              "DELETE_APIKEY",
              s"User deleted an ApiKey",
              "ApiKeyDeletedAlert",
              Json.obj(
                "desc"   -> desc.toJson(),
                "apikey" -> apiKey.toJson
              ),
              ctx
            )
            env.datastores.apiKeyDataStore.deleteFastLookupByService(id, apiKey)
            apiKey.delete().map(res => Ok(Json.obj("deleted" -> true)))
          }
        }
    }

  def apiKeysOfRoute(routeId: String) = {
    ApiAction.async(parse.json) { ctx =>
      apiKeys(ctx, routeId, isRoute = true)
    }
  }

  def apiKeysOfApi(apiId: String) = {
    ApiAction.async(parse.json) { ctx =>
      apiKeys(ctx, apiId, isRoute = false)
    }
  }

  private def apiKeys(ctx: ApiActionContext[JsValue], id: String, isRoute: Boolean) = {
    val paginationPage: Int      = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int  =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition       = (paginationPage - 1) * paginationPageSize
    val clientId: Option[String] = ctx.request.queryString.get("clientId").flatMap(_.headOption)
    val name: Option[String]     = ctx.request.queryString.get("name").flatMap(_.headOption)
    val group: Option[String]    = ctx.request.queryString.get("group").flatMap(_.headOption)
    val enabled: Option[String]  = ctx.request.queryString.get("enabled").flatMap(_.headOption)
    val hasFilters               = clientId.orElse(name).orElse(group).orElse(name).orElse(enabled).isDefined
    env.datastores.apiKeyDataStore.findByService(id).fold {
      case Failure(_)       => NotFound(Json.obj("error" -> s"ApiKeys for service with id: '$id' does not exist"))
      case Success(apiKeys) => {
        sendAudit(
          "ACCESS_SERVICE_APIKEYS",
          s"User accessed apikeys from a service descriptor",
          getAuditData(None, isRoute, id),
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

  def apiKeyOfRoute(routeId: String, clientId: String) = {
    ApiAction.async(parse.json) { ctx =>
      apiKey(ctx, routeId, clientId, isRoute = true)
    }
  }

  def apiKeyOfApi(apiId: String, clientId: String) = {
    ApiAction.async(parse.json) { ctx =>
      apiKey(ctx, apiId, clientId, isRoute = false)
    }
  }

  private def apiKey(ctx: ApiActionContext[JsValue], id: String, clientId: String, isRoute: Boolean) =
    (if (isRoute)
       canReadOrWriteRoute[JsValue](ctx, id, (ctx, entity) => ctx.canUserRead(entity))
     else canReadOrWriteApi[JsValue](ctx, id, (ctx, entity) => ctx.canUserRead(entity))).flatMap {
      case Left(err)   => err.vfuture
      case Right(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).map {
          case None                                                                      => NotFound(Json.obj("error" -> s"ApiKey with clientId '$clientId' not found"))
          case Some(apiKey) if !ctx.canUserRead(apiKey)                                  => ctx.forbidden
          case Some(apiKey) if !apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups) =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clientId '$clientId' not found for service with id: '$id'")
            )
          case Some(apiKey) if apiKey.authorizedOnServiceOrGroups(desc.id, desc.groups)  => {
            sendAudit(
              "ACCESS_SERVICE_APIKEY",
              s"User accessed an apikey from a service descriptor",
              getAuditData(clientId.some, isRoute, id),
              ctx
            )
            Ok(apiKey.toJson)
          }
        }
    }

}
