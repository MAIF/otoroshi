package controllers.adminapi

import actions.ApiAction
import env.Env
import events._
import models.ServiceGroup
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{AbstractController, ControllerComponents, RequestHeader}
import utils._

import scala.concurrent.{ExecutionContext, Future}

class ServiceGroupController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
  extends AbstractController(cc) with BulkControllerHelper[ServiceGroup, JsValue] with CrudControllerHelper[ServiceGroup, JsValue] {

  implicit val ec  = env.otoroshiExecutionContext
  implicit val mat = env.otoroshiMaterializer

  override def readEntity(json: JsValue): Either[String, ServiceGroup] = ServiceGroup._fmt.reads(json).asEither match {
    case Left(e) => Left(e.toString())
    case Right(r) => Right(r)
  }

  override def writeEntity(entity: ServiceGroup): JsValue = ServiceGroup._fmt.writes(entity)

  override def findByIdOps(id: String)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], OptionalEntityAndContext[ServiceGroup]]] = {
    env.datastores.serviceGroupDataStore.findById(id).map { opt =>
      Right(OptionalEntityAndContext(
        entity = opt,
        action = "ACCESS_SERVICES_GROUP",
        message = "User accessed a service group",
        metadata = Json.obj("serviceGroupId" -> id),
        alert = "ServiceGroupAccessed"
      ))
    }
  }

  override def findAllOps(req: RequestHeader)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[ServiceGroup]]] = {
    env.datastores.serviceGroupDataStore.findAll().map { seq =>
      Right(SeqEntityAndContext(
        entity = seq,
        action = "ACCESS_ALL_SERVICES_GROUPS",
        message = "User accessed all services groups",
        metadata = Json.obj(),
        alert = "ServiceGroupsAccessed"
      ))
    }
  }

  override def createEntityOps(entity: ServiceGroup)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[ServiceGroup]]] = {
    entity.save().map {
      case true => {
        Right(EntityAndContext(
          entity = entity,
          action = "CREATE_SERVICE_GROUP",
          message = "User created a service group",
          metadata = entity.toJson.as[JsObject],
          alert = "ServiceGroupCreatedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "Service group not stored ...")
        ))
      }
    }
  }

  override def updateEntityOps(entity: ServiceGroup)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[ServiceGroup]]] = {
    entity.save().map {
      case true => {
        Right(EntityAndContext(
          entity = entity,
          action = "UPDATE_SERVICE_GROUP",
          message = "User updated a service group",
          metadata = entity.toJson.as[JsObject],
          alert = "ServiceGroupUpdatedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "Service group not stored ...")
        ))
      }
    }
  }

  override def deleteEntityOps(id: String)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[ServiceGroup]]] = {
    env.datastores.serviceGroupDataStore.delete(id).map {
      case true => {
        Right(NoEntityAndContext(
          action = "DELETE_SERVICE_GROUP",
          message = "User deleted a service group",
          metadata = Json.obj("serviceGroupId" -> id),
          alert = "ServiceGroupDeletedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "Service group not deleted ...")
        ))
      }
    }
  }

  def serviceGroupServices(serviceGroupId: String) = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.serviceGroupDataStore.findById(serviceGroupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceGroup with id: '$serviceGroupId' not found")).future
      case Some(group) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICES_FROM_SERVICES_GROUP",
            s"User accessed all services from a services group",
            ctx.from,
            ctx.ua,
            Json.obj("serviceGroupId" -> serviceGroupId)
          )
        )
        group.services
          .map(services => Ok(JsArray(services.drop(paginationPosition).take(paginationPageSize).map(_.toJson))))
      }
    }
  }

  /*
  def createGroup() = ApiAction.async(parse.json) { ctx =>
    val body: JsObject = (ctx.request.body \ "id").asOpt[String] match {
      case None    => ctx.request.body.as[JsObject] ++ Json.obj("id" -> IdGenerator.token(64))
      case Some(b) => ctx.request.body.as[JsObject]
    }
    ServiceGroup.fromJsonSafe(body) match {
      case JsError(e) => BadRequest(Json.obj("error" -> "Bad ServiceGroup format")).asFuture
      case JsSuccess(group, _) =>
        group.save().map {
          case true => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "CREATE_SERVICE_GROUP",
              s"User created a service group",
              ctx.from,
              ctx.ua,
              body
            )
            Audit.send(event)
            Alerts.send(
              ServiceGroupCreatedAlert(env.snowflakeGenerator.nextIdStr(),
                                       env.env,
                                       ctx.user.getOrElse(ctx.apiKey.toJson),
                                       event,
                                       ctx.from,
                                       ctx.ua)
            )
            Ok(group.toJson)
          }
          case false => InternalServerError(Json.obj("error" -> "Developer not stored ..."))
        }
    }
  }

  def updateGroup(serviceGroupId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceGroupDataStore.findById(serviceGroupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceGroup with clienId '$serviceGroupId' not found")).asFuture
      case Some(group) => {
        ServiceGroup.fromJsonSafe(ctx.request.body) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad ServiceGroup format")).asFuture
          case JsSuccess(newGroup, _) if newGroup.id != serviceGroupId =>
            BadRequest(Json.obj("error" -> "Bad ServiceGroup format")).asFuture
          case JsSuccess(newGroup, _) if newGroup.id == serviceGroupId => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "UPDATE_SERVICE_GROUP",
              s"User updated a service group",
              ctx.from,
              ctx.ua,
              ctx.request.body
            )
            Audit.send(event)
            Alerts.send(
              ServiceGroupUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                       env.env,
                                       ctx.user.getOrElse(ctx.apiKey.toJson),
                                       event,
                                       ctx.from,
                                       ctx.ua)
            )
            newGroup.save().map(_ => Ok(newGroup.toJson))
          }
        }
      }
    }
  }

  def patchGroup(serviceGroupId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceGroupDataStore.findById(serviceGroupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceGroup with clienId '$serviceGroupId' not found")).asFuture
      case Some(group) => {
        val currentGroupJson = group.toJson
        val newGroupJson     = patchJson(ctx.request.body, currentGroupJson)
        ServiceGroup.fromJsonSafe(newGroupJson) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad ServiceGroup format")).asFuture
          case JsSuccess(newGroup, _) if newGroup.id != serviceGroupId =>
            BadRequest(Json.obj("error" -> "Bad ServiceGroup format")).asFuture
          case JsSuccess(newGroup, _) if newGroup.id == serviceGroupId => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "UPDATE_SERVICE_GROUP",
              s"User updated a service group",
              ctx.from,
              ctx.ua,
              ctx.request.body
            )
            Audit.send(event)
            Alerts.send(
              ServiceGroupUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                       env.env,
                                       ctx.user.getOrElse(ctx.apiKey.toJson),
                                       event,
                                       ctx.from,
                                       ctx.ua)
            )
            newGroup.save().map(_ => Ok(newGroup.toJson))
          }
        }
      }
    }
  }

  def deleteGroup(serviceGroupId: String) = ApiAction.async { ctx =>
    env.datastores.serviceGroupDataStore.findById(serviceGroupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceGroup with id: '$serviceGroupId' not found")).asFuture
      case Some(group) =>
        group.delete().map { res =>
          val event: AdminApiEvent = AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "DELETE_SERVICE_GROUP",
            s"User deleted a service group",
            ctx.from,
            ctx.ua,
            Json.obj("serviceGroupId" -> serviceGroupId)
          )
          Audit.send(event)
          Alerts.send(
            ServiceGroupDeletedAlert(env.snowflakeGenerator.nextIdStr(),
                                     env.env,
                                     ctx.user.getOrElse(ctx.apiKey.toJson),
                                     event,
                                     ctx.from,
                                     ctx.ua)
          )
          Ok(Json.obj("deleted" -> res))
        }
    }
  }

  // TODO
  def addServiceToGroup(serviceGroupId: String) = ApiAction.async(parse.json) { ctx =>
    ???
  }

  // TODO
  def addExistingServiceToGroup(serviceGroupId: String, serviceId: String) = ApiAction.async(parse.json) { ctx =>
    ???
  }

  // TODO
  def removeServiceFromGroup(serviceGroupId: String, serviceId: String) = ApiAction.async { ctx =>
    ???
  }

  def allServiceGroups() = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_ALL_SERVICES_GROUPS",
        s"User accessed all services groups",
        ctx.from,
        ctx.ua
      )
    )
    val id: Option[String]   = ctx.request.queryString.get("id").flatMap(_.headOption)
    val name: Option[String] = ctx.request.queryString.get("name").flatMap(_.headOption)
    val hasFilters           = id.orElse(name).orElse(name).isDefined
    env.datastores.serviceGroupDataStore.streamedFindAndMat(_ => true, 50, paginationPage, paginationPageSize).map {
      groups =>
        if (hasFilters) {
          Ok(
            JsArray(
              groups
                .filter {
                  case group if id.isDefined && group.id == id.get       => true
                  case group if name.isDefined && group.name == name.get => true
                  case _                                                 => false
                }
                .map(_.toJson)
            )
          )
        } else {
          Ok(JsArray(groups.map(_.toJson)))
        }
    }
  }

  def serviceGroup(serviceGroupId: String) = ApiAction.async { ctx =>
    env.datastores.serviceGroupDataStore.findById(serviceGroupId).map {
      case None => NotFound(Json.obj("error" -> s"ServiceGroup with id: '$serviceGroupId' not found"))
      case Some(group) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICES_GROUP",
            s"User accessed a service group",
            ctx.from,
            ctx.ua,
            Json.obj("serviceGroupId" -> serviceGroupId)
          )
        )
        Ok(group.toJson)
      }
    }
  }

  def serviceGroupServices(serviceGroupId: String) = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.serviceGroupDataStore.findById(serviceGroupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceGroup with id: '$serviceGroupId' not found")).asFuture
      case Some(group) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICES_FROM_SERVICES_GROUP",
            s"User accessed all services from a services group",
            ctx.from,
            ctx.ua,
            Json.obj("serviceGroupId" -> serviceGroupId)
          )
        )
        group.services
          .map(services => Ok(JsArray(services.drop(paginationPosition).take(paginationPageSize).map(_.toJson))))
      }
    }
  }
*/
}
