package controllers.adminapi

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.utils.controllers.{ApiError, BulkControllerHelper, CrudControllerHelper, EntityAndContext, JsonApiError, NoEntityAndContext, OptionalEntityAndContext, SeqEntityAndContext}
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.{AbstractController, ControllerComponents, RequestHeader}
import otoroshi.ssl.ClientCertificateValidator

import scala.concurrent.{ExecutionContext, Future}

class ClientValidatorsController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
  extends AbstractController(cc) with BulkControllerHelper[ClientCertificateValidator, JsValue] with CrudControllerHelper[ClientCertificateValidator, JsValue] {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  override def buildError(status: Int, message: String): ApiError[JsValue] = JsonApiError(status, play.api.libs.json.JsString(message))

  override def extractId(entity: ClientCertificateValidator): String = entity.id

  override def readEntity(json: JsValue): Either[String, ClientCertificateValidator] = ClientCertificateValidator.fmt.reads(json).asEither match {
    case Left(e) => Left(e.toString())
    case Right(r) => Right(r)
  }

  override def writeEntity(entity: ClientCertificateValidator): JsValue = ClientCertificateValidator.fmt.writes(entity)

  override def findByIdOps(id: String)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], OptionalEntityAndContext[ClientCertificateValidator]]] = {
    env.datastores.clientCertificateValidationDataStore.findById(id).map { opt =>
      Right(OptionalEntityAndContext(
        entity = opt,
        action = "ACCESS_CLIENT_CERT_VALIDATOR",
        message = "User accessed a client cert. validator",
        metadata = Json.obj("ClientCertificateValidatorId" -> id),
        alert = "ClientCertificateValidatorAccessed"
      ))
    }
  }

  override def findAllOps(req: RequestHeader)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[ClientCertificateValidator]]] = {
    env.datastores.clientCertificateValidationDataStore.findAll().map { seq =>
      Right(SeqEntityAndContext(
        entity = seq,
        action = "ACCESS_ALL_CLIENT_CERT_VALIDATORS",
        message = "User accessed all client cert. validators",
        metadata = Json.obj(),
        alert = "ClientCertificateValidatorsAccessed"
      ))
    }
  }

  override def createEntityOps(entity: ClientCertificateValidator)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[ClientCertificateValidator]]] = {
    env.datastores.clientCertificateValidationDataStore.set(entity).map {
      case true => {
        Right(EntityAndContext(
          entity = entity,
          action = "CREATE_CLIENT_CERT_VALIDATOR",
          message = "User created a client cert. validator",
          metadata = entity.asJson.as[JsObject],
          alert = "ClientCertificateValidatorCreatedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "client cert. validator not stored ...")
        ))
      }
    }
  }

  override def updateEntityOps(entity: ClientCertificateValidator)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[ClientCertificateValidator]]] = {
    env.datastores.clientCertificateValidationDataStore.set(entity).map {
      case true => {
        Right(EntityAndContext(
          entity = entity,
          action = "UPDATE_CLIENT_CERT_VALIDATOR",
          message = "User updated a client cert. validator",
          metadata = entity.asJson.as[JsObject],
          alert = "ClientCertificateValidatorUpdatedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "client cert. validator not stored ...")
        ))
      }
    }
  }

  override def deleteEntityOps(id: String)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[ClientCertificateValidator]]] = {
    env.datastores.clientCertificateValidationDataStore.delete(id).map {
      case true => {
        Right(NoEntityAndContext(
          action = "DELETE_CLIENT_CERT_VALIDATOR",
          message = "User deleted a client cert. validator",
          metadata = Json.obj("ClientCertificateValidatorId" -> id),
          alert = "ClientCertificateValidatorDeletedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "client cert. validator not deleted ...")
        ))
      }
    }
  }
}