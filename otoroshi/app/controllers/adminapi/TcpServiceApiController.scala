package controllers.adminapi

import actions.ApiAction
import env.Env
import otoroshi.tcp.TcpService
import play.api.Logger
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.{AbstractController, ControllerComponents, RequestHeader}
import utils._

import scala.concurrent.{ExecutionContext, Future}

class TcpServiceApiController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
  extends AbstractController(cc) with BulkControllerHelper[TcpService, JsValue] with CrudControllerHelper[TcpService, JsValue] {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  val logger = Logger("otoroshi-tcp-service-api")


  override def readEntity(json: JsValue): Either[String, TcpService] = TcpService.fmt.reads(json).asEither match {
    case Left(e) => Left(e.toString())
    case Right(r) => Right(r)
  }

  override def writeEntity(entity: TcpService): JsValue = TcpService.fmt.writes(entity)

  override def findByIdOps(id: String)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], OptionalEntityAndContext[TcpService]]] = {
    env.datastores.tcpServiceDataStore.findById(id).map { opt =>
      Right(OptionalEntityAndContext(
        entity = opt,
        action = "ACCESS_TCP_SERVICE",
        message = "User accessed a tcp service",
        metadata = Json.obj("TcpServiceId" -> id),
        alert = "TcpServiceAccessed"
      ))
    }
  }

  override def findAllOps(req: RequestHeader)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[TcpService]]] = {
    env.datastores.tcpServiceDataStore.findAll().map { seq =>
      Right(SeqEntityAndContext(
        entity = seq,
        action = "ACCESS_ALL_TCP_SERVICES",
        message = "User accessed all tcp services",
        metadata = Json.obj(),
        alert = "TcpServicesAccessed"
      ))
    }
  }

  override def createEntityOps(entity: TcpService)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[TcpService]]] = {
    env.datastores.tcpServiceDataStore.set(entity).map {
      case true => {
        Right(EntityAndContext(
          entity = entity,
          action = "CREATE_TCP_SERVICE",
          message = "User created a tcp service",
          metadata = entity.json.as[JsObject],
          alert = "TcpServiceCreatedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "Tcp service not stored ...")
        ))
      }
    }
  }

  override def updateEntityOps(entity: TcpService)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[TcpService]]] = {
    env.datastores.tcpServiceDataStore.set(entity).map {
      case true => {
        Right(EntityAndContext(
          entity = entity,
          action = "UPDATE_TCP_SERVICE",
          message = "User updated a tcp service",
          metadata = entity.json.as[JsObject],
          alert = "TcpServiceUpdatedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "Tcp service not stored ...")
        ))
      }
    }
  }

  override def deleteEntityOps(id: String)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[TcpService]]] = {
    env.datastores.tcpServiceDataStore.delete(id).map {
      case true => {
        Right(NoEntityAndContext(
          action = "DELETE_TCP_SERVICE",
          message = "User deleted a tcp service",
          metadata = Json.obj("TcpServiceId" -> id),
          alert = "TcpServiceDeletedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "Tcp service not deleted ...")
        ))
      }
    }
  }

  /*
  def findAllTcpServices() = ApiAction.async { ctx =>
    env.datastores.tcpServiceDataStore.findAll().map(all => Ok(JsArray(all.map(_.json))))
  }

  def findTcpServiceById(id: String) = ApiAction.async { ctx =>
    env.datastores.tcpServiceDataStore.findById(id).map {
      case Some(service) => Ok(service.json)
      case None =>
        NotFound(
          Json.obj("error" -> s"TcpService with id $id not found")
        )
    }
  }

  def createTcpService() = ApiAction.async(parse.json) { ctx =>
    val id = (ctx.request.body \ "id").asOpt[String]
    val body = ctx.request.body
      .as[JsObject] ++ id.map(v => Json.obj("id" -> id)).getOrElse(Json.obj("id" -> IdGenerator.token))
    TcpService.fromJsonSafe(body) match {
      case Left(_) => BadRequest(Json.obj("error" -> "Bad TcpService format")).asFuture
      case Right(service) =>
        env.datastores.tcpServiceDataStore.set(service).map { _ =>
          Ok(service.json)
        }
    }
  }

  def updateTcpService(id: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.tcpServiceDataStore.findById(id).flatMap {
      case None =>
        NotFound(
          Json.obj("error" -> s"TcpService with id $id not found")
        ).asFuture
      case Some(initialTcpService) => {
        TcpService.fromJsonSafe(ctx.request.body) match {
          case Left(_) => BadRequest(Json.obj("error" -> "Bad TcpService format")).asFuture
          case Right(service) => {
            env.datastores.tcpServiceDataStore.set(service).map { _ =>
              Ok(service.json)
            }
          }
        }
      }
    }
  }

  def patchTcpService(id: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.tcpServiceDataStore.findById(id).flatMap {
      case None =>
        NotFound(
          Json.obj("error" -> s"TcpService with id $id not found")
        ).asFuture
      case Some(initialTcpService) => {
        val currentJson   = initialTcpService.json
        val newTcpService = utils.JsonPatchHelpers.patchJson(ctx.request.body, currentJson)
        TcpService.fromJsonSafe(newTcpService) match {
          case Left(_) => BadRequest(Json.obj("error" -> "Bad TcpService format")).asFuture
          case Right(newTcpService) => {
            env.datastores.tcpServiceDataStore.set(newTcpService).map { _ =>
              Ok(newTcpService.json)
            }
          }
        }
      }
    }
  }

  def deleteTcpService(id: String) = ApiAction.async { ctx =>
    env.datastores.tcpServiceDataStore.delete(id).map { _ =>
      Ok(Json.obj("deleted" -> true))
    }
  }
  */
}

