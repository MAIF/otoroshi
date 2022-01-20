package otoroshi.next.controllers.adminapi

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.models._
import otoroshi.security.IdGenerator
import otoroshi.utils.controllers._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

class BackendsController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
  extends AbstractController(cc)
    with BulkControllerHelper[StoredBackend, JsValue]
    with CrudControllerHelper[StoredBackend, JsValue] {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-backends-api")

  override def buildError(status: Int, message: String): ApiError[JsValue] =
    JsonApiError(status, play.api.libs.json.JsString(message))

  override def extractId(entity: StoredBackend): String = entity.id

  override def readEntity(json: JsValue): Either[String, StoredBackend] =
    StoredBackend.format.reads(json).asEither match {
      case Left(e)  => Left(e.toString())
      case Right(r) => Right(r)
    }

  override def writeEntity(entity: StoredBackend): JsValue = StoredBackend.format.writes(entity)

  override def findByIdOps(
                            id: String
                          )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], OptionalEntityAndContext[StoredBackend]]] = {
    env.datastores.backendsDataStore.findById(id).map { opt =>
      Right(
        OptionalEntityAndContext(
          entity = opt,
          action = "ACCESS_STORED_BACKEND",
          message = "User accessed a backend",
          metadata = Json.obj("StoredBackendId" -> id),
          alert = "StoredBackendAccessed"
        )
      )
    }
  }

  override def findAllOps(
                           req: RequestHeader
                         )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[StoredBackend]]] = {
    env.datastores.backendsDataStore.findAll().map { seq =>
      Right(
        SeqEntityAndContext(
          entity = seq,
          action = "ACCESS_ALL_STORED_BACKENDS",
          message = "User accessed all backends",
          metadata = Json.obj(),
          alert = "StoredBackendsAccessed"
        )
      )
    }
  }

  override def createEntityOps(
                                entity: StoredBackend
                              )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[StoredBackend]]] = {
    env.datastores.backendsDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "CREATE_STORED_BACKEND",
            message = "User created a backend",
            metadata = entity.json.as[JsObject],
            alert = "StoredBackendCreatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "backend not stored ...")
          )
        )
      }
    }
  }

  override def updateEntityOps(
                                entity: StoredBackend
                              )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[StoredBackend]]] = {
    env.datastores.backendsDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "UPDATE_STORED_BACKEND",
            message = "User updated a backend",
            metadata = entity.json.as[JsObject],
            alert = "StoredBackendUpdatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "backend not stored ...")
          )
        )
      }
    }
  }

  override def deleteEntityOps(
                                id: String
                              )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[StoredBackend]]] = {
    env.datastores.backendsDataStore.delete(id).map {
      case true  => {
        Right(
          NoEntityAndContext(
            action = "DELETE_STORED_BACKEND",
            message = "User deleted a backend",
            metadata = Json.obj("StoredBackendId" -> id),
            alert = "StoredBackendDeletedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "backend not deleted ...")
          )
        )
      }
    }
  }

  def initiateStoredBackend() = ApiAction {
    Ok(StoredBackend(
      location = EntityLocation.default,
      id = s"backend_${IdGenerator.uuid}",
      name = "New backend",
      description = "A new backend",
      tags = Seq.empty,
      metadata = Map.empty,
      backend = NgTarget(
        id = "target_1",
        hostname = "mirror.otoroshi.io",
        port = 443,
        tls = true
      )
    ).json)
  }
}
